package de.koshi.photodream.util

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import com.google.gson.Gson
import de.koshi.photodream.model.AppSettings
import de.koshi.photodream.model.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Manages app settings and device configuration
 */
object ConfigManager {
    
    private const val TAG = "ConfigManager"
    private const val SETTINGS_FILE = "app_settings.json"
    private const val CONFIG_CACHE_FILE = "device_config_cache.json"
    private const val REGISTER_WEBHOOK = "photo_dream_register"
    
    private val gson = Gson()
    private val httpClient = OkHttpClient()
    
    /**
     * Get local app settings
     */
    fun getSettings(context: Context): AppSettings {
        val file = File(context.filesDir, SETTINGS_FILE)
        return if (file.exists()) {
            try {
                gson.fromJson(file.readText(), AppSettings::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load settings: ${e.message}")
                AppSettings()
            }
        } else {
            AppSettings()
        }
    }
    
    /**
     * Save local app settings
     */
    fun saveSettings(context: Context, settings: AppSettings) {
        val file = File(context.filesDir, SETTINGS_FILE)
        file.writeText(gson.toJson(settings))
        Log.d(TAG, "Settings saved")
    }
    
    /**
     * Register device with Home Assistant
     * Returns: "pending", "configured", or null on error
     */
    suspend fun registerWithHA(context: Context): String? {
        val settings = getSettings(context)
        
        if (settings.haUrl.isBlank() || settings.deviceId.isBlank()) {
            Log.w(TAG, "HA URL or Device ID not configured")
            return null
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val deviceIp = getDeviceIpAddress(context)
                if (deviceIp == null) {
                    Log.e(TAG, "Could not determine device IP")
                    return@withContext null
                }
                
                val url = "${settings.haUrl.trimEnd('/')}/api/webhook/$REGISTER_WEBHOOK"
                Log.d(TAG, "Registering with HA: $url")
                
                val body = gson.toJson(mapOf(
                    "device_id" to settings.deviceId,
                    "device_ip" to deviceIp,
                    "device_port" to settings.serverPort
                ))
                
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val result = gson.fromJson(responseBody, Map::class.java)
                    val status = result["status"] as? String
                    
                    Log.i(TAG, "Registration response: $status")
                    
                    // If configured, save the config
                    if (status == "configured") {
                        val config = result["config"]
                        if (config != null) {
                            val configJson = gson.toJson(config)
                            val deviceConfig = gson.fromJson(configJson, DeviceConfig::class.java)
                            cacheConfig(context, deviceConfig)
                        }
                    }
                    
                    status
                } else {
                    Log.e(TAG, "Registration failed: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error registering with HA: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Poll HA for configuration (used while waiting for approval)
     */
    suspend fun pollForConfig(context: Context): DeviceConfig? {
        val settings = getSettings(context)
        
        if (settings.haUrl.isBlank() || settings.deviceId.isBlank()) {
            return null
        }
        
        return withContext(Dispatchers.IO) {
            try {
                val url = "${settings.haUrl.trimEnd('/')}/api/webhook/$REGISTER_WEBHOOK"
                Log.d(TAG, "Polling for config: $url")
                
                // Use POST with poll action (GET not supported by HA webhooks)
                val body = gson.toJson(mapOf(
                    "action" to "poll",
                    "device_id" to settings.deviceId
                ))
                
                val request = Request.Builder()
                    .url(url)
                    .post(body.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val responseBody = response.body?.string()
                    val result = gson.fromJson(responseBody, Map::class.java)
                    val status = result["status"] as? String
                    
                    if (status == "configured") {
                        val config = result["config"]
                        if (config != null) {
                            val configJson = gson.toJson(config)
                            val deviceConfig = gson.fromJson(configJson, DeviceConfig::class.java)
                            cacheConfig(context, deviceConfig)
                            return@withContext deviceConfig
                        }
                    }
                }
                null
            } catch (e: Exception) {
                Log.e(TAG, "Error polling for config: ${e.message}", e)
                null
            }
        }
    }
    
    /**
     * Load device config - from cache or poll HA
     */
    suspend fun loadConfig(context: Context, forceRefresh: Boolean = false): DeviceConfig? {
        // Try cache first
        if (!forceRefresh) {
            val cached = loadCachedConfig(context)
            if (cached != null) {
                return cached
            }
        }
        
        // Try polling HA
        return pollForConfig(context)
    }
    
    /**
     * Save config received from HA (called from HTTP server)
     */
    fun saveConfigFromHA(context: Context, config: DeviceConfig) {
        cacheConfig(context, config)
    }
    
    /**
     * Cache config to disk
     */
    private fun cacheConfig(context: Context, config: DeviceConfig) {
        val file = File(context.filesDir, CONFIG_CACHE_FILE)
        file.writeText(gson.toJson(config))
        Log.d(TAG, "Config cached")
    }
    
    /**
     * Load cached config from disk
     */
    fun loadCachedConfig(context: Context): DeviceConfig? {
        val file = File(context.filesDir, CONFIG_CACHE_FILE)
        return if (file.exists()) {
            try {
                gson.fromJson(file.readText(), DeviceConfig::class.java)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load cached config: ${e.message}")
                null
            }
        } else {
            null
        }
    }
    
    /**
     * Clear cached config
     */
    fun clearCache(context: Context) {
        File(context.filesDir, CONFIG_CACHE_FILE).delete()
        Log.d(TAG, "Config cache cleared")
    }
    
    /**
     * Get device's IP address
     */
    private fun getDeviceIpAddress(context: Context): String? {
        try {
            // Try NetworkInterface first (works on most devices)
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
            
            // Fallback: WifiManager (deprecated but still works)
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}", e)
        }
        return null
    }
}
