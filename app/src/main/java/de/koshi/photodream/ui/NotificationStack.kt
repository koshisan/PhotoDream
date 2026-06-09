package de.koshi.photodream.ui

import android.animation.LayoutTransition
import android.animation.ObjectAnimator
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.Log
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
import de.koshi.photodream.model.NotificationPayload
import de.koshi.photodream.util.MdiIcons
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shared notification UI used by BOTH the in-slideshow overlay (SlideshowController)
 * and the system-overlay window (NotificationOverlayService).
 *
 * Renders the "Aurora" notification cards as a vertical stack: a new notification is
 * added on top, older ones slide down; when any card expires (its own [NotificationPayload.duration])
 * or is tapped, it's removed and the rest reflow. Multiple notifications are visible at once.
 *
 * The host should be a top-anchored container; cards manage their own width.
 */
class NotificationStack(
    private val context: Context,
    private val host: ViewGroup,
    /** Called with true when the stack becomes non-empty, false when it empties. */
    private val onActiveChanged: ((Boolean) -> Unit)? = null
) {
    companion object {
        private const val TAG = "NotificationStack"
        private val DEFAULT_NC = Color.parseColor("#5B8DEF")
        private val CARD_BG = Color.parseColor("#F212141A")   // opaque frosted
        private val CARD_BORDER = Color.parseColor("#29FFFFFF")
    }

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private val density = context.resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    private class Holder(
        val card: View,
        val progressFill: View,
        val payload: NotificationPayload,
        var dismiss: Runnable? = null,
        var animator: ObjectAnimator? = null
    )

    private val holders = mutableListOf<Holder>()  // index 0 = top-most (newest)

    init {
        // Smooth add / remove / reflow of the stacked cards.
        host.layoutTransition = LayoutTransition().apply {
            setDuration(260)
        }
    }

    /** Add a new notification on top of the stack. */
    fun push(payload: NotificationPayload) {
        if (payload.sound) playSound()

        val holder = buildCard(payload)
        holders.add(0, holder)
        host.addView(holder.card, 0)

        // Drain the per-card timer bar over its own duration.
        val durationMs = if (payload.duration > 0) payload.duration * 1000L else 0L
        if (durationMs > 0) {
            holder.animator = ObjectAnimator.ofFloat(holder.progressFill, "scaleX", 1f, 0f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                start()
            }
            val r = Runnable { dismiss(holder) }
            holder.dismiss = r
            handler.postDelayed(r, durationMs)
        }

        if (holders.size == 1) onActiveChanged?.invoke(true)
    }

    /**
     * Hit-test a tap (screen coords) against the visible cards. If it lands on one,
     * trigger its callback + dismiss and return true. Used by the slideshow, whose
     * gesture detector would otherwise consume the tap.
     */
    fun handleTap(event: MotionEvent): Boolean {
        val x = event.rawX
        val y = event.rawY
        for (h in holders) {
            if (isInside(h.card, x, y)) {
                onTap(h)
                return true
            }
        }
        return false
    }

    val isActive: Boolean get() = holders.isNotEmpty()

    /** Remove everything (e.g. on teardown). */
    fun clear() {
        holders.forEach {
            it.dismiss?.let { r -> handler.removeCallbacks(r) }
            it.animator?.cancel()
        }
        holders.clear()
        host.removeAllViews()
    }

    private fun onTap(h: Holder) {
        fireCallback(h.payload)
        dismiss(h)
    }

    private fun dismiss(h: Holder) {
        if (!holders.remove(h)) return
        h.dismiss?.let { handler.removeCallbacks(it) }
        h.animator?.cancel()
        host.removeView(h.card)  // LayoutTransition animates it out + reflows the rest
        if (holders.isEmpty()) onActiveChanged?.invoke(false)
    }

    private fun fireCallback(payload: NotificationPayload) {
        val url = payload.callbackUrl
        if (url.isNullOrBlank()) return
        val method = payload.callbackMethod.uppercase()
        Thread {
            try {
                val b = Request.Builder().url(url)
                if (method == "GET") b.get()
                else b.post("{}".toRequestBody("application/json".toMediaType()))
                httpClient.newCall(b.build()).execute().use { r ->
                    Log.d(TAG, "Callback ${r.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Callback failed: ${e.message}")
            }
        }.start()
    }

    private fun isInside(view: View, x: Float, y: Float): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2); view.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + view.width && y >= loc[1] && y <= loc[1] + view.height
    }

    private fun playSound() {
        try {
            val uri = android.media.RingtoneManager
                .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(context, uri)?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Sound failed: ${e.message}")
        }
    }

    // ---- card construction (the shared "Aurora" look) ----

    private fun buildCard(payload: NotificationPayload): Holder {
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
            addView(title)
            addView(message)
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

        val durationMs = if (payload.duration > 0) payload.duration * 1000L else 0L
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
            visibility = if (durationMs > 0) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, dp(3)).apply {
                gravity = Gravity.BOTTOM; leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(9)
            }
        }

        val screenW = context.resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.6f).toInt().coerceIn(dp(320), dp(900))

        val card = FrameLayout(context).apply {
            clipToOutline = true
            elevation = dp(12).toFloat()
            background = rounded(dp(22).toFloat(), CARD_BG)
            foreground = roundedBorder(dp(22).toFloat(), CARD_BORDER)
            addView(row); addView(progressTrack)
            layoutParams = LinearLayout.LayoutParams(cardW, LinearLayout.LayoutParams.WRAP_CONTENT).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(12)
            }
        }

        val holder = Holder(card, progressFill, payload)
        card.setOnClickListener { onTap(holder) }  // used by the overlay window (direct touch)
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
