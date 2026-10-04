package de.koshi.photodream.api

import android.util.Log
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import de.koshi.photodream.model.Asset
import de.koshi.photodream.model.ExifInfo
import de.koshi.photodream.model.SearchFilter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Flickr photo source: free-text search via flickr.photos.search.
 *
 * Always requests safe_search=1 (unauthenticated calls only ever return "safe" photos anyway).
 * Note that Flickr's safety level is self-rated by the uploader, so it is not a guarantee.
 * Assets carry a public [Asset.directUrl], which the renderer loads without Immich auth.
 */
class FlickrClient(private val apiKey: String, private val maxDimension: Int) {

    companion object {
        private const val TAG = "FlickrClient"
        private const val ENDPOINT = "https://api.flickr.com/services/rest/"
        private const val PER_PAGE = 250
        // Flickr returns at most 4000 unique results per search
        private const val MAX_PAGES = 4000 / PER_PAGE
    }

    private val http = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
    private val gson = Gson()

    /**
     * Same contract as [ImmichClient.loadPlaylist]. The search term is the filter's query.
     * An empty query yields an empty list -- never an unfiltered "everything" result.
     */
    suspend fun loadPlaylist(
        filter: SearchFilter?,
        mode: String,
        limit: Int = PER_PAGE * 2,
        page: Int = 1
    ): Pair<List<Asset>, Boolean> = withContext(Dispatchers.IO) {
        val query = filter?.query?.trim()
        if (query.isNullOrEmpty()) {
            Log.w(TAG, "No search term configured - showing nothing")
            return@withContext Pair(emptyList<Asset>(), false)
        }
        try {
            if (mode == "sequential") {
                // Fixed relevance order, paged
                val result = search(query, page) ?: return@withContext Pair(emptyList<Asset>(), false)
                val pages = minOf(result.pages, MAX_PAGES)
                Pair(result.photo.mapNotNull { it.toAsset() }, page < pages)
            } else {
                // random / smart_shuffle: first page plus one random further page, shuffled
                val first = search(query, 1) ?: return@withContext Pair(emptyList<Asset>(), false)
                val pages = minOf(first.pages, MAX_PAGES)
                val photos = first.photo.toMutableList()
                if (pages > 1 && photos.size < limit) {
                    search(query, (2..pages).random())?.let { photos += it.photo }
                }
                val assets = photos.mapNotNull { it.toAsset() }.distinctBy { it.id }.shuffled()
                Pair(assets.take(limit), false)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Flickr search failed: ${e.message}", e)
            Pair(emptyList(), false)
        }
    }

    private fun search(query: String, page: Int): PhotoPage? {
        val url = ENDPOINT.toHttpUrl().newBuilder()
            .addQueryParameter("method", "flickr.photos.search")
            .addQueryParameter("api_key", apiKey)
            .addQueryParameter("text", query)
            .addQueryParameter("safe_search", "1")
            .addQueryParameter("content_types", "0") // photos only, no screenshots/art
            .addQueryParameter("media", "photos")
            .addQueryParameter("sort", "relevance")
            .addQueryParameter("extras", "url_l,url_h,url_k,date_taken,owner_name")
            .addQueryParameter("per_page", PER_PAGE.toString())
            .addQueryParameter("page", page.toString())
            .addQueryParameter("format", "json")
            .addQueryParameter("nojsoncallback", "1")
            .build()
        http.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            val body = resp.body?.string()
            if (!resp.isSuccessful || body == null) {
                Log.e(TAG, "Flickr HTTP ${resp.code}")
                return null
            }
            val parsed = gson.fromJson(body, SearchResponse::class.java)
            if (parsed.stat != "ok" || parsed.photos == null) {
                Log.e(TAG, "Flickr error ${parsed.code}: ${parsed.message}")
                return null
            }
            Log.d(TAG, "Flickr '$query' page $page/${parsed.photos.pages}: ${parsed.photos.photo.size} photos")
            return parsed.photos
        }
    }

    /** Pick the smallest size that still covers the display, falling back to what exists. */
    private fun Photo.toAsset(): Asset? {
        val sizes = listOfNotNull(
            urlL?.let { Triple(it, widthL, heightL) },
            urlH?.let { Triple(it, widthH, heightH) },
            urlK?.let { Triple(it, widthK, heightK) }
        )
        if (sizes.isEmpty()) return null
        val (url, w, h) = sizes.firstOrNull { (_, w, h) -> maxOf(w ?: 0, h ?: 0) >= maxDimension }
            ?: sizes.last()
        return Asset(
            id = "flickr_$id",
            originalPath = "flickr/${ownerName ?: owner}/$id",
            originalFileName = title?.takeIf { it.isNotBlank() },
            fileCreatedAt = dateTaken,
            type = "IMAGE",
            exifInfo = if (w != null && h != null) ExifInfo(exifImageWidth = w, exifImageHeight = h) else null,
            directUrl = url
        )
    }

    private data class SearchResponse(
        val stat: String?,
        val code: Int?,
        val message: String?,
        val photos: PhotoPage?
    )

    private data class PhotoPage(
        val page: Int = 1,
        val pages: Int = 1,
        val photo: List<Photo> = emptyList()
    )

    private data class Photo(
        val id: String,
        val owner: String?,
        val title: String?,
        @SerializedName("ownername") val ownerName: String?,
        @SerializedName("datetaken") val dateTaken: String?,
        @SerializedName("url_l") val urlL: String?,
        @SerializedName("width_l") val widthL: Int?,
        @SerializedName("height_l") val heightL: Int?,
        @SerializedName("url_h") val urlH: String?,
        @SerializedName("width_h") val widthH: Int?,
        @SerializedName("height_h") val heightH: Int?,
        @SerializedName("url_k") val urlK: String?,
        @SerializedName("width_k") val widthK: Int?,
        @SerializedName("height_k") val heightK: Int?
    )
}
