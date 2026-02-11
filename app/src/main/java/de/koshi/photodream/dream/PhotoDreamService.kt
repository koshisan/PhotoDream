package de.koshi.photodream.dream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.service.dreams.DreamService
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.TextView
import kotlin.math.abs
import com.bumptech.glide.Glide
import com.bumptech.glide.load.model.GlideUrl
import com.bumptech.glide.load.model.LazyHeaders
import de.koshi.photodream.api.ImmichClient
import de.koshi.photodream.model.*
import de.koshi.photodream.server.HttpServerService
import de.koshi.photodream.util.ConfigManager
import de.koshi.photodream.util.DeviceInfo
import de.koshi.photodream.util.SmartShuffle
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.*

/**
 * Main DreamService - displays photos from Immich as a screensaver
 */
class PhotoDreamService : DreamService() {
    
    companion object {
        private const val TAG = "PhotoDreamService"
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
    
    private var httpService: HttpServerService? = null
    private var serviceBound = false
    
    // Gesture detection for swipes and taps
    private lateinit var gestureDetector: GestureDetector
    
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            // Single tap = exit dream
            Log.d(TAG, "Tap detected - finishing dream")
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
            
            // Only handle horizontal swipes (ignore vertical)
            if (abs(diffX) > abs(diffY) && 
                abs(diffX) > SWIPE_THRESHOLD && 
                abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                
                if (diffX > 0) {
                    // Swipe right = previous image
                    Log.d(TAG, "Swipe right - previous image")
                    showPreviousImage()
                } else {
                    // Swipe left = next image
                    Log.d(TAG, "Swipe left - next image")
                    showNextImage()
                }
                
                // Reset slideshow timer on manual navigation
                resetSlideshowTimer()
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
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as HttpServerService.LocalBinder
            httpService = localBinder.getService().apply {
                // Setup callbacks
                onRefreshConfig = { refreshConfig() }
                onNextImage = { showNextImage() }
                onSetProfile = { profile -> setProfile(profile) }
                getStatus = { getCurrentStatus() }
                
                // Start server
                startServer(ConfigManager.getSettings(this@PhotoDreamService).serverPort)
            }
            serviceBound = true
            Log.d(TAG, "HttpServerService connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            httpService = null
            serviceBound = false
            Log.d(TAG, "HttpServerService disconnected")
        }
    }
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        
        // Dream settings
        isFullscreen = true
        isInteractive = true // Allow touch for gestures
        isScreenBright = true
        
        // Initialize gesture detector
        gestureDetector = GestureDetector(this, gestureListener)
        
        setupUI()
        bindHttpService()
        loadConfigAndStart()
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        // Let gesture detector handle the event
        if (gestureDetector.onTouchEvent(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    
    override fun onDetachedFromWindow() {
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(slideshowRunnable)
        scope.cancel()
        unbindHttpService()
        super.onDetachedFromWindow()
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
    
    private fun bindHttpService() {
        val intent = Intent(this, HttpServerService::class.java)
        startService(intent) // Keep service alive
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun unbindHttpService() {
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }
    
    private fun loadConfigAndStart() {
        scope.launch {
            try {
                // Load config from HA (or cached)
                config = ConfigManager.loadConfig(this@PhotoDreamService)
                
                if (config == null) {
                    showError("No configuration found. Please configure in app settings.")
                    return@launch
                }
                
                // Setup Immich client
                config?.let { cfg ->
                    immichClient = ImmichClient(cfg.immich)
                    
                    // Setup clock
                    setupClock(cfg.display)
                    
                    // Load playlist
                    loadPlaylist(cfg.profile)
                    
                    // Start slideshow
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
        
        // Position clock based on config (0=top-left, 1=top-right, 2=bottom-left, 3=bottom-right)
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
        
        // Start clock updates
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
            
            // Search using profile search filter (from Immich URL)
            val allAssets = client.searchWithFilter(profile.searchFilter, limit = 500)
            
            // Filter by excluded paths
            val filtered = allAssets.filter { asset ->
                profile.excludePaths.none { pattern ->
                    asset.originalPath.contains(pattern.replace("*", ""))
                }
            }
            
            // Smart shuffle
            playlist = SmartShuffle.shuffle(filtered)
            currentIndex = 0
            
            Log.i(TAG, "Loaded playlist with ${playlist.size} images (filter: ${profile.searchFilter})")
        }
    }
    
    private fun startSlideshow() {
        if (playlist.isEmpty()) {
            showError("No images found for current profile")
            return
        }
        
        // Show first image
        showCurrentImage()
        
        // Schedule next image
        val interval = (config?.display?.intervalSeconds ?: 30) * 1000L
        handler.postDelayed(slideshowRunnable, interval)
    }
    
    private fun showCurrentImage() {
        if (playlist.isEmpty()) return
        
        val asset = playlist[currentIndex]
        val client = immichClient ?: return
        
        val url = asset.getThumbnailUrl(config?.immich?.baseUrl ?: "", ThumbnailSize.PREVIEW)
        
        // Build Glide URL with auth headers
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
        // Cancel and restart the slideshow timer
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
    
    // --- Callbacks for HTTP Server ---
    
    private fun refreshConfig() {
        scope.launch {
            config = ConfigManager.loadConfig(this@PhotoDreamService, forceRefresh = true)
            config?.let { cfg ->
                immichClient = ImmichClient(cfg.immich)
                loadPlaylist(cfg.profile)
            }
        }
    }
    
    private fun setProfile(profileName: String) {
        Log.i(TAG, "Profile change requested: $profileName")
        // This would require fetching new profile from HA
        refreshConfig()
    }
    
    private fun getCurrentStatus(): DeviceStatus {
        val currentAsset = playlist.getOrNull(currentIndex)
        val resolution = DeviceInfo.getDisplayResolution(this)
        return DeviceStatus(
            online = true,
            active = true, // We're in DreamService = active
            currentImage = currentAsset?.id,
            currentImageUrl = currentAsset?.getThumbnailUrl(config?.immich?.baseUrl ?: ""),
            profile = config?.profile?.name,
            lastRefresh = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
            macAddress = DeviceInfo.getMacAddress(this),
            ipAddress = DeviceInfo.getIpAddress(this),
            displayWidth = resolution.first,
            displayHeight = resolution.second
        )
    }
}
