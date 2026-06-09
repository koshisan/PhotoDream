package de.koshi.photodream.ui

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import de.koshi.photodream.util.MdiIcons

/**
 * Renders the "Aurora" notification cards as a vertical stack (newest on top; older
 * cards slide down). Driven by [NotificationCenter] — it owns no timers/callbacks,
 * it only shows/removes cards. Used by both the slideshow and the overlay window.
 *
 * Frost mode (slideshow): when [backdropFactory] is set, each card gets a blurred,
 * pan-tracked photo backdrop + translucent tint; the host (SlideshowController) drives
 * those layers via [frostLayers] / [onCardAttached]. Without it (overlay), cards are
 * solid frosted.
 */
class NotificationStack(
    private val context: Context,
    private val host: ViewGroup,
    private val backdropFactory: (() -> ImageView)? = null,
    private val onCardAttached: ((FrostLayer) -> Unit)? = null,
    private val onActiveChanged: ((Boolean) -> Unit)? = null
) : NotificationCenter.Renderer {

    companion object {
        private val DEFAULT_NC = Color.parseColor("#5B8DEF")
        private val CARD_BG = Color.parseColor("#F212141A")   // opaque frosted (solid mode)
        private val CARD_BORDER = Color.parseColor("#29FFFFFF")
    }

    /** A card's frost layers, exposed so the slideshow can drive the blurred backdrop. */
    class FrostLayer(val card: FrameLayout, val tint: View, val backdrop: ImageView)

    private val frostEnabled = backdropFactory != null
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private class Holder(
        val id: Long,
        val card: FrameLayout,
        val progressFill: View,
        var animator: ObjectAnimator? = null,
        val frost: FrostLayer? = null
    )

    private val holders = mutableListOf<Holder>()  // index 0 = top-most

    init {
        host.layoutTransition = LayoutTransition().apply { setDuration(260) }
    }

    // ---- NotificationCenter.Renderer ----

    override fun show(active: NotificationCenter.Active) {
        val holder = buildCard(active)
        holders.add(0, holder)
        host.addView(holder.card, 0)
        startProgress(holder, active)
        holder.frost?.let { onCardAttached?.invoke(it) }
        if (holders.size == 1) onActiveChanged?.invoke(true)
    }

    override fun remove(id: Long) {
        val h = holders.firstOrNull { it.id == id } ?: return
        h.animator?.cancel()
        holders.remove(h)
        host.removeView(h.card)
        if (holders.isEmpty()) onActiveChanged?.invoke(false)
    }

    override fun reset(actives: List<NotificationCenter.Active>) {
        clearViews()
        // oldest first -> insert each at top so the newest ends up on top
        for (a in actives) {
            val holder = buildCard(a)
            holders.add(0, holder)
            host.addView(holder.card, 0)
            startProgress(holder, a)
            holder.frost?.let { onCardAttached?.invoke(it) }
        }
        onActiveChanged?.invoke(holders.isNotEmpty())
    }

    override fun detach() {
        clearViews()
        onActiveChanged?.invoke(false)
    }

    /** Current cards' frost layers (slideshow drives the blurred backdrops via these). */
    fun frostLayers(): List<FrostLayer> = holders.mapNotNull { it.frost }

    private fun clearViews() {
        holders.forEach { it.animator?.cancel() }
        holders.clear()
        host.removeAllViews()
    }

    /** Hit-test a tap against the visible cards (slideshow path, where the gesture
     *  detector would otherwise consume it). */
    fun handleTap(event: MotionEvent): Boolean {
        val x = event.rawX
        val y = event.rawY
        for (h in holders) {
            if (isInside(h.card, x, y)) {
                NotificationCenter.dismiss(h.id, true)
                return true
            }
        }
        return false
    }

    private fun startProgress(holder: Holder, active: NotificationCenter.Active) {
        if (active.persistent) return
        val remaining = active.remainingMs()
        if (remaining <= 0L) return
        holder.progressFill.scaleX = active.fraction()
        holder.animator = ObjectAnimator.ofFloat(holder.progressFill, "scaleX", active.fraction(), 0f).apply {
            duration = remaining
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun isInside(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
    }

    // ---- card construction ----

    private fun buildCard(active: NotificationCenter.Active): Holder {
        val payload = active.payload
        val nc = parseColorOrNull(payload.color) ?: DEFAULT_NC

        val glyph = MdiIcons.glyph(context, payload.icon) ?: MdiIcons.glyph(context, "bell")
        val iconTile = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(18) }
            background = rounded(dp(16).toFloat(), withAlpha(nc, 0x38))
            visibility = if (glyph != null) View.VISIBLE else View.GONE
            if (glyph != null) addView(TextView(context).apply {
                text = glyph
                MdiIcons.typeface(this@NotificationStack.context)?.let { typeface = it }
                textSize = 30f
                gravity = Gravity.CENTER
                setTextColor(nc)
            }, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }

        val title = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = if (payload.title.isNullOrBlank()) View.GONE else View.VISIBLE
            text = payload.title ?: ""
        }
        val message = TextView(context).apply {
            setTextColor(withAlpha(Color.WHITE, 0xCC))
            textSize = 17f
            maxLines = 3
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = payload.message
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(title); addView(message)
        }

        val image = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            background = rounded(dp(13).toFloat(), withAlpha(Color.WHITE, 0x14))
            layoutParams = LinearLayout.LayoutParams(dp(116), dp(68)).apply {
                gravity = Gravity.CENTER_VERTICAL; marginStart = dp(16)
            }
            visibility = View.GONE
        }
        if (!payload.imageUrl.isNullOrBlank()) {
            image.visibility = View.VISIBLE
            try { Glide.with(context).load(payload.imageUrl).centerCrop().into(image) }
            catch (e: Exception) { image.visibility = View.GONE }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT)
            addView(iconTile); addView(body); addView(image)
        }

        val progressFill = View(context).apply {
            background = rounded(dp(2).toFloat(), nc)
            pivotX = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT)
        }
        val progressTrack = FrameLayout(context).apply {
            clipToOutline = true
            background = rounded(dp(2).toFloat(), withAlpha(Color.WHITE, 0x24))
            addView(progressFill)
            visibility = if (active.persistent) View.GONE else View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3)).apply {
                gravity = Gravity.BOTTOM; leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(9)
            }
        }

        val screenW = context.resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.6f).toInt().coerceIn(dp(320), dp(900))

        var frost: FrostLayer? = null
        val card = FrameLayout(context).apply {
            clipToOutline = true
            elevation = dp(12).toFloat()
            foreground = roundedBorder(dp(22).toFloat(), CARD_BORDER)
            if (frostEnabled) {
                // transparent fill + blurred backdrop + tint layers (host drives them)
                background = rounded(dp(22).toFloat(), Color.TRANSPARENT)
                val backdrop = backdropFactory!!.invoke()
                val tint = View(context).apply {
                    layoutParams = FrameLayout.LayoutParams(0, 0)
                }
                addView(backdrop)
                addView(tint)
                addView(row)
                addView(progressTrack)
                frost = FrostLayer(this, tint, backdrop)
                // keep backdrop + tint sized to the card
                addOnLayoutChangeListener { _, l, t, r, b, _, _, _, _ ->
                    val w = r - l; val h = b - t
                    if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
                    for (layer in listOf<View>(backdrop, tint)) {
                        val lp = layer.layoutParams
                        if (lp.width != w || lp.height != h) { lp.width = w; lp.height = h; layer.layoutParams = lp }
                    }
                    onCardAttached?.invoke(frost!!)
                }
            } else {
                background = rounded(dp(22).toFloat(), CARD_BG)
                addView(row)
                addView(progressTrack)
            }
            layoutParams = LinearLayout.LayoutParams(cardW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        }

        val holder = Holder(active.id, card, progressFill, frost = frost)
        card.setOnClickListener { NotificationCenter.dismiss(active.id, true) }  // overlay (direct touch)
        return holder
    }

    private fun rounded(radius: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = radius; setColor(color)
    }

    private fun roundedBorder(radius: Float, stroke: Int) = GradientDrawable().apply {
        cornerRadius = radius; setColor(Color.TRANSPARENT); setStroke(dp(1), stroke)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun parseColorOrNull(hex: String?): Int? =
        if (hex.isNullOrBlank()) null else try { Color.parseColor(hex) } catch (e: Exception) { null }
}
