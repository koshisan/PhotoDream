package de.koshi.photodream.util

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.Log

/**
 * Loads the bundled variable font (Figtree) and exposes exact weights via the 'wght'
 * axis. Figtree is a free (OFL) Segoe-UI-adjacent humanist sans; its weight axis is
 * 300–1000, so weights are clamped into that range (Segoe UI Light ≈ 300).
 *
 * Asset: app/src/main/assets/fonts/figtree.ttf
 * Requires API 26 for variation settings; older devices fall back to named families.
 */
object Fonts {

    private const val ASSET = "fonts/figtree.ttf"
    private val cache = HashMap<String, Typeface>()

    /** Figtree at the given weight; clamped to the font's axis (300–900). */
    fun weight(context: Context, w: Int, tabular: Boolean = false): Typeface {
        val ww = w.coerceIn(300, 900)
        val key = "$ww-$tabular"
        cache[key]?.let { return it }

        val tf: Typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val settings = if (tabular) "'wght' $ww, 'tnum' 1" else "'wght' $ww"
                Typeface.Builder(context.assets, ASSET)
                    .setFontVariationSettings(settings)
                    .setWeight(ww)
                    .build() ?: fallback(ww)
            } catch (e: Exception) {
                Log.e("Fonts", "Figtree load failed: ${e.message}")
                fallback(ww)
            }
        } else {
            fallback(ww)
        }

        cache[key] = tf
        return tf
    }

    private fun fallback(w: Int): Typeface = when {
        w <= 250 -> Typeface.create("sans-serif-thin", Typeface.NORMAL)
        w <= 350 -> Typeface.create("sans-serif-light", Typeface.NORMAL)
        w >= 600 -> Typeface.create("sans-serif", Typeface.BOLD)
        else -> Typeface.SANS_SERIF
    }
}
