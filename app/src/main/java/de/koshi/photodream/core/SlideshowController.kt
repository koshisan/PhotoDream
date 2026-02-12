package de.koshi.photodream.core

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.graphics.Color
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.google.gson.Gson
import de.koshi.photodream.api.ImmichClient
import de.koshi.photodream.model.*
import de.koshi.photodream.server.HttpServerService
import de.koshi.photodream.ui.SlideshowRenderer
import de.koshi.photodream.util.ConfigManager
import de.koshi.photodream.util.DeviceInfo
import de.koshi.photodream.util.SmartShuffle
import kotlinx.coroutines.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.SimpleDateFormat
import java.util.*

/**
 * Central slideshow controller - contains ALL slideshow logic.
 * Used by both PhotoDreamService (Daydream) and SlideshowActivity (Intent).
 * 
 * This ensures identical behavior regardless of how the slideshow is started.
 */
class SlideshowController(
    private val context: Context,
    private val container: FrameLayout,
    private val onFinish: () -> Unit
) {
    companion object {
        private const val TAG = "SlideshowController"
    }
    
    // UI components
    private lateinit var imageContainer: FrameLayout
    private lateinit var clockContainer: LinearLayout
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var renderer: SlideshowRenderer
    
    // State
    private var config: DeviceConfig? = null
    private var immichClient: ImmichClient? = null
    private var playlist: List<Asset> = emptyList()
    private var currentIndex = 0
    
    // Coroutines & handlers
    private val handler = Handler(Looper.getMainLooper())
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // HTTP service binding
    private var httpService: HttpServerService? = null
    private var serviceBound = false
    
    // Webhook client
    private val httpClient = OkHttpClient()
    private val gson = Gson()
    
    // Gesture detection
    lateinit var gestureDetector: GestureDetector
        private set
    
    private val gestureListener = object : GestureDetector.SimpleOnGestureListener() {
        private val SWIPE_THRESHOLD = 100
        private val SWIPE_VELOCITY_THRESHOLD = 100
        
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            Log.d(TAG, "Tap detected - finishing")
            onFinish()
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
            
            if (kotlin.math.abs(diffX) > kotlin.math.abs(diffY) &&
                kotlin.math.abs(diffX) > SWIPE_THRESHOLD &&
                kotlin.math.abs(velocityX) > SWIPE_VELOCITY_THRESHOLD) {
                
                if (diffX > 0) {
                    Log.d(TAG, "Swipe right - previous image")
                    showPreviousImage()
                } else {
                    Log.d(TAG, "Swipe left - next image")
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
    
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as HttpServerService.LocalBinder
            httpService = localBinder.getService().apply {
                onConfigReceived = { newConfig -> applyConfigLive(newConfig) }
                onRefreshConfig = { refreshConfig() }
                onNextImage = { showNextImage() }
                onSetProfile = { profile -> setProfile(profile) }
                getStatus = { getCurrentStatus() }
                updateStatus(getCurrentStatus())
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
    
    /**
     * Start the slideshow. Call this after creating the controller.
     */
    fun start() {
        gestureDetector = GestureDetector(context, gestureListener)
        setupUI()
        bindHttpService()
        loadConfigAndStart()
    }
    
    /**
     * Stop the slideshow. Call this when the activity/service is destroyed.
     */
    fun stop() {
        handler.removeCallbacks(clockRunnable)
        handler.removeCallbacks(slideshowRunnable)
        if (::renderer.isInitialized) {
            renderer.cleanup()
        }
        scope.cancel()
        unbindHttpService()
    }
    
    /**
     * Handle touch events. Call this from dispatchTouchEvent.
     */
    fun onTouchEvent(event: MotionEvent): Boolean {
        return gestureDetector.onTouchEvent(event)
    }
    
    private fun setupUI() {
        container.setBackgroundColor(Color.BLACK)
        
        // Image container
        imageContainer = FrameLayout(context).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        
        // Clock container
        clockContainer = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        
        clockView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 32f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        dateView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            visibility = View.GONE
        }
        
        clockContainer.addView(clockView)
        clockContainer.addView(dateView)
        
        container.addView(imageContainer)
        container.addView(clockContainer)
        
        // Initialize renderer
        renderer = SlideshowRenderer(context, imageContainer).apply {
            crossfadeDuration = 800L
            panEnabled = true
            panDuration = 10000L
            
            onImageShown = { asset ->
                Log.d(TAG, "Image shown: ${asset.id}")
                reportStatusToHA()
                httpService?.updateStatus(getCurrentStatus())
            }
            
            onImageError = { error ->
                Log.e(TAG, "Image load error: $error")
            }
        }
    }
    
    private fun bindHttpService() {
        val intent = Intent(context, HttpServerService::class.java)
        context.bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)
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
            context.unbindService(serviceConnection)
            serviceBound = false
            Log.d(TAG, "HttpServerService unbound")
        }
    }
    
    private fun loadConfigAndStart() {
        scope.launch {
            try {
                config = ConfigManager.loadConfig(context)
                
                if (config == null) {
                    showError("No configuration found. Please configure in app settings.")
                    return@launch
                }
                
                config?.let { cfg ->
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
        clockView.textSize = display.clockFontSize.toFloat()
        
        if (display.date) {
            dateView.visibility = View.VISIBLE
            dateView.textSize = (display.clockFontSize * 0.35f).coerceAtLeast(10f)
            dateView.layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = -35  // Negative margin to pull date closer to clock
            }
        } else {
            dateView.visibility = View.GONE
        }
        
        // Align children within the LinearLayout based on clock position
        clockContainer.gravity = when (display.clockPosition) {
            0, 3 -> Gravity.START          // Left positions
            1, 4, 6 -> Gravity.CENTER_HORIZONTAL  // Center positions
            else -> Gravity.END            // Right positions
        }
        
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
    
    private fun applyPanSpeed(panSpeed: Float, reload: Boolean = false) {
        val effectiveSpeed = panSpeed.coerceIn(0.1f, 2.0f)
        renderer.panDuration = (10000L / effectiveSpeed).toLong()
        Log.d(TAG, "Pan speed set to $panSpeed -> duration ${renderer.panDuration}ms")
        
        if (reload && playlist.isNotEmpty()) {
            showCurrentImage(withTransition = false)
        }
    }
    
    private fun updateClock() {
        val now = Date()
        val timeFormat = if (config?.display?.clockFormat == "12h") "hh:mm a" else "HH:mm"
        clockView.text = SimpleDateFormat(timeFormat, Locale.getDefault()).format(now)
        
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
            
            Log.i(TAG, "Loaded playlist with ${playlist.size} images (filter: ${profile.searchFilter})")
        }
    }
    
    private fun startSlideshow() {
        if (playlist.isEmpty()) {
            showError("No images found for current profile")
            return
        }
        
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
    
    /**
     * Send current status to Home Assistant via webhook
     */
    private fun reportStatusToHA() {
        val webhookUrl = config?.webhookUrl
        if (webhookUrl.isNullOrBlank()) {
            Log.d(TAG, "No webhook URL configured, skipping status report")
            return
        }
        
        scope.launch(Dispatchers.IO) {
            httpService?.webhookAttemptCount = (httpService?.webhookAttemptCount ?: 0) + 1
            httpService?.lastWebhookAttempt = System.currentTimeMillis()
            
            try {
                val status = getCurrentStatus()
                val statusWithDeviceId = mapOf(
                    "device_id" to (config?.deviceId ?: "unknown"),
                    "online" to status.online,
                    "active" to status.active,
                    "current_image" to status.currentImage,
                    "current_image_url" to status.currentImageUrl,
                    "profile" to status.profile,
                    "last_refresh" to status.lastRefresh,
                    "mac_address" to status.macAddress,
                    "ip_address" to status.ipAddress,
                    "display_width" to status.displayWidth,
                    "display_height" to status.displayHeight,
                    "app_version" to status.appVersion
                )
                
                val json = gson.toJson(statusWithDeviceId)
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                
                val response = httpClient.newCall(request).execute()
                if (response.isSuccessful) {
                    Log.d(TAG, "Status reported to HA successfully")
                    httpService?.lastWebhookSuccess = true
                    httpService?.lastWebhookError = null
                } else {
                    Log.w(TAG, "Failed to report status to HA: ${response.code}")
                    httpService?.lastWebhookSuccess = false
                    httpService?.lastWebhookError = "HTTP ${response.code}: ${response.message}"
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error reporting status to HA: ${e.message}", e)
                httpService?.lastWebhookSuccess = false
                httpService?.lastWebhookError = "${e.javaClass.simpleName}: ${e.message}"
            }
        }
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
    
    private fun applyConfigLive(newConfig: DeviceConfig) {
        Log.i(TAG, "Applying config live: clock=${newConfig.display.clock}, interval=${newConfig.display.intervalSeconds}")
        
        val oldProfile = config?.profile?.name
        val oldPanSpeed = config?.display?.panSpeed
        config = newConfig
        
        setupClock(newConfig.display)
        applyPanSpeed(newConfig.display.panSpeed, reload = oldPanSpeed != newConfig.display.panSpeed)
        resetSlideshowTimer()
        
        if (oldProfile != newConfig.profile.name) {
            Log.i(TAG, "Profile changed from $oldProfile to ${newConfig.profile.name}, reloading playlist")
            scope.launch {
                immichClient = ImmichClient(newConfig.immich)
                loadPlaylist(newConfig.profile)
            }
        }
        
        httpService?.updateConfig(newConfig)
        httpService?.updateStatus(getCurrentStatus())
    }
    
    private fun refreshConfig() {
        scope.launch {
            config = ConfigManager.loadConfig(context, forceRefresh = true)
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
        val resolution = DeviceInfo.getDisplayResolution(context)
        return DeviceStatus(
            online = true,
            active = true,
            currentImage = currentAsset?.id,
            currentImageUrl = currentAsset?.getThumbnailUrl(config?.immich?.baseUrl ?: ""),
            profile = config?.profile?.name,
            lastRefresh = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).format(Date()),
            macAddress = DeviceInfo.getMacAddress(context),
            ipAddress = DeviceInfo.getIpAddress(context),
            displayWidth = resolution.first,
            displayHeight = resolution.second,
            appVersion = de.koshi.photodream.BuildConfig.VERSION_NAME
        )
    }
}
