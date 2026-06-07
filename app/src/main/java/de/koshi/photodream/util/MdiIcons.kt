package de.koshi.photodream.util

import android.content.Context
import android.graphics.Typeface
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken

/**
 * Renders Material Design Icons (the same set Home Assistant uses) from a locally
 * bundled webfont, so HA-style icon names like "mdi:doorbell" can be displayed.
 *
 * Assets (bundled in app/src/main/assets):
 * - fonts/materialdesignicons-webfont.ttf  - the MDI glyph font (@mdi/font 7.4.x)
 * - mdi_map.json                           - { "doorbell": "F12E6", ... } name -> codepoint
 *
 * Usage: set [typeface] on a TextView and its text to [glyph]("mdi:doorbell").
 */
object MdiIcons {

    private const val TAG = "MdiIcons"
    private const val FONT_ASSET = "fonts/materialdesignicons-webfont.ttf"
    private const val MAP_ASSET = "mdi_map.json"

    @Volatile private var typefaceCache: Typeface? = null
    @Volatile private var mapCache: Map<String, String>? = null

    /** The MDI typeface, or null if the font asset is missing/unreadable. */
    fun typeface(context: Context): Typeface? {
        typefaceCache?.let { return it }
        return synchronized(this) {
            typefaceCache ?: try {
                Typeface.createFromAsset(context.assets, FONT_ASSET).also { typefaceCache = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load MDI font: ${e.message}")
                null
            }
        }
    }

    private fun map(context: Context): Map<String, String> {
        mapCache?.let { return it }
        return synchronized(this) {
            mapCache ?: try {
                val json = context.assets.open(MAP_ASSET).bufferedReader().use { it.readText() }
                val type = object : TypeToken<Map<String, String>>() {}.type
                Gson().fromJson<Map<String, String>>(json, type).also { mapCache = it }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load MDI map: ${e.message}")
                emptyMap<String, String>().also { mapCache = it }
            }
        }
    }

    /**
     * Resolve an MDI icon name to its glyph string.
     * Accepts "mdi:doorbell" or "doorbell". Returns null if unknown.
     */
    fun glyph(context: Context, name: String?): String? {
        if (name.isNullOrBlank()) return null
        val clean = name.removePrefix("mdi:").removePrefix("mdi-").trim().lowercase()
        val hex = map(context)[clean] ?: return null
        return try {
            String(Character.toChars(hex.toInt(16)))
        } catch (e: Exception) {
            null
        }
    }
}
