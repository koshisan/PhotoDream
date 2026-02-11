package de.koshi.photodream.model

import com.google.gson.annotations.SerializedName
import java.time.Instant

/**
 * Immich API response models
 */

data class SearchResponse(
    val assets: SearchAssets
)

data class SearchAssets(
    val items: List<Asset>,
    val total: Int,
    val count: Int
)

data class Asset(
    val id: String,
    
    @SerializedName("originalPath")
    val originalPath: String,
    
    @SerializedName("originalFileName")
    val originalFileName: String? = null,
    
    @SerializedName("fileCreatedAt")
    val fileCreatedAt: String? = null,
    
    @SerializedName("localDateTime")
    val localDateTime: String? = null,
    
    val type: String = "IMAGE", // IMAGE or VIDEO
    
    @SerializedName("thumbhash")
    val thumbhash: String? = null
) {
    /**
     * Get thumbnail URL for this asset
     */
    fun getThumbnailUrl(baseUrl: String, size: ThumbnailSize = ThumbnailSize.PREVIEW): String {
        return "$baseUrl/api/assets/$id/thumbnail?size=${size.value}"
    }
    
    /**
     * Get original image URL
     */
    fun getOriginalUrl(baseUrl: String): String {
        return "$baseUrl/api/assets/$id/original"
    }
    
    /**
     * Parse creation date
     */
    fun getCreationInstant(): Instant? {
        return try {
            fileCreatedAt?.let { Instant.parse(it) }
        } catch (e: Exception) {
            null
        }
    }
}

enum class ThumbnailSize(val value: String) {
    THUMBNAIL("thumbnail"), // small
    PREVIEW("preview")      // larger, better for display
}

/**
 * Smart search request body - supports full Immich search filter
 */
data class SmartSearchRequest(
    val query: String? = null,
    val page: Int = 1,
    val size: Int = 100,
    val type: String = "IMAGE",
    
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
    val isFavorite: Boolean? = null
)
