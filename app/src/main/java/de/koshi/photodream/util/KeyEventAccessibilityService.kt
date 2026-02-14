package de.koshi.photodream.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.util.Log
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.google.gson.Gson
import de.koshi.photodream.model.AppSettings

/**
 * Accessibility Service for capturing global key events
 * 
 * Captures key presses system-wide and forwards them to Home Assistant
 * via the configured webhook.
 * 
 * User must enable this service manually:
 * Settings → Accessibility → PhotoDream Key Capture → Enable
 */
class KeyEventAccessibilityService : AccessibilityService() {
    
    companion object {
        private const val TAG = "KeyEventService"
        private const val WEBHOOK_PATH = "photo_dream_key_event"
    }
    
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()
    private val gson = Gson()
    
    private var haUrl: String? = null
    private var deviceId: String? = null
    
    override fun onServiceConnected() {
        super.onServiceConnected()
        
        // Configure to receive key events
        serviceInfo = serviceInfo.apply {
            flags = flags or AccessibilityServiceInfo.FLAG_REQUEST_FILTER_KEY_EVENTS
        }
        
        // Load settings
        loadSettings()
        
        Log.i(TAG, "KeyEventAccessibilityService connected")
    }
    
    private fun loadSettings() {
        try {
            val prefs = getSharedPreferences("app_settings", MODE_PRIVATE)
            val settingsJson = prefs.getString("settings", null)
            if (settingsJson != null) {
                // Try to read from the app's config file instead
            }
            
            // Read from ConfigManager's file
            val file = java.io.File(filesDir, "app_settings.json")
            if (file.exists()) {
                val settings = gson.fromJson(file.readText(), AppSettings::class.java)
                haUrl = settings.haUrl
                deviceId = settings.deviceId
                Log.i(TAG, "Loaded settings: haUrl=$haUrl, deviceId=$deviceId")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load settings: ${e.message}")
        }
    }
    
    override fun onKeyEvent(event: KeyEvent): Boolean {
        // Only handle key down events to avoid duplicates
        if (event.action != KeyEvent.ACTION_DOWN) {
            return false // Don't consume, let system handle
        }
        
        val keyCode = event.keyCode
        val keyName = KeyEvent.keyCodeToString(keyCode)
        
        Log.d(TAG, "Key pressed: $keyName ($keyCode)")
        
        // Send to Home Assistant
        sendKeyEventToHA(keyCode, keyName)
        
        // Return false to let the key event propagate normally
        // Return true to consume the event (block it from other apps)
        return false
    }
    
    private fun sendKeyEventToHA(keyCode: Int, keyName: String) {
        val url = haUrl
        val device = deviceId
        
        if (url.isNullOrBlank() || device.isNullOrBlank()) {
            Log.w(TAG, "HA not configured, skipping key event")
            return
        }
        
        scope.launch {
            try {
                val webhookUrl = "${url.trimEnd('/')}/api/webhook/$WEBHOOK_PATH"
                
                val payload = gson.toJson(mapOf(
                    "device_id" to device,
                    "event_type" to "key_press",
                    "key_code" to keyCode,
                    "key_name" to keyName
                ))
                
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(payload.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    Log.d(TAG, "Key event sent to HA: $keyName")
                } else {
                    Log.w(TAG, "HA responded with ${response.code}")
                }
                
                response.close()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to send key event: ${e.message}")
            }
        }
    }
    
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // Not used, but required to implement
    }
    
    override fun onInterrupt() {
        Log.w(TAG, "KeyEventAccessibilityService interrupted")
    }
    
    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
        Log.i(TAG, "KeyEventAccessibilityService destroyed")
    }
}
