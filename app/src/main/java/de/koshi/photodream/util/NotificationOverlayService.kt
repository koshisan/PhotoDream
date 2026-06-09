package de.koshi.photodream.util

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import com.google.gson.Gson
import de.koshi.photodream.model.NotificationPayload
import de.koshi.photodream.ui.NotificationStack

/**
 * Shows notifications as a system-overlay window when the slideshow isn't running.
 * The actual cards + stacking are handled by the shared [NotificationStack]; this
 * service just hosts it in a floating window. When the slideshow IS running, the
 * SlideshowController renders the same stack instead.
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
            context.startService(Intent(context, NotificationOverlayService::class.java).apply {
                putExtra(EXTRA_PAYLOAD, gson.toJson(payload))
            })
            return true
        }
    }

    private val handler = Handler(Looper.getMainLooper())
    private var windowManager: WindowManager? = null
    private var host: LinearLayout? = null
    private var stack: NotificationStack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val payload = try {
            intent?.getStringExtra(EXTRA_PAYLOAD)?.let { gson.fromJson(it, NotificationPayload::class.java) }
        } catch (e: Exception) {
            Log.e(TAG, "Bad payload: ${e.message}"); null
        }
        if (payload == null || payload.message.isBlank()) {
            if (stack?.isActive != true) stopSelf()
            return START_NOT_STICKY
        }
        ensureWindow()
        stack?.push(payload)
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        removeWindow()
        super.onDestroy()
    }

    private fun ensureWindow() {
        if (host != null) return

        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }

        val params = WindowManager.LayoutParams().apply {
            width = WindowManager.LayoutParams.WRAP_CONTENT
            height = WindowManager.LayoutParams.WRAP_CONTENT
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
            format = PixelFormat.TRANSLUCENT
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = (16 * resources.displayMetrics.density).toInt()
        }

        try {
            windowManager?.addView(container, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to add overlay window: ${e.message}", e)
            stopSelf(); return
        }

        host = container
        stack = NotificationStack(this, container) { active ->
            // When the stack empties, tear the window down (after the out-animation).
            if (!active) handler.postDelayed({ removeWindow(); stopSelf() }, 320)
        }
    }

    private fun removeWindow() {
        stack?.clear()
        stack = null
        host?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { /* already gone */ }
        }
        host = null
    }
}
