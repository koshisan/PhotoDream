package de.koshi.photodream.util

import android.app.admin.DeviceAdminReceiver
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.WifiManager
import android.os.PowerManager
import android.util.Log
import de.koshi.photodream.SlideshowActivity
import de.koshi.photodream.server.SlideshowBridge

/**
 * Turns the display "off" and back on from Home Assistant without losing the device.
 *
 * Two modes:
 * - soft: fully opaque black overlay + minimum backlight. The display stays technically on
 *   (held by a screen wake lock), so the device never sleeps, the slideshow/dream keeps
 *   running (paused) and nothing has to be set up. Works on every device that granted the
 *   overlay permission. A tap on the black screen turns it back on.
 * - hard: real backlight off via DevicePolicyManager.lockNow(). Needs PhotoDream to be an
 *   active device admin (MainActivity / `dpm set-active-admin`). A running dream ends when
 *   the screen goes off, so on wake the slideshow is restarted as SlideshowActivity (which is
 *   allowed over the keyguard). Falls back to soft if the admin isn't active.
 *
 * In both modes a PARTIAL_WAKE_LOCK + WifiLock is held while off, so the HTTP server and
 * the status webhook stay reachable and the screen can be switched on again from HA.
 */
object ScreenManager {

    private const val TAG = "ScreenManager"
    private const val LOCK_TAG = "PhotoDream:screen"

    const val MODE_SOFT = "soft"
    const val MODE_HARD = "hard"

    @Volatile
    var isOff = false
        private set

    /** Effective mode of the current "off" state (null while on). */
    @Volatile
    var offMode: String? = null
        private set

    /** Called (on any thread) after the screen state changed, e.g. to report it to HA. */
    var onChanged: (() -> Unit)? = null

    private var cpuLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null
    private var screenLock: PowerManager.WakeLock? = null

    // Soft mode: brightness to restore on wake
    private var savedBrightness = 50
    private var savedAutoBrightness = false

    // Hard mode: the dream/slideshow ends with the screen, restart it on wake
    private var restartSlideshow = false

    // Hard mode: someone pressed the power button -> treat as "on" (and report it to HA)
    private var screenOnReceiver: BroadcastReceiver? = null

    class AdminReceiver : DeviceAdminReceiver()

    fun adminComponent(context: Context) = ComponentName(context, AdminReceiver::class.java)

    fun isHardOffAvailable(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        return dpm.isAdminActive(adminComponent(context))
    }

    fun isScreenOn() = !isOff

    /** Turn the screen off. Must run on the main thread. Returns the effective mode. */
    @Synchronized
    fun screenOff(context: Context, requestedMode: String): String {
        val app = context.applicationContext
        if (isOff) screenOn(app)   // switching modes: undo the old one first

        val mode = if (requestedMode == MODE_HARD && isHardOffAvailable(app)) MODE_HARD else {
            if (requestedMode == MODE_HARD) Log.w(TAG, "Hard off requested but device admin not active - using soft off")
            MODE_SOFT
        }
        acquireKeepAliveLocks(app)

        if (mode == MODE_HARD) {
            restartSlideshow = SlideshowBridge.ownerAlive()
            screenOnReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    if (isOff) screenOn(context)
                }
            }.also { app.registerReceiver(it, IntentFilter(Intent.ACTION_SCREEN_ON)) }
            val dpm = app.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
        } else {
            // Re-read the system value unless we're overlay-dimmed (only tracked in memory);
            // the cached value is stale if MainActivity never ran in this process.
            if (BrightnessManager.getBrightness() >= 0) BrightnessManager.init(app)
            savedBrightness = BrightnessManager.getBrightness()
            savedAutoBrightness = BrightnessManager.isAutoBrightnessEnabled(app)
            // Keep the panel powered even if no slideshow holds FLAG_KEEP_SCREEN_ON
            @Suppress("DEPRECATION")
            screenLock = (app.getSystemService(Context.POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.SCREEN_DIM_WAKE_LOCK, "$LOCK_TAG-soft")
                .apply { setReferenceCounted(false); acquire() }
            BrightnessOverlayService.start(app)
            BrightnessManager.setBrightness(-100, app)
            BrightnessOverlayService.setTapToWake(true)
        }

        isOff = true
        offMode = mode
        SlideshowBridge.commands()?.onScreenPower()
        Log.i(TAG, "Screen off ($mode)")
        onChanged?.invoke()
        return mode
    }

    /** Turn the screen back on. Must run on the main thread. */
    @Synchronized
    fun screenOn(context: Context) {
        if (!isOff) return
        val app = context.applicationContext

        if (offMode == MODE_HARD) {
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
            screenOnReceiver?.let {
                try { app.unregisterReceiver(it) } catch (e: Exception) {}
            }
            screenOnReceiver = null
        } else {
            BrightnessOverlayService.setTapToWake(false)
            if (savedAutoBrightness) {
                BrightnessManager.setAutoBrightness(true, app)
            } else {
                BrightnessManager.setBrightness(savedBrightness, app)
            }
            screenLock?.let { if (it.isHeld) it.release() }
            screenLock = null
        }

        releaseKeepAliveLocks()
        Log.i(TAG, "Screen on (was $offMode)")
        isOff = false
        offMode = null
        SlideshowBridge.commands()?.onScreenPower()
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
