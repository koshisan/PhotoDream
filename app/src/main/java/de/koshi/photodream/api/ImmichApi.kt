package de.koshi.photodream.api

import de.koshi.photodream.model.Asset
import de.koshi.photodream.model.RandomSearchRequest
import de.koshi.photodream.model.SearchResponse
import de.koshi.photodream.model.SmartSearchRequest
import retrofit2.http.*

/**
 * Retrofit interface for Immich API
 */
interface ImmichApi {
    
    /**
     * Smart search using ML-based search (returns fixed "relevance" order)
     * Use for: sequential mode
     */
    @POST("api/search/smart")
    suspend fun smartSearch(
        @Body request: SmartSearchRequest
    ): SearchResponse
    
    /**
     * Random search with filters (returns random selection each time)
     * Use for: random mode, smart_shuffle mode
     */
    @POST("api/search/random")
    suspend fun randomSearch(
        @Body request: RandomSearchRequest
    ): List<Asset>
    
    /**
     * Get random assets (deprecated - use randomSearch instead)
     */
    @Deprecated("Use randomSearch with filters instead")
    @GET("api/assets/random")
    suspend fun getRandomAssets(
        @Query("count") count: Int = 200
    ): List<Asset>
}
