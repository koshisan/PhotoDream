package de.koshi.photodream

import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView
import de.koshi.photodream.model.AppSettings
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
    private lateinit var cardStatus: MaterialCardView
    private lateinit var txtStatusTitle: TextView
    private lateinit var txtStatus: TextView
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var pollingJob: Job? = null
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Find views
        editHaUrl = findViewById(R.id.editHaUrl)
        editDeviceId = findViewById(R.id.editDeviceId)
        editServerPort = findViewById(R.id.editServerPort)
        btnRegister = findViewById(R.id.btnRegister)
        cardStatus = findViewById(R.id.cardStatus)
        txtStatusTitle = findViewById(R.id.txtStatusTitle)
        txtStatus = findViewById(R.id.txtStatus)
        
        // Load existing settings
        loadSettings()
        
        // Check if already configured
        checkExistingConfig()
        
        // Setup button
        btnRegister.setOnClickListener { registerWithHA() }
    }
    
    override fun onDestroy() {
        pollingJob?.cancel()
        scope.cancel()
        super.onDestroy()
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
