package de.koshi.photodream.dream

import android.service.dreams.DreamService
import android.view.MotionEvent
import android.view.View
import android.widget.FrameLayout
import de.koshi.photodream.core.SlideshowController

/**
 * DreamService wrapper - displays photos from Immich as a screensaver.
 * 
 * All slideshow logic is in SlideshowController.
 * This is just a thin wrapper for Android's Daydream/Screensaver system.
 */
class PhotoDreamService : DreamService() {
    
    private lateinit var controller: SlideshowController
    
    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        
        // Dream settings
        isFullscreen = true
        isInteractive = true
        isScreenBright = true

        // Hide the system bars (status + navigation). isFullscreen alone doesn't hide
        // the navigation bar on many devices (e.g. NSPanel Pro / Android 8.1), so apply
        // the same immersive flags the SlideshowActivity uses.
        @Suppress("DEPRECATION")
        window?.decorView?.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )

        // Create container and controller
        val container = FrameLayout(this)
        setContentView(container)
        
        controller = SlideshowController(this, container) { finish() }
        controller.start()
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::controller.isInitialized && controller.onTouchEvent(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    
    override fun onDetachedFromWindow() {
        if (::controller.isInitialized) {
            controller.stop()
        }
        super.onDetachedFromWindow()
    }
}
