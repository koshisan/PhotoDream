package de.koshi.photodream

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.slider.Slider
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView
import de.koshi.photodream.model.AppSettings
import de.koshi.photodream.server.HttpServerService
import de.koshi.photodream.util.BrightnessManager
import de.koshi.photodream.util.BrightnessOverlayService
import de.koshi.photodream.util.ConfigManager
import kotlinx.coroutines.*

/**
 * Settings activity for PhotoDream
 * 
 * Simple setup flow:
 * 1. User enters HA URL + Device ID
 * 2. Click Register → device appears in HA
 * 3. User configures in HA → config is pushed back automatically
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var editHaUrl: TextInputEditText
    private lateinit var editDeviceId: TextInputEditText
    private lateinit var editServerPort: TextInputEditText
    private lateinit var btnRegister: MaterialButton
    private lateinit var btnStartSlideshow: MaterialButton
    private lateinit var cardStatus: MaterialCardView
    private lateinit var txtStatusTitle: TextView
    private lateinit var txtStatus: TextView
    
    // Brightness control views
    private lateinit var cardBrightness: MaterialCardView
    private lateinit var sliderBrightness: Slider
    private lateinit var txtBrightnessValue: TextView
    private lateinit var btnAutoBrightness: MaterialButton
    private lateinit var txtBrightnessPermission: TextView
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null
    
    companion object {
        private const val REQUEST_WRITE_SETTINGS = 1001
        private const val REQUEST_OVERLAY_PERMISSION = 1002
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Find views
        editHaUrl = findViewById(R.id.editHaUrl)
        editDeviceId = findViewById(R.id.editDeviceId)
        editServerPort = findViewById(R.id.editServerPort)
        btnRegister = findViewById(R.id.btnRegister)
        btnStartSlideshow = findViewById(R.id.btnStartSlideshow)
        cardStatus = findViewById(R.id.cardStatus)
        txtStatusTitle = findViewById(R.id.txtStatusTitle)
        txtStatus = findViewById(R.id.txtStatus)
        
        // Find brightness control views
        cardBrightness = findViewById(R.id.cardBrightness)
        sliderBrightness = findViewById(R.id.sliderBrightness)
        txtBrightnessValue = findViewById(R.id.txtBrightnessValue)
        btnAutoBrightness = findViewById(R.id.btnAutoBrightness)
        txtBrightnessPermission = findViewById(R.id.txtBrightnessPermission)
        
        // Load existing settings
        loadSettings()
        
        // Start HTTP server as foreground service (runs permanently)
        startHttpServer()
        
        // Initialize brightness control
        setupBrightnessControl()
        
        // Check if already configured
        checkExistingConfig()
        
        // Setup buttons
        btnRegister.setOnClickListener { registerWithHA() }
        btnStartSlideshow.setOnClickListener { SlideshowActivity.start(this) }
    }
    
    override fun onResume() {
        super.onResume()
        // Refresh permission states
        updateBrightnessPermissionUI()
    }
    
    private fun setupBrightnessControl() {
        // Initialize BrightnessManager
        BrightnessManager.init(this)
        
        // Check and request permissions
        checkBrightnessPermissions()
        
        // Set initial slider value
        sliderBrightness.value = BrightnessManager.getBrightness().toFloat()
        updateBrightnessValueText(BrightnessManager.getBrightness())
        
        // Slider change listener
        sliderBrightness.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                val intValue = value.toInt()
                updateBrightnessValueText(intValue)
                
                // Start overlay service if going negative
                if (intValue < 0 && !BrightnessOverlayService.isRunning()) {
                    if (Settings.canDrawOverlays(this)) {
                        BrightnessOverlayService.start(this)
                    }
                }
                
                BrightnessManager.setBrightness(intValue, this)
            }
        }
        
        // Auto-brightness button
        updateAutoBrightnessButton()
        btnAutoBrightness.setOnClickListener {
            val currentState = BrightnessManager.isAutoBrightnessEnabled(this)
            BrightnessManager.setAutoBrightness(!currentState, this)
            updateAutoBrightnessButton()
        }
        
        // Permission warning click handler
        txtBrightnessPermission.setOnClickListener {
            requestBrightnessPermissions()
        }
    }
    
    private fun checkBrightnessPermissions() {
        val hasWriteSettings = Settings.System.canWrite(this)
        val hasOverlay = Settings.canDrawOverlays(this)
        
        if (!hasWriteSettings || !hasOverlay) {
            txtBrightnessPermission.visibility = View.VISIBLE
            txtBrightnessPermission.text = buildString {
                append("⚠️ Permissions needed: ")
                if (!hasWriteSettings) append("Write Settings ")
                if (!hasOverlay) append("Overlay")
                append("\nTap to grant")
            }
        } else {
            txtBrightnessPermission.visibility = View.GONE
            // Start overlay service if permissions granted
            BrightnessOverlayService.start(this)
        }
    }
    
    private fun updateBrightnessPermissionUI() {
        checkBrightnessPermissions()
        updateAutoBrightnessButton()
    }
    
    private fun requestBrightnessPermissions() {
        val hasWriteSettings = Settings.System.canWrite(this)
        val hasOverlay = Settings.canDrawOverlays(this)
        
        when {
            !hasWriteSettings -> {
                // Request WRITE_SETTINGS permission
                val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_WRITE_SETTINGS)
            }
            !hasOverlay -> {
                // Request SYSTEM_ALERT_WINDOW permission
                val intent = Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivityForResult(intent, REQUEST_OVERLAY_PERMISSION)
            }
        }
    }
    
    private fun updateBrightnessValueText(value: Int) {
        val label = when {
            value < 0 -> "Brightness: $value (Overlay Dim)"
            value == 0 -> "Brightness: 0 (Minimum)"
            else -> "Brightness: $value"
        }
        txtBrightnessValue.text = label
    }
    
    private fun updateAutoBrightnessButton() {
        val hasSupport = BrightnessManager.hasAutoBrightnessSupport(this)
        val hasPermission = BrightnessManager.hasWriteSettingsPermission(this)
        
        if (!hasSupport) {
            btnAutoBrightness.isEnabled = false
            btnAutoBrightness.text = "Auto-Brightness: Not Supported"
        } else if (!hasPermission) {
            btnAutoBrightness.isEnabled = false
            btnAutoBrightness.text = "Auto-Brightness: No Permission"
        } else {
            btnAutoBrightness.isEnabled = true
            val enabled = BrightnessManager.isAutoBrightnessEnabled(this)
            btnAutoBrightness.text = if (enabled) "Auto-Brightness: ON" else "Auto-Brightness: OFF"
        }
    }
    
    private fun startHttpServer() {
        val settings = ConfigManager.getSettings(this)
        HttpServerService.start(this, settings.serverPort)
    }
    
    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }
    
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        
        when (requestCode) {
            REQUEST_WRITE_SETTINGS, REQUEST_OVERLAY_PERMISSION -> {
                // Re-check permissions and update UI
                updateBrightnessPermissionUI()
                
                // If we now have overlay permission, start the service
                if (Settings.canDrawOverlays(this)) {
                    BrightnessOverlayService.start(this)
                }
                
                // If still missing permissions, request the next one
                if (!Settings.System.canWrite(this) || !Settings.canDrawOverlays(this)) {
                    requestBrightnessPermissions()
                }
            }
        }
    }
    
    private fun loadSettings() {
        val settings = ConfigManager.getSettings(this)
        
        if (settings.haUrl.isNotBlank()) {
            editHaUrl.setText(settings.haUrl)
        }
        if (settings.deviceId.isNotBlank()) {
            editDeviceId.setText(settings.deviceId)
        }
        editServerPort.setText(settings.serverPort.toString())
    }
    
    private fun checkExistingConfig() {
        val config = ConfigManager.loadCachedConfig(this)
        if (config != null) {
            showStatus(
                "✓ Configured",
                """
                Device: ${config.deviceId}
                Profile: ${config.profile.name}
                Immich: ${config.immich.baseUrl}
                
                PhotoDream is ready! Go to:
                Settings → Display → Screen saver → PhotoDream
                """.trimIndent(),
                isSuccess = true
            )
            btnRegister.text = "Re-register"
        }
    }
    
    private fun saveSettings(): Boolean {
        val haUrl = editHaUrl.text?.toString()?.trim() ?: ""
        val deviceId = editDeviceId.text?.toString()?.trim() ?: ""
        val serverPort = editServerPort.text?.toString()?.toIntOrNull() ?: 8080
        
        if (haUrl.isBlank()) {
            editHaUrl.error = "Required"
            return false
        }
        if (deviceId.isBlank()) {
            editDeviceId.error = "Required"
            return false
        }
        
        val settings = AppSettings(
            haUrl = haUrl,
            deviceId = deviceId,
            webhookId = "", // Not needed anymore
            serverPort = serverPort
        )
        
        ConfigManager.saveSettings(this, settings)
        return true
    }
    
    private fun registerWithHA() {
        if (!saveSettings()) return
        
        btnRegister.isEnabled = false
        showStatus("⏳ Registering...", "Connecting to Home Assistant...", isSuccess = false)
        
        scope.launch {
            val result = ConfigManager.registerWithHA(this@MainActivity)
            
            when (result) {
                "configured" -> {
                    // Already configured - load and show config
                    val config = ConfigManager.loadCachedConfig(this@MainActivity)
                    showStatus(
                        "✓ Configured",
                        """
                        Device: ${config?.deviceId ?: "?"}
                        Profile: ${config?.profile?.name ?: "?"}
                        
                        PhotoDream is ready! Go to:
                        Settings → Display → Screen saver → PhotoDream
                        """.trimIndent(),
                        isSuccess = true
                    )
                    btnRegister.text = "Re-register"
                    btnRegister.isEnabled = true
                }
                
                "pending" -> {
                    // Waiting for approval in HA
                    showStatus(
                        "⏳ Waiting for approval",
                        """
                        Device registered! Now:
                        
                        1. Open Home Assistant
                        2. Go to Settings → Devices & Services
                        3. Look for 'PhotoDream' notification
                        4. Click 'Configure' and set up your device
                        
                        This screen will update automatically...
                        """.trimIndent(),
                        isSuccess = false
                    )
                    
                    // Start polling for config
                    startPolling()
                }
                
                else -> {
                    showStatus(
                        "✗ Registration failed",
                        """
                        Could not connect to Home Assistant.
                        
                        Please check:
                        - Home Assistant URL is correct
                        - PhotoDream integration is installed in HA
                        - Device is on the same network as HA
                        """.trimIndent(),
                        isSuccess = false
                    )
                    btnRegister.isEnabled = true
                }
            }
        }
    }
    
    private fun startPolling() {
        pollingJob?.cancel()
        pollingJob = scope.launch {
            var attempts = 0
            val maxAttempts = 60 // Poll for 5 minutes
            
            while (attempts < maxAttempts) {
                delay(5000) // Poll every 5 seconds
                attempts++
                
                val config = ConfigManager.pollForConfig(this@MainActivity)
                if (config != null) {
                    // Config received!
                    showStatus(
                        "✓ Configured!",
                        """
                        Device: ${config.deviceId}
                        Profile: ${config.profile.name}
                        Immich: ${config.immich.baseUrl}
                        
                        PhotoDream is ready! Go to:
                        Settings → Display → Screen saver → PhotoDream
                        """.trimIndent(),
                        isSuccess = true
                    )
                    btnRegister.text = "Re-register"
                    btnRegister.isEnabled = true
                    return@launch
                }
            }
            
            // Timeout
            showStatus(
                "⏳ Still waiting...",
                "Device is still waiting for configuration in Home Assistant.\nTap 'Register' to try again.",
                isSuccess = false
            )
            btnRegister.isEnabled = true
        }
    }
    
    private fun showStatus(title: String, message: String, isSuccess: Boolean) {
        cardStatus.visibility = View.VISIBLE
        txtStatusTitle.text = title
        txtStatus.text = message
        
        // Could add color styling based on isSuccess
    }
}
