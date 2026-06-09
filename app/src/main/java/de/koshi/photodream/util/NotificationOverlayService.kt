package de.koshi.photodream.util

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.widget.LinearLayout
import de.koshi.photodream.ui.NotificationCenter
import de.koshi.photodream.ui.NotificationStack

/**
 * Hosts the shared notification stack in a floating system-overlay window when the
 * slideshow isn't running. State (active notifications + timers) lives in
 * [NotificationCenter]; this service is just the fallback renderer.
 *
 * Requires SYSTEM_ALERT_WINDOW ("draw over other apps").
 */
class NotificationOverlayService : Service() {

    companion object {
        private const val TAG = "NotifOverlay"

        /** Start the overlay (idempotent). It renders whatever the center currently holds. */
        fun ensure(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "No SYSTEM_ALERT_WINDOW permission - overlay notifications unavailable")
                return
            }
            context.startService(Intent(context, NotificationOverlayService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NotificationOverlayService::class.java))
        }
    }

    private var windowManager: WindowManager? = null
    private var host: LinearLayout? = null
    private var stack: NotificationStack? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        if (!ensureWindow()) { stopSelf(); return }
        stack?.let { NotificationCenter.attachOverlay(it) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        stack?.let { NotificationCenter.detachOverlay(it) }
        removeWindow()
        super.onDestroy()
    }

    private fun ensureWindow(): Boolean {
        if (host != null) return true

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
            return false
        }
        host = container
        // Solid stack (no frost backdrop). When it empties or is detached, tear down.
        stack = NotificationStack(this, container, onActiveChanged = { active ->
            if (!active) { NotificationCenter.detachOverlay(stack!!); removeWindow(); stopSelf() }
        })
        return true
    }

    private fun removeWindow() {
        host?.let {
            try { windowManager?.removeView(it) } catch (e: Exception) { /* already gone */ }
        }
        host = null
        stack = null
    }
}
