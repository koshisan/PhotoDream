package de.koshi.photodream.util

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.JsonParser
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Resolves a landscape background image for an artist name, for the media player's
 * focus mode. Tries fanart.tv (HD "artistbackground" via a MusicBrainz id lookup,
 * needs a free project key) and falls back to TheAudioDB (free, no key).
 *
 * Results (including "not found") are cached per artist; lookups run on a background
 * thread and the callback is delivered on the main thread.
 */
object ArtistImageProvider {

    private const val TAG = "ArtistImage"
    private const val UA = "PhotoDream/1.0 (https://github.com/koshisan/PhotoDream)"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()
    private val main = Handler(Looper.getMainLooper())

    private val cache = HashMap<String, String?>()   // artist(lower) -> url (or null = none)
    private val inFlight = HashSet<String>()

    /** Look up a background image URL for [artist]. Callback gets null if none found. */
    fun fetch(artist: String?, fanartKey: String?, callback: (String?) -> Unit) {
        val name = artist?.trim().orEmpty()
        if (name.isEmpty()) { callback(null); return }
        val key = name.lowercase()

        synchronized(cache) {
            if (cache.containsKey(key)) { callback(cache[key]); return }
            if (inFlight.contains(key)) { return }  // a lookup is already running
            inFlight.add(key)
        }

        Thread {
            val url = try {
                resolve(name, fanartKey)
            } catch (e: Exception) {
                Log.e(TAG, "Lookup failed for '$name': ${e.message}"); null
            }
            synchronized(cache) {
                cache[key] = url
                inFlight.remove(key)
            }
            main.post { callback(url) }
        }.start()
    }

    private fun resolve(artist: String, fanartKey: String?): String? {
        if (!fanartKey.isNullOrBlank()) {
            mbid(artist)?.let { id ->
                fanartBackground(id, fanartKey)?.let { return it }
            }
        }
        return audioDbFanart(artist)
    }

    /** MusicBrainz: artist name -> MBID. */
    private fun mbid(artist: String): String? {
        val q = URLEncoder.encode("artist:\"$artist\"", "UTF-8")
        val url = "https://musicbrainz.org/ws/2/artist/?query=$q&fmt=json&limit=1"
        getJson(url)?.let { root ->
            val arr = root.asJsonObject.getAsJsonArray("artists") ?: return null
            if (arr.size() == 0) return null
            return arr[0].asJsonObject.get("id")?.asString
        }
        return null
    }

    /** fanart.tv: MBID -> first HD artist background URL. */
    private fun fanartBackground(mbid: String, key: String): String? {
        val url = "https://webservice.fanart.tv/v3/music/$mbid?api_key=$key"
        getJson(url)?.let { root ->
            val arr = root.asJsonObject.getAsJsonArray("artistbackground") ?: return null
            if (arr.size() == 0) return null
            return arr[0].asJsonObject.get("url")?.asString
        }
        return null
    }

    /** TheAudioDB (free): artist name -> fanart (landscape) or wide thumb. */
    private fun audioDbFanart(artist: String): String? {
        val q = URLEncoder.encode(artist, "UTF-8")
        val url = "https://www.theaudiodb.com/api/v1/json/2/search.php?s=$q"
        getJson(url)?.let { root ->
            val arr = root.asJsonObject.getAsJsonArray("artists") ?: return null
            if (arr.size() == 0) return null
            val a = arr[0].asJsonObject
            for (field in listOf("strArtistFanart", "strArtistFanart2", "strArtistWideThumb", "strArtistThumb")) {
                val v = a.get(field)
                if (v != null && !v.isJsonNull && v.asString.isNotBlank()) return v.asString
            }
        }
        return null
    }

    private fun getJson(url: String): com.google.gson.JsonElement? {
        val req = Request.Builder().url(url).header("User-Agent", UA).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) return null
            val text = resp.body?.string() ?: return null
            return try { JsonParser.parseString(text) } catch (e: Exception) { null }
        }
    }
}
