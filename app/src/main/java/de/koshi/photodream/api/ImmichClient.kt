package de.koshi.photodream.api

import android.util.Log
import de.koshi.photodream.model.Asset
import de.koshi.photodream.model.ImmichConfig
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
     * Search multiple queries and combine results (deduplicated)
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
