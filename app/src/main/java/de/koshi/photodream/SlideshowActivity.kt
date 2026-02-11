package de.koshi.photodream

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import de.koshi.photodream.api.ImmichClient
import de.koshi.photodream.model.*
import de.koshi.photodream.util.ConfigManager
import de.koshi.photodream.util.SmartShuffle
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.abs

/**
 * Fullscreen slideshow activity - works even without Daydream support.
 * Can be started via intent from other apps or the "Start Now" button.
 * 
 * Intent actions:
 * - de.koshi.photodream.START_SLIDESHOW
 * - android.intent.action.MAIN (with category LAUNCHER_ALTERNATE)
 */
class SlideshowActivity : AppCompatActivity() {
    
    companion object {
        private const val TAG = "SlideshowActivity"
        const val ACTION_START_SLIDESHOW = "de.koshi.photodream.START_SLIDESHOW"
        
        fun start(context: Context) {
            val intent = Intent(context, SlideshowActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
    
    private lateinit var rootLayout: FrameLayout
    private lateinit var imageView: ImageView
    private lateinit var clockView: TextView
    
    private var config: DeviceConfig? = null
    private var immichClient: ImmichClient? = null
    private var playlist: List<Asset> = emptyList()
    private var currentIndex = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private lateinit var gestureDetector: GestureDetector
    
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Log.d(TAG, "Tap detected - finishing")
            finish()
            return true
        }
        
        override fun onFling(
            e1: MotionEvent?,
            e2: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            if (e1 == null) return false
            
            val diffX = e2.x - e1.x
            val diffY = e2.y - e1.y
            
            if (abs(diffX) > abs(diffY) && 
                abs(diffX) > SWIPE_THRESHOLD && 
                abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                
                if (diffX > 0) {
                    showPreviousImage()
                } else {
                    showNextImage()
                }
                return true
            }
            return false
        }
    }
    
    private val clockRunnable = object : Runnable {
        override fun run() {
            updateClock()
            handler.postDelayed(this, 1000)
        }
    }
    
    private val slideshowRunnable = object : Runnable {
        override fun run() {
            showNextImage()
            val interval = (config?.display?.intervalSeconds ?: 30) * 1000L
            handler.postDelayed(this, interval)
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen immersive mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        gestureDetector = GestureDetector(this, gestureListener)
        
        setupUI()
        loadConfigAndStart()
    }
    
    override fun onDestroy() {
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(slideshowRunnable)
        scope.cancel()
        super.onDestroy()
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (gestureDetector.onTouchEvent(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    
    private fun setupUI() {
        rootLayout = FrameLayout(this).apply {
            setBackgroundColor(Color.BLACK)
        }
        
        imageView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        clockView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 32f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            visibility = View.GONE
        }
        
        rootLayout.addView(imageView)
        rootLayout.addView(clockView)
        
        setContentView(rootLayout)
    }
    
    private fun loadConfigAndStart() {
        scope.launch {
            try {
                config = ConfigManager.loadConfig(this@SlideshowActivity)
                
                if (config == null) {
                    showError("No configuration found. Please configure in app settings.")
                    return@launch
                }
                
                config?.let { cfg ->
                    immichClient = ImmichClient(cfg.immich)
                    setupClock(cfg.display)
                    loadPlaylist(cfg.profile)
                    startSlideshow()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load config: ${e.message}", e)
                showError("Failed to load configuration: ${e.message}")
            }
        }
    }
    
    private fun setupClock(display: DisplayConfig) {
        if (!display.clock) {
            clockView.visibility = View.GONE
            return
        }
        
        clockView.visibility = View.VISIBLE
        
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val margin = 32
            setMargins(margin, margin, margin, margin)
            gravity = when (display.clockPosition) {
                0 -> Gravity.TOP or Gravity.START
                1 -> Gravity.TOP or Gravity.END
                2 -> Gravity.BOTTOM or Gravity.START
                else -> Gravity.BOTTOM or Gravity.END
            }
        }
        clockView.layoutParams = layoutParams
        
        handler.post(clockRunnable)
    }
    
    private fun updateClock() {
        val format = if (config?.display?.clockFormat == "12h") "hh:mm a" else "HH:mm"
        val sdf = SimpleDateFormat(format, Locale.getDefault())
        clockView.text = sdf.format(Date())
    }
    
    private suspend fun loadPlaylist(profile: ProfileConfig) {
        withContext(Dispatchers.IO) {
            val client = immichClient ?: return@withContext
            
            val allAssets = client.searchWithFilter(profile.searchFilter, limit = 500)
            
            val filtered = allAssets.filter { asset ->
                profile.excludePaths.none { pattern ->
                    asset.originalPath.contains(pattern.replace("*", ""))
                }
            }
            
            playlist = SmartShuffle.shuffle(filtered)
            currentIndex = 0
            
            Log.i(TAG, "Loaded playlist with ${playlist.size} images")
        }
    }
    
    private fun startSlideshow() {
        if (playlist.isEmpty()) {
            showError("No images found for current profile")
            return
        }
        
        showCurrentImage()
        
        val interval = (config?.display?.intervalSeconds ?: 30) * 1000L
        handler.postDelayed(slideshowRunnable, interval)
    }
    
    private fun showCurrentImage() {
        if (playlist.isEmpty()) return
        
        val asset = playlist[currentIndex]
        
        val url = asset.getThumbnailUrl(config?.immich?.baseUrl ?: "", ThumbnailSize.PREVIEW)
        
        val glideUrl = GlideUrl(
            url,
            LazyHeaders.Builder()
                .addHeader("x-api-key", config?.immich?.apiKey ?: "")
                .build()
        )
        
        Glide.with(this)
            .load(glideUrl)
            .centerCrop()
            .into(imageView)
        
        Log.d(TAG, "Showing image ${currentIndex + 1}/${playlist.size}: ${asset.id}")
        
        resetSlideshowTimer()
    }
    
    private fun showNextImage() {
        if (playlist.isEmpty()) return
        currentIndex = (currentIndex + 1) % playlist.size
        showCurrentImage()
    }
    
    private fun showPreviousImage() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1
        showCurrentImage()
    }
    
    private fun resetSlideshowTimer() {
        handler.removeCallbacks(slideshowRunnable)
        val interval = (config?.display?.intervalSeconds ?: 30) * 1000L
        handler.postDelayed(slideshowRunnable, interval)
    }
    
    private fun showError(message: String) {
        Log.e(TAG, message)
        clockView.apply {
            text = message
            visibility = View.VISIBLE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
    }
}
