package de.koshi.photodream.api

import android.util.Log
import de.koshi.photodream.model.Asset
import de.koshi.photodream.model.ImmichConfig
import de.koshi.photodream.model.SearchFilter
import de.koshi.photodream.model.SmartSearchRequest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.util.concurrent.TimeUnit

/**
 * Client for Immich API communication
 */
class ImmichClient(private val config: ImmichConfig) {
    
    companion object {
        private const val TAG = "ImmichClient"
    }
    
    private val api: ImmichApi
    
    init {
        val authInterceptor = Interceptor { chain ->
            val request = chain.request().newBuilder()
                .addHeader("x-api-key", config.apiKey)
                .addHeader("Accept", "application/json")
                .build()
            chain.proceed(request)
        }
        
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        
        val client = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
        
        val retrofit = Retrofit.Builder()
            .baseUrl(config.baseUrl.trimEnd('/') + "/")
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
        
        api = retrofit.create(ImmichApi::class.java)
    }
    
    /**
     * Search for assets using smart search (ML-based)
     */
    suspend fun smartSearch(query: String, limit: Int = 100): List<Asset> {
        return try {
            val request = SmartSearchRequest(
                query = query,
                size = limit,
                type = "IMAGE"
            )
            val response = api.smartSearch(request)
            Log.d(TAG, "Smart search '$query' returned ${response.assets.count} results")
            response.assets.items
        } catch (e: Exception) {
            Log.e(TAG, "Smart search failed: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Search using a SearchFilter object (from Immich URL)
     */
    suspend fun searchWithFilter(filter: SearchFilter?, limit: Int = 200): List<Asset> {
        if (filter == null) {
            Log.w(TAG, "No search filter provided")
            return emptyList()
        }
        
        return try {
            val request = SmartSearchRequest(
                query = filter.query,
                size = limit,
                type = filter.type ?: "IMAGE",
                personIds = filter.personIds,
                tagIds = filter.tagIds,
                albumId = filter.albumId,
                city = filter.city,
                country = filter.country,
                state = filter.state,
                takenAfter = filter.takenAfter,
                takenBefore = filter.takenBefore,
                isArchived = filter.isArchived,
                isFavorite = filter.isFavorite
            )
            val response = api.smartSearch(request)
            Log.d(TAG, "Search with filter returned ${response.assets.count} results")
            response.assets.items
        } catch (e: Exception) {
            Log.e(TAG, "Search with filter failed: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Search multiple queries and combine results (deduplicated)
     * @deprecated Use searchWithFilter instead
     */
    suspend fun searchMultiple(queries: List<String>, limitPerQuery: Int = 100): List<Asset> {
        val allAssets = mutableMapOf<String, Asset>()
        
        for (query in queries) {
            val results = smartSearch(query, limitPerQuery)
            results.forEach { asset ->
                allAssets[asset.id] = asset
            }
        }
        
        Log.d(TAG, "Combined search returned ${allAssets.size} unique assets")
        return allAssets.values.toList()
    }
    
    /**
     * Get thumbnail URL for an asset
     */
    fun getThumbnailUrl(assetId: String): String {
        return "${config.baseUrl.trimEnd('/')}/api/assets/$assetId/thumbnail?size=preview"
    }
    
    /**
     * Get full headers map for image loading (Glide)
     */
    fun getAuthHeaders(): Map<String, String> {
        return mapOf("x-api-key" to config.apiKey)
    }
}
