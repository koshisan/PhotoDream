package de.koshi.photodream.ui

import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import de.koshi.photodream.model.MediaState
import de.koshi.photodream.util.MdiIcons
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Renders the Home Assistant media_player as a "Now Playing" widget in two modes:
 *  - compact: small frosted card, top-left
 *  - focus: large centered cover + big transport (the slideshow stays behind, dimmed)
 *
 * Only visible while media is active (playing/paused) and the mode isn't "off".
 * Transport buttons POST to the per-action webhook URLs from the media payload.
 */
class MediaPlayerOverlay(
    private val context: Context,
    private val host: FrameLayout,
    private val backdropFactory: (() -> ImageView)? = null,
    private val onCardAttached: ((NotificationStack.FrostLayer) -> Unit)? = null
) {
    companion object {
        private const val TAG = "MediaPlayer"
        private val CARD_BG = Color.parseColor("#7512141A")
        private val CARD_BORDER = Color.parseColor("#24FFFFFF")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private val frostEnabled = backdropFactory != null
    private var builtMode: String? = null
    private var state: MediaState? = null

    private var compactCard: FrameLayout? = null
    /** Frost layers of the compact card (driven by the slideshow, like the agenda). */
    var compactFrost: NotificationStack.FrostLayer? = null
        private set
    private lateinit var progressTrackView: View

    // dynamic view refs (rebuilt per mode)
    private lateinit var cover: ImageView
    private lateinit var eqBox: LinearLayout
    private lateinit var sourceIcon: TextView
    private lateinit var sourceText: TextView
    private lateinit var title: TextView
    private lateinit var artist: TextView
    private lateinit var progressFill: View
    private lateinit var prevBtn: FrameLayout
    private lateinit var playBtn: FrameLayout
    private lateinit var nextBtn: FrameLayout
    private lateinit var playGlyph: TextView
    private var eqAnimators = mutableListOf<ObjectAnimator>()

    // progress interpolation
    private var posBaseSec = 0f
    private var durSec = 0f
    private var baseUptime = 0L
    private var ticking = false

    val isFocusActive: Boolean
        get() = builtMode == "focus" && (state?.isActive == true)

    /** True if a screen-coord tap lands on the player widget (so the slideshow's
     *  gesture detector shouldn't treat it as "tap to exit"). */
    fun containsTap(rawX: Float, rawY: Float): Boolean {
        val widget = if (host.childCount > 0) host.getChildAt(0) else return false
        if (widget.visibility != View.VISIBLE) return false
        val loc = IntArray(2); widget.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX <= loc[0] + widget.width &&
            rawY >= loc[1] && rawY <= loc[1] + widget.height
    }

    /** True if the player is currently shown (active media + mode on). */
    fun isVisible(): Boolean = host.childCount > 0 && (state?.isActive == true)

    fun apply(mode: String, newState: MediaState?, compactGravity: Int = Gravity.TOP or Gravity.START) {
        state = newState
        val show = mode != "off" && newState?.isActive == true
        if (!show) { hide(); return }

        if (builtMode != mode) {
            host.removeAllViews()
            stopTicker()
            compactCard = null
            compactFrost = null
            if (mode == "focus") buildFocus() else buildCompact()
            builtMode = mode
        }
        // Reposition the compact card (diagonal to the clock / dodging the agenda)
        compactCard?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            if (lp.gravity != compactGravity) { lp.gravity = compactGravity; it.layoutParams = lp }
        }
        updateDynamic(newState!!)
    }

    private fun hide() {
        host.removeAllViews()
        stopTicker()
        stopEq()
        compactCard = null
        compactFrost = null
        builtMode = null
    }

    /** Re-anchor the compact card (e.g. when the agenda appears/disappears). */
    fun reposition(compactGravity: Int) {
        compactCard?.let {
            val lp = it.layoutParams as FrameLayout.LayoutParams
            if (lp.gravity != compactGravity) { lp.gravity = compactGravity; it.layoutParams = lp }
        }
    }

    // ---- dynamic update ----

    private fun updateDynamic(s: MediaState) {
        // cover
        if (!s.coverUrl.isNullOrBlank()) {
            try { Glide.with(context).load(s.coverUrl).centerCrop().into(cover) } catch (e: Exception) {}
        }
        title.text = s.title ?: ""
        artist.text = s.artist ?: ""

        val srcGlyph = MdiIcons.glyph(context, s.sourceIcon ?: "music")
        if (srcGlyph != null) { sourceIcon.text = srcGlyph; sourceIcon.visibility = View.VISIBLE }
        else sourceIcon.visibility = View.GONE
        sourceText.text = s.source ?: ""

        playGlyph.text = MdiIcons.glyph(context, if (s.isPlaying) "pause" else "play") ?: ""
        prevBtn.alpha = if (s.canPrev) 1f else 0.35f
        nextBtn.alpha = if (s.canNext) 1f else 0.35f

        // EQ only while playing
        if (s.isPlaying) startEq() else stopEq()

        // progress — only when we actually have duration (e.g. Bluetooth audio often doesn't)
        posBaseSec = s.position ?: 0f
        durSec = s.duration ?: 0f
        baseUptime = SystemClock.uptimeMillis()
        if (::progressTrackView.isInitialized) {
            progressTrackView.visibility = if (durSec > 0f) View.VISIBLE else View.GONE
        }
        if (durSec > 0f && s.isPlaying) startTicker() else { stopTicker(); renderProgress() }
    }

    private fun renderProgress() {
        val elapsed = if (state?.isPlaying == true) (SystemClock.uptimeMillis() - baseUptime) / 1000f else 0f
        val pos = posBaseSec + elapsed
        val frac = if (durSec > 0f) (pos / durSec).coerceIn(0f, 1f) else 0f
        if (::progressFill.isInitialized) progressFill.scaleX = frac
    }

    private val ticker = object : Runnable {
        override fun run() {
            renderProgress()
            if (ticking) handler.postDelayed(this, 500)
        }
    }
    private fun startTicker() { if (!ticking) { ticking = true; handler.post(ticker) } }
    private fun stopTicker() { ticking = false; handler.removeCallbacks(ticker) }

    // ---- EQ bars ----

    private fun startEq() {
        eqBox.visibility = View.VISIBLE
        if (eqAnimators.isNotEmpty()) return
        for (i in 0 until eqBox.childCount) {
            val bar = eqBox.getChildAt(i)
            bar.pivotY = bar.layoutParams.height.toFloat()
            val a = ObjectAnimator.ofFloat(bar, "scaleY", 0.3f, 1f, 0.3f).apply {
                duration = 1000
                startDelay = (i * 130).toLong()
                repeatCount = ObjectAnimator.INFINITE
                start()
            }
            eqAnimators.add(a)
        }
    }
    private fun stopEq() {
        eqAnimators.forEach { it.cancel() }
        eqAnimators.clear()
        if (::eqBox.isInitialized) eqBox.visibility = View.GONE
    }

    // ---- controls ----

    /** Toggle play/pause: fire the webhook AND flip the icon/EQ immediately (optimistic),
     *  so it feels responsive even if HA echoes the new state slowly. */
    private fun onPlayPauseTap() {
        sendControl(state?.controls?.playPauseUrl)
        val nowPlaying = state?.isPlaying == true
        if (::playGlyph.isInitialized) {
            playGlyph.text = MdiIcons.glyph(context, if (nowPlaying) "play" else "pause") ?: ""
            if (nowPlaying) stopEq() else startEq()
        }
    }

    private fun sendControl(url: String?) {
        if (url.isNullOrBlank()) return
        Thread {
            try {
                val req = Request.Builder().url(url)
                    .post("{}".toRequestBody("application/json".toMediaType())).build()
                httpClient.newCall(req).execute().use { Log.d(TAG, "Control ${it.code}") }
            } catch (e: Exception) { Log.e(TAG, "Control failed: ${e.message}") }
        }.start()
    }

    private fun controlButton(sizeDp: Int, glyphName: String, glyphSp: Float, filled: Boolean): Pair<FrameLayout, TextView> {
        val glyph = TextView(context).apply {
            MdiIcons.typeface(this@MediaPlayerOverlay.context)?.let { typeface = it }
            text = MdiIcons.glyph(this@MediaPlayerOverlay.context, glyphName) ?: ""
            setTextColor(Color.WHITE)
            textSize = glyphSp
            gravity = Gravity.CENTER
        }
        val btn = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
            if (filled) background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(withAlpha(Color.WHITE, 0x2B))
            }
            isClickable = true
            addView(glyph, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        return btn to glyph
    }

    // ---- COMPACT layout ----

    private fun buildCompact() {
        cover = makeCover(dp(84), dp(16))
        eqBox = makeEq(dp(3), dp(15), dp(2))

        val coverBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(84), dp(84))
            addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(eqBox, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(7), 0, 0, dp(7)) })
        }

        sourceIcon = mdiText(13f, withAlpha(Color.WHITE, 0x99))
        sourceText = TextView(context).apply {
            setTextColor(withAlpha(Color.WHITE, 0x99)); textSize = 11f
            letterSpacing = 0.12f; isAllCaps = true; maxLines = 1
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val sourceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(sourceIcon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(6) })
            addView(sourceText)
        }
        title = ellipsisText(18f, Color.WHITE, true)
        artist = ellipsisText(14f, withAlpha(Color.WHITE, 0xBD), false)
        progressFill = View(context).apply { background = rounded(dp(2).toFloat(), Color.WHITE); pivotX = 0f }
        progressTrackView = progressTrack(progressFill)

        val (prev, _) = controlButton(34, "skip-previous", 22f, false); prevBtn = prev
        val (play, pg) = controlButton(40, "play", 24f, true); playBtn = play; playGlyph = pg
        val (next, _) = controlButton(34, "skip-next", 22f, false); nextBtn = next
        prevBtn.setOnClickListener { sendControl(state?.controls?.prevUrl) }
        playBtn.setOnClickListener { onPlayPauseTap() }
        nextBtn.setOnClickListener { sendControl(state?.controls?.nextUrl) }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) }
            addView(prevBtn); addView(play, LinearLayout.LayoutParams(dp(40), dp(40)).apply { marginStart = dp(6); marginEnd = dp(6) }); addView(nextBtn)
        }

        val textCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(sourceRow); addView(title); addView(artist)
            addView(progressTrackView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(4)).apply { topMargin = dp(8) })
            addView(controls)
        }

        // content row (padded) — the card itself stays unpadded so the frost layers fill it
        val content = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(coverBox, LinearLayout.LayoutParams(dp(84), dp(84)).apply { marginEnd = dp(16) })
            addView(textCol)
        }

        val card = FrameLayout(context).apply {
            clipToOutline = true
            foreground = roundedBorder(dp(24).toFloat(), CARD_BORDER)
            elevation = dp(10).toFloat()
            if (frostEnabled) {
                // same live frost as the agenda: [blurred backdrop] -> [tint] -> [content]
                background = rounded(dp(24).toFloat(), Color.TRANSPARENT)
                val backdrop = backdropFactory!!.invoke()
                val tint = View(context).apply { layoutParams = FrameLayout.LayoutParams(0, 0) }
                addView(backdrop); addView(tint); addView(content)
                compactFrost = NotificationStack.FrostLayer(this, tint, backdrop)
                addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                    val w = r - l; val h = b - t
                    if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
                    for (layer in listOf<View>(backdrop, tint)) {
                        val lp = layer.layoutParams
                        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; layer.layoutParams = lp }
                    }
                    onCardAttached?.invoke(compactFrost!!)
                }
            } else {
                background = rounded(dp(24).toFloat(), CARD_BG)
                addView(content)
            }
            layoutParams = FrameLayout.LayoutParams(dp(360), FrameLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.TOP or Gravity.START; setMargins(dp(40), dp(36), dp(40), dp(36))
            }
        }
        compactCard = card
        host.addView(card)
    }

    // ---- FOCUS layout ----

    private fun buildFocus() {
        cover = makeCover(dp(360), dp(30))
        eqBox = makeEq(dp(5), dp(24), dp(3))

        val coverBox = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(360), dp(360))
            addView(cover, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(eqBox, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.BOTTOM or Gravity.START; setMargins(dp(16), 0, 0, dp(16)) })
        }

        title = TextView(context).apply {
            setTextColor(Color.WHITE); textSize = 40f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            letterSpacing = -0.02f; gravity = Gravity.CENTER
            setShadowLayer(24f, 0f, 2f, withAlpha(Color.BLACK, 0x80)); maxLines = 2
        }
        artist = TextView(context).apply {
            setTextColor(withAlpha(Color.WHITE, 0xD1)); textSize = 23f; gravity = Gravity.CENTER
            setShadowLayer(16f, 0f, 1f, withAlpha(Color.BLACK, 0x80)); maxLines = 1
        }
        sourceIcon = mdiText(15f, withAlpha(Color.WHITE, 0xA6))
        sourceText = TextView(context).apply {
            setTextColor(withAlpha(Color.WHITE, 0xA6)); textSize = 13f
            letterSpacing = 0.1f; isAllCaps = true; setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        val sourceRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(10) }
            addView(sourceIcon, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginEnd = dp(7) })
            addView(sourceText)
        }

        progressFill = View(context).apply { background = rounded(dp(3).toFloat(), Color.WHITE); pivotX = 0f }
        progressTrackView = progressTrack(progressFill)

        val (prev, _) = controlButton(60, "skip-previous", 38f, false); prevBtn = prev
        val (play, pg) = controlButton(78, "play", 46f, true); playBtn = play; playGlyph = pg
        val (next, _) = controlButton(60, "skip-next", 38f, false); nextBtn = next
        prevBtn.setOnClickListener { sendControl(state?.controls?.prevUrl) }
        playBtn.setOnClickListener { onPlayPauseTap() }
        nextBtn.setOnClickListener { sendControl(state?.controls?.nextUrl) }
        val controls = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(20) }
            addView(prevBtn); addView(play, LinearLayout.LayoutParams(dp(78), dp(78)).apply { marginStart = dp(22); marginEnd = dp(22) }); addView(next)
        }

        val column = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
            addView(coverBox)
            addView(title, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(28) })
            addView(artist, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(6) })
            addView(sourceRow)
            addView(progressTrackView, LinearLayout.LayoutParams(dp(440), dp(6)).apply { topMargin = dp(24) })
            addView(controls)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply { gravity = Gravity.CENTER }
        }
        host.addView(column)
    }

    // ---- small builders ----

    private fun makeCover(size: Int, radius: Int) = ImageView(context).apply {
        scaleType = ImageView.ScaleType.CENTER_CROP
        clipToOutline = true
        background = rounded(radius.toFloat(), withAlpha(Color.WHITE, 0x12))
        elevation = dp(8).toFloat()
    }

    private fun makeEq(barW: Int, barH: Int, gap: Int): LinearLayout {
        return LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM
            visibility = View.GONE
            for (i in 0 until 4) {
                addView(View(context).apply {
                    background = rounded(dp(1).toFloat(), Color.WHITE)
                    layoutParams = LinearLayout.LayoutParams(barW, barH).apply { if (i > 0) marginStart = gap }
                })
            }
        }
    }

    private fun progressTrack(fill: View): FrameLayout = FrameLayout(context).apply {
        clipToOutline = true
        background = rounded(dp(2).toFloat(), withAlpha(Color.WHITE, 0x2E))
        addView(fill, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
    }

    private fun mdiText(sizeSp: Float, color: Int) = TextView(context).apply {
        MdiIcons.typeface(this@MediaPlayerOverlay.context)?.let { typeface = it }
        textSize = sizeSp; setTextColor(color)
    }

    private fun ellipsisText(sizeSp: Float, color: Int, bold: Boolean) = TextView(context).apply {
        setTextColor(color); textSize = sizeSp; maxLines = 1
        ellipsize = android.text.TextUtils.TruncateAt.END
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun rounded(radius: Float, color: Int) = GradientDrawable().apply { cornerRadius = radius; setColor(color) }
    private fun roundedBorder(radius: Float, stroke: Int) = GradientDrawable().apply {
        cornerRadius = radius; setColor(Color.TRANSPARENT); setStroke(dp(1), stroke)
    }
    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)
}
