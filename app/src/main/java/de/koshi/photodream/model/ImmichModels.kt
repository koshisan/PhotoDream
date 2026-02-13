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
 * Extended asset details from GET /api/assets/{id}
 * Contains additional info not included in search results
 */
data class AssetDetails(
    val id: String,
    
    @SerializedName("originalPath")
    val originalPath: String? = null,
    
    @SerializedName("originalFileName")
    val originalFileName: String? = null,
    
    @SerializedName("fileCreatedAt")
    val fileCreatedAt: String? = null,
    
    @SerializedName("localDateTime")
    val localDateTime: String? = null,
    
    @SerializedName("people")
    val people: List<PersonInfo>? = null,
    
    @SerializedName("tags")
    val tags: List<TagInfo>? = null,
    
    @SerializedName("exifInfo")
    val exifInfo: ExifInfo? = null,
    
    @SerializedName("isFavorite")
    val isFavorite: Boolean = false
)

data class PersonInfo(
    val id: String,
    val name: String? = null,
    
    @SerializedName("thumbnailPath")
    val thumbnailPath: String? = null
)

data class TagInfo(
    val id: String,
    val name: String? = null,
    val value: String? = null  // Some tags have value, e.g. "Rating: 5"
)

data class ExifInfo(
    val make: String? = null,           // Camera brand (e.g. "Sony")
    val model: String? = null,          // Camera model (e.g. "ILCE-7M3")
    
    @SerializedName("lensModel")
    val lensModel: String? = null,      // Lens (e.g. "FE 24-70mm F2.8 GM")
    
    @SerializedName("fNumber")
    val fNumber: Float? = null,         // Aperture (e.g. 2.8)
    
    @SerializedName("exposureTime")
    val exposureTime: String? = null,   // Shutter speed (e.g. "1/250")
    
    val iso: Int? = null,               // ISO value
    
    @SerializedName("focalLength")
    val focalLength: Float? = null,     // Focal length in mm
    
    val city: String? = null,
    val state: String? = null,
    val country: String? = null,
    
    @SerializedName("dateTimeOriginal")
    val dateTimeOriginal: String? = null
)

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

/**
 * Random search request body - returns random assets matching filters
 * Used for random and smart_shuffle display modes
 */
data class RandomSearchRequest(
    val count: Int = 250,
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
    val isFavorite: Boolean? = null,
    
    // Note: query field for semantic search not supported in random endpoint
)
