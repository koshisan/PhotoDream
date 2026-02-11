package de.koshi.photodream.api

import de.koshi.photodream.model.SearchResponse
import de.koshi.photodream.model.SmartSearchRequest
import retrofit2.http.*

/**
 * Retrofit interface for Immich API
 */
interface ImmichApi {
    
    /**
     * Smart search using ML-based search
     */
    @POST("api/search/smart")
    suspend fun smartSearch(
        @Body request: SmartSearchRequest
    ): SearchResponse
}
