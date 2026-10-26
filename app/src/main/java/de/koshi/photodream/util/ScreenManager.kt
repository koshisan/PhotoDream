package de.koshi.photodream.util

import android.app.AppOpsManager
import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.util.Log
import de.koshi.photodream.SlideshowActivity
import de.koshi.photodream.server.SlideshowBridge

/**
 * Turns the display off and back on from Home Assistant without losing the device.
 *
 * Off = real backlight off via DevicePolicyManager.lockNow(), which needs PhotoDream to be an
 * active device admin (MainActivity / `dpm set-active-admin`). While off, a PARTIAL_WAKE_LOCK
 * + WifiLock keep the HTTP server and the status webhook reachable.
 *
 * On = SCREEN_BRIGHT wake lock with ACQUIRE_CAUSES_WAKEUP. Since Android 14 that flag is only
 * honoured with the separate "Turn screen on" special app access (appop TURN_SCREEN_ON), so
 * turning off is refused without it - otherwise HA could switch the screen off but never on.
 * A running dream ends when the screen goes off, so on wake the slideshow is restarted as
 * SlideshowActivity (which is allowed over the keyguard).
 */
object ScreenManager {

    private const val TAG = "ScreenManager"
    private const val LOCK_TAG = "PhotoDream:screen"
    private const val OP_TURN_SCREEN_ON = "android:turn_screen_on"

    @Volatile
    var isOff = false
        private set

    /** Called (on the main thread) after the screen state changed, e.g. to report it to HA. */
    var onChanged: (() -> Unit)? = null

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    // The dream/slideshow ends with the screen, restart it on wake
    private var restartSlideshow = false

    // Someone pressed the power button -> treat as "on" (and report it to HA)
    private var screenOnReceiver: BroadcastReceiver? = null

    class AdminReceiver : DeviceAdminReceiver()

    fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

    /** Device admin active -> lockNow() allowed. */
    fun canTurnOff(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent(context))
    }

    /** "Turn screen on" special access (Android 14+); always true on older versions. */
    fun canTurnOn(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
            val mode = appOps.unsafeCheckOpNoThrow(OP_TURN_SCREEN_ON, Process.myUid(), context.packageName)
            // DEFAULT falls back to the manifest permission, which is a normal (auto-granted) one
            mode == AppOpsManager.MODE_ALLOWED || mode == AppOpsManager.MODE_DEFAULT
        } catch (e: Exception) {
            Log.w(TAG, "Could not check TURN_SCREEN_ON: ${e.message}")
            true
        }
    }

    fun isScreenOn() = !isOff

    /**
     * Turn the screen off. Must run on the main thread.
     * Returns null on success, otherwise why it was refused.
     */
    @Synchronized
    fun screenOff(context: Context): String? {
        val app = context.applicationContext
        if (isOff) return null
        if (!canTurnOff(app)) return "Device admin not active (PhotoDream settings: Screen Off)"
        if (!canTurnOn(app)) return "'Turn screen on' access missing - the screen could not be switched on again"

        acquireKeepAliveLocks(app)
        restartSlideshow = SlideshowBridge.ownerAlive()
        screenOnReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                if (isOff) screenOn(context)
            }
        }.also { app.registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_ON)) }

        isOff = true
        val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        dpm.lockNow()
        Log.i(TAG, "Screen off")
        onChanged?.invoke()
        return null
    }

    /** Turn the screen back on. Must run on the main thread. */
    @Synchronized
    fun screenOn(context: Context) {
        if (!isOff) return
        val app = context.applicationContext
        isOff = false

        screenOnReceiver?.let {
            try { app.unregisterReceiver(it) } catch (e: Exception) {}
        }
        screenOnReceiver = null

        @Suppress("DEPRECATION")
        (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "$LOCK_TAG-wake"
            )
            .acquire(5_000L)
        if (restartSlideshow) {
            try {
                SlideshowActivity.start(app)
            } catch (e: Exception) {
                Log.e(TAG, "Could not restart slideshow after wake: ${e.message}")
            }
        }
        restartSlideshow = false

        releaseKeepAliveLocks()
        Log.i(TAG, "Screen on")
        onChanged?.invoke()
    }

    private fun acquireKeepAliveLocks(app: Context) {
        if (cpuLock == null) {
            cpuLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "$LOCK_TAG-cpu")
                .apply { setReferenceCounted(false); acquire() }
        }
        if (wifiLock == null) {
            val wifi = app.getSystemService(Context.WIFI_SERVICE) as WifiManager
            @Suppress("DEPRECATION")
            wifiLock = wifi.createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "$LOCK_TAG-wifi")
                .apply { setReferenceCounted(false); acquire() }
        }
    }

    private fun releaseKeepAliveLocks() {
        cpuLock?.let { if (it.isHeld) it.release() }
        cpuLock = null
        wifiLock?.let { if (it.isHeld) it.release() }
        wifiLock = null
    }
}
