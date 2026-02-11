package de.koshi.photodream.server

import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.IBinder
import android.util.Log
import com.google.gson.Gson
import fi.iki.elonen.NanoHTTPD
import de.koshi.photodream.model.DeviceStatus

/**
 * HTTP Server Service for Home Assistant communication
 * 
 * Endpoints:
 * - GET /status - Returns current device status
 * - POST /refresh-config - Triggers config refresh from HA
 * - POST /next - Advances to next image
 * - POST /set-profile - Changes active profile
 */
class HttpServerService : Service() {
    
    companion object {
        private const val TAG = "HttpServerService"
        const val DEFAULT_PORT = 8080
    }
    
    private var server: PhotoDreamServer? = null
    private val binder = LocalBinder()
    private val gson = Gson()
    
    // Callbacks for DreamService
    var onRefreshConfig: (() -> Unit)? = null
    var onNextImage: (() -> Unit)? = null
    var onSetProfile: ((String) -> Unit)? = null
    var getStatus: (() -> DeviceStatus)? = null
    
    inner class LocalBinder : Binder() {
        fun getService(): HttpServerService = this@HttpServerService
    }
    
    override fun onBind(intent: Intent?): IBinder = binder
    
    override fun onCreate() {
        super.onCreate()
        Log.d(TAG, "HttpServerService created")
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
                    method == Method.POST && uri == "/refresh-config" -> handleRefreshConfig()
                    method == Method.POST && uri == "/next" -> handleNext()
                    method == Method.POST && uri == "/set-profile" -> handleSetProfile(session)
                    method == Method.GET && uri == "/health" -> handleHealth()
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
            val status = getStatus?.invoke() ?: DeviceStatus(online = true)
            return jsonResponse(status)
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
