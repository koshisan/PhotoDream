package de.koshi.photodream.util

import android.content.Context
import android.graphics.Typeface
import android.os.Build
import android.util.Log

/**
 * Loads the bundled variable font (Roboto Flex) and exposes exact weights via the
 * 'wght' axis. Roboto's static cuts don't include a real 200, so the thin display
 * clock needs a variable font (see design handoff).
 *
 * Asset: app/src/main/assets/fonts/robotoflex.ttf
 * Requires API 26 for variation settings; older devices fall back to named families.
 */
object Fonts {

    private const val ASSET = "fonts/robotoflex.ttf"
    private val cache = HashMap<String, Typeface>()

    /** Roboto Flex at the given weight (100–1000); set [tabular] for tabular figures. */
    fun weight(context: Context, w: Int, tabular: Boolean = false): Typeface {
        val key = "$w-$tabular"
        cache[key]?.let { return it }

        val tf: Typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            try {
                val settings = if (tabular) "'wght' $w, 'tnum' 1" else "'wght' $w"
                Typeface.Builder(context.assets, ASSET)
                    .setFontVariationSettings(settings)
                    .setWeight(w)
                    .build() ?: fallback(w)
            } catch (e: Exception) {
                Log.e("Fonts", "Roboto Flex load failed: ${e.message}")
                fallback(w)
            }
        } else {
            fallback(w)
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
