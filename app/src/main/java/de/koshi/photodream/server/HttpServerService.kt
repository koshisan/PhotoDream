package de.koshi.photodream.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.wifi.WifiManager
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import de.koshi.photodream.BuildConfig
import de.koshi.photodream.MainActivity
import de.koshi.photodream.R
import de.koshi.photodream.SlideshowActivity
import java.net.Inet4Address
import java.net.NetworkInterface
import de.koshi.photodream.model.CalendarData
import de.koshi.photodream.model.CalendarEvent
import de.koshi.photodream.model.DeviceConfig
import de.koshi.photodream.model.DeviceStatus
import de.koshi.photodream.model.MediaState
import de.koshi.photodream.model.NotificationPayload
import android.provider.Settings
import de.koshi.photodream.util.BrightnessManager
import de.koshi.photodream.util.BrightnessOverlayService
import de.koshi.photodream.util.ScreenManager
import de.koshi.photodream.ui.NotificationCenter
import de.koshi.photodream.util.ConfigManager

/**
 * HTTP Server Service for Home Assistant communication
 * 
 * Runs as a Foreground Service so it stays alive even when DreamService is not active.
 * This allows HA to push config and send commands at any time.
 * 
 * Endpoints:
 * - GET /status - Returns current device status
 * - GET /health - Health check
 * - POST /configure - Receive config from HA (auto-discovery)
 * - POST /refresh-config - Triggers config refresh from HA
 * - POST /next - Advances to next image
 * - POST /set-profile - Changes active profile
 * - POST /calendar - Receive aggregated calendar events from HA
 * - POST /notify - Show a notification overlay (with optional tap callback)
 * - GET /playlist - Full current playlist as JSON
 * - GET /gallery - Browser page showing the whole playlist as thumbnails
 * - GET /thumb?id=<assetId> - Immich thumbnail proxy (the browser has no API key)
 * - GET/POST /screen - Screen on/off ({"on": false, "mode": "soft"|"hard"})
 */
class HttpServerService : Service() {
    
    companion object {
        private const val TAG = "HttpServerService"
        private val THUMB_ID_REGEX = Regex("^[0-9a-fA-F-]{36}$")
        const val DEFAULT_PORT = 8080
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "photodream_server"
        
        // Cached calendar events (survive cold start so the agenda shows immediately)
        private const val CALENDAR_CACHE_FILE = "calendar_cache.json"

        // SharedPreferences for persistent update state
        private const val PREFS_NAME = "photodream_updates"
        private const val PREF_UPDATE_VERSION = "pending_update_version"
        private const val PREF_UPDATE_APK_PATH = "pending_update_apk_path"
        
        fun start(context: Context, port: Int = DEFAULT_PORT) {
            val intent = Intent(context, HttpServerService::class.java).apply {
                putExtra("port", port)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }
        
        fun stop(context: Context) {
            context.stopService(Intent(context, HttpServerService::class.java))
        }
    }
    
    private var server: PhotoDreamServer? = null
    private val binder = LocalBinder()
    private val gson = Gson()

    // Shared client for /thumb (a gallery page fires hundreds of requests)
    private val thumbHttpClient by lazy { okhttp3.OkHttpClient() }
    private var serverPort = DEFAULT_PORT
    private val mainHandler = Handler(Looper.getMainLooper())
    
    // Status tracking (persists even when DreamService is not bound)
    private var currentStatus = DeviceStatus(online = true, active = false)
    
    // Config for image proxy (to get API key for Immich)
    private var currentConfig: DeviceConfig? = null
    private var lastReceivedConfigJson: String? = null  // Raw JSON for debugging via /health
    
    // Webhook status tracking for debugging
    var lastWebhookAttempt: Long = 0
    var lastWebhookSuccess: Boolean? = null
    var lastWebhookError: String? = null
    var webhookAttemptCount: Int = 0
    
    fun updateConfig(config: DeviceConfig) {
        currentConfig = config
    }
    
    // Commands are routed to the active slideshow via the process-global SlideshowBridge
    // (NOT via callbacks set on this bound service). The bound-callback approach went stale
    // after long runtime -- see SlideshowBridge for the full rationale.

    // Last media state (cached so a freshly started slideshow shows current playback).
    var lastMediaState: MediaState? = null
        private set

    // Last aggregated calendar events (cached in memory + on disk so a freshly
    // started slideshow can render them immediately after binding, even after a reboot).
    var lastCalendarEvents: List<CalendarEvent> = emptyList()
        private set

    private fun saveCalendarEvents(events: List<CalendarEvent>) {
        try {
            java.io.File(filesDir, CALENDAR_CACHE_FILE).writeText(gson.toJson(events))
        } catch (e: Exception) {
            Log.e(TAG, "Failed to persist calendar events: ${e.message}")
        }
    }

    private fun loadCalendarEvents(): List<CalendarEvent> {
        return try {
            val file = java.io.File(filesDir, CALENDAR_CACHE_FILE)
            if (!file.exists()) return emptyList()
            val type = com.google.gson.reflect.TypeToken
                .getParameterized(List::class.java, CalendarEvent::class.java).type
            gson.fromJson<List<CalendarEvent>>(file.readText(), type) ?: emptyList()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load cached calendar events: ${e.message}")
            emptyList()
        }
    }
    
    // Slideshow control callbacks
    var onSlideshowStart: (() -> Unit)? = null
    var onSlideshowExit: (() -> Unit)? = null
    
    // Update state (persisted to SharedPreferences)
    var pendingUpdate: UpdateInfo? = null
        private set
    
    private val updatePrefs by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    
    /**
     * Load pending update from SharedPreferences (survives app restart)
     */
    private fun loadPendingUpdate(): UpdateInfo? {
        val version = updatePrefs.getString(PREF_UPDATE_VERSION, null) ?: return null
        val apkPath = updatePrefs.getString(PREF_UPDATE_APK_PATH, null) ?: return null
        
        // Verify APK still exists
        val apkFile = java.io.File(apkPath)
        if (!apkFile.exists()) {
            Log.w(TAG, "Pending update APK no longer exists, clearing state")
            clearPendingUpdate()
            return null
        }
        
        Log.i(TAG, "Loaded pending update from prefs: $version at $apkPath")
        return UpdateInfo(version, apkPath)
    }
    
    /**
     * Save pending update to SharedPreferences
     */
    private fun savePendingUpdate(updateInfo: UpdateInfo) {
        updatePrefs.edit()
            .putString(PREF_UPDATE_VERSION, updateInfo.version)
            .putString(PREF_UPDATE_APK_PATH, updateInfo.apkPath)
            .apply()
        Log.i(TAG, "Saved pending update to prefs: ${updateInfo.version}")
    }
    
    data class UpdateInfo(
        val version: String,
        val apkPath: String
    )
    
    /**
     * Playlist information for /health endpoint
     */
    data class PlaylistInfo(
        val currentIndex: Int,
        val totalImages: Int,
        val displayMode: String,
        val searchFilter: Map<String, Any?>?,
        val previousImage: ImageInfo?,
        val currentImage: ImageInfo?,
        val nextImage: ImageInfo?
    )
    
    data class ImageInfo(
        val id: String,
        val thumbnailUrl: String?,
        val originalPath: String?,
        val createdAt: String?,
        val type: String? = null,
        val width: Int? = null,
        val height: Int? = null,
        // Public URL for non-Immich sources (Flickr); null means "proxy via /thumb"
        val directUrl: String? = null
    )
    
    fun updateStatus(status: DeviceStatus) {
        currentStatus = status
    }
    
    fun clearPendingUpdate() {
        pendingUpdate = null
        updatePrefs.edit()
            .remove(PREF_UPDATE_VERSION)
            .remove(PREF_UPDATE_APK_PATH)
            .apply()
        Log.i(TAG, "Cleared pending update from prefs")
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): HttpServerService = this@HttpServerService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HttpServerService created")
        createNotificationChannel()

        // Load port from saved settings (works even when started via binding only, e.g. Daydream)
        serverPort = ConfigManager.getSettings(this).serverPort

        // Promote to foreground immediately so the service survives even when bound-only
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }

        // Start HTTP server
        startServer(serverPort)

        // Load cached config if available
        val cachedConfig = ConfigManager.loadCachedConfig(this)
        if (cachedConfig != null) {
            currentConfig = cachedConfig
            Log.i(TAG, "Loaded cached config for device: ${cachedConfig.deviceId}")
        }

        // Load pending update from SharedPreferences (survives app restart)
        pendingUpdate = loadPendingUpdate()

        // Load cached calendar events (survives reboot)
        lastCalendarEvents = loadCalendarEvents()
        if (lastCalendarEvents.isNotEmpty()) {
            Log.i(TAG, "Loaded ${lastCalendarEvents.size} cached calendar events")
        }

        // Initialize device info in status
        updateDeviceInfo()

        // Report screen on/off to HA right away (the slideshow may not be running to do it)
        ScreenManager.onChanged = { reportScreenStateToHA() }
    }

    private val webhookClient by lazy { okhttp3.OkHttpClient() }

    /** Push the full status (incl. screen_on) to the HA status webhook. */
    private fun reportScreenStateToHA() {
        val config = currentConfig ?: return
        val webhookUrl = config.webhookUrl
        if (webhookUrl.isNullOrBlank()) return
        val status = (SlideshowBridge.commands()?.status() ?: currentStatus)
            .copy(screenOn = ScreenManager.isScreenOn())
        val json = gson.toJson(status.webhookPayload(config.deviceId))
        Thread {
            try {
                val request = okhttp3.Request.Builder()
                    .url(webhookUrl)
                    .post(json.toRequestBody("application/json".toMediaType()))
                    .build()
                webhookClient.newCall(request).execute().close()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to report screen state: ${e.message}")
            }
        }.start()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val requestedPort = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT

        // Restart server only if MainActivity requests a different port
        if (requestedPort != serverPort) {
            Log.d(TAG, "Port changed from $serverPort to $requestedPort, restarting server")
            serverPort = requestedPort
            startServer(serverPort)
        }

        // Return sticky so system restarts service if killed
        return START_STICKY
    }
    
    /**
     * Update currentStatus with device info (IP, MAC, display resolution, app version)
     * This ensures status is available even when slideshow is not running.
     */
    private fun updateDeviceInfo() {
        val displayMetrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(displayMetrics)
        
        currentStatus = currentStatus.copy(
            ipAddress = getIpAddress(),
            macAddress = getMacAddress(),
            displayWidth = displayMetrics.widthPixels,
            displayHeight = displayMetrics.heightPixels,
            appVersion = BuildConfig.VERSION_NAME
        )
        
        Log.i(TAG, "Device info updated: IP=${currentStatus.ipAddress}, " +
                "Display=${currentStatus.displayWidth}x${currentStatus.displayHeight}, " +
                "Version=${currentStatus.appVersion}")
    }
    
    private fun getIpAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.isLoopback || !networkInterface.isUp) continue
                
                val addresses = networkInterface.inetAddresses
                while (addresses.hasMoreElements()) {
                    val address = addresses.nextElement()
                    if (address is Inet4Address && !address.isLoopbackAddress) {
                        return address.hostAddress
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}")
        }
        return null
    }
    
    private fun getMacAddress(): String? {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                if (networkInterface.name.startsWith("wlan") || networkInterface.name.startsWith("eth")) {
                    val mac = networkInterface.hardwareAddress ?: continue
                    return mac.joinToString(":") { String.format("%02X", it) }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting MAC address: ${e.message}")
        }
        return null
    }
    
    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "PhotoDream Server",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "HTTP Server for Home Assistant communication"
                setShowBadge(false)
            }
            
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager?.createNotificationChannel(channel)
        }
    }
    
    private fun createNotification(): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("PhotoDream")
            .setContentText("Server running on port $serverPort")
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }
    
    fun startServer(port: Int = DEFAULT_PORT) {
        if (server?.isAlive == true) {
            Log.d(TAG, "Server already running on port $port")
            return
        }
        
        try {
            server = PhotoDreamServer(port)
            server?.start()
            Log.i(TAG, "HTTP Server started on port $port")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start server: ${e.message}", e)
        }
    }
    
    fun stopServer() {
        server?.stop()
        server = null
        Log.i(TAG, "HTTP Server stopped")
    }
    
    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
    
    /**
     * NanoHTTPD server implementation
     */
    private inner class PhotoDreamServer(port: Int) : NanoHTTPD(port) {
        
        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val method = session.method
            
            Log.d(TAG, "Request: $method $uri")
            
            return try {
                when {
                    method == Method.GET && uri == "/status" -> handleStatus()
                    method == Method.GET && uri == "/health" -> handleHealth()
                    method == Method.GET && uri == "/current-image" -> handleCurrentImage()
                    method == Method.GET && uri == "/playlist" -> handlePlaylist()
                    method == Method.GET && uri == "/gallery" -> handleGallery()
                    method == Method.GET && uri == "/thumb" -> handleThumb(session)
                    method == Method.POST && uri == "/configure" -> handleConfigure(session)
                    method == Method.POST && uri == "/refresh-config" -> handleRefreshConfig()
                    method == Method.POST && uri == "/next" -> handleNext()
                    method == Method.POST && uri == "/set-profile" -> handleSetProfile(session)
                    method == Method.POST && uri == "/calendar" -> handleCalendar(session)
                    method == Method.POST && uri == "/media" -> handleMedia(session)
                    method == Method.POST && uri == "/notify" -> handleNotify(session)
                    method == Method.POST && uri == "/prepare-update" -> handlePrepareUpdate(session)
                    
                    // Brightness control endpoints
                    method == Method.GET && uri == "/brightness" -> handleGetBrightness()
                    method == Method.POST && uri == "/brightness" -> handleSetBrightness(session)
                    method == Method.GET && uri == "/auto-brightness" -> handleGetAutoBrightness()
                    method == Method.POST && uri == "/auto-brightness" -> handleSetAutoBrightness(session)
                    
                    // Screen on/off
                    method == Method.GET && uri == "/screen" -> jsonResponse(screenState())
                    method == Method.POST && uri == "/screen" -> handleSetScreen(session)

                    // Slideshow control endpoints
                    method == Method.POST && uri == "/slideshow/start" -> handleSlideshowStart()
                    method == Method.POST && uri == "/slideshow/exit" -> handleSlideshowExit()
                    
                    else -> newFixedLengthResponse(
                        Response.Status.NOT_FOUND,
                        MIME_PLAINTEXT,
                        "Not Found"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error handling request: ${e.message}", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Internal Error: ${e.message}"
                )
            }
        }
        
        private fun handleStatus(): Response {
            // Use callback if DreamService is bound, otherwise use cached status
            val status = SlideshowBridge.commands()?.status() ?: currentStatus
            return jsonResponse(status.copy(screenOn = ScreenManager.isScreenOn()))
        }
        
        private fun handleConfigure(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"] ?: "{}"
            Log.i(TAG, "Received config from HA: ${postData.take(200)}...")
            
            return try {
                val config = gson.fromJson(postData, DeviceConfig::class.java)
                
                // Save config to disk
                ConfigManager.saveConfigFromHA(this@HttpServerService, config)
                
                // Update runtime config (for /health endpoint)
                currentConfig = config
                lastReceivedConfigJson = postData  // Store raw JSON for debugging
                
                // Notify active slideshow on main thread (if running)
                mainHandler.post { SlideshowBridge.commands()?.onConfig(config) }
                
                jsonResponse(mapOf("success" to true, "message" to "Config received"))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse config: ${e.message}", e)
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid config: ${e.message}"
                )
            }
        }
        
        private fun handleRefreshConfig(): Response {
            // Run callback on main thread
            mainHandler.post { SlideshowBridge.commands()?.onRefreshConfig() }
            return jsonResponse(mapOf("success" to true, "message" to "Config refresh triggered"))
        }
        
        private fun handleNext(): Response {
            // Run callback on main thread (UI operations require main thread)
            mainHandler.post { SlideshowBridge.commands()?.onNext() }
            return jsonResponse(mapOf("success" to true, "message" to "Next image triggered"))
        }
        
        private fun handleSetProfile(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"] ?: "{}"
            val request = gson.fromJson(postData, Map::class.java)
            val profile = request["profile"] as? String
            
            return if (profile != null) {
                // Run callback on main thread
                mainHandler.post { SlideshowBridge.commands()?.onSetProfile(profile) }
                jsonResponse(mapOf("success" to true, "profile" to profile))
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing 'profile' field"
                )
            }
        }
        
        private fun handleCalendar(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)

            val postData = body["postData"] ?: "{}"

            return try {
                val data = gson.fromJson(postData, CalendarData::class.java)
                val events = data?.events ?: emptyList()
                lastCalendarEvents = events
                saveCalendarEvents(events)
                Log.i(TAG, "Received ${events.size} calendar events from HA")

                // Notify active slideshow on main thread (if running)
                mainHandler.post { SlideshowBridge.commands()?.onCalendar(events) }

                jsonResponse(mapOf("success" to true, "count" to events.size))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse calendar payload: ${e.message}", e)
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid calendar payload: ${e.message}"
                )
            }
        }

        private fun handleMedia(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            val postData = body["postData"] ?: "{}"
            return try {
                val media = gson.fromJson(postData, MediaState::class.java) ?: MediaState()
                lastMediaState = media
                Log.d(TAG, "Media: ${media.state} - ${media.artist} - ${media.title}")
                mainHandler.post { SlideshowBridge.commands()?.onMedia(media) }
                jsonResponse(mapOf("success" to true, "state" to media.state))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse media payload: ${e.message}", e)
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid media payload: ${e.message}"
                )
            }
        }

        private fun handleNotify(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)

            val postData = body["postData"] ?: "{}"

            return try {
                val payload = gson.fromJson(postData, NotificationPayload::class.java)
                if (payload == null || payload.message.isBlank()) {
                    return newFixedLengthResponse(
                        Response.Status.BAD_REQUEST,
                        MIME_PLAINTEXT,
                        "Missing 'message' field"
                    )
                }

                Log.i(TAG, "Received notification: '${payload.title}' / '${payload.message.take(60)}'")

                // The NotificationCenter holds the active notifications and routes them to
                // the slideshow (frosted) or the system overlay (solid), carrying over on
                // slideshow start/stop. Shows nothing only if neither is available.
                val canOverlay = Settings.canDrawOverlays(this@HttpServerService)
                NotificationCenter.init(this@HttpServerService)
                NotificationCenter.post(payload)

                jsonResponse(mapOf(
                    "success" to true,
                    "overlay_permission" to canOverlay
                ))
            } catch (e: Exception) {
                Log.e(TAG, "Failed to parse notification payload: ${e.message}", e)
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Invalid notification payload: ${e.message}"
                )
            }
        }

        private fun handlePrepareUpdate(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"] ?: "{}"
            val request = gson.fromJson(postData, Map::class.java)
            val apkUrl = request["apk_url"] as? String
            val version = request["version"] as? String ?: "unknown"
            
            if (apkUrl.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing 'apk_url' field"
                )
            }
            
            Log.i(TAG, "Preparing update: version=$version, url=$apkUrl")
            
            // Download APK in background
            Thread {
                try {
                    val apkFile = downloadApk(apkUrl, version)
                    if (apkFile != null) {
                        val updateInfo = UpdateInfo(version, apkFile.absolutePath)
                        pendingUpdate = updateInfo
                        savePendingUpdate(updateInfo)  // Persist to SharedPreferences
                        mainHandler.post { SlideshowBridge.commands()?.onUpdateAvailable(updateInfo) }
                        Log.i(TAG, "Update prepared: $version at ${apkFile.absolutePath}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to download APK: ${e.message}", e)
                }
            }.start()
            
            return jsonResponse(mapOf(
                "success" to true,
                "message" to "Update download started",
                "version" to version
            ))
        }
        
        private fun downloadApk(url: String, version: String): java.io.File? {
            val client = okhttp3.OkHttpClient.Builder()
                .connectTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
                .build()
            
            val request = okhttp3.Request.Builder()
                .url(url)
                .build()
            
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    Log.e(TAG, "APK download failed: ${response.code}")
                    return null
                }
                
                // Save to app's cache directory
                val cacheDir = this@HttpServerService.cacheDir
                val apkFile = java.io.File(cacheDir, "photodream-update-$version.apk")
                
                // Delete old APKs
                cacheDir.listFiles()?.filter { 
                    it.name.startsWith("photodream-update-") && it.name != apkFile.name 
                }?.forEach { it.delete() }
                
                // Write new APK
                response.body?.byteStream()?.use { input ->
                    apkFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                
                Log.i(TAG, "APK downloaded: ${apkFile.absolutePath} (${apkFile.length()} bytes)")
                return apkFile
            }
        }
        
        private fun handleHealth(): Response {
            // Parse stored JSON to include as nested object (not escaped string)
            val configObject = lastReceivedConfigJson?.let {
                try { gson.fromJson(it, Map::class.java) } catch (e: Exception) { null }
            }
            
            // Get playlist info from controller
            val playlistInfo = SlideshowBridge.commands()?.playlistInfo()
            
            return jsonResponse(mapOf(
                "status" to "ok",
                "service" to "PhotoDream",
                "app_version" to de.koshi.photodream.BuildConfig.VERSION_NAME,
                "webhook_url" to (currentConfig?.webhookUrl ?: "NOT SET"),
                "webhook_attempts" to webhookAttemptCount,
                "webhook_last_attempt" to if (lastWebhookAttempt > 0) lastWebhookAttempt else null,
                "webhook_last_success" to lastWebhookSuccess,
                "webhook_last_error" to lastWebhookError,
                "config_loaded" to (currentConfig != null),
                // True when a live slideshow is registered to receive commands (next_image,
                // refresh_config, set_profile, ...). If this is false while the slideshow is
                // visibly rendering, commands are being dropped -- the exact long-runtime bug.
                "slideshow_bound" to (SlideshowBridge.commands() != null),
                "device_id" to currentConfig?.deviceId,
                "playlist" to playlistInfo?.let { mapOf(
                    "current_index" to it.currentIndex,
                    "total_images" to it.totalImages,
                    "position" to "${it.currentIndex + 1}/${it.totalImages}",
                    "display_mode" to it.displayMode,
                    "search_filter" to it.searchFilter,
                    "previous_image" to it.previousImage?.let { img -> mapOf(
                        "id" to img.id,
                        "thumbnail_url" to img.thumbnailUrl,
                        "original_path" to img.originalPath,
                        "created_at" to img.createdAt
                    )},
                    "current_image" to it.currentImage?.let { img -> mapOf(
                        "id" to img.id,
                        "thumbnail_url" to img.thumbnailUrl,
                        "original_path" to img.originalPath,
                        "created_at" to img.createdAt
                    )},
                    "next_image" to it.nextImage?.let { img -> mapOf(
                        "id" to img.id,
                        "thumbnail_url" to img.thumbnailUrl,
                        "original_path" to img.originalPath,
                        "created_at" to img.createdAt
                    )}
                )},
                "pending_update" to pendingUpdate?.let { mapOf(
                    "version" to it.version,
                    "apk_path" to it.apkPath
                )},
                // Last media state exactly as the app parsed it (for diagnosing /media pushes).
                // Always present so a missing /media push is obvious.
                "media" to (lastMediaState ?: mapOf("received" to false)),
                "brightness" to mapOf(
                    "value" to BrightnessManager.getBrightness(),
                    "auto" to BrightnessManager.isAutoBrightnessEnabled(this@HttpServerService),
                    "has_permission" to BrightnessManager.hasWriteSettingsPermission(this@HttpServerService)
                ),
                "screen" to screenState(),
                "last_received_config" to configObject
            ))
        }
        
        private fun handleCurrentImage(): Response {
            val imageUrl = currentStatus.currentImageUrl
            val apiKey = currentConfig?.immich?.apiKey
            
            if (imageUrl.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.NOT_FOUND,
                    MIME_PLAINTEXT,
                    "No current image"
                )
            }
            
            // Public sources (Flickr) need no key - and must not receive the Immich one
            val immichBase = currentConfig?.immich?.baseUrl
            val isImmich = !immichBase.isNullOrBlank() && imageUrl.startsWith(immichBase)
            if (isImmich && apiKey.isNullOrBlank()) {
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "No API key configured"
                )
            }
            
            // Fetch image (from Immich with authentication)
            return try {
                val request = okhttp3.Request.Builder()
                    .url(imageUrl)
                    .apply { if (isImmich) addHeader("x-api-key", apiKey!!) }
                    .build()
                
                val response = okhttp3.OkHttpClient().newCall(request).execute()
                
                if (response.isSuccessful) {
                    val body = response.body
                    val contentType = response.header("Content-Type", "image/jpeg")
                    val bytes = body?.bytes()
                    
                    if (bytes != null) {
                        newFixedLengthResponse(
                            Response.Status.OK,
                            contentType,
                            java.io.ByteArrayInputStream(bytes),
                            bytes.size.toLong()
                        )
                    } else {
                        newFixedLengthResponse(
                            Response.Status.INTERNAL_ERROR,
                            MIME_PLAINTEXT,
                            "Empty response from Immich"
                        )
                    }
                } else {
                    newFixedLengthResponse(
                        Response.Status.lookup(response.code) ?: Response.Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT,
                        "Immich returned: ${response.code}"
                    )
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error fetching image: ${e.message}", e)
                newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR,
                    MIME_PLAINTEXT,
                    "Error fetching image: ${e.message}"
                )
            }
        }
        
        private fun handlePlaylist(): Response {
            val commands = SlideshowBridge.commands()
            val info = commands?.playlistInfo()
            val items = commands?.playlistItems().orEmpty()
            return jsonResponse(mapOf(
                "device_id" to currentConfig?.deviceId,
                "profile" to currentConfig?.profile?.name,
                "display_mode" to info?.displayMode,
                "skip_wrong_aspect" to currentConfig?.display?.skipWrongAspect,
                "current_index" to info?.currentIndex,
                "total_images" to items.size,
                "items" to items.mapIndexed { i, img -> mapOf(
                    "index" to i,
                    "id" to img.id,
                    "type" to img.type,
                    "original_path" to img.originalPath,
                    "created_at" to img.createdAt,
                    "width" to img.width,
                    "height" to img.height,
                    "direct_url" to img.directUrl
                )}
            ))
        }

        private fun handleGallery(): Response {
            val html = assets.open("gallery.html").bufferedReader().use { it.readText() }
            return newFixedLengthResponse(Response.Status.OK, "text/html; charset=utf-8", html)
        }

        private fun handleThumb(session: IHTTPSession): Response {
            val id = session.parameters["id"]?.firstOrNull()
            // Only a bare asset UUID may reach the Immich URL
            if (id == null || !THUMB_ID_REGEX.matches(id)) {
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, MIME_PLAINTEXT, "Invalid id")
            }
            val base = currentConfig?.immich?.baseUrl?.trimEnd('/')
            val apiKey = currentConfig?.immich?.apiKey
            if (base.isNullOrBlank() || apiKey.isNullOrBlank()) {
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, MIME_PLAINTEXT, "No Immich config")
            }
            val request = okhttp3.Request.Builder()
                .url("$base/api/assets/$id/thumbnail?size=thumbnail")
                .addHeader("x-api-key", apiKey)
                .build()
            thumbHttpClient.newCall(request).execute().use { response ->
                val bytes = response.body?.bytes()
                if (!response.isSuccessful || bytes == null) {
                    return newFixedLengthResponse(
                        Response.Status.lookup(response.code) ?: Response.Status.INTERNAL_ERROR,
                        MIME_PLAINTEXT,
                        "Immich returned: ${response.code}"
                    )
                }
                return newFixedLengthResponse(
                    Response.Status.OK,
                    response.header("Content-Type", "image/jpeg"),
                    java.io.ByteArrayInputStream(bytes),
                    bytes.size.toLong()
                ).apply { addHeader("Cache-Control", "max-age=86400") }
            }
        }

        // ========== Brightness Control Handlers ==========
        
        private fun handleGetBrightness(): Response {
            val brightness = BrightnessManager.getBrightness()
            val hasPermission = BrightnessManager.hasWriteSettingsPermission(this@HttpServerService)
            
            return jsonResponse(mapOf(
                "brightness" to brightness,
                "has_permission" to hasPermission
            ))
        }
        
        private fun handleSetBrightness(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"] ?: "{}"
            val request = gson.fromJson(postData, Map::class.java)
            val value = (request["value"] as? Number)?.toInt()
            
            if (value == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing 'value' field (expected -100 to 100)"
                )
            }
            
            // Execute on main thread (required for WindowManager)
            mainHandler.post {
                // Ensure overlay service is running for negative values
                if (value < 0 && !BrightnessOverlayService.isRunning()) {
                    BrightnessOverlayService.start(this@HttpServerService)
                }
                BrightnessManager.setBrightness(value, this@HttpServerService)
            }
            
            // Return both brightness and auto_brightness state
            // (manual brightness change disables auto)
            return jsonResponse(mapOf(
                "success" to true,
                "brightness" to value.coerceIn(-100, 100),
                "auto_brightness" to false  // Manual change always disables auto
            ))
        }
        
        private fun handleGetAutoBrightness(): Response {
            val enabled = BrightnessManager.isAutoBrightnessEnabled(this@HttpServerService)
            val supported = BrightnessManager.hasAutoBrightnessSupport(this@HttpServerService)
            val hasPermission = BrightnessManager.hasWriteSettingsPermission(this@HttpServerService)
            
            return jsonResponse(mapOf(
                "auto_brightness" to enabled,
                "supported" to supported,
                "has_permission" to hasPermission
            ))
        }
        
        private fun handleSetAutoBrightness(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"] ?: "{}"
            val request = gson.fromJson(postData, Map::class.java)
            val enabled = request["enabled"] as? Boolean
            
            if (enabled == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing 'enabled' field (expected boolean)"
                )
            }
            
            val success = BrightnessManager.setAutoBrightness(enabled, this@HttpServerService)
            
            return jsonResponse(mapOf(
                "success" to success,
                "auto_brightness" to enabled
            ))
        }
        
        // ========== Screen On/Off ==========

        private fun screenState(): Map<String, Any?> = mapOf(
            "screen_on" to ScreenManager.isScreenOn(),
            "mode" to ScreenManager.offMode,
            "hard_off_available" to ScreenManager.isHardOffAvailable(this@HttpServerService),
            "overlay_permission" to Settings.canDrawOverlays(this@HttpServerService)
        )

        private fun handleSetScreen(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)

            val postData = body["postData"] ?: "{}"
            val request = gson.fromJson(postData, Map::class.java)
            val on = request["on"] as? Boolean
            val mode = (request["mode"] as? String) ?: ScreenManager.MODE_SOFT

            if (on == null) {
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing 'on' field (expected boolean)"
                )
            }

            // Wake locks, WindowManager and lockNow() all want the main thread; wait for it
            // so the response carries the effective mode (hard falls back to soft).
            val done = java.util.concurrent.CountDownLatch(1)
            mainHandler.post {
                try {
                    if (on) ScreenManager.screenOn(this@HttpServerService)
                    else ScreenManager.screenOff(this@HttpServerService, mode)
                } catch (e: Exception) {
                    Log.e(TAG, "Screen ${if (on) "on" else "off"} failed: ${e.message}", e)
                } finally {
                    done.countDown()
                }
            }
            done.await(3, java.util.concurrent.TimeUnit.SECONDS)

            return jsonResponse(mapOf<String, Any?>("success" to (ScreenManager.isScreenOn() == on)) + screenState())
        }

        // ========== Slideshow Control Handlers ==========
        
        private fun handleSlideshowStart(): Response {
            mainHandler.post { 
                // Start SlideshowActivity directly
                SlideshowActivity.start(this@HttpServerService)
            }
            return jsonResponse(mapOf(
                "success" to true,
                "message" to "Slideshow start triggered"
            ))
        }
        
        private fun handleSlideshowExit(): Response {
            mainHandler.post {
                // Send broadcast to exit slideshow
                val intent = Intent(SlideshowActivity.ACTION_EXIT_SLIDESHOW)
                this@HttpServerService.sendBroadcast(intent)
            }
            return jsonResponse(mapOf(
                "success" to true,
                "message" to "Slideshow exit triggered"
            ))
        }
        
        private fun jsonResponse(data: Any): Response {
            val json = gson.toJson(data)
            return newFixedLengthResponse(
                Response.Status.OK,
                "application/json",
                json
            )
        }
    }
}
