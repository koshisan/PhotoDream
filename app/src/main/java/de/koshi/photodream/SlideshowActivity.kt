package de.koshi.photodream

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import de.koshi.photodream.api.ImmichClient
import de.koshi.photodream.ui.SlideshowRenderer
import de.koshi.photodream.model.*
import de.koshi.photodream.server.HttpServerService
import de.koshi.photodream.util.ConfigManager
import de.koshi.photodream.util.DeviceInfo
import de.koshi.photodream.util.SmartShuffle
import de.koshi.photodream.BuildConfig
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
    private lateinit var imageContainer: FrameLayout
    private lateinit var clockContainer: android.widget.LinearLayout
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var renderer: SlideshowRenderer
    
    private var config: DeviceConfig? = null
    private var immichClient: ImmichClient? = null
    private var playlist: List<Asset> = emptyList()
    private var currentIndex = 0
    
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    private var httpService: HttpServerService? = null
    private var serviceBound = false
    
    private lateinit var gestureDetector: GestureDetector
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as HttpServerService.LocalBinder
            httpService = localBinder.getService().apply {
                // Setup callbacks - same as DreamService
                onConfigReceived = { newConfig -> applyConfigLive(newConfig) }
                onRefreshConfig = { refreshConfig() }
                onNextImage = { showNextImage() }
                onSetProfile = { profile -> setProfile(profile) }
                getStatus = { getCurrentStatus() }
                
                // Update status to show we're active
                updateStatus(getCurrentStatus())
            }
            serviceBound = true
            Log.d(TAG, "HttpServerService connected")
        }
        
        override fun onServiceDisconnected(name: ComponentName?) {
            httpService = null
            serviceBound = false
        }
    }
    
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
        bindHttpService()
        loadConfigAndStart()
    }
    
    override fun onDestroy() {
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(slideshowRunnable)
        renderer.cleanup()
        scope.cancel()
        unbindHttpService()
        super.onDestroy()
    }
    
    private fun bindHttpService() {
        val intent = Intent(this, HttpServerService::class.java)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
    }
    
    private fun unbindHttpService() {
        if (serviceBound) {
            httpService?.apply {
                onConfigReceived = null
                onRefreshConfig = null
                onNextImage = null
                onSetProfile = null
                getStatus = null
                updateStatus(DeviceStatus(online = true, active = false))
            }
            unbindService(serviceConnection)
            serviceBound = false
        }
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
        
        // Container for the slideshow renderer (holds two ImageViews)
        imageContainer = FrameLayout(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        // Container for clock + date
        clockContainer = android.widget.LinearLayout(this).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            visibility = View.GONE
        }
        
        clockView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 32f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }
        
        dateView = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            visibility = View.GONE
        }
        
        clockContainer.addView(clockView)
        clockContainer.addView(dateView)
        
        rootLayout.addView(imageContainer)
        rootLayout.addView(clockContainer)
        
        setContentView(rootLayout)
        
        // Initialize renderer after views are added
        renderer = SlideshowRenderer(this, imageContainer).apply {
            crossfadeDuration = 800L
            panEnabled = true
            panDuration = 10000L // Pan over 10 seconds
            
            onImageShown = { asset ->
                Log.d(TAG, "Image shown: ${asset.id}")
                httpService?.updateStatus(getCurrentStatus())
            }
            
            onImageError = { error ->
                Log.e(TAG, "Image load error: $error")
            }
        }
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
                    // Share config with HTTP service
                    httpService?.updateConfig(cfg)
                    
                    immichClient = ImmichClient(cfg.immich)
                    setupClock(cfg.display)
                    applyPanSpeed(cfg.display.panSpeed)
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
            clockContainer.visibility = View.GONE
            return
        }
        
        clockContainer.visibility = View.VISIBLE
        
        // Set font size from config
        clockView.textSize = display.clockFontSize.toFloat()
        
        // Setup date if enabled
        if (display.date) {
            dateView.visibility = View.VISIBLE
            dateView.textSize = (display.clockFontSize * 0.5f).coerceAtLeast(12f)
        } else {
            dateView.visibility = View.GONE
        }
        
        // Text alignment based on position
        val textAlignment = when (display.clockPosition) {
            0, 3 -> View.TEXT_ALIGNMENT_VIEW_START  // Left positions
            1, 4, 6 -> View.TEXT_ALIGNMENT_CENTER   // Center positions
            else -> View.TEXT_ALIGNMENT_VIEW_END    // Right positions
        }
        clockView.textAlignment = textAlignment
        dateView.textAlignment = textAlignment
        
        // Position: 0=top-left, 1=top-center, 2=top-right, 3=bottom-left, 4=bottom-center, 5=bottom-right, 6=center
        val layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            val margin = 32
            setMargins(margin, margin, margin, margin)
            gravity = when (display.clockPosition) {
                0 -> Gravity.TOP or Gravity.START
                1 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
                2 -> Gravity.TOP or Gravity.END
                3 -> Gravity.BOTTOM or Gravity.START
                4 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                5 -> Gravity.BOTTOM or Gravity.END
                6 -> Gravity.CENTER
                else -> Gravity.BOTTOM or Gravity.START
            }
        }
        clockContainer.layoutParams = layoutParams
        
        handler.post(clockRunnable)
    }
    
    /**
     * Apply pan speed from config.
     * panSpeed: 0.0-2.0, where 1.0 = 10 seconds per pan cycle
     * Higher = faster panning
     */
    private fun applyPanSpeed(panSpeed: Float, reload: Boolean = false) {
        val effectiveSpeed = panSpeed.coerceIn(0.1f, 2.0f)
        renderer.panDuration = (10000L / effectiveSpeed).toLong()
        Log.d(TAG, "Pan speed set to $panSpeed -> duration ${renderer.panDuration}ms")
        
        if (reload && playlist.isNotEmpty()) {
            // Reload current image to apply new pan speed immediately
            showCurrentImage(withTransition = false)
        }
    }
    
    private fun updateClock() {
        val now = Date()
        
        // Update time
        val timeFormat = if (config?.display?.clockFormat == "12h") "hh:mm a" else "HH:mm"
        clockView.text = SimpleDateFormat(timeFormat, Locale.getDefault()).format(now)
        
        // Update date if visible
        if (dateView.visibility == View.VISIBLE) {
            val dateFormat = config?.display?.dateFormat ?: "dd.MM.yyyy"
            dateView.text = SimpleDateFormat(dateFormat, Locale.getDefault()).format(now)
        }
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
        
        // First image without transition
        showCurrentImage(withTransition = false)
        
        val interval = (config?.display?.intervalSeconds ?: 30) * 1000L
        handler.postDelayed(slideshowRunnable, interval)
    }
    
    private fun showCurrentImage(withTransition: Boolean = true) {
        if (playlist.isEmpty()) return
        
        val asset = playlist[currentIndex]
        val cfg = config ?: return
        
        renderer.showImage(
            asset = asset,
            baseUrl = cfg.immich.baseUrl,
            apiKey = cfg.immich.apiKey,
            withTransition = withTransition
        )
        
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
    
    // --- Callbacks for HTTP Server (same as DreamService) ---
    
    private fun applyConfigLive(newConfig: DeviceConfig) {
        Log.i(TAG, "Applying config live: clock=${newConfig.display.clock}, interval=${newConfig.display.intervalSeconds}")
        
        val oldProfile = config?.profile?.name
        val oldPanSpeed = config?.display?.panSpeed
        config = newConfig
        
        // Apply display settings immediately
        setupClock(newConfig.display)
        applyPanSpeed(newConfig.display.panSpeed, reload = oldPanSpeed != newConfig.display.panSpeed)
        
        // Reset slideshow timer with new interval
        resetSlideshowTimer()
        
        // If profile changed, reload playlist
        if (oldProfile != newConfig.profile.name) {
            Log.i(TAG, "Profile changed, reloading playlist")
            scope.launch {
                immichClient = ImmichClient(newConfig.immich)
                loadPlaylist(newConfig.profile)
            }
        }
        
        // Update HTTP service
        httpService?.updateConfig(newConfig)
        httpService?.updateStatus(getCurrentStatus())
    }
    
    private fun refreshConfig() {
        scope.launch {
            config = ConfigManager.loadConfig(this@SlideshowActivity, forceRefresh = true)
            config?.let { cfg ->
                immichClient = ImmichClient(cfg.immich)
                loadPlaylist(cfg.profile)
            }
        }
    }
    
    private fun setProfile(profileName: String) {
        Log.i(TAG, "Profile change requested: $profileName")
        refreshConfig()
    }
    
    private fun getCurrentStatus(): DeviceStatus {
        val currentAsset = playlist.getOrNull(currentIndex)
        val resolution = DeviceInfo.getDisplayResolution(this)
        return DeviceStatus(
            online = true,
            active = true,
            currentImage = currentAsset?.id,
            currentImageUrl = currentAsset?.getThumbnailUrl(config?.immich?.baseUrl ?: ""),
            profile = config?.profile?.name,
            lastRefresh = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
            macAddress = DeviceInfo.getMacAddress(this),
            ipAddress = DeviceInfo.getIpAddress(this),
            displayWidth = resolution.first,
            displayHeight = resolution.second,
            appVersion = BuildConfig.VERSION_NAME
        )
    }
}
