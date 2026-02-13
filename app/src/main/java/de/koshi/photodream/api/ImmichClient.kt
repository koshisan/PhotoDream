package de.koshi.photodream.api

import android.util.Log
import de.koshi.photodream.model.Asset
import de.koshi.photodream.model.AssetDetails
import de.koshi.photodream.model.ImmichConfig
import de.koshi.photodream.model.RandomSearchRequest
import de.koshi.photodream.model.SearchFilter
import de.koshi.photodream.model.SmartSearchRequest
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.TimeUnit

/**
 * Client for Immich API communication
 * 
 * Display modes:
 * - sequential: Fixed order from /api/search/smart (same images each time)
 * - random: Random selection from /api/search/random (different each time)
 * - smart_shuffle: Mix of random + recent (last 30 days), interleaved 50/50
 */
class ImmichClient(private val config: ImmichConfig) {
    
    companion object {
        private const val TAG = "ImmichClient"
        private const val RECENT_DAYS = 30
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
     * Load playlist based on display mode
     * 
     * @param filter Search filter from profile config
     * @param mode Display mode: "sequential", "random", or "smart_shuffle"
     * @param limit Total number of images to load
     */
    suspend fun loadPlaylist(filter: SearchFilter?, mode: String, limit: Int = 500): List<Asset> {
        Log.i(TAG, "Loading playlist: mode=$mode, limit=$limit, filter=$filter")
        
        return when (mode) {
            "sequential" -> loadSequential(filter, limit)
            "random" -> loadRandom(filter, limit)
            "smart_shuffle" -> loadSmartShuffle(filter, limit)
            else -> {
                Log.w(TAG, "Unknown display mode '$mode', defaulting to smart_shuffle")
                loadSmartShuffle(filter, limit)
            }
        }
    }
    
    /**
     * Sequential mode: Chronological order (oldest to newest)
     * Fetches ALL matching images via pagination, then sorts by date.
     * 
     * Note: Can be slow for large result sets. Set appropriate filters
     * to keep the number of matching images reasonable.
     */
    private suspend fun loadSequential(filter: SearchFilter?, limit: Int): List<Asset> {
        val allAssets = fetchAllWithPagination(filter)
        
        // Sort chronologically (oldest first)
        val sorted = allAssets.sortedBy { asset ->
            asset.fileCreatedAt ?: asset.localDateTime ?: ""
        }
        
        Log.i(TAG, "Sequential: fetched ${allAssets.size} total, sorted chronologically")
        return sorted
    }
    
    /**
     * Fetch ALL matching assets using pagination
     */
    private suspend fun fetchAllWithPagination(filter: SearchFilter?, pageSize: Int = 500): List<Asset> {
        if (!hasFilter(filter)) {
            Log.i(TAG, "No filter for sequential - fetching via timeline would be better, using random fallback")
            return searchWithRandomApi(null, 500)
        }
        
        val allAssets = mutableListOf<Asset>()
        var page = 1
        var hasMore = true
        
        while (hasMore) {
            val request = SmartSearchRequest(
                query = filter!!.query,
                page = page,
                size = pageSize,
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
            
            try {
                val response = api.smartSearch(request)
                val items = response.assets.items
                allAssets.addAll(items)
                
                Log.d(TAG, "Pagination: page $page, got ${items.size}, total so far: ${allAssets.size}/${response.assets.total}")
                
                // Check if there are more pages
                hasMore = allAssets.size < response.assets.total && items.isNotEmpty()
                page++
                
                // Safety limit to prevent infinite loops
                if (page > 100) {
                    Log.w(TAG, "Pagination safety limit reached (100 pages)")
                    break
                }
            } catch (e: Exception) {
                Log.e(TAG, "Pagination failed at page $page: ${e.message}", e)
                break
            }
        }
        
        return allAssets.distinctBy { it.id }  // Deduplicate just in case
    }
    
    /**
     * Random mode: Random selection each time
     */
    private suspend fun loadRandom(filter: SearchFilter?, limit: Int): List<Asset> {
        return searchWithRandomApi(filter, limit)
    }
    
    /**
     * Smart shuffle mode: 50/50 mix of random + recent, interleaved
     * Combines "throwback memories" with "recent photos"
     */
    private suspend fun loadSmartShuffle(filter: SearchFilter?, limit: Int): List<Asset> {
        val halfLimit = limit / 2
        
        // Get random assets from all time
        val randomAssets = searchWithRandomApi(filter, halfLimit)
        
        // Get random assets from last 30 days
        val recentFilter = addRecentFilter(filter)
        val recentAssets = searchWithRandomApi(recentFilter, halfLimit)
        
        Log.i(TAG, "Smart shuffle: ${randomAssets.size} random + ${recentAssets.size} recent")
        
        // Interleave the two lists (alternating)
        return interleave(randomAssets, recentAssets)
    }
    
    /**
     * Add takenAfter filter for recent photos (last 30 days)
     */
    private fun addRecentFilter(filter: SearchFilter?): SearchFilter {
        val thirtyDaysAgo = Instant.now().minus(RECENT_DAYS.toLong(), ChronoUnit.DAYS).toString()
        
        return if (filter != null) {
            // Copy existing filter but override takenAfter
            filter.copy(takenAfter = thirtyDaysAgo)
        } else {
            SearchFilter(takenAfter = thirtyDaysAgo)
        }
    }
    
    /**
     * Interleave two lists: A1, B1, A2, B2, A3, B3...
     * If one list is shorter, remaining items from longer list are appended
     */
    private fun interleave(listA: List<Asset>, listB: List<Asset>): List<Asset> {
        val result = mutableListOf<Asset>()
        val maxLen = maxOf(listA.size, listB.size)
        
        for (i in 0 until maxLen) {
            if (i < listA.size) result.add(listA[i])
            if (i < listB.size) result.add(listB[i])
        }
        
        // Deduplicate (same image could be in both lists)
        return result.distinctBy { it.id }
    }
    
    /**
     * Search using /api/search/smart (fixed relevance order)
     */
    private suspend fun searchWithSmartApi(filter: SearchFilter?, limit: Int): List<Asset> {
        if (!hasFilter(filter)) {
            Log.i(TAG, "No filter for sequential mode - using random instead")
            return searchWithRandomApi(null, limit)
        }
        
        return try {
            val request = SmartSearchRequest(
                query = filter!!.query,
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
            Log.d(TAG, "Smart search returned ${response.assets.count} results (total: ${response.assets.total})")
            response.assets.items
        } catch (e: Exception) {
            Log.e(TAG, "Smart search failed: ${e.message}", e)
            emptyList()
        }
    }
    
    /**
     * Search using /api/search/random (random each time)
     */
    private suspend fun searchWithRandomApi(filter: SearchFilter?, count: Int): List<Asset> {
        return try {
            val request = RandomSearchRequest(
                count = count,
                type = filter?.type ?: "IMAGE",
                personIds = filter?.personIds,
                tagIds = filter?.tagIds,
                albumId = filter?.albumId,
                city = filter?.city,
                country = filter?.country,
                state = filter?.state,
                takenAfter = filter?.takenAfter,
                takenBefore = filter?.takenBefore,
                isArchived = filter?.isArchived,
                isFavorite = filter?.isFavorite
            )
            val assets = api.randomSearch(request)
            Log.d(TAG, "Random search returned ${assets.size} results")
            assets
        } catch (e: Exception) {
            Log.e(TAG, "Random search failed: ${e.message}", e)
            // Fallback to deprecated endpoint for older Immich versions
            try {
                Log.i(TAG, "Falling back to deprecated /api/assets/random")
                @Suppress("DEPRECATION")
                api.getRandomAssets(count)
            } catch (e2: Exception) {
                Log.e(TAG, "Fallback also failed: ${e2.message}", e2)
                emptyList()
            }
        }
    }
    
    /**
     * Check if filter has any criteria
     */
    private fun hasFilter(filter: SearchFilter?): Boolean {
        return filter != null && (
            filter.query != null ||
            !filter.personIds.isNullOrEmpty() ||
            !filter.tagIds.isNullOrEmpty() ||
            filter.albumId != null ||
            filter.city != null ||
            filter.country != null ||
            filter.state != null ||
            filter.takenAfter != null ||
            filter.takenBefore != null ||
            filter.isArchived != null ||
            filter.isFavorite != null
        )
    }
    
    // ========== Legacy methods for compatibility ==========
    
    /**
     * @deprecated Use loadPlaylist with mode parameter instead
     */
    @Deprecated("Use loadPlaylist instead", ReplaceWith("loadPlaylist(filter, mode, limit)"))
    suspend fun searchWithFilter(filter: SearchFilter?, limit: Int = 200): List<Asset> {
        return loadPlaylist(filter, "smart_shuffle", limit)
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
    
    /**
     * Get detailed info for a specific asset (people, tags, exif)
     */
    suspend fun getAssetDetails(assetId: String): AssetDetails? {
        return try {
            val details = api.getAssetDetails(assetId)
            Log.d(TAG, "Got asset details: ${details.originalFileName}, ${details.people?.size ?: 0} people, ${details.tags?.size ?: 0} tags")
            details
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get asset details: ${e.message}", e)
            null
        }
    }
}
