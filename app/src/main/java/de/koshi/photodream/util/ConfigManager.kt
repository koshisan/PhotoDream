package de.koshi.photodream.util

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import de.koshi.photodream.model.AppSettings
import de.koshi.photodream.model.DeviceConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File

/**
 * Manages app settings and device configuration
 */
object ConfigManager {
    
    private const val TAG = "ConfigManager"
    private const val SETTINGS_FILE = "app_settings.json"
    private const val CONFIG_CACHE_FILE = "device_config_cache.json"
    
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
     * Load device config from Home Assistant or cache
     */
    suspend fun loadConfig(context: Context, forceRefresh: Boolean = false): DeviceConfig? {
        val settings = getSettings(context)
        
        if (settings.haUrl.isBlank() || settings.webhookId.isBlank()) {
            Log.w(TAG, "HA URL or Webhook ID not configured")
            return loadCachedConfig(context)
        }
        
        // Try to fetch from HA
        if (forceRefresh || !hasCachedConfig(context)) {
            val freshConfig = fetchConfigFromHA(settings)
            if (freshConfig != null) {
                cacheConfig(context, freshConfig)
                return freshConfig
            }
        }
        
        return loadCachedConfig(context)
    }
    
    /**
     * Fetch config from Home Assistant webhook
     */
    private suspend fun fetchConfigFromHA(settings: AppSettings): DeviceConfig? {
        return withContext(Dispatchers.IO) {
            try {
                val url = "${settings.haUrl.trimEnd('/')}/api/webhook/${settings.webhookId}"
                Log.d(TAG, "Fetching config from: $url")
                
                val request = Request.Builder()
                    .url(url)
                    .get()
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body?.string()
                    val config = gson.fromJson(body, DeviceConfig::class.java)
                    Log.i(TAG, "Config loaded from HA: device=${config.deviceId}, profile=${config.profile.name}")
                    config
                } else {
                    Log.e(TAG, "Failed to fetch config: ${response.code}")
                    null
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching config: ${e.message}", e)
                null
            }
        }
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
    private fun loadCachedConfig(context: Context): DeviceConfig? {
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
     * Check if cached config exists
     */
    private fun hasCachedConfig(context: Context): Boolean {
        return File(context.filesDir, CONFIG_CACHE_FILE).exists()
    }
    
    /**
     * Clear cached config
     */
    fun clearCache(context: Context) {
        File(context.filesDir, CONFIG_CACHE_FILE).delete()
        Log.d(TAG, "Config cache cleared")
    }
}
