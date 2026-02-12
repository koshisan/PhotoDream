package de.koshi.photodream.dream

import android.service.dreams.DreamService
import android.view.MotionEvent
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
