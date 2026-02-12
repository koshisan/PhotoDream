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
    val clockPosition: Int = 2, // 0=top-left, 1=top-center, 2=top-right, 3=bottom-left, 4=bottom-center, 5=bottom-right, 6=center
    
    @SerializedName("clock_format")
    val clockFormat: String = "24h", // "12h" or "24h"
    
    @SerializedName("clock_font_size")
    val clockFontSize: Int = 32, // Font size in sp
    
    val date: Boolean = false, // Show date below clock
    
    @SerializedName("date_format")
    val dateFormat: String = "dd.MM.yyyy", // Date format string
    
    val weather: WeatherConfig? = null, // Weather display settings
    
    @SerializedName("interval_seconds")
    val intervalSeconds: Int = 30,
    
    @SerializedName("pan_speed")
    val panSpeed: Float = 0.5f, // Ken Burns effect speed
    
    val mode: String = "smart_shuffle" // display mode
)

/**
 * Weather display configuration
 */
data class WeatherConfig(
    val enabled: Boolean = false,
    
    @SerializedName("entity_id")
    val entityId: String? = null, // e.g. "weather.home"
    
    val condition: String? = null, // Current condition: sunny, cloudy, rainy, etc.
    
    val temperature: Float? = null, // Current temperature
    
    @SerializedName("temperature_unit")
    val temperatureUnit: String = "°C" // °C or °F
)

data class ProfileConfig(
    val name: String,
    
    @SerializedName("search_filter")
    val searchFilter: SearchFilter? = null,
    
    @SerializedName("exclude_paths")
    val excludePaths: List<String> = emptyList()
)

/**
 * Immich search filter - matches the Immich smart search API
 */
data class SearchFilter(
    val query: String? = null,
    
    @SerializedName("personIds")
    val personIds: List<String>? = null,
    
    @SerializedName("tagIds") 
    val tagIds: List<String>? = null,
    
    @SerializedName("albumId")
    val albumId: String? = null,
    
    @SerializedName("city")
    val city: String? = null,
    
    @SerializedName("country")
    val country: String? = null,
    
    @SerializedName("state")
    val state: String? = null,
    
    @SerializedName("takenAfter")
    val takenAfter: String? = null,
    
    @SerializedName("takenBefore")
    val takenBefore: String? = null,
    
    @SerializedName("isArchived")
    val isArchived: Boolean? = null,
    
    @SerializedName("isFavorite")
    val isFavorite: Boolean? = null,
    
    @SerializedName("type")
    val type: String? = null  // IMAGE or VIDEO
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
    
    val active: Boolean = false, // true when DreamService is in foreground
    
    @SerializedName("current_image")
    val currentImage: String? = null,
    
    @SerializedName("current_image_url")
    val currentImageUrl: String? = null,
    
    val profile: String? = null,
    
    @SerializedName("last_refresh")
    val lastRefresh: String? = null,
    
    @SerializedName("mac_address")
    val macAddress: String? = null,
    
    @SerializedName("ip_address")
    val ipAddress: String? = null,
    
    @SerializedName("display_width")
    val displayWidth: Int? = null,
    
    @SerializedName("display_height")
    val displayHeight: Int? = null,
    
    @SerializedName("app_version")
    val appVersion: String? = null
)
