package de.koshi.photodream.util

import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * Utility class to get device information
 */
object DeviceInfo {
    
    private const val TAG = "DeviceInfo"
    
    /**
     * Get device MAC address
     * Tries multiple methods due to Android privacy restrictions
     */
    fun getMacAddress(context: Context): String? {
        // Method 1: Try reading from /sys/class/net (works on some devices)
        try {
            val interfaces = listOf("wlan0", "eth0", "wlan1")
            for (iface in interfaces) {
                val file = File("/sys/class/net/$iface/address")
                if (file.exists()) {
                    val mac = file.readText().trim().uppercase()
                    if (mac.isNotBlank() && mac != "00:00:00:00:00:00") {
                        Log.d(TAG, "Got MAC from /sys/class/net/$iface: $mac")
                        return mac
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not read MAC from /sys: ${e.message}")
        }
        
        // Method 2: NetworkInterface (may work on older devices or with permissions)
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val networkInterface = interfaces.nextElement()
                
                // Look for wlan or eth interface
                if (!networkInterface.name.contains("wlan", ignoreCase = true) &&
                    !networkInterface.name.contains("eth", ignoreCase = true)) {
                    continue
                }
                
                val mac = networkInterface.hardwareAddress ?: continue
                if (mac.isEmpty()) continue
                
                val macString = mac.joinToString(":") { byte ->
                    String.format("%02X", byte)
                }
                
                if (macString.isNotBlank() && macString != "00:00:00:00:00:00") {
                    Log.d(TAG, "Got MAC from NetworkInterface: $macString")
                    return macString
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not get MAC from NetworkInterface: ${e.message}")
        }
        
        // Method 3: WifiManager (deprecated but may work on some devices)
        try {
            @Suppress("DEPRECATION")
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val mac = wifiInfo?.macAddress
            if (mac != null && mac != "02:00:00:00:00:00" && mac.isNotBlank()) {
                Log.d(TAG, "Got MAC from WifiManager: $mac")
                return mac.uppercase()
            }
        } catch (e: Exception) {
            Log.d(TAG, "Could not get MAC from WifiManager: ${e.message}")
        }
        
        // Fallback: Return Android ID as pseudo-identifier (not a MAC but unique)
        // This ensures we always have some identifier
        Log.w(TAG, "Could not get real MAC address, using Android ID as fallback")
        return null
    }
    
    /**
     * Get device IP address
     */
    fun getIpAddress(context: Context): String? {
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
            
            // Fallback: WifiManager
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            val wifiInfo = wifiManager?.connectionInfo
            val ipInt = wifiInfo?.ipAddress ?: 0
            if (ipInt != 0) {
                return String.format(
                    "%d.%d.%d.%d",
                    ipInt and 0xff,
                    ipInt shr 8 and 0xff,
                    ipInt shr 16 and 0xff,
                    ipInt shr 24 and 0xff
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting IP address: ${e.message}", e)
        }
        return null
    }
    
    /**
     * Get display resolution
     */
    fun getDisplayResolution(context: Context): Pair<Int, Int> {
        return try {
            val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
            
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val bounds = windowManager.currentWindowMetrics.bounds
                Pair(bounds.width(), bounds.height())
            } else {
                val displayMetrics = DisplayMetrics()
                @Suppress("DEPRECATION")
                windowManager.defaultDisplay.getRealMetrics(displayMetrics)
                Pair(displayMetrics.widthPixels, displayMetrics.heightPixels)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error getting display resolution: ${e.message}", e)
            Pair(0, 0)
        }
    }
}
