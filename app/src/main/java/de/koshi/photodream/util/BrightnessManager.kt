package de.koshi.photodream.util

import android.content.Context
import android.provider.Settings
import android.util.Log

/**
 * Manages screen brightness across two modes:
 * - System brightness (0 to 100): Uses Android Settings
 * - Overlay dimming (-1 to -100): Black overlay with transparency
 * 
 * Combined range: -100 to +100
 * At 0: System brightness at 0%, no overlay
 * At 100: System brightness at 100%, no overlay
 * At -1: System brightness at 0%, overlay at 99% transparent (barely visible)
 * At -100: System brightness at 0%, overlay fully opaque (screen black)
 */
object BrightnessManager {
    
    private const val TAG = "BrightnessManager"
    
    // Current combined brightness value (-100 to +100)
    private var currentBrightness: Int = 50
    
    // Listener for overlay changes
    var onOverlayAlphaChanged: ((Float) -> Unit)? = null
    
    /**
     * Initialize with current system brightness
     */
    fun init(context: Context) {
        try {
            val systemBrightness = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS,
                128
            )
            // Convert 0-255 to 0-100
            currentBrightness = (systemBrightness * 100 / 255).coerceIn(0, 100)
            Log.i(TAG, "Initialized with system brightness: $currentBrightness")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read system brightness: ${e.message}")
            currentBrightness = 50
        }
    }
    
    /**
     * Get current brightness value (-100 to +100)
     */
    fun getBrightness(): Int = currentBrightness
    
    /**
     * Set brightness value (-100 to +100)
     * 
     * @param value Combined brightness value
     * @param context Context for system settings access
     * @return true if successful
     */
    fun setBrightness(value: Int, context: Context): Boolean {
        val clampedValue = value.coerceIn(-100, 100)
        currentBrightness = clampedValue
        
        return try {
            if (clampedValue >= 0) {
                // Positive: Use system brightness, disable overlay
                setSystemBrightness(clampedValue, context)
                onOverlayAlphaChanged?.invoke(0f) // Hide overlay
            } else {
                // Negative: System brightness to 0, use overlay
                setSystemBrightness(0, context)
                // Convert -1..-100 to alpha 0.01..1.0
                val alpha = (-clampedValue) / 100f
                onOverlayAlphaChanged?.invoke(alpha)
            }
            
            Log.i(TAG, "Brightness set to $clampedValue")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set brightness: ${e.message}", e)
            false
        }
    }
    
    /**
     * Set system brightness (0-100)
     */
    private fun setSystemBrightness(percent: Int, context: Context) {
        // Check if we have WRITE_SETTINGS permission
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "No WRITE_SETTINGS permission - cannot change system brightness")
            return
        }
        
        // Disable auto-brightness when manually setting
        try {
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            )
        } catch (e: Exception) {
            Log.w(TAG, "Could not disable auto-brightness: ${e.message}")
        }
        
        // Convert 0-100 to 0-255
        val brightness = (percent * 255 / 100).coerceIn(1, 255) // Min 1 to avoid full black
        
        Settings.System.putInt(
            context.contentResolver,
            Settings.System.SCREEN_BRIGHTNESS,
            brightness
        )
        
        Log.d(TAG, "System brightness set to $brightness (${percent}%)")
    }
    
    /**
     * Check if auto-brightness is enabled
     */
    fun isAutoBrightnessEnabled(context: Context): Boolean {
        return try {
            val mode = Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            )
            mode == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read auto-brightness mode: ${e.message}")
            false
        }
    }
    
    /**
     * Set auto-brightness mode
     */
    fun setAutoBrightness(enabled: Boolean, context: Context): Boolean {
        if (!Settings.System.canWrite(context)) {
            Log.w(TAG, "No WRITE_SETTINGS permission - cannot change auto-brightness")
            return false
        }
        
        return try {
            val mode = if (enabled) {
                Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC
            } else {
                Settings.System.SCREEN_BRIGHTNESS_MODE_MANUAL
            }
            
            Settings.System.putInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE,
                mode
            )
            
            // If enabling auto, disable our overlay and reset brightness tracking
            if (enabled) {
                onOverlayAlphaChanged?.invoke(0f)
                
                // Read current system brightness and update our tracking
                val systemBrightness = Settings.System.getInt(
                    context.contentResolver,
                    Settings.System.SCREEN_BRIGHTNESS,
                    128
                )
                currentBrightness = (systemBrightness * 100 / 255).coerceIn(0, 100)
            }
            
            Log.i(TAG, "Auto-brightness set to $enabled")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to set auto-brightness: ${e.message}", e)
            false
        }
    }
    
    /**
     * Check if device supports auto-brightness
     */
    fun hasAutoBrightnessSupport(context: Context): Boolean {
        return try {
            // Try to read the setting - if it exists, device supports it
            Settings.System.getInt(
                context.contentResolver,
                Settings.System.SCREEN_BRIGHTNESS_MODE
            )
            true
        } catch (e: Settings.SettingNotFoundException) {
            false
        }
    }
    
    /**
     * Check if we have permission to modify settings
     */
    fun hasWriteSettingsPermission(context: Context): Boolean {
        return Settings.System.canWrite(context)
    }
}
