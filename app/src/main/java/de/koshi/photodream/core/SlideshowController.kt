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
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.google.gson.Gson
import de.koshi.photodream.R
import de.koshi.photodream.api.ImmichClient
import de.koshi.photodream.model.*
import android.graphics.drawable.GradientDrawable
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
    private lateinit var overlayContainer: FrameLayout
    private lateinit var mainRow: LinearLayout        // Horizontal: [leftCol] [rightCol]
    private lateinit var leftColumn: LinearLayout     // Vertical: clock, date
    private lateinit var rightColumn: LinearLayout    // Vertical: weather icon, temp
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var updateBanner: TextView       // "Update verfügbar" banner
    private lateinit var infoPanel: LinearLayout      // Image info panel (long-press)
    private lateinit var renderer: SlideshowRenderer
    
    // Info panel state
    private var isInfoPanelVisible = false
    
    // Update state
    private var pendingUpdateInfo: HttpServerService.UpdateInfo? = null
    
    // State
    private var config: DeviceConfig? = null
    private var lastReceivedConfigJson: String? = null  // For debugging - raw JSON of last config push
    private var immichClient: ImmichClient? = null
    private var playlist: List<Asset> = emptyList()
    private var currentIndex = 0
    
    // Pagination state (survives slideshow restarts, but not HTTP server restarts)
    private var currentPage = 1
    private var hasMorePages = false
    private var displayMode = "smart_shuffle"
    
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
            // If info panel is visible, hide it instead of finishing
            if (isInfoPanelVisible) {
                hideInfoPanel()
                return true
            }
            Log.d(TAG, "Tap detected - finishing")
            onFinish()
            return true
        }
        
        override fun onLongPress(e: MotionEvent) {
            Log.d(TAG, "Long press detected - showing info panel")
            showInfoPanel()
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
                getPlaylistInfo = { getPlaylistInfoInternal() }
                onUpdateAvailable = { updateInfo -> showUpdateAvailable(updateInfo) }
                // Check if there's already a pending update
                pendingUpdate?.let { showUpdateAvailable(it) }
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
        // Report inactive BEFORE cancelling scope (needs coroutine)
        unbindHttpService()
        scope.cancel()
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
        
        // Overlay container (positioned via gravity)
        overlayContainer = FrameLayout(context).apply {
            visibility = View.GONE
        }
        
        // Layout structure:
        // mainRow (horizontal)
        // ├── leftColumn (vertical): clock, date
        // └── rightColumn (vertical): weather icon, temp
        //
        // With date:    [Clock]  [Icon]
        //               [Date]   [Temp]
        //
        // Without date: [Clock]  [Icon+Temp]
        
        mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        
        // Left column: Clock + Date
        leftColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        clockView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 32f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
        }
        
        dateView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            visibility = View.GONE
            // MATCH_PARENT width = same width as clock (column width)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        leftColumn.addView(clockView)
        leftColumn.addView(dateView)
        
        // Right column: Weather Icon + Temp
        rightColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = 32
            }
        }
        
        weatherIcon = ImageView(context).apply {
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }
        
        weatherTemp = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            gravity = Gravity.CENTER
            visibility = View.GONE
            // MATCH_PARENT width = same width as weather icon (column width)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
        }
        
        rightColumn.addView(weatherIcon)
        rightColumn.addView(weatherTemp)
        
        mainRow.addView(leftColumn)
        mainRow.addView(rightColumn)
        overlayContainer.addView(mainRow)
        
        // Update banner (shown when update is available)
        updateBanner = TextView(context).apply {
            text = "⬆️ Update verfügbar — Tippen zum Installieren"
            setTextColor(Color.WHITE)
            textSize = 14f
            setBackgroundColor(Color.parseColor("#CC2196F3"))  // Semi-transparent blue
            setPadding(32, 16, 32, 16)
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            }
            setOnClickListener {
                pendingUpdateInfo?.let { installUpdate(it) }
            }
        }
        
        // Info panel (shown on long press)
        infoPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setPadding(32, 24, 32, 24)
            
            // Semi-transparent black background with rounded corners
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#CC000000"))  // 80% opacity black
                cornerRadius = 24f
            }
            
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = 48
                setMargins(margin, margin, margin, margin)
                // Default to right side (will be adjusted based on clock position)
                gravity = Gravity.END or Gravity.CENTER_VERTICAL
            }
        }
        
        container.addView(imageContainer)
        container.addView(overlayContainer)
        container.addView(infoPanel)
        container.addView(updateBanner)
        
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
            // Send "inactive" status to HA before unbinding
            reportInactiveToHA()
            
            httpService?.apply {
                onConfigReceived = null
                onRefreshConfig = null
                onNextImage = null
                onSetProfile = null
                getStatus = null
                getPlaylistInfo = null
                onUpdateAvailable = null
                updateStatus(DeviceStatus(online = true, active = false))
            }
            context.unbindService(serviceConnection)
            serviceBound = false
            Log.d(TAG, "HttpServerService unbound")
        }
    }
    
    /**
     * Send inactive status to Home Assistant when slideshow stops
     * Runs on a separate thread to ensure it completes even during shutdown
     */
    private fun reportInactiveToHA() {
        val webhookUrl = config?.webhookUrl
        if (webhookUrl.isNullOrBlank()) return
        
        // Use a simple Thread instead of coroutine scope (which may be cancelled)
        Thread {
            try {
                val inactiveStatus = mapOf(
                    "device_id" to (config?.deviceId ?: "unknown"),
                    "online" to true,
                    "active" to false,
                    "current_image" to null,
                    "current_image_url" to null,
                    "profile" to config?.profile?.name,
                    "mac_address" to DeviceInfo.getMacAddress(context),
                    "ip_address" to DeviceInfo.getIpAddress(context),
                    "app_version" to de.koshi.photodream.BuildConfig.VERSION_NAME
                )
                
                val json = gson.toJson(inactiveStatus)
                val request = Request.Builder()
                    .url(webhookUrl)
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                
                httpClient.newCall(request).execute().use { response ->
                    Log.d(TAG, "Reported inactive to HA: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Failed to report inactive to HA: ${e.message}", e)
            }
        }.start()
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
            overlayContainer.visibility = View.GONE
            return
        }
        
        overlayContainer.visibility = View.VISIBLE
        clockView.textSize = display.clockFontSize.toFloat()
        
        // Configure date visibility
        if (display.date) {
            dateView.visibility = View.VISIBLE
            dateView.textSize = (display.clockFontSize * 0.4f).coerceAtLeast(12f)
        } else {
            dateView.visibility = View.GONE
        }
        
        // Setup weather if enabled (needs to know about date visibility)
        setupWeather(display)
        
        // Position the overlay container
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
        overlayContainer.layoutParams = layoutParams
        
        handler.post(clockRunnable)
    }
    
    private fun setupWeather(display: DisplayConfig) {
        val weather = display.weather
        
        // Hide weather if not configured or no data
        if (weather == null || !weather.enabled || weather.condition == null) {
            Log.d(TAG, "Weather hidden: config=${weather != null}, enabled=${weather?.enabled}, condition=${weather?.condition}")
            rightColumn.visibility = View.GONE
            return
        }
        
        Log.d(TAG, "Weather shown: ${weather.condition} ${weather.temperature}${weather.temperatureUnit}")
        rightColumn.visibility = View.VISIBLE
        
        val iconRes = getWeatherIconResource(weather.condition)
        val temp = weather.temperature
        val tempText = if (temp != null) "${temp.toInt()}${weather.temperatureUnit}" else ""
        
        // Set the icon drawable
        weatherIcon.setImageResource(iconRes)
        
        if (display.date) {
            // 2-row mode: icon on top, temp on bottom (aligned with date)
            val iconSize = (display.clockFontSize * 1.2f).toInt()
            weatherIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            
            weatherTemp.text = tempText
            weatherTemp.textSize = (display.clockFontSize * 0.4f).coerceAtLeast(12f)
            weatherTemp.visibility = View.VISIBLE
            weatherTemp.gravity = Gravity.CENTER
            
            // Reset order: icon first, temp second
            rightColumn.removeAllViews()
            rightColumn.addView(weatherIcon)
            rightColumn.addView(weatherTemp)
        } else {
            // 1-row mode: temp small on top-left, icon below
            val iconSize = (display.clockFontSize * 0.9f).toInt()
            weatherIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize).apply {
                gravity = Gravity.CENTER_HORIZONTAL
            }
            
            weatherTemp.text = tempText
            weatherTemp.textSize = (display.clockFontSize * 0.25f).coerceAtLeast(10f)
            weatherTemp.visibility = View.VISIBLE
            weatherTemp.gravity = Gravity.START  // Left-aligned
            
            // Reorder: temp first (top), icon second (bottom)
            rightColumn.removeAllViews()
            rightColumn.addView(weatherTemp)
            rightColumn.addView(weatherIcon)
        }
    }
    
    /**
     * Map weather condition string to drawable resource
     */
    private fun getWeatherIconResource(condition: String?): Int {
        return when (condition?.lowercase()) {
            "sunny", "clear", "clear-night" -> R.drawable.ic_weather_sunny
            "partlycloudy", "partly-cloudy", "partly_cloudy" -> R.drawable.ic_weather_partlycloudy
            "cloudy" -> R.drawable.ic_weather_cloudy
            "rainy", "rain", "pouring" -> R.drawable.ic_weather_rainy
            "snowy", "snow", "snowy-rainy" -> R.drawable.ic_weather_snowy
            "windy", "wind" -> R.drawable.ic_weather_windy
            "fog", "foggy", "hazy" -> R.drawable.ic_weather_foggy
            "lightning", "lightning-rainy", "thunderstorm" -> R.drawable.ic_weather_thunderstorm
            "hail" -> R.drawable.ic_weather_snowy
            "exceptional" -> R.drawable.ic_weather_default
            else -> R.drawable.ic_weather_default
        }
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
    
    private suspend fun loadPlaylist(profile: ProfileConfig, resetPage: Boolean = true) {
        withContext(Dispatchers.IO) {
            val client = immichClient ?: return@withContext
            displayMode = config?.display?.mode ?: "smart_shuffle"
            
            // Reset page for new playlist loads (profile change, etc.)
            // Keep page for sequential mode continuation
            if (resetPage) {
                currentPage = 1
            }
            
            // Load assets based on display mode
            // - sequential: fixed order from smart search (paginated)
            // - random: random selection each time  
            // - smart_shuffle: 50/50 mix of random + recent (last 30 days)
            val (allAssets, hasMore) = client.loadPlaylist(
                profile.searchFilter, 
                displayMode, 
                limit = 500, 
                page = currentPage
            )
            hasMorePages = hasMore
            
            // Apply exclude paths filter
            val filtered = allAssets.filter { asset ->
                profile.excludePaths.none { pattern ->
                    asset.originalPath.contains(pattern.replace("*", ""))
                }
            }
            
            // For sequential mode, keep order as-is. For others, already randomized by API
            playlist = filtered
            currentIndex = 0
            
            Log.i(TAG, "Loaded playlist with ${playlist.size} images (mode: $displayMode, page: $currentPage, hasMore: $hasMorePages)")
        }
    }
    
    /**
     * Load next batch of images when reaching end of playlist
     */
    private fun loadNextBatch() {
        val profile = config?.profile ?: return
        
        scope.launch {
            when (displayMode) {
                "sequential" -> {
                    if (hasMorePages) {
                        // Load next page
                        currentPage++
                        Log.i(TAG, "Sequential: loading next page $currentPage")
                    } else {
                        // No more pages, restart from beginning
                        currentPage = 1
                        Log.i(TAG, "Sequential: no more pages, restarting from page 1")
                    }
                    loadPlaylist(profile, resetPage = false)
                }
                "random", "smart_shuffle" -> {
                    // Load fresh random batch
                    Log.i(TAG, "$displayMode: loading fresh random batch")
                    loadPlaylist(profile, resetPage = true)
                }
                else -> {
                    loadPlaylist(profile, resetPage = true)
                }
            }
            
            // Continue slideshow if we got images
            if (playlist.isNotEmpty()) {
                handler.post { showCurrentImage(withTransition = true) }
            }
        }
    }
    
    private fun startSlideshow() {
        if (playlist.isEmpty()) {
            Log.w(TAG, "No images found - showing placeholder message")
            showError("No images found for current profile.\nPlease check your Immich filter settings.")
            // Don't crash - just don't start the slideshow timer
            // The user can tap to exit or wait for new config
            reportStatusToHA()
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
        
        val nextIndex = currentIndex + 1
        if (nextIndex >= playlist.size) {
            // End of playlist - load next batch
            Log.i(TAG, "Reached end of playlist, loading next batch")
            loadNextBatch()
            return
        }
        
        currentIndex = nextIndex
        showCurrentImage()
        // Refresh info panel if visible
        if (isInfoPanelVisible) {
            refreshInfoPanel()
        }
    }
    
    private fun showPreviousImage() {
        if (playlist.isEmpty()) return
        currentIndex = if (currentIndex > 0) currentIndex - 1 else playlist.size - 1
        showCurrentImage()
        // Refresh info panel if visible
        if (isInfoPanelVisible) {
            refreshInfoPanel()
        }
    }
    
    private fun refreshInfoPanel() {
        val currentAsset = playlist.getOrNull(currentIndex) ?: return
        
        // Show loading briefly, then fetch new details
        scope.launch {
            val details = immichClient?.getAssetDetails(currentAsset.id)
            handler.post {
                infoPanel.removeAllViews()
                populateInfoPanel(currentAsset, details)
            }
        }
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
        
        // Hide overlay container if showing error
        if (::overlayContainer.isInitialized) {
            overlayContainer.visibility = View.GONE
        }
        
        // Create error text view
        val errorView = TextView(context).apply {
            text = message
            setTextColor(Color.WHITE)
            textSize = 20f
            gravity = Gravity.CENTER
            setShadowLayer(4f, 2f, 2f, Color.BLACK)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER
            )
        }
        container.addView(errorView)
    }
    
    // --- Callbacks for HTTP Server ---
    
    private fun applyConfigLive(newConfig: DeviceConfig) {
        Log.i(TAG, "Applying config live: clock=${newConfig.display.clock}, interval=${newConfig.display.intervalSeconds}")
        
        // Store raw JSON for debugging (accessible via /status endpoint)
        lastReceivedConfigJson = gson.toJson(newConfig)
        
        // Compare profile by ID (unique) not just name (could be duplicate across Immich instances)
        val oldProfileId = config?.profile?.id
        val newProfileId = newConfig.profile.id
        val oldPanSpeed = config?.display?.panSpeed
        
        Log.i(TAG, "Profile check: old='$oldProfileId' new='$newProfileId' changed=${oldProfileId != newProfileId}")
        
        config = newConfig
        
        setupClock(newConfig.display)
        applyPanSpeed(newConfig.display.panSpeed, reload = oldPanSpeed != newConfig.display.panSpeed)
        resetSlideshowTimer()
        
        if (oldProfileId != newProfileId) {
            Log.i(TAG, "Profile changed from $oldProfileId to $newProfileId (${newConfig.profile.name}), reloading playlist")
            scope.launch {
                immichClient = ImmichClient(newConfig.immich)
                loadPlaylist(newConfig.profile)
                // Show new image immediately after loading playlist
                if (playlist.isNotEmpty()) {
                    currentIndex = 0
                    showCurrentImage(withTransition = true)
                }
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
                // Show new image immediately
                if (playlist.isNotEmpty()) {
                    currentIndex = 0
                    showCurrentImage(withTransition = true)
                }
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
            appVersion = de.koshi.photodream.BuildConfig.VERSION_NAME,
            lastReceivedConfig = lastReceivedConfigJson
        )
    }
    
    private fun getPlaylistInfoInternal(): HttpServerService.PlaylistInfo? {
        if (playlist.isEmpty()) return null
        
        val baseUrl = config?.immich?.baseUrl ?: ""
        val displayMode = config?.display?.mode ?: "smart_shuffle"
        val searchFilter = config?.profile?.searchFilter
        
        fun assetToImageInfo(asset: Asset?): HttpServerService.ImageInfo? {
            return asset?.let {
                HttpServerService.ImageInfo(
                    id = it.id,
                    thumbnailUrl = it.getThumbnailUrl(baseUrl),
                    originalPath = it.originalPath,
                    createdAt = it.fileCreatedAt
                )
            }
        }
        
        val prevAsset = if (currentIndex > 0) playlist.getOrNull(currentIndex - 1) else null
        val currAsset = playlist.getOrNull(currentIndex)
        val nextAsset = playlist.getOrNull(currentIndex + 1)
        
        // Convert SearchFilter to Map for JSON serialization
        val filterMap = searchFilter?.let { filter ->
            mutableMapOf<String, Any?>().apply {
                filter.query?.let { put("query", it) }
                filter.personIds?.let { if (it.isNotEmpty()) put("personIds", it) }
                filter.tagIds?.let { if (it.isNotEmpty()) put("tagIds", it) }
                filter.albumId?.let { put("albumId", it) }
                filter.city?.let { put("city", it) }
                filter.country?.let { put("country", it) }
                filter.state?.let { put("state", it) }
                filter.takenAfter?.let { put("takenAfter", it) }
                filter.takenBefore?.let { put("takenBefore", it) }
                filter.isArchived?.let { put("isArchived", it) }
                filter.isFavorite?.let { put("isFavorite", it) }
            }.ifEmpty { null }
        }
        
        return HttpServerService.PlaylistInfo(
            currentIndex = currentIndex,
            totalImages = playlist.size,
            displayMode = displayMode,
            searchFilter = filterMap,
            previousImage = assetToImageInfo(prevAsset),
            currentImage = assetToImageInfo(currAsset),
            nextImage = assetToImageInfo(nextAsset)
        )
    }
    
    private fun showUpdateAvailable(updateInfo: HttpServerService.UpdateInfo) {
        pendingUpdateInfo = updateInfo
        handler.post {
            updateBanner.text = "⬆️ Update ${updateInfo.version} verfügbar — Tippen zum Installieren"
            updateBanner.visibility = View.VISIBLE
            Log.i(TAG, "Showing update banner for version ${updateInfo.version}")
        }
    }
    
    private fun installUpdate(updateInfo: HttpServerService.UpdateInfo) {
        Log.i(TAG, "Installing update from ${updateInfo.apkPath}")
        
        try {
            val apkFile = java.io.File(updateInfo.apkPath)
            if (!apkFile.exists()) {
                Log.e(TAG, "APK file not found: ${updateInfo.apkPath}")
                return
            }
            
            // Use FileProvider for Android 7+ compatibility
            val apkUri = androidx.core.content.FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            
            context.startActivity(intent)
            
            // Hide banner and clear pending update
            handler.post {
                updateBanner.visibility = View.GONE
                pendingUpdateInfo = null
            }
            httpService?.clearPendingUpdate()
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to install update: ${e.message}", e)
        }
    }
    
    // --- Info Panel ---
    
    private fun showInfoPanel() {
        if (playlist.isEmpty()) return
        
        val currentAsset = playlist.getOrNull(currentIndex) ?: return
        isInfoPanelVisible = true
        
        // Position panel on opposite side of clock
        val clockPosition = config?.display?.clockPosition ?: 3
        val clockOnLeft = clockPosition in listOf(0, 3)  // top-left or bottom-left
        
        (infoPanel.layoutParams as FrameLayout.LayoutParams).gravity = 
            if (clockOnLeft) Gravity.END or Gravity.CENTER_VERTICAL
            else Gravity.START or Gravity.CENTER_VERTICAL
        
        // Show loading state
        infoPanel.removeAllViews()
        addInfoText("Lade...", bold = false, size = 16f)
        infoPanel.visibility = View.VISIBLE
        
        // Fetch details async
        scope.launch {
            val details = immichClient?.getAssetDetails(currentAsset.id)
            
            handler.post {
                infoPanel.removeAllViews()
                populateInfoPanel(currentAsset, details)
            }
        }
        
        // Pause slideshow timer while panel is shown
        handler.removeCallbacks(slideshowRunnable)
    }
    
    private fun hideInfoPanel() {
        isInfoPanelVisible = false
        infoPanel.visibility = View.GONE
        
        // Resume slideshow timer
        resetSlideshowTimer()
    }
    
    private fun populateInfoPanel(asset: Asset, details: AssetDetails?) {
        val profileName = config?.profile?.name ?: "Unknown"
        val position = "${currentIndex + 1} / ${playlist.size}"
        
        // Profile & Position
        addInfoText("📁 $profileName", bold = true, size = 18f)
        addInfoText("📍 Bild $position", bold = false, size = 14f, topMargin = 4)
        
        // Divider
        addDivider()
        
        // Filename
        val filename = details?.originalFileName ?: asset.originalFileName ?: asset.originalPath.substringAfterLast('/')
        addInfoText("📄 $filename", bold = false, size = 14f)
        
        // Date
        val dateStr = details?.fileCreatedAt ?: asset.fileCreatedAt
        if (dateStr != null) {
            val formatted = formatDate(dateStr)
            addInfoText("📅 $formatted", bold = false, size = 14f, topMargin = 4)
        }
        
        // People
        val people = details?.people
        if (!people.isNullOrEmpty()) {
            val names = people.mapNotNull { it.name }.filter { it.isNotBlank() }
            if (names.isNotEmpty()) {
                addInfoText("👤 ${names.joinToString(", ")}", bold = false, size = 14f, topMargin = 4)
            }
        }
        
        // Tags
        val tags = details?.tags
        if (!tags.isNullOrEmpty()) {
            val tagNames = tags.mapNotNull { it.name ?: it.value }.filter { it.isNotBlank() }
            if (tagNames.isNotEmpty()) {
                addInfoText("🏷️ ${tagNames.joinToString(", ")}", bold = false, size = 14f, topMargin = 4)
            }
        }
        
        // EXIF info
        val exif = details?.exifInfo
        if (exif != null) {
            val exifParts = mutableListOf<String>()
            
            // Camera
            val camera = listOfNotNull(exif.make, exif.model)
                .filter { it.isNotBlank() }
                .joinToString(" ")
            if (camera.isNotBlank()) {
                addDivider()
                addInfoText("📷 $camera", bold = false, size = 14f)
            }
            
            // Lens
            exif.lensModel?.let { lens ->
                if (lens.isNotBlank()) {
                    addInfoText("🔭 $lens", bold = false, size = 13f, topMargin = 2)
                }
            }
            
            // Settings line: f/2.8 · 1/250s · ISO 400 · 50mm
            val settings = mutableListOf<String>()
            exif.fNumber?.let { settings.add("f/${it}") }
            exif.exposureTime?.let { settings.add("${it}s") }
            exif.iso?.let { settings.add("ISO $it") }
            exif.focalLength?.let { settings.add("${it.toInt()}mm") }
            
            if (settings.isNotEmpty()) {
                addInfoText("⚙️ ${settings.joinToString(" · ")}", bold = false, size = 13f, topMargin = 2)
            }
            
            // Location
            val location = listOfNotNull(exif.city, exif.state, exif.country)
                .filter { it.isNotBlank() }
                .joinToString(", ")
            if (location.isNotBlank()) {
                addInfoText("📍 $location", bold = false, size = 13f, topMargin = 4)
            }
        }
        
        // Favorite indicator
        if (details?.isFavorite == true) {
            addInfoText("❤️ Favorit", bold = false, size = 13f, topMargin = 4)
        }
    }
    
    private fun addInfoText(text: String, bold: Boolean, size: Float, topMargin: Int = 0) {
        val textView = TextView(context).apply {
            this.text = text
            setTextColor(Color.WHITE)
            textSize = size
            if (bold) {
                setTypeface(typeface, android.graphics.Typeface.BOLD)
            }
            setShadowLayer(2f, 1f, 1f, Color.BLACK)
            maxWidth = 500  // Prevent overly wide panel
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.topMargin = topMargin
            }
        }
        infoPanel.addView(textView)
    }
    
    private fun addDivider() {
        val divider = View(context).apply {
            setBackgroundColor(Color.parseColor("#44FFFFFF"))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                2
            ).apply {
                topMargin = 12
                bottomMargin = 12
            }
        }
        infoPanel.addView(divider)
    }
    
    private fun formatDate(isoDate: String): String {
        return try {
            val instant = java.time.Instant.parse(isoDate)
            val zoned = instant.atZone(java.time.ZoneId.systemDefault())
            val formatter = java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
            zoned.format(formatter)
        } catch (e: Exception) {
            isoDate.substringBefore('T')  // Fallback: just the date part
        }
    }
}
