package de.koshi.photodream

import android.os.Bundle
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.textfield.TextInputEditText
import android.widget.TextView
import de.koshi.photodream.model.AppSettings
import de.koshi.photodream.util.ConfigManager
import kotlinx.coroutines.*

/**
 * Settings activity for PhotoDream
 * 
 * Allows user to configure:
 * - Home Assistant URL
 * - Device ID
 * - Webhook ID
 * - HTTP Server Port
 */
class MainActivity : AppCompatActivity() {
    
    private lateinit var editHaUrl: TextInputEditText
    private lateinit var editDeviceId: TextInputEditText
    private lateinit var editWebhookId: TextInputEditText
    private lateinit var editServerPort: TextInputEditText
    private lateinit var btnSave: MaterialButton
    private lateinit var btnTest: MaterialButton
    private lateinit var txtStatus: TextView
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        
        // Find views
        editHaUrl = findViewById(R.id.editHaUrl)
        editDeviceId = findViewById(R.id.editDeviceId)
        editWebhookId = findViewById(R.id.editWebhookId)
        editServerPort = findViewById(R.id.editServerPort)
        btnSave = findViewById(R.id.btnSave)
        btnTest = findViewById(R.id.btnTest)
        txtStatus = findViewById(R.id.txtStatus)
        
        // Load existing settings
        loadSettings()
        
        // Setup buttons
        btnSave.setOnClickListener { saveSettings() }
        btnTest.setOnClickListener { testConnection() }
    }
    
    override fun onDestroy() {
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
        if (settings.webhookId.isNotBlank()) {
            editWebhookId.setText(settings.webhookId)
        }
        editServerPort.setText(settings.serverPort.toString())
    }
    
    private fun saveSettings() {
        val haUrl = editHaUrl.text?.toString()?.trim() ?: ""
        val deviceId = editDeviceId.text?.toString()?.trim() ?: ""
        val webhookId = editWebhookId.text?.toString()?.trim() ?: ""
        val serverPort = editServerPort.text?.toString()?.toIntOrNull() ?: 8080
        
        if (haUrl.isBlank()) {
            editHaUrl.error = "Required"
            return
        }
        if (deviceId.isBlank()) {
            editDeviceId.error = "Required"
            return
        }
        if (webhookId.isBlank()) {
            editWebhookId.error = "Required"
            return
        }
        
        val settings = AppSettings(
            haUrl = haUrl,
            deviceId = deviceId,
            webhookId = webhookId,
            serverPort = serverPort
        )
        
        ConfigManager.saveSettings(this, settings)
        
        Toast.makeText(this, "Settings saved!", Toast.LENGTH_SHORT).show()
        txtStatus.text = "Settings saved. Select PhotoDream as your screensaver to test."
    }
    
    private fun testConnection() {
        val haUrl = editHaUrl.text?.toString()?.trim() ?: ""
        val webhookId = editWebhookId.text?.toString()?.trim() ?: ""
        
        if (haUrl.isBlank() || webhookId.isBlank()) {
            txtStatus.text = "Please fill in HA URL and Webhook ID first"
            return
        }
        
        txtStatus.text = "Testing connection..."
        btnTest.isEnabled = false
        
        scope.launch {
            try {
                // Save settings first
                saveSettings()
                
                // Try to load config
                val config = ConfigManager.loadConfig(this@MainActivity, forceRefresh = true)
                
                if (config != null) {
                    txtStatus.text = """
                        ✓ Connection successful!
                        
                        Device: ${config.deviceId}
                        Profile: ${config.profile.name}
                        Immich: ${config.immich.baseUrl}
                        Search queries: ${config.profile.searchQueries.joinToString(", ")}
                    """.trimIndent()
                } else {
                    txtStatus.text = "✗ Failed to load config from Home Assistant.\nCheck URL and Webhook ID."
                }
            } catch (e: Exception) {
                txtStatus.text = "✗ Error: ${e.message}"
            } finally {
                btnTest.isEnabled = true
            }
        }
    }
}
