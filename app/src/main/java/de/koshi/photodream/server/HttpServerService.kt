package de.koshi.photodream.server

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import de.koshi.photodream.MainActivity
import de.koshi.photodream.R
import de.koshi.photodream.model.DeviceConfig
import de.koshi.photodream.model.DeviceStatus
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
 */
class HttpServerService : Service() {
    
    companion object {
        private const val TAG = "HttpServerService"
        const val DEFAULT_PORT = 8080
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL_ID = "photodream_server"
        
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
    private var serverPort = DEFAULT_PORT
    
    // Status tracking (persists even when DreamService is not bound)
    private var currentStatus = DeviceStatus(online = true, active = false)
    
    // Callbacks for DreamService (set when DreamService binds)
    var onConfigReceived: ((DeviceConfig) -> Unit)? = null
    var onRefreshConfig: (() -> Unit)? = null
    var onNextImage: (() -> Unit)? = null
    var onSetProfile: ((String) -> Unit)? = null
    var getStatus: (() -> DeviceStatus)? = null
    
    fun updateStatus(status: DeviceStatus) {
        currentStatus = status
    }
    
    inner class LocalBinder : Binder() {
        fun getService(): HttpServerService = this@HttpServerService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HttpServerService created")
        createNotificationChannel()
    }
    
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        serverPort = intent?.getIntExtra("port", DEFAULT_PORT) ?: DEFAULT_PORT
        
        // Start as foreground service with special use type
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, createNotification(), 
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
        } else {
            startForeground(NOTIFICATION_ID, createNotification())
        }
        
        // Start HTTP server
        startServer(serverPort)
        
        // Return sticky so system restarts service if killed
        return START_STICKY
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
                    method == Method.POST && uri == "/configure" -> handleConfigure(session)
                    method == Method.POST && uri == "/refresh-config" -> handleRefreshConfig()
                    method == Method.POST && uri == "/next" -> handleNext()
                    method == Method.POST && uri == "/set-profile" -> handleSetProfile(session)
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
            val status = getStatus?.invoke() ?: currentStatus
            return jsonResponse(status)
        }
        
        private fun handleConfigure(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"] ?: "{}"
            Log.i(TAG, "Received config from HA: ${postData.take(200)}...")
            
            return try {
                val config = gson.fromJson(postData, DeviceConfig::class.java)
                
                // Save config
                ConfigManager.saveConfigFromHA(this@HttpServerService, config)
                
                // Notify DreamService
                onConfigReceived?.invoke(config)
                
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
            onRefreshConfig?.invoke()
            return jsonResponse(mapOf("success" to true, "message" to "Config refresh triggered"))
        }
        
        private fun handleNext(): Response {
            onNextImage?.invoke()
            return jsonResponse(mapOf("success" to true, "message" to "Next image triggered"))
        }
        
        private fun handleSetProfile(session: IHTTPSession): Response {
            val body = mutableMapOf<String, String>()
            session.parseBody(body)
            
            val postData = body["postData"] ?: "{}"
            val request = gson.fromJson(postData, Map::class.java)
            val profile = request["profile"] as? String
            
            return if (profile != null) {
                onSetProfile?.invoke(profile)
                jsonResponse(mapOf("success" to true, "profile" to profile))
            } else {
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST,
                    MIME_PLAINTEXT,
                    "Missing 'profile' field"
                )
            }
        }
        
        private fun handleHealth(): Response {
            return jsonResponse(mapOf("status" to "ok", "service" to "PhotoDream"))
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
