package de.koshi.photodream.model

import com.google.gson.annotations.SerializedName

/**
 * Device configuration received from Home Assistant
 */
data class DeviceConfig(
    @SerializedName("device_id")
    val deviceId: String,
    
    val immich: ImmichConfig,
    val display: DisplayConfig,
    val profile: ProfileConfig,
    
    @SerializedName("webhook_url")
    val webhookUrl: String
)

data class ImmichConfig(
    @SerializedName("base_url")
    val baseUrl: String,
    
    @SerializedName("api_key")
    val apiKey: String
)

data class DisplayConfig(
    val clock: Boolean = true,
    
    @SerializedName("clock_position")
    val clockPosition: Int = 2, // 0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right
    
    @SerializedName("clock_format")
    val clockFormat: String = "24h", // "12h" or "24h"
    
    val weather: Boolean = false,
    
    @SerializedName("interval_seconds")
    val intervalSeconds: Int = 30,
    
    @SerializedName("pan_speed")
    val panSpeed: Float = 0.5f, // Ken Burns effect speed
    
    val mode: String = "smart_shuffle" // display mode
)

data class ProfileConfig(
    val name: String,
    
    @SerializedName("search_queries")
    val searchQueries: List<String> = emptyList(),
    
    @SerializedName("exclude_paths")
    val excludePaths: List<String> = emptyList()
)

/**
 * Local app settings (stored on device)
 */
data class AppSettings(
    @SerializedName("ha_url")
    val haUrl: String = "",
    
    @SerializedName("device_id")
    val deviceId: String = "",
    
    @SerializedName("webhook_id")
    val webhookId: String = "",
    
    @SerializedName("server_port")
    val serverPort: Int = 8080
)

/**
 * Status sent to Home Assistant via webhook
 */
data class DeviceStatus(
    val online: Boolean = true,
    
    @SerializedName("current_image")
    val currentImage: String? = null,
    
    @SerializedName("current_image_url")
    val currentImageUrl: String? = null,
    
    val profile: String? = null,
    
    @SerializedName("last_refresh")
    val lastRefresh: String? = null
)
