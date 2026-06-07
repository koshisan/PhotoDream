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
import android.animation.ObjectAnimator
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import android.view.animation.LinearInterpolator
import android.view.animation.OvershootInterpolator
import androidx.core.content.ContextCompat
import com.bumptech.glide.Glide
import com.google.gson.Gson
import de.koshi.photodream.util.Fonts
import de.koshi.photodream.util.ImageBlur
import de.koshi.photodream.util.MdiIcons
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

        // Aurora / frosted-glass palette (approximation of the design's oklch values;
        // a bit more opaque than the spec since Android has no real backdrop blur).
        private val GOLD = Color.parseColor("#E0C16A")          // warm gold day labels
        private val DEFAULT_NC = Color.parseColor("#5B8DEF")    // fallback notification color
        private val DOT_DEFAULT = Color.parseColor("#9AA3AD")   // fallback calendar dot
        private val NOTIF_BORDER = Color.parseColor("#29FFFFFF") // ~0.16 hairline
        private val AURORA_BORDER = Color.parseColor("#24FFFFFF") // ~0.14 hairline
        // Frosted fill: more translucent when a real backdrop blur is available (API 31+),
        // more opaque otherwise so text stays readable over busy photos.
        private val NOTIF_FILL_BLUR = Color.parseColor("#8012141A")    // 0.50 (over blur)
        private val NOTIF_FILL_OPAQUE = Color.parseColor("#F212141A")  // ~0.95 (video, no blur)
        private val AGENDA_FILL_BLUR = Color.parseColor("#7312141A")   // 0.45 (over blur)
        private val AGENDA_FILL_OPAQUE = Color.parseColor("#EE12141A") // ~0.93 (video, no blur)
    }
    
    // UI components
    private lateinit var imageContainer: FrameLayout
    private lateinit var overlayContainer: FrameLayout
    private lateinit var clockScrim: View             // legibility gradient that follows the clock
    private lateinit var mainRow: LinearLayout        // Horizontal: [clock block] [weather block]
    private lateinit var leftColumn: LinearLayout     // Vertical: clock, date
    private lateinit var rightColumn: LinearLayout    // Horizontal: weather icon + (temp, meta)
    private lateinit var clockView: TextView
    private lateinit var dateView: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var weatherTemp: TextView
    private lateinit var weatherMeta: TextView
    private lateinit var updateBanner: TextView       // "Update verfügbar" banner
    private lateinit var infoPanel: LinearLayout      // Image info panel (long-press)
    private lateinit var calendarCard: FrameLayout    // Agenda card (frosted, positioned)
    private lateinit var calendarPanel: LinearLayout  // Agenda content (inside the card)
    private lateinit var calendarTint: View           // translucent frosted fill (agenda)
    private var calendarBackdrop: ImageView? = null   // blurred photo behind agenda (API 31+)
    private lateinit var topScrim: View               // legibility gradient while a notification is up
    private lateinit var notificationCard: FrameLayout // HA-style notification overlay (Aurora)
    private lateinit var notifTint: View              // translucent frosted fill (notification)
    private var notifBackdrop: ImageView? = null      // blurred photo behind notification (API 31+)
    private lateinit var notifIconTile: FrameLayout
    private lateinit var notifIcon: TextView          // MDI glyph
    private lateinit var notifTitle: TextView
    private lateinit var notifMessage: TextView
    private lateinit var notifTime: TextView
    private lateinit var notifImage: ImageView
    private lateinit var notifProgressFill: View
    private lateinit var notifProgressTrack: View
    private var notifProgressAnimator: ObjectAnimator? = null
    private lateinit var renderer: SlideshowRenderer

    // Info panel state
    private var isInfoPanelVisible = false

    // Calendar state
    private var calendarEvents: List<CalendarEvent> = emptyList()

    // Notification state
    private var currentNotification: NotificationPayload? = null
    private var notificationVisible = false
    private var notificationDismissRunnable: Runnable? = null
    
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
            // If a notification is showing and the tap landed on it, trigger its callback
            if (notificationVisible && isTouchInside(notificationCard, e)) {
                onNotificationTapped()
                return true
            }
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
                onCalendarReceived = { events -> applyCalendar(events) }
                onShowNotification = { payload -> showNotification(payload) }
                // Check if there's already a pending update
                pendingUpdate?.let { showUpdateAvailable(it) }
                // Render any calendar events that arrived before the slideshow started
                if (lastCalendarEvents.isNotEmpty()) applyCalendar(lastCalendarEvents)
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
        notificationDismissRunnable?.let { handler.removeCallbacks(it) }
        notifProgressAnimator?.cancel()
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
        
        // Overlay container (positioned via gravity). Don't clip children so large
        // thin glyphs / shadows are never cut at the container edge.
        overlayContainer = FrameLayout(context).apply {
            visibility = View.GONE
            clipChildren = false
            clipToPadding = false
        }
        
        // Clean "Aurora" info cluster (design): thin display typo, carried by a scrim.
        // mainRow (horizontal, bottom-aligned)
        // ├── leftColumn (vertical): clock (thin), date (weekday + date)
        // └── rightColumn (horizontal): weather icon + [temp, meta]
        // Bundled variable font (Roboto Flex) at exact weights, per the design handoff.
        val clockTypeface = Fonts.weight(context, 200, tabular = true)
        val dateTypeface = Fonts.weight(context, 400)
        val tempTypeface = Fonts.weight(context, 300, tabular = true)
        val metaTypeface = Fonts.weight(context, 400, tabular = true)

        mainRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.BOTTOM  // align-items: flex-end
            clipChildren = false
            clipToPadding = false
        }

        leftColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            clipChildren = false
            clipToPadding = false
        }

        clockView = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 32f
            typeface = clockTypeface
            // NOTE: no negative letterSpacing / includeFontPadding=false here — both
            // cause TextView to clip large glyphs (the "18:0" bug).
            setShadowLayer(16f, 0f, 2f, withAlpha(Color.BLACK, 0x73))
        }

        dateView = TextView(context).apply {
            setTextColor(withAlpha(Color.WHITE, 0xEB))
            textSize = 18f
            typeface = dateTypeface
            letterSpacing = 0.01f
            setShadowLayer(12f, 0f, 1f, withAlpha(Color.BLACK, 0x80))
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
        }

        leftColumn.addView(clockView)
        leftColumn.addView(dateView)

        // Weather block: [icon] [temp / meta]
        weatherIcon = ImageView(context).apply {
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.FIT_CENTER
        }

        weatherTemp = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 18f
            typeface = tempTypeface
            setShadowLayer(12f, 0f, 1f, withAlpha(Color.BLACK, 0x80))
        }

        weatherMeta = TextView(context).apply {
            setTextColor(withAlpha(Color.WHITE, 0xC7))
            textSize = 13f
            typeface = metaTypeface
            setShadowLayer(12f, 0f, 1f, withAlpha(Color.BLACK, 0x80))
            visibility = View.GONE
        }

        val weatherTextCol = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginStart = dp(13) }
            addView(weatherTemp)
            addView(weatherMeta)
        }

        rightColumn = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(30)
                bottomMargin = dp(8)  // baseline-ish alignment with the date
            }
            addView(weatherIcon)
            addView(weatherTextCol)
        }

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
        
        // Agenda card (frosted "Aurora" style) - upcoming calendar events from HA.
        // Layered: [blurred backdrop] -> [translucent tint] -> [content panel]
        calendarPanel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
        }
        // Decorative layers start at 0x0 so they don't inflate the WRAP_CONTENT card;
        // syncCardLayers() resizes them to the card after layout.
        calendarTint = View(context).apply {
            setBackgroundColor(AGENDA_FILL_BLUR)
            layoutParams = FrameLayout.LayoutParams(0, 0)
        }
        calendarBackdrop = createBackdrop(24)
        calendarCard = FrameLayout(context).apply {
            visibility = View.GONE
            clipToOutline = true
            elevation = dp(10).toFloat()
            background = roundedTransparent(dp(24).toFloat())
            foreground = roundedBorder(dp(24).toFloat(), AURORA_BORDER)
            addView(calendarBackdrop)  // blurred photo
            addView(calendarTint)      // frosted tint
            addView(calendarPanel)     // content
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                val margin = dp(40)
                setMargins(margin, margin, margin, margin)
                gravity = Gravity.BOTTOM or Gravity.END
            }
        }
        syncCardLayers(calendarCard, calendarTint, calendarBackdrop)

        // Clock legibility scrim - dynamically positioned to follow the clock (set in setupClock)
        clockScrim = View(context).apply {
            visibility = View.GONE
        }

        // Top legibility scrim (only visible while a notification is shown)
        topScrim = View(context).apply {
            visibility = View.GONE
            alpha = 0f
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(Color.parseColor("#8C000000"), Color.TRANSPARENT)
            )
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(220)
            ).apply { gravity = Gravity.TOP }
        }

        // Notification card (HA-style "Aurora" overlay, tappable)
        buildNotificationCard()

        container.addView(imageContainer)
        container.addView(clockScrim)
        container.addView(topScrim)
        container.addView(overlayContainer)
        container.addView(calendarCard)
        container.addView(infoPanel)
        container.addView(updateBanner)
        container.addView(notificationCard)  // always on top

        // Initialize renderer
        renderer = SlideshowRenderer(context, imageContainer).apply {
            crossfadeDuration = 800L
            panEnabled = true
            panDuration = 10000L
            
            onImageShown = { asset ->
                Log.d(TAG, "Image shown: ${asset.id}")
                reportStatusToHA()
                httpService?.updateStatus(getCurrentStatus())
                setBackdropImage()
            }
            
            onImageError = { error ->
                Log.e(TAG, "Image load error: $error")
            }

            // Mirror the Ken Burns pan onto the frosted backdrops each frame (cheap).
            onPanFrame = { matrix -> updateBackdropMatrices(matrix) }
        }
    }
    
    private fun bindHttpService() {
        // Start the service explicitly so it persists as a started service even after Daydream ends.
        // Without this, the service is only bound (BIND_AUTO_CREATE) and Android destroys it
        // when the last binding is released — even if startForeground() was called in onCreate().
        HttpServerService.start(context)
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
                onCalendarReceived = null
                onShowNotification = null
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
                    // Pull any calendar events the HTTP service already has cached
                    // (covers the case where the service bound before config loaded).
                    if (calendarEvents.isEmpty()) {
                        httpService?.lastCalendarEvents?.let {
                            if (it.isNotEmpty()) calendarEvents = it
                        }
                    }
                    setupCalendar(cfg.display)
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
            clockScrim.visibility = View.GONE
            return
        }

        overlayContainer.visibility = View.VISIBLE
        clockView.textSize = display.clockFontSize.toFloat()

        // Reserve ~12% extra width: the thin font's last glyph can exceed the measured
        // advance width and gets clipped by the TextView at larger sizes (WRAP_CONTENT
        // padding does not help). Fixed width + left alignment keeps it clean.
        val clockSample = if (display.clockFormat == "12h") "88:88 AM" else "88:88"
        val clockWidth = (clockView.paint.measureText(clockSample) * 1.12f).toInt()
        clockView.layoutParams = LinearLayout.LayoutParams(
            clockWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Date (weekday + date), sized relative to the clock
        if (display.date) {
            dateView.visibility = View.VISIBLE
            dateView.textSize = (display.clockFontSize * 0.32f).coerceAtLeast(14f)
        } else {
            dateView.visibility = View.GONE
        }

        setupWeather(display)

        // Position the cluster
        val margin = dp(40)
        overlayContainer.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.WRAP_CONTENT,
            FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            setMargins(margin, margin, margin, margin)
            gravity = gravityForPosition(display.clockPosition)
        }

        // Legibility scrim follows the clock's vertical band
        setupClockScrim(display.clockPosition)

        handler.post(clockRunnable)
    }

    private fun gravityForPosition(position: Int): Int = when (position) {
        0 -> Gravity.TOP or Gravity.START
        1 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
        2 -> Gravity.TOP or Gravity.END
        3 -> Gravity.BOTTOM or Gravity.START
        4 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
        5 -> Gravity.BOTTOM or Gravity.END
        6 -> Gravity.CENTER
        else -> Gravity.BOTTOM or Gravity.START
    }

    /**
     * Position the legibility scrim so its dark edge sits behind the clock,
     * regardless of where the clock is shown (top / bottom / center).
     */
    private fun setupClockScrim(position: Int) {
        val screenH = context.resources.displayMetrics.heightPixels
        val band = (screenH * 0.55f).toInt()
        val dark = withAlpha(Color.BLACK, 0xB8)  // 0.72
        val mid = withAlpha(Color.BLACK, 0x59)   // 0.35

        val colors: IntArray
        val grav: Int
        val height: Int
        when (position) {
            0, 1, 2 -> {  // top
                colors = intArrayOf(dark, mid, Color.TRANSPARENT)
                grav = Gravity.TOP
                height = band
            }
            6 -> {        // center
                colors = intArrayOf(Color.TRANSPARENT, withAlpha(Color.BLACK, 0x73), Color.TRANSPARENT)
                grav = Gravity.CENTER
                height = FrameLayout.LayoutParams.MATCH_PARENT
            }
            else -> {     // bottom
                colors = intArrayOf(Color.TRANSPARENT, mid, dark)
                grav = Gravity.BOTTOM
                height = band
            }
        }
        clockScrim.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors)
        clockScrim.layoutParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, height
        ).apply { gravity = grav }
        clockScrim.visibility = View.VISIBLE
    }
    
    private fun setupWeather(display: DisplayConfig) {
        val weather = display.weather

        if (weather == null || !weather.enabled || weather.condition == null) {
            rightColumn.visibility = View.GONE
            return
        }

        rightColumn.visibility = View.VISIBLE
        weatherIcon.setImageResource(getWeatherIconResource(weather.condition))

        val iconSize = (display.clockFontSize * 0.55f).toInt().coerceAtLeast(dp(28))
        weatherIcon.layoutParams = LinearLayout.LayoutParams(iconSize, iconSize)

        val temp = weather.temperature
        weatherTemp.text = if (temp != null) "${temp.toInt()}${weather.temperatureUnit}" else ""
        weatherTemp.textSize = (display.clockFontSize * 0.32f).coerceAtLeast(16f)
        // Same anti-clip width reservation as the clock (thin font + "°").
        val tempWidth = (weatherTemp.paint.measureText("88${weather.temperatureUnit}") * 1.15f).toInt()
        weatherTemp.layoutParams = LinearLayout.LayoutParams(
            tempWidth, LinearLayout.LayoutParams.WRAP_CONTENT
        )

        // Meta line: "Regen · 14° / 21°"  (condition · day low / day high)
        val meta = StringBuilder()
        val cond = conditionLabel(weather.condition)
        if (cond.isNotBlank()) meta.append(cond)
        val lo = weather.tempLow
        val hi = weather.tempHigh
        if (lo != null && hi != null) {
            if (meta.isNotEmpty()) meta.append("  ·  ")
            meta.append("${lo.toInt()}° / ${hi.toInt()}°")
        }
        if (meta.isNotBlank()) {
            weatherMeta.text = meta.toString()
            weatherMeta.textSize = (display.clockFontSize * 0.16f).coerceAtLeast(11f)
            weatherMeta.visibility = View.VISIBLE
        } else {
            weatherMeta.visibility = View.GONE
        }
    }

    /** Human-readable German label for a Home Assistant weather condition. */
    private fun conditionLabel(condition: String?): String = when (condition?.lowercase()) {
        "sunny", "clear", "clear-night" -> "Klar"
        "partlycloudy", "partly-cloudy", "partly_cloudy" -> "Teils bewölkt"
        "cloudy" -> "Bewölkt"
        "rainy", "rain" -> "Regen"
        "pouring" -> "Starkregen"
        "snowy", "snow", "snowy-rainy" -> "Schnee"
        "windy", "wind" -> "Windig"
        "fog", "foggy", "hazy" -> "Nebel"
        "lightning", "lightning-rainy", "thunderstorm" -> "Gewitter"
        "hail" -> "Hagel"
        "exceptional" -> "Unwetter"
        else -> ""
    }
    
    /**
     * Map weather condition string to drawable resource
     */
    private fun getWeatherIconResource(condition: String?): Int {
        // Lucide line icons mapped from HA weather states (see design handoff).
        return when (condition?.lowercase()) {
            "sunny", "clear" -> R.drawable.ic_wx_sun
            "clear-night" -> R.drawable.ic_wx_moon
            "partlycloudy", "partly-cloudy", "partly_cloudy" -> R.drawable.ic_wx_cloud_sun
            "cloudy" -> R.drawable.ic_wx_cloudy
            "fog", "foggy", "hazy" -> R.drawable.ic_wx_cloud_fog
            "rainy", "rain" -> R.drawable.ic_wx_cloud_rain
            "pouring" -> R.drawable.ic_wx_cloud_rain_wind
            "lightning", "lightning-rainy", "thunderstorm" -> R.drawable.ic_wx_cloud_lightning
            "snowy", "snow" -> R.drawable.ic_wx_cloud_snow
            "hail", "snowy-rainy" -> R.drawable.ic_wx_cloud_hail
            "windy", "wind", "windy-variant" -> R.drawable.ic_wx_wind
            "exceptional" -> R.drawable.ic_wx_cloud
            else -> R.drawable.ic_wx_cloud
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
            val weekday = SimpleDateFormat("EEE", Locale.getDefault()).format(now)
            val dateStr = SimpleDateFormat(dateFormat, Locale.getDefault()).format(now)
            // Weekday dimmed, date in full white (matches the design)
            val sb = android.text.SpannableStringBuilder()
            val wd = android.text.SpannableString("$weekday ")
            wd.setSpan(
                android.text.style.ForegroundColorSpan(withAlpha(Color.WHITE, 0xB3)),
                0, wd.length, 0
            )
            sb.append(wd)
            sb.append(dateStr)
            dateView.text = sb
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
                page = currentPage,
                mediaType = profile.mediaType ?: "image"
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
        val intervalMs = cfg.display.intervalSeconds * 1000L

        if (asset.type == "VIDEO") {
            renderer.showVideo(
                asset = asset,
                baseUrl = cfg.immich.baseUrl,
                apiKey = cfg.immich.apiKey,
                intervalMs = intervalMs,
                withTransition = withTransition
            )
        } else {
            renderer.showImage(
                asset = asset,
                baseUrl = cfg.immich.baseUrl,
                apiKey = cfg.immich.apiKey,
                withTransition = withTransition
            )
        }

        Log.d(TAG, "Showing ${asset.type} ${currentIndex + 1}/${playlist.size}: ${asset.id}")
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
        setupCalendar(newConfig.display)
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
    
    // --- Calendar Overlay ---

    /**
     * Apply display settings for the calendar overlay (called when config changes).
     */
    private fun setupCalendar(display: DisplayConfig) {
        val cal = display.calendar
        if (cal == null || !cal.enabled) {
            calendarCard.visibility = View.GONE
            return
        }

        val lp = calendarCard.layoutParams as FrameLayout.LayoutParams
        val margin = dp(40)
        lp.setMargins(margin, margin, margin, margin)
        // Width scales with the screen (design: ~430px on a 1280px display)
        val screenW = context.resources.displayMetrics.widthPixels
        lp.width = (screenW * 0.34f).toInt().coerceIn(dp(280), dp(460))
        lp.gravity = when (cal.position) {
            0 -> Gravity.TOP or Gravity.START
            1 -> Gravity.TOP or Gravity.CENTER_HORIZONTAL
            2 -> Gravity.TOP or Gravity.END
            3 -> Gravity.BOTTOM or Gravity.START
            4 -> Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
            5 -> Gravity.BOTTOM or Gravity.END
            6 -> Gravity.CENTER
            else -> Gravity.BOTTOM or Gravity.END
        }
        calendarCard.layoutParams = lp

        renderCalendar()
    }

    /**
     * Store new events (from POST /calendar) and re-render.
     */
    private fun applyCalendar(events: List<CalendarEvent>) {
        Log.i(TAG, "Applying ${events.size} calendar events")
        calendarEvents = events
        renderCalendar()
    }

    private fun renderCalendar() {
        if (!::calendarPanel.isInitialized) return
        val cal = config?.display?.calendar

        calendarPanel.removeAllViews()

        if (cal == null || !cal.enabled || calendarEvents.isEmpty()) {
            calendarCard.visibility = View.GONE
            return
        }

        val baseFont = cal.fontSize.toFloat()

        val sorted = calendarEvents
            .sortedBy { parseEventTime(it.start)?.toInstant() ?: java.time.Instant.MAX }
            .take(cal.maxEvents.coerceAtLeast(1))

        // Header: calendar icon + "TERMINE" + count chip
        addAgendaHeader(sorted.size)

        var lastDay: String? = null
        var firstDay = true
        for (ev in sorted) {
            val (rel, abs) = dayParts(ev.start)
            val key = "$rel|$abs"
            if (key != lastDay) {
                addAgendaDayLabel(rel, abs, firstDay)
                lastDay = key
                firstDay = false
            }
            addAgendaEvent(ev, baseFont, cal.showLocation)
        }

        calendarCard.visibility = View.VISIBLE
        setBackdropImage()
    }

    private fun addAgendaHeader(total: Int) {
        val head = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = dp(16) }
        }

        val titleRow = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        MdiIcons.glyph(context, "calendar")?.let { g ->
            val icon = TextView(context).apply {
                text = g
                MdiIcons.typeface(context)?.let { typeface = it }
                setTextColor(withAlpha(Color.WHITE, 0xA6))
                textSize = 14f
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginEnd = dp(8) }
            }
            titleRow.addView(icon)
        }
        titleRow.addView(TextView(context).apply {
            text = "TERMINE"
            setTextColor(withAlpha(Color.WHITE, 0x9E))
            textSize = 12f
            letterSpacing = 0.14f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        })

        val countChip = TextView(context).apply {
            text = total.toString()
            setTextColor(withAlpha(Color.WHITE, 0xD9))
            textSize = 12f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(dp(9), dp(3), dp(9), dp(3))
            background = GradientDrawable().apply {
                cornerRadius = dp(999).toFloat()
                setColor(withAlpha(Color.WHITE, 0x1F))
            }
        }

        head.addView(titleRow)
        head.addView(countChip)
        calendarPanel.addView(head)
    }

    private fun addAgendaDayLabel(rel: String?, abs: String, first: Boolean) {
        val tv = TextView(context).apply {
            textSize = 13f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = if (first) 0 else dp(18)
                bottomMargin = dp(9)
            }
        }
        if (rel != null) {
            val sb = android.text.SpannableStringBuilder()
            val relPart = android.text.SpannableString("$rel  ")
            relPart.setSpan(android.text.style.ForegroundColorSpan(GOLD), 0, relPart.length, 0)
            val absPart = android.text.SpannableString(abs)
            absPart.setSpan(
                android.text.style.ForegroundColorSpan(withAlpha(Color.WHITE, 0x73)),
                0, absPart.length, 0
            )
            sb.append(relPart)
            sb.append(absPart)
            tv.text = sb
        } else {
            tv.text = abs
            tv.setTextColor(withAlpha(Color.WHITE, 0xB3))
        }
        calendarPanel.addView(tv)
    }

    private fun addAgendaEvent(ev: CalendarEvent, baseFont: Float, showLocation: Boolean) {
        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(2) }
        }

        val zdt = parseEventTime(ev.start)
        val timeStr = if (ev.allDay) "" else
            zdt?.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm")) ?: ""

        val time = TextView(context).apply {
            text = timeStr
            setTextColor(withAlpha(Color.WHITE, 0xF2))
            textSize = baseFont + 1f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
            layoutParams = LinearLayout.LayoutParams(
                dp(54), LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { marginEnd = dp(10) }
        }

        val titleWrap = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        val dot = View(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(9), dp(9)).apply {
                marginEnd = dp(9)
                topMargin = dp(6)
            }
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(parseColorOrNull(ev.color) ?: DOT_DEFAULT)
            }
        }
        val title = TextView(context).apply {
            text = ev.title
            setTextColor(withAlpha(Color.WHITE, 0xE6))
            textSize = baseFont
            setShadowLayer(3f, 0f, 1f, Color.BLACK)
        }
        titleWrap.addView(dot)
        titleWrap.addView(title)

        row.addView(time)
        row.addView(titleWrap)
        calendarPanel.addView(row)

        if (showLocation && !ev.location.isNullOrBlank()) {
            val loc = TextView(context).apply {
                text = ev.location
                setTextColor(withAlpha(Color.WHITE, 0x99))
                textSize = baseFont * 0.82f
                setShadowLayer(3f, 0f, 1f, Color.BLACK)
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { marginStart = dp(64); bottomMargin = dp(2) }
            }
            calendarPanel.addView(loc)
        }
    }

    /**
     * (relativeLabel, absoluteLabel) for the day an event falls on.
     * e.g. ("Heute", "Sa, 07.06.") or (null, "Di, 09.06.").
     */
    private fun dayParts(start: String?): Pair<String?, String> {
        val zdt = parseEventTime(start) ?: return null to "—"
        val date = zdt.toLocalDate()
        val today = java.time.LocalDate.now()
        val abs = date.format(
            java.time.format.DateTimeFormatter.ofPattern("EEE, dd.MM.", Locale.getDefault())
        )
        val rel = when (date) {
            today -> "Heute"
            today.plusDays(1) -> "Morgen"
            else -> null
        }
        return rel to abs
    }

    /**
     * Parse an ISO-8601 event time into a ZonedDateTime in the device's zone.
     * Accepts offset date-times, instants (Z), and date-only all-day values.
     */
    private fun parseEventTime(iso: String?): java.time.ZonedDateTime? {
        if (iso.isNullOrBlank()) return null
        val zone = java.time.ZoneId.systemDefault()
        return try {
            java.time.OffsetDateTime.parse(iso).atZoneSameInstant(zone)
        } catch (e: Exception) {
            try {
                java.time.Instant.parse(iso).atZone(zone)
            } catch (e2: Exception) {
                try {
                    java.time.LocalDate.parse(iso).atStartOfDay(zone)
                } catch (e3: Exception) {
                    null
                }
            }
        }
    }

    // --- Notification Overlay ---

    private fun buildNotificationCard() {
        // Icon tile (54x54, rounded, tinted with the source color)
        notifIcon = TextView(context).apply {
            MdiIcons.typeface(context)?.let { typeface = it }
            textSize = 30f
            gravity = Gravity.CENTER
        }
        notifIconTile = FrameLayout(context).apply {
            layoutParams = LinearLayout.LayoutParams(dp(54), dp(54)).apply { marginEnd = dp(18) }
            addView(
                notifIcon,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }

        notifTitle = TextView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 20f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
        }
        notifMessage = TextView(context).apply {
            setTextColor(withAlpha(Color.WHITE, 0xCC))
            textSize = 17f
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(3) }
        }
        val body = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            addView(notifTitle)
            addView(notifMessage)
        }

        notifImage = ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            visibility = View.GONE
            background = GradientDrawable().apply {
                cornerRadius = dp(13).toFloat()
                setColor(withAlpha(Color.WHITE, 0x14))
            }
            layoutParams = LinearLayout.LayoutParams(dp(116), dp(68)).apply {
                gravity = Gravity.CENTER_VERTICAL
                marginStart = dp(16)
            }
        }

        notifTime = TextView(context).apply {
            text = "jetzt"
            setTextColor(withAlpha(Color.WHITE, 0x99))
            textSize = 15f
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.TOP
                marginStart = dp(14)
                topMargin = dp(2)
            }
        }

        val row = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(24), dp(18), dp(24), dp(20))
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            )
            addView(notifIconTile)
            addView(body)
            addView(notifImage)
            addView(notifTime)
        }

        // Timer/progress bar pinned to the bottom (drains over the display duration)
        notifProgressFill = View(context).apply {
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(DEFAULT_NC)
            }
            pivotX = 0f
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            )
        }
        notifProgressTrack = FrameLayout(context).apply {
            clipToOutline = true
            background = GradientDrawable().apply {
                cornerRadius = dp(2).toFloat()
                setColor(withAlpha(Color.WHITE, 0x24))
            }
            addView(notifProgressFill)
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                dp(3)
            ).apply {
                gravity = Gravity.BOTTOM
                leftMargin = dp(24)
                rightMargin = dp(24)
                bottomMargin = dp(9)
            }
        }

        // Decorative layers start at 0x0 so they don't inflate the WRAP_CONTENT card;
        // syncCardLayers() resizes them to the card after layout.
        notifTint = View(context).apply {
            setBackgroundColor(NOTIF_FILL_BLUR)
            layoutParams = FrameLayout.LayoutParams(0, 0)
        }
        notifBackdrop = createBackdrop(22)

        notificationCard = FrameLayout(context).apply {
            visibility = View.GONE
            clipToOutline = true
            elevation = dp(12).toFloat()
            background = roundedTransparent(dp(22).toFloat())
            foreground = roundedBorder(dp(22).toFloat(), NOTIF_BORDER)
            addView(notifBackdrop)  // blurred photo
            addView(notifTint)      // frosted tint
            addView(row)            // content
            addView(notifProgressTrack)
        }

        // Width scales with the screen (design: 760px on a 1280px display)
        val screenW = context.resources.displayMetrics.widthPixels
        val cardW = (screenW * 0.6f).toInt().coerceIn(dp(320), dp(900))
        notificationCard.layoutParams = FrameLayout.LayoutParams(
            cardW, FrameLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            topMargin = dp(20)
        }
        syncCardLayers(notificationCard, notifTint, notifBackdrop)
    }

    private fun showNotification(payload: NotificationPayload) {
        if (!::notificationCard.isInitialized) return
        Log.i(TAG, "Showing notification: '${payload.title}' icon=${payload.icon}")

        currentNotification = payload
        notificationDismissRunnable?.let { handler.removeCallbacks(it) }
        notifProgressAnimator?.cancel()

        if (payload.sound) playNotificationSound()

        val nc = parseColorOrNull(payload.color) ?: DEFAULT_NC

        // Icon tile (MDI glyph tinted with the source color; falls back to a bell)
        val glyph = MdiIcons.glyph(context, payload.icon) ?: MdiIcons.glyph(context, "bell")
        if (glyph != null) {
            notifIcon.text = glyph
            notifIcon.setTextColor(nc)
            notifIconTile.background = GradientDrawable().apply {
                cornerRadius = dp(16).toFloat()
                setColor(withAlpha(nc, 0x38))  // ~22% tint
            }
            notifIconTile.visibility = View.VISIBLE
        } else {
            notifIconTile.visibility = View.GONE
        }

        // Title / message
        if (payload.title.isNullOrBlank()) {
            notifTitle.visibility = View.GONE
        } else {
            notifTitle.text = payload.title
            notifTitle.visibility = View.VISIBLE
        }
        notifMessage.text = payload.message

        // Optional image
        if (!payload.imageUrl.isNullOrBlank()) {
            notifImage.visibility = View.VISIBLE
            try {
                Glide.with(context).load(payload.imageUrl).centerCrop().into(notifImage)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load notification image: ${e.message}")
                notifImage.visibility = View.GONE
            }
        } else {
            notifImage.visibility = View.GONE
        }

        // Progress/timer bar
        val durationMs = if (payload.duration > 0) payload.duration * 1000L else 0L
        if (durationMs > 0) {
            notifProgressTrack.visibility = View.VISIBLE
            notifProgressFill.scaleX = 1f
            (notifProgressFill.background as? GradientDrawable)?.setColor(nc)
            notifProgressAnimator = ObjectAnimator.ofFloat(notifProgressFill, "scaleX", 1f, 0f).apply {
                duration = durationMs
                interpolator = LinearInterpolator()
                start()
            }
        } else {
            notifProgressTrack.visibility = View.GONE
        }

        // Slide in (with slight overshoot) + top legibility scrim
        showTopScrim(true)
        notificationCard.visibility = View.VISIBLE
        notificationVisible = true
        notificationCard.alpha = 0f
        notificationCard.translationY = -dp(160).toFloat()
        notificationCard.animate()
            .alpha(1f)
            .translationY(0f)
            .setDuration(500)
            .setInterpolator(OvershootInterpolator(0.9f))
            .start()
        setBackdropImage()

        if (durationMs > 0) {
            val runnable = Runnable { dismissNotification() }
            notificationDismissRunnable = runnable
            handler.postDelayed(runnable, durationMs)
        }
    }

    private fun dismissNotification() {
        if (!::notificationCard.isInitialized) return
        notificationVisible = false
        notificationDismissRunnable?.let { handler.removeCallbacks(it) }
        notificationDismissRunnable = null
        notifProgressAnimator?.cancel()
        notifProgressAnimator = null
        currentNotification = null

        showTopScrim(false)
        notificationCard.animate()
            .alpha(0f)
            .translationY(-dp(160).toFloat())
            .setDuration(280)
            .setInterpolator(LinearInterpolator())
            .withEndAction { notificationCard.visibility = View.GONE }
            .start()
    }

    private fun showTopScrim(show: Boolean) {
        if (!::topScrim.isInitialized) return
        if (show) {
            topScrim.visibility = View.VISIBLE
            topScrim.animate().alpha(1f).setDuration(400).start()
        } else {
            topScrim.animate().alpha(0f).setDuration(400)
                .withEndAction { topScrim.visibility = View.GONE }.start()
        }
    }

    private fun onNotificationTapped() {
        val payload = currentNotification
        Log.d(TAG, "Notification tapped, callback=${payload?.callbackUrl}")
        dismissNotification()

        val url = payload?.callbackUrl
        if (url.isNullOrBlank()) return

        val method = payload.callbackMethod.uppercase()
        // Fire-and-forget on a background thread (scope may be cancelled on finish)
        Thread {
            try {
                val builder = Request.Builder().url(url)
                if (method == "GET") {
                    builder.get()
                } else {
                    builder.post("{}".toRequestBody("application/json".toMediaType()))
                }
                httpClient.newCall(builder.build()).execute().use { response ->
                    Log.d(TAG, "Notification callback response: ${response.code}")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Notification callback failed: ${e.message}", e)
            }
        }.start()
    }

    private fun playNotificationSound() {
        try {
            val uri = android.media.RingtoneManager
                .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(context, uri)?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to play notification sound: ${e.message}")
        }
    }

    // --- Small helpers ---

    private fun dp(value: Int): Int =
        (value * context.resources.displayMetrics.density).toInt()

    /** Apply an explicit alpha (0..255) to an opaque color. */
    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    /** GPU backdrop blur (RenderEffect) available? Otherwise we software-blur the bitmap. */
    private val gpuBlur: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S

    /**
     * Resize a card's decorative layers (tint + blurred backdrop) to match the card
     * once it is laid out. The layers start at 0x0 so they never inflate the card's
     * WRAP_CONTENT height; the content child alone determines the card size.
     */
    private fun syncCardLayers(card: FrameLayout, tint: View, backdrop: ImageView?) {
        card.addOnLayoutChangeListener { _, left, top, right, bottom, _, _, _, _ ->
            val w = right - left
            val h = bottom - top
            if (w <= 0 || h <= 0) return@addOnLayoutChangeListener
            for (layer in listOfNotNull<View>(tint, backdrop)) {
                val lp = layer.layoutParams
                if (lp.width != w || lp.height != h) {
                    lp.width = w
                    lp.height = h
                    layer.layoutParams = lp
                }
            }
            // Refresh the backdrop slice after the layers have been laid out.
            card.post { setBackdropImage() }
        }
    }

    /** Transparent rounded drawable (defines the rounded outline for clipping). */
    private fun roundedTransparent(radius: Float): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
        }

    /** Rounded hairline border (used as a card foreground, drawn over the content). */
    private fun roundedBorder(radius: Float, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke(dp(1), stroke)
        }

    /**
     * Frosted-glass backdrop ImageView. On API 31+ a GPU RenderEffect blur is applied;
     * on older devices the bitmap is software-blurred in [setBackdropImage] instead.
     * Starts at 0x0 (sized by [syncCardLayers]) so it never inflates the card.
     */
    private fun createBackdrop(radiusDp: Int): ImageView =
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.MATRIX
            clipToOutline = true
            background = roundedTransparent(dp(radiusDp).toFloat())
            layoutParams = FrameLayout.LayoutParams(0, 0)
            if (gpuBlur) {
                setRenderEffect(
                    RenderEffect.createBlurEffect(
                        dp(24).toFloat(), dp(24).toFloat(), Shader.TileMode.CLAMP
                    )
                )
            }
        }

    // Downscale factor (full bitmap width / backdrop bitmap width) for mapping the
    // foreground pan matrix onto the (smaller) blurred backdrop bitmap.
    private var backdropSourceScale = 1f

    private fun anyCardVisible(): Boolean =
        (::calendarCard.isInitialized && calendarCard.visibility == View.VISIBLE) ||
        (::notificationCard.isInitialized && notificationCard.visibility == View.VISIBLE)

    /**
     * Set the frosted-glass source ONCE per image: a downscaled (and, on API < 31,
     * pre-blurred) copy of the current photo. The pan is then tracked per-frame in
     * [updateBackdropMatrices] (cheap), so the frost stays smooth.
     *
     * Videos render on a SurfaceView that can't be captured/blurred, so video assets
     * get a genuinely opaque frost instead (no moving image bleeding through).
     */
    private fun setBackdropImage() {
        if (!anyCardVisible()) return

        val isVideo = playlist.getOrNull(currentIndex)?.type == "VIDEO"
        if (isVideo) {
            if (::calendarTint.isInitialized) calendarTint.setBackgroundColor(AGENDA_FILL_OPAQUE)
            if (::notifTint.isInitialized) notifTint.setBackgroundColor(NOTIF_FILL_OPAQUE)
            calendarBackdrop?.visibility = View.INVISIBLE
            notifBackdrop?.visibility = View.INVISIBLE
            // No photo to sample -> neutral white hairline
            applyCardBorder(calendarCard, AURORA_BORDER)
            applyCardBorder(notificationCard, NOTIF_BORDER)
            return
        }
        if (::calendarTint.isInitialized) calendarTint.setBackgroundColor(AGENDA_FILL_BLUR)
        if (::notifTint.isInitialized) notifTint.setBackgroundColor(NOTIF_FILL_BLUR)
        calendarBackdrop?.visibility = View.VISIBLE
        notifBackdrop?.visibility = View.VISIBLE

        val full = (if (::renderer.isInitialized) renderer.currentBitmap() else null) ?: return
        val down = try {
            downscale(full, 480)
        } catch (e: Exception) {
            Log.e(TAG, "Backdrop downscale failed: ${e.message}"); return
        }
        val out: Bitmap = if (gpuBlur) down else {
            val b = ImageBlur.stackBlur(down, 12)
            if (b !== down) down.recycle()
            b
        }
        backdropSourceScale = full.width.toFloat() / out.width.toFloat()
        setBackdropBitmap(calendarBackdrop, out)
        setBackdropBitmap(notifBackdrop, out)

        // Adaptive outline: tint the hairline with the (lightened) average background
        // color so the border "hangs on" the photo behind it.
        val rim = adaptiveRim(averageColor(out))
        applyCardBorder(calendarCard, rim)
        applyCardBorder(notificationCard, rim)

        out.recycle()

        if (::renderer.isInitialized) updateBackdropMatrices(renderer.currentImageMatrix())
    }

    /** Average color of a bitmap via a 1x1 bilinear downscale (cheap). */
    private fun averageColor(bmp: Bitmap): Int {
        val one = Bitmap.createScaledBitmap(bmp, 1, 1, true)
        val c = one.getPixel(0, 0)
        if (one != bmp) one.recycle()
        return c
    }

    /** Lighten a color toward white and apply a soft alpha for a glassy, tinted edge. */
    private fun adaptiveRim(color: Int): Int {
        val r = (Color.red(color) + (255 - Color.red(color)) * 0.45f).toInt()
        val g = (Color.green(color) + (255 - Color.green(color)) * 0.45f).toInt()
        val b = (Color.blue(color) + (255 - Color.blue(color)) * 0.45f).toInt()
        return Color.argb(0x70, r, g, b)
    }

    /** Update a card's hairline (foreground) stroke color. */
    private fun applyCardBorder(card: FrameLayout, color: Int) {
        (card.foreground as? GradientDrawable)?.let {
            it.setStroke(dp(1), color)
            card.invalidate()
        }
    }

    private fun setBackdropBitmap(backdrop: ImageView?, bmp: Bitmap) {
        if (backdrop == null) return
        val copy = bmp.copy(Bitmap.Config.ARGB_8888, false)
        val old = (backdrop.drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap
        backdrop.setImageBitmap(copy)
        if (old != null && old != copy && !old.isRecycled) old.recycle()
    }

    /** Mirror the foreground pan matrix onto each backdrop (called per pan frame). */
    private fun updateBackdropMatrices(panMatrix: Matrix) {
        applyBackdropMatrix(calendarBackdrop, panMatrix)
        applyBackdropMatrix(notifBackdrop, panMatrix)
    }

    private fun applyBackdropMatrix(backdrop: ImageView?, panMatrix: Matrix) {
        if (backdrop == null || backdrop.visibility != View.VISIBLE || backdrop.width <= 0) return
        val loc = IntArray(2); backdrop.getLocationInWindow(loc)
        val cloc = IntArray(2); container.getLocationInWindow(cloc)
        val m = Matrix(panMatrix)
        m.preScale(backdropSourceScale, backdropSourceScale)  // full-res matrix -> downscaled bitmap
        m.postTranslate(-(loc[0] - cloc[0]).toFloat(), -(loc[1] - cloc[1]).toFloat())
        backdrop.imageMatrix = m
    }

    /** Downscale a bitmap so its largest side is at most [maxDim] (returns an own copy). */
    private fun downscale(src: Bitmap, maxDim: Int): Bitmap {
        val largest = maxOf(src.width, src.height)
        if (largest <= maxDim) {
            return src.copy(src.config ?: Bitmap.Config.ARGB_8888, false)
        }
        val s = maxDim.toFloat() / largest
        return Bitmap.createScaledBitmap(
            src,
            (src.width * s).toInt().coerceAtLeast(1),
            (src.height * s).toInt().coerceAtLeast(1),
            true
        )
    }

    private fun parseColorOrNull(hex: String?): Int? {
        if (hex.isNullOrBlank()) return null
        return try {
            Color.parseColor(hex)
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Hit-test a touch event (screen coords) against a visible view.
     */
    private fun isTouchInside(view: View, e: MotionEvent): Boolean {
        if (view.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        view.getLocationOnScreen(loc)
        val x = e.rawX
        val y = e.rawY
        return x >= loc[0] && x <= loc[0] + view.width &&
            y >= loc[1] && y <= loc[1] + view.height
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
