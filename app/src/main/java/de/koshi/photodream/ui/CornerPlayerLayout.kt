package de.koshi.photodream.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Outline
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.view.View
import android.view.ViewOutlineProvider
import android.widget.FrameLayout
import de.koshi.photodream.util.Fonts
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * Quarter disc docked into the top-right screen corner, used by the "corner" media mode.
 *
 * Everything is arranged radially around that corner. The view clips itself and its children
 * to the disc; children (frost, cover, buttons) are positioned by [MediaPlayerOverlay], while
 * the arc text, the progress arc and the rim are drawn here, on top of them.
 */
class CornerPlayerLayout(context: Context) : FrameLayout(context) {

    companion object {
        const val RADIUS_DP = 320
        private const val TITLE_BASELINE_DP = 208f
        private const val ARTIST_BASELINE_DP = 228f
        private const val PROGRESS_RADIUS_DP = 303f
        private const val PROGRESS_STROKE_DP = 4f
        private const val RIM_STROKE_DP = 1f

        /** Share of the arc that text may fill, so glyphs don't run into the screen edges. */
        private const val TEXT_ARC_USAGE = 0.92f
    }

    private val density = resources.displayMetrics.density
    private val discRadius = RADIUS_DP * density

    private val titleArc = TextArc(TITLE_BASELINE_DP * density, textPaint(15f, 600, Color.WHITE))
    private val artistArc = TextArc(ARTIST_BASELINE_DP * density, textPaint(12f, 400, 0xB8FFFFFF.toInt()))

    private val progressBounds = cornerCircle(PROGRESS_RADIUS_DP * density)
    private val trackPaint = strokePaint(PROGRESS_STROKE_DP, 0x2EFFFFFF, Paint.Cap.ROUND)
    private val fillPaint = strokePaint(PROGRESS_STROKE_DP, Color.WHITE, Paint.Cap.ROUND)
    private var progressVisible = false
    private var progressFraction = 0f

    private val rimBounds = cornerCircle(discRadius - RIM_STROKE_DP * density / 2f)
    private val rimPaint = strokePaint(RIM_STROKE_DP, 0x24FFFFFF, Paint.Cap.BUTT)

    init {
        // Outline = full circle around the corner; the view's own bounds cut it to a quarter.
        outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val r = view.width
                outline.setRoundRect(0, -r, 2 * r, r, r.toFloat())
            }
        }
        clipToOutline = true
        elevation = 10 * density
    }

    fun setTrack(title: String, artist: String) {
        val titleChanged = titleArc.setText(title)
        val artistChanged = artistArc.setText(artist)
        if (titleChanged || artistChanged) invalidate()
    }

    fun setProgress(fraction: Float, visible: Boolean) {
        if (visible == progressVisible && abs(fraction - progressFraction) < 0.0005f) return
        progressVisible = visible
        progressFraction = fraction
        invalidate()
    }

    /** True if a screen coordinate is on the disc, not just inside the view's square bounds. */
    fun containsScreenPoint(rawX: Float, rawY: Float): Boolean {
        val location = IntArray(2)
        getLocationOnScreen(location)
        val cornerX = location[0] + width.toFloat()
        val cornerY = location[1].toFloat()
        return rawX <= cornerX && rawY >= cornerY && hypot(rawX - cornerX, rawY - cornerY) <= width
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        titleArc.draw(canvas)
        artistArc.draw(canvas)
        if (progressVisible) {
            canvas.drawArc(progressBounds, ARC_START, ARC_SWEEP, false, trackPaint)
            if (progressFraction > 0f) {
                canvas.drawArc(progressBounds, ARC_START, ARC_SWEEP * progressFraction, false, fillPaint)
            }
        }
        canvas.drawArc(rimBounds, ARC_START, ARC_SWEEP, false, rimPaint)
    }

    /** Bounds of a circle of radius [r] around the top-right corner of the disc. */
    private fun cornerCircle(r: Float) = RectF(discRadius - r, -r, discRadius + r, r)

    private fun textPaint(sizeDp: Float, weight: Int, color: Int) = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sizeDp * density
        typeface = Fonts.weight(context, weight)
        letterSpacing = 0.01f
        this.color = color
    }

    private fun strokePaint(widthDp: Float, color: Int, cap: Paint.Cap) = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = widthDp * density
        strokeCap = cap
        this.color = color
    }

    /** One line of text centered on an arc, with the glyph tops pointing at the corner. */
    private inner class TextArc(radius: Float, private val paint: TextPaint) {
        private val path = Path().apply { arcTo(cornerCircle(radius), ARC_START, ARC_SWEEP, true) }
        private val arcLength = (PI / 2 * radius).toFloat()
        private var source = ""
        private var fitted = ""
        private var startOffset = 0f

        fun setText(text: String): Boolean {
            if (text == source) return false
            source = text
            fitted = TextUtils.ellipsize(text, paint, arcLength * TEXT_ARC_USAGE, TextUtils.TruncateAt.END).toString()
            startOffset = (arcLength - paint.measureText(fitted)) / 2f
            return true
        }

        fun draw(canvas: Canvas) {
            if (fitted.isNotEmpty()) canvas.drawTextOnPath(fitted, path, startOffset, 0f, paint)
        }
    }
}

// Android angles grow clockwise from 3 o'clock: 180 degrees (left of the corner) back to 90
// (below it) runs from the top screen edge to the right screen edge.
private const val ARC_START = 180f
private const val ARC_SWEEP = -90f
