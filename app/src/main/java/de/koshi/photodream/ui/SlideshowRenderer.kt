package de.koshi.photodream.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Matrix
import android.graphics.drawable.Drawable
import android.util.Log
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.LinearInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import com.bumptech.glide.Glide
import com.bumptech.glide.load.DataSource
import com.bumptech.glide.load.engine.GlideException
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import com.bumptech.glide.request.RequestListener
import com.bumptech.glide.request.target.Target
import de.koshi.photodream.R
import de.koshi.photodream.model.Asset
import de.koshi.photodream.model.ThumbnailSize

/**
 * Handles slideshow rendering with smooth transitions and pan effects.
 * Used by both DreamService and SlideshowActivity.
 */
class SlideshowRenderer(
    private val context: Context,
    private val container: FrameLayout
) {
    companion object {
        private const val TAG = "SlideshowRenderer"
    }
    
    // Two ImageViews for crossfade effect
    private val imageViewA: ImageView
    private val imageViewB: ImageView
    private var frontView: ImageView
    private var backView: ImageView
    
    // Animation state
    private var currentPanAnimator: Animator? = null
    private var currentTransitionAnimator: Animator? = null
    
    // Configuration
    var crossfadeDuration: Long = 800L
    var panEnabled: Boolean = true
    var panDuration: Long = 12000L // Pan duration per image
    
    // Callbacks
    var onImageShown: ((Asset) -> Unit)? = null
    var onImageError: ((String) -> Unit)? = null
    
    init {
        // Create two ImageViews
        imageViewA = createImageView()
        imageViewB = createImageView()
        
        // Add to container (B behind A)
        container.addView(imageViewB, 0)
        container.addView(imageViewA, 1)
        
        // A is front, B is back initially
        frontView = imageViewA
        backView = imageViewB
        
        // Back view starts invisible
        backView.alpha = 0f
    }
    
    private fun createImageView(): ImageView {
        return ImageView(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
            scaleType = ImageView.ScaleType.MATRIX // We'll handle scaling ourselves for pan
            adjustViewBounds = false
        }
    }
    
    /**
     * Show an image with optional crossfade transition and pan effect.
     * Automatically chooses image quality based on display resolution.
     */
    fun showImage(
        asset: Asset,
        baseUrl: String,
        apiKey: String,
        withTransition: Boolean = true
    ) {
        // Get display dimensions
        val displayMetrics = context.resources.displayMetrics
        val displayWidth = displayMetrics.widthPixels
        val displayHeight = displayMetrics.heightPixels
        val maxDimension = maxOf(displayWidth, displayHeight)
        
        // Choose quality based on display resolution
        // PREVIEW = 1440px, so use original for anything larger
        val url = if (maxDimension > 1440) {
            Log.d(TAG, "High-res display ($maxDimension px), loading original image")
            asset.getOriginalUrl(baseUrl)
        } else {
            asset.getThumbnailUrl(baseUrl, ThumbnailSize.PREVIEW)
        }
        
        val glideUrl = GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("x-api-key", apiKey)
                .build()
        )
        
        // Stop any running pan animation on front view
        currentPanAnimator?.cancel()
        
        // Load into back view
        Glide.with(context)
            .load(glideUrl)
            .listener(object : RequestListener<Drawable> {
                override fun onLoadFailed(
                    e: GlideException?,
                    model: Any?,
                    target: Target<Drawable>,
                    isFirstResource: Boolean
                ): Boolean {
                    Log.e(TAG, "Failed to load image: ${e?.message}")
                    onImageError?.invoke(e?.message ?: "Unknown error")
                    return false
                }
                
                override fun onResourceReady(
                    resource: Drawable,
                    model: Any,
                    target: Target<Drawable>?,
                    dataSource: DataSource,
                    isFirstResource: Boolean
                ): Boolean {
                    // Image loaded, now do transition
                    backView.post {
                        setupImageMatrix(backView, resource)
                        
                        if (withTransition) {
                            crossfadeToBackView {
                                startPanAnimation(frontView)
                                onImageShown?.invoke(asset)
                            }
                        } else {
                            // Instant switch
                            swapViews()
                            frontView.alpha = 1f
                            backView.alpha = 0f
                            startPanAnimation(frontView)
                            onImageShown?.invoke(asset)
                        }
                    }
                    return false
                }
            })
            .into(backView)
    }
    
    /**
     * Setup the image matrix for proper display and pan capability.
     * Centers the image and scales it to cover the view while maintaining aspect ratio.
     */
    private fun setupImageMatrix(imageView: ImageView, drawable: Drawable) {
        val viewWidth = imageView.width.toFloat()
        val viewHeight = imageView.height.toFloat()
        val drawableWidth = drawable.intrinsicWidth.toFloat()
        val drawableHeight = drawable.intrinsicHeight.toFloat()
        
        Log.d(TAG, "=== setupImageMatrix DEBUG ===")
        Log.d(TAG, "View dimensions: ${viewWidth}x${viewHeight}")
        Log.d(TAG, "Drawable dimensions: ${drawableWidth}x${drawableHeight}")
        
        if (viewWidth == 0f || viewHeight == 0f || drawableWidth == 0f || drawableHeight == 0f) {
            // Fallback to center crop
            Log.w(TAG, "FALLBACK to CENTER_CROP - zero dimensions detected!")
            imageView.scaleType = ImageView.ScaleType.CENTER_CROP
            return
        }
        
        // Calculate scale to cover the view (like CENTER_CROP)
        val scaleX = viewWidth / drawableWidth
        val scaleY = viewHeight / drawableHeight
        val scale = maxOf(scaleX, scaleY)
        
        val scaledWidth = drawableWidth * scale
        val scaledHeight = drawableHeight * scale
        
        Log.d(TAG, "Scale factors: scaleX=$scaleX, scaleY=$scaleY, chosen=$scale")
        Log.d(TAG, "Scaled image: ${scaledWidth}x${scaledHeight}")
        
        // Center the image
        val translateX = (viewWidth - scaledWidth) / 2f
        val translateY = (viewHeight - scaledHeight) / 2f
        
        Log.d(TAG, "Translation: x=$translateX, y=$translateY")
        
        val matrix = Matrix()
        matrix.setScale(scale, scale)
        matrix.postTranslate(translateX, translateY)
        
        imageView.scaleType = ImageView.ScaleType.MATRIX
        imageView.imageMatrix = matrix
        
        // Store pan bounds as tag for animation
        val panBounds = PanBounds(
            minX = viewWidth - scaledWidth,
            maxX = 0f,
            minY = viewHeight - scaledHeight,
            maxY = 0f,
            scale = scale
        )
        imageView.setTag(R.id.pan_bounds, panBounds)
        
        Log.d(TAG, "PanBounds: minX=${panBounds.minX}, minY=${panBounds.minY}")
        Log.d(TAG, "Overflow: horizontal=${panBounds.minX < 0}, vertical=${panBounds.minY < 0}")
        Log.d(TAG, "=== setupImageMatrix DONE ===")
    }
    
    /**
     * Crossfade from front to back view, then swap references.
     */
    private fun crossfadeToBackView(onComplete: () -> Unit) {
        currentTransitionAnimator?.cancel()
        
        val fadeOut = ObjectAnimator.ofFloat(frontView, View.ALPHA, 1f, 0f)
        val fadeIn = ObjectAnimator.ofFloat(backView, View.ALPHA, 0f, 1f)
        
        val animatorSet = AnimatorSet().apply {
            playTogether(fadeOut, fadeIn)
            duration = crossfadeDuration
            interpolator = AccelerateDecelerateInterpolator()
            
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    swapViews()
                    onComplete()
                }
            })
        }
        
        currentTransitionAnimator = animatorSet
        animatorSet.start()
    }
    
    /**
     * Swap front and back view references.
     */
    private fun swapViews() {
        val temp = frontView
        frontView = backView
        backView = temp
    }
    
    /**
     * Start a slow pan animation on the image.
     * Pans from one edge to another based on which dimension overflows.
     */
    private fun startPanAnimation(imageView: ImageView) {
        Log.d(TAG, "=== startPanAnimation DEBUG ===")
        Log.d(TAG, "panEnabled=$panEnabled")
        
        if (!panEnabled) {
            Log.d(TAG, "Pan DISABLED - returning")
            return
        }
        
        val bounds = imageView.getTag(R.id.pan_bounds) as? PanBounds
        if (bounds == null) {
            Log.w(TAG, "No PanBounds found on ImageView - returning")
            return
        }
        
        Log.d(TAG, "PanBounds: minX=${bounds.minX}, minY=${bounds.minY}")
        
        // Determine pan direction based on overflow
        val horizontalOverflow = bounds.minX < 0
        val verticalOverflow = bounds.minY < 0
        
        Log.d(TAG, "Overflow: horizontal=$horizontalOverflow, vertical=$verticalOverflow")
        
        if (!horizontalOverflow && !verticalOverflow) {
            // Image fits perfectly, no pan needed
            Log.d(TAG, "No overflow - no pan needed")
            return
        }
        
        currentPanAnimator?.cancel()
        
        val currentMatrix = Matrix(imageView.imageMatrix)
        val values = FloatArray(9)
        currentMatrix.getValues(values)
        
        val startX = values[Matrix.MTRANS_X]
        val startY = values[Matrix.MTRANS_Y]
        
        // Choose random start position and pan direction
        val random = java.util.Random()
        
        val (targetX, targetY) = if (horizontalOverflow && verticalOverflow) {
            // Both overflow - pick a corner to corner pan
            if (random.nextBoolean()) {
                Pair(if (startX > bounds.minX / 2) bounds.minX else bounds.maxX,
                     if (startY > bounds.minY / 2) bounds.minY else bounds.maxY)
            } else {
                Pair(if (startX > bounds.minX / 2) bounds.minX else bounds.maxX, startY)
            }
        } else if (horizontalOverflow) {
            // Only horizontal overflow
            Pair(if (startX > bounds.minX / 2) bounds.minX else bounds.maxX, startY)
        } else {
            // Only vertical overflow
            Pair(startX, if (startY > bounds.minY / 2) bounds.minY else bounds.maxY)
        }
        
        val animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = panDuration
            interpolator = LinearInterpolator()
            repeatMode = ValueAnimator.REVERSE
            repeatCount = ValueAnimator.INFINITE
            
            addUpdateListener { animation ->
                val fraction = animation.animatedFraction
                val newX = startX + (targetX - startX) * fraction
                val newY = startY + (targetY - startY) * fraction
                
                val newMatrix = Matrix()
                newMatrix.setScale(bounds.scale, bounds.scale)
                newMatrix.postTranslate(newX, newY)
                imageView.imageMatrix = newMatrix
            }
        }
        
        currentPanAnimator = animator
        animator.start()
        
        Log.d(TAG, "Pan animation STARTED: from ($startX,$startY) to ($targetX,$targetY), duration=${panDuration}ms")
        Log.d(TAG, "=== startPanAnimation DONE ===")
    }
    
    /**
     * Stop all animations and cleanup.
     */
    fun cleanup() {
        currentPanAnimator?.cancel()
        currentTransitionAnimator?.cancel()
        currentPanAnimator = null
        currentTransitionAnimator = null
    }
    
    /**
     * Data class to store pan animation bounds.
     */
    private data class PanBounds(
        val minX: Float,
        val maxX: Float,
        val minY: Float,
        val maxY: Float,
        val scale: Float
    )
}
