package de.koshi.photodream.server

import de.koshi.photodream.model.CalendarEvent
import de.koshi.photodream.model.DeviceConfig
import de.koshi.photodream.model.DeviceStatus
import de.koshi.photodream.model.MediaState

/**
 * Process-global bridge between the HTTP server and the currently-active slideshow.
 *
 * Why this exists: the HttpServerService used to hold command callbacks (onNextImage,
 * onConfigReceived, ...) that the bound SlideshowController set on connect and nulled on
 * unbind. After long runtime those references went stale -- Daydream restarts caused
 * bind/unbind races where an old controller nulled a newer controller's callbacks, and a
 * re-created service would come up with null callbacks while the rendering controller was
 * still bound to the dead instance. The server kept answering HTTP 200 while commands were
 * silently dropped; only the status push (its own path) kept working. Exactly the
 * "annimmt, reagiert aber nicht" symptom.
 *
 * The bridge decouples command delivery from the binding lifecycle. The active controller
 * registers itself here in start() and unregisters in stop(). Registration is a single
 * @Volatile slot with last-writer-wins semantics, and unregister is identity-checked so a
 * stopping old controller can never clear a newer controller's registration -- regardless
 * of the order in which overlapping Daydream lifecycles fire.
 */
object SlideshowBridge {

    /** Implemented by the active SlideshowController. All command methods run on the main thread. */
    interface Commands {
        fun onConfig(config: DeviceConfig)
        fun onRefreshConfig()
        fun onNext()
        fun onSetProfile(profile: String)
        fun onCalendar(events: List<CalendarEvent>)
        fun onMedia(media: MediaState)
        fun onUpdateAvailable(info: HttpServerService.UpdateInfo)

        /** Queried synchronously from the HTTP thread for /status and /health. */
        fun status(): DeviceStatus
        fun playlistInfo(): HttpServerService.PlaylistInfo?
    }

    @Volatile
    private var current: Commands? = null
    @Volatile
    private var aliveCheck: (() -> Boolean)? = null

    /**
     * Become the active command target. [alive] lets others tell whether this owner is still
     * on-screen, so a self-healing controller reclaims only from a *gone* owner -- never steals
     * from a live one (which would cause two attached controllers to ping-pong ownership).
     */
    fun register(commands: Commands, alive: (() -> Boolean)? = null) {
        current = commands
        aliveCheck = alive
    }

    /** Step down, but only if still the active target (an older controller must not clear a newer one). */
    fun unregister(commands: Commands) {
        if (current === commands) { current = null; aliveCheck = null }
    }

    /** The currently-active controller, or null if no slideshow is running. */
    fun commands(): Commands? = current

    /** True if a controller is registered AND still on-screen (no liveness check => assumed alive). */
    fun ownerAlive(): Boolean = current != null && (aliveCheck?.invoke() ?: true)
}
