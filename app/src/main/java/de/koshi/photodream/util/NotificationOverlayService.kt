package de.koshi.photodream.util

import android.animation.ObjectAnimator
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.bumptech.glide.Glide
import com.google.gson.Gson
import de.koshi.photodream.model.NotificationPayload
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Shows an "Aurora" notification card as a system overlay window, so notifications
 * work even when the slideshow isn't running. When the slideshow IS running, the
 * SlideshowController renders the (frosted) in-slideshow card instead.
 *
 * Requires SYSTEM_ALERT_WINDOW ("draw over other apps") permission.
 */
class NotificationOverlayService : Service() {

    companion object {
        private const val TAG = "NotifOverlay"
        private const val EXTRA_PAYLOAD = "payload_json"

        private val gson = Gson()

        /** Show a notification overlay. Returns false if the overlay permission is missing. */
        fun show(context: Context, payload: NotificationPayload): Boolean {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "No SYSTEM_ALERT_WINDOW permission - cannot show overlay notification")
                return false
            }
            val intent = Intent(context, NotificationOverlayService::class.java).apply {
                putExtra(EXTRA_PAYLOAD, gson.toJson(payload))
            }
            context.startService(intent)
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private var windowManager: WindowManager? = null
    private var cardView: View? = null
    private var progressAnimator: ObjectAnimator? = null
    private var dismissRunnable: Runnable? = null

    private val density get() = resources.displayMetrics.density
    private fun dp(v: Int) = (v * density).toInt()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val json = intent?.getStringExtra(EXTRA_PAYLOAD)
        val payload = try {
            json?.let { gson.fromJson(it, NotificationPayload::class.java) }
        } catch (e: Exception) {
            Log.e(TAG, "Bad payload: ${e.message}"); null
        }
        if (payload == null || payload.message.isBlank()) {
            stopSelf(); return START_NOT_STICKY
        }
        showCard(payload)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeCard()
        super.onDestroy()
    }

    private fun showCard(payload: NotificationPayload) {
        removeCard()  // replace any current notification

        if (payload.sound) playSound()

        val nc = parseColorOrNull(payload.color) ?: Color.parseColor("#5B8DEF")

        // Icon tile (MDI glyph tinted with the source color)
        val glyph = MdiIcons.glyph(this, payload.icon) ?: MdiIcons.glyph(this, "bell")
        val iconTile = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(18) }
            background = rounded(dp(16).toFloat(), withAlpha(nc, 0x38))
            visibility = if (glyph != null) View.VISIBLE else View.GONE
            if (glyph != null) {
                addView(TextView(context).apply {
                    text = glyph
                    MdiIcons.typeface(this@NotificationOverlayService)?.let { typeface = it }
                    textSize = 30f
                    gravity = Gravity.CENTER
                    setTextColor(nc)
                }, FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
                ))
            }
        }

        val title = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            visibility = if (payload.title.isNullOrBlank()) View.GONE else View.VISIBLE
            text = payload.title ?: ""
        }
        val message = TextView(this).apply {
            setTextColor(withAlpha(Color.WHITE, 0xCC))
            textSize = 17f
            maxLines = 2
            ellipsize = android.text.TextUtils.TruncateAt.END
            text = payload.message
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(title)
            addView(message)
        }

        val image = ImageView(this).apply {
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
            try { Glide.with(this).load(payload.imageUrl).centerCrop().into(image) }
            catch (e: Exception) { image.visibility = View.GONE }
        }

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(iconTile)
            addView(body)
            addView(image)
        }

        val durationMs = if (payload.duration > 0) payload.duration * 1000L else 0L
        val progressFill = View(this).apply {
            background = rounded(dp(2).toFloat(), nc)
            pivotX = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        val progressTrack = FrameLayout(this).apply {
            clipToOutline = true
            background = rounded(dp(2).toFloat(), withAlpha(Color.WHITE, 0x24))
            addView(progressFill)
            visibility = if (durationMs > 0) View.VISIBLE else View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, dp(3)
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(24); rightMargin = dp(24); bottomMargin = dp(9)
            }
        }

        val card = FrameLayout(this).apply {
            clipToOutline = true
            elevation = dp(12).toFloat()
            background = rounded(dp(22).toFloat(), Color.parseColor("#F212141A"))
            foreground = roundedBorder(dp(22).toFloat(), withAlpha(Color.WHITE, 0x29))
            addView(row)
            addView(progressTrack)
            setOnClickListener { onTapped(payload) }
        }

        // Window: top-center, sized to ~60% of the screen width
        val screenW = resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.6f).toInt().coerceIn(dp(320), dp(900))
        val params = WindowManager.LayoutParams().apply {
            width = cardW
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = dp(20)
        }

        try {
            windowManager?.addView(card, params)
            cardView = card
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay: ${e.message}", e)
            stopSelf(); return
        }

        // Slide-in animation
        card.alpha = 0f
        card.translationY = -dp(160).toFloat()
        card.animate().alpha(1f).translationY(0f).setDuration(500)
            .setInterpolator(OvershootInterpolator(0.9f)).start()

        // Progress + auto-dismiss
        if (durationMs > 0) {
            progressAnimator = ObjectAnimator.ofFloat(progressFill, "scaleX", 1f, 0f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                start()
            }
            dismissRunnable = Runnable { dismiss() }.also { handler.postDelayed(it, durationMs) }
        }
    }

    private fun onTapped(payload: NotificationPayload) {
        dismiss()
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

    private fun dismiss() {
        val view = cardView
        if (view == null) { stopSelf(); return }
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        progressAnimator?.cancel()
        view.animate().alpha(0f).translationY(-dp(160).toFloat()).setDuration(250)
            .setInterpolator(LinearInterpolator())
            .withEndAction { removeCard(); stopSelf() }
            .start()
    }

    private fun removeCard() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = null
        progressAnimator?.cancel()
        progressAnimator = null
        cardView?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { /* already gone */ }
        }
        cardView = null
    }

    private fun playSound() {
        try {
            val uri = android.media.RingtoneManager
                .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(this, uri)?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Sound failed: ${e.message}")
        }
    }

    private fun rounded(radius: Float, color: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(color)
    }

    private fun roundedBorder(radius: Float, stroke: Int) = GradientDrawable().apply {
        cornerRadius = radius
        setColor(Color.TRANSPARENT)
        setStroke(dp(1), stroke)
    }

    private fun withAlpha(color: Int, alpha: Int) = (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun parseColorOrNull(hex: String?): Int? =
        if (hex.isNullOrBlank()) null else try { Color.parseColor(hex) } catch (e: Exception) { null }
}
