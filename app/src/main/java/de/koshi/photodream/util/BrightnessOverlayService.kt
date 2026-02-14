package de.koshi.photodream.util

import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.util.Log
import android.view.View
import android.view.WindowManager

/**
 * Overlay Service for sub-zero brightness dimming
 * 
 * Creates a black overlay that covers the screen but passes through all touch events.
 * Used when brightness is set below 0 (-1 to -100).
 * 
 * Requires SYSTEM_ALERT_WINDOW permission.
 */
class BrightnessOverlayService : Service() {
    
    companion object {
        private const val TAG = "BrightnessOverlay"
        
        private var instance: BrightnessOverlayService? = null
        
        /**
         * Start the overlay service
         */
        fun start(context: Context) {
            if (!Settings.canDrawOverlays(context)) {
                Log.w(TAG, "No SYSTEM_ALERT_WINDOW permission")
                return
            }
            
            val intent = Intent(context, BrightnessOverlayService::class.java)
            context.startService(intent)
        }
        
        /**
         * Stop the overlay service
         */
        fun stop(context: Context) {
            context.stopService(Intent(context, BrightnessOverlayService::class.java))
        }
        
        /**
         * Update overlay alpha directly (if service is running)
         */
        fun setAlpha(alpha: Float) {
            instance?.updateOverlayAlpha(alpha)
        }
        
        /**
         * Check if service is running
         */
        fun isRunning(): Boolean = instance != null
    }
    
    private var windowManager: WindowManager? = null
    private var overlayView: View? = null
    private var currentAlpha: Float = 0f
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "BrightnessOverlayService created")
        
        instance = this
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        createOverlay()
        
        // Register with BrightnessManager
        BrightnessManager.onOverlayAlphaChanged = { alpha ->
            updateOverlayAlpha(alpha)
        }
    }
    
    override fun onDestroy() {
        removeOverlay()
        BrightnessManager.onOverlayAlphaChanged = null
        instance = null
        super.onDestroy()
        Log.d(TAG, "BrightnessOverlayService destroyed")
    }
    
    override fun onBind(intent: Intent?): IBinder? = null
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
    
    private fun createOverlay() {
        if (overlayView != null) return
        
        // Create a simple black view
        overlayView = View(this).apply {
            setBackgroundColor(Color.BLACK)
            alpha = 0f // Start invisible
        }
        
        // Window parameters - the key is FLAG_NOT_TOUCHABLE
        val params = WindowManager.LayoutParams().apply {
            // Cover the entire screen
            width = WindowManager.LayoutParams.MATCH_PARENT
            height = WindowManager.LayoutParams.MATCH_PARENT
            
            // Overlay type (depends on Android version)
            type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_SYSTEM_OVERLAY
            }
            
            // CRITICAL: These flags make the overlay non-interactive
            flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            
            // Translucent to allow alpha blending
            format = PixelFormat.TRANSLUCENT
        }
        
        try {
            windowManager?.addView(overlayView, params)
            Log.i(TAG, "Overlay created")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to create overlay: ${e.message}", e)
            overlayView = null
        }
    }
    
    private fun removeOverlay() {
        overlayView?.let { view ->
            try {
                windowManager?.removeView(view)
                Log.i(TAG, "Overlay removed")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to remove overlay: ${e.message}", e)
            }
        }
        overlayView = null
    }
    
    /**
     * Update overlay transparency
     * 
     * @param alpha 0.0 = invisible, 1.0 = fully opaque (black screen)
     */
    fun updateOverlayAlpha(alpha: Float) {
        val clampedAlpha = alpha.coerceIn(0f, 1f)
        
        if (clampedAlpha == currentAlpha) return
        currentAlpha = clampedAlpha
        
        overlayView?.let { view ->
            if (clampedAlpha <= 0f) {
                // Hide overlay completely when not needed
                view.visibility = View.GONE
                view.alpha = 0f
            } else {
                view.visibility = View.VISIBLE
                view.alpha = clampedAlpha
            }
            
            Log.d(TAG, "Overlay alpha set to $clampedAlpha")
        }
    }
}
