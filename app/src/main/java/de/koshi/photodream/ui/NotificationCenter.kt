package de.koshi.photodream.ui

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import de.koshi.photodream.model.NotificationPayload
import de.koshi.photodream.util.NotificationOverlayService
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * Single source of truth for active notifications. Holds the payloads + their
 * absolute expiry times (so remaining time survives a renderer swap) and runs the
 * expiry timers itself, independent of who is currently showing them.
 *
 * Two renderers plug in:
 *  - the slideshow stack (live frosted cards) — preferred when the slideshow runs
 *  - the system overlay window (solid cards)  — fallback otherwise
 *
 * When the slideshow starts/stops, the active notifications carry over to the other
 * renderer with their remaining time (the overlay window is started/stopped as needed).
 */
object NotificationCenter {

    private const val TAG = "NotificationCenter"

    data class Active(
        val id: Long,
        val payload: NotificationPayload,
        val totalMs: Long,     // 0 = persistent (no timer)
        val endAt: Long        // SystemClock.uptimeMillis() deadline (only if totalMs > 0)
    ) {
        val persistent: Boolean get() = totalMs <= 0L
        fun remainingMs(): Long =
            if (persistent) 0L else (endAt - SystemClock.uptimeMillis()).coerceAtLeast(0L)
        /** Timer-bar fraction, 1f (full) .. 0f (expired). */
        fun fraction(): Float =
            if (persistent) 1f else (remainingMs().toFloat() / totalMs).coerceIn(0f, 1f)
    }

    interface Renderer {
        fun show(active: Active)
        fun remove(id: Long)
        fun reset(actives: List<Active>)
        fun detach()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val httpClient = OkHttpClient()
    private val actives = LinkedHashMap<Long, Active>()   // insertion order (oldest first)
    private val expiries = HashMap<Long, Runnable>()
    private var nextId = 1L
    private var appContext: Context? = null

    private var slideshowRenderer: Renderer? = null
    private var overlayRenderer: Renderer? = null
    private fun current(): Renderer? = slideshowRenderer ?: overlayRenderer

    fun init(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    fun hasActive(): Boolean = actives.isNotEmpty()

    /** Post a new notification (from HA). Safe to call off the main thread. */
    fun post(payload: NotificationPayload) {
        handler.post {
            if (payload.sound) playSound()

            val total = if (payload.duration > 0) payload.duration * 1000L else 0L
            val id = nextId++
            val active = Active(id, payload, total, if (total > 0) SystemClock.uptimeMillis() + total else 0L)
            actives[id] = active

            if (total > 0) {
                val r = Runnable { dismiss(id, false) }
                expiries[id] = r
                handler.postDelayed(r, total)
            }

            val cur = current()
            if (cur != null) cur.show(active)
            else appContext?.let { NotificationOverlayService.ensure(it) }  // it will attach + reset
        }
    }

    /** Remove a notification (timer expiry or tap). */
    fun dismiss(id: Long, triggerCallback: Boolean) {
        val active = actives.remove(id) ?: return
        expiries.remove(id)?.let { handler.removeCallbacks(it) }
        current()?.remove(id)
        if (triggerCallback) fireCallback(active.payload)
    }

    // ---- renderer registration ----

    /** True if [r] is the current slideshow renderer (cheap; for self-heal re-claim checks). */
    fun isSlideshowRenderer(r: Renderer): Boolean = slideshowRenderer === r

    fun attachSlideshow(r: Renderer) {
        slideshowRenderer = r
        // Slideshow has priority: take the cards away from the overlay window.
        overlayRenderer?.detach()
        appContext?.let { NotificationOverlayService.stop(it) }
        r.reset(actives.values.toList())
    }

    fun detachSlideshow(r: Renderer) {
        if (slideshowRenderer !== r) return
        slideshowRenderer = null
        // Hand any still-active notifications back to the overlay window.
        if (actives.isNotEmpty()) appContext?.let { NotificationOverlayService.ensure(it) }
    }

    fun attachOverlay(r: Renderer) {
        overlayRenderer = r
        if (slideshowRenderer == null) r.reset(actives.values.toList()) else r.detach()
    }

    fun detachOverlay(r: Renderer) {
        if (overlayRenderer === r) overlayRenderer = null
    }

    private fun fireCallback(payload: NotificationPayload) {
        val url = payload.callbackUrl
        if (url.isNullOrBlank()) return
        val method = payload.callbackMethod.uppercase()
        Thread {
            try {
                val b = Request.Builder().url(url)
                if (method == "GET") b.get()
                else b.post("{}".toRequestBody("application/json".toMediaType()))
                httpClient.newCall(b.build()).execute().use { Log.d(TAG, "Callback ${it.code}") }
            } catch (e: Exception) {
                Log.e(TAG, "Callback failed: ${e.message}")
            }
        }.start()
    }

    private fun playSound() {
        val ctx = appContext ?: return
        try {
            val uri = android.media.RingtoneManager
                .getDefaultUri(android.media.RingtoneManager.TYPE_NOTIFICATION)
            android.media.RingtoneManager.getRingtone(ctx, uri)?.play()
        } catch (e: Exception) {
            Log.e(TAG, "Sound failed: ${e.message}")
        }
    }
}
