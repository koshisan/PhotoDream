package de.koshi.photodream.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.bumptech.glide.load.engine.bitmap_recycle.BitmapPool
import com.bumptech.glide.load.resource.bitmap.BitmapTransformation
import java.security.MessageDigest

/**
 * Simple Glide BitmapTransformation that applies a blur by downscaling and upscaling.
 * Lightweight and works on all API levels without RenderScript.
 */
class BlurTransformation(private val scaleFactor: Float = 0.25f) : BitmapTransformation() {

    override fun transform(pool: BitmapPool, toTransform: Bitmap, outWidth: Int, outHeight: Int): Bitmap {
        val smallWidth = (toTransform.width * scaleFactor).toInt().coerceAtLeast(1)
        val smallHeight = (toTransform.height * scaleFactor).toInt().coerceAtLeast(1)

        // Downscale
        val small = Bitmap.createScaledBitmap(toTransform, smallWidth, smallHeight, true)

        // Upscale back - bilinear filtering creates blur effect
        val blurred = pool.get(outWidth, outHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(blurred)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        canvas.drawBitmap(small, null, Rect(0, 0, outWidth, outHeight), paint)

        if (small != toTransform) small.recycle()

        return blurred
    }

    override fun updateDiskCacheKey(messageDigest: MessageDigest) {
        messageDigest.update("BlurTransformation(scaleFactor=$scaleFactor)".toByteArray())
    }

    override fun equals(other: Any?) = other is BlurTransformation && other.scaleFactor == scaleFactor
    override fun hashCode() = scaleFactor.hashCode()
}
