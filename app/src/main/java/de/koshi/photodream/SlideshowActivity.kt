package de.koshi.photodream

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.Bundle
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import de.koshi.photodream.core.SlideshowController

/**
 * Fullscreen slideshow activity - works even without Daydream support.
 * Can be started via intent from other apps or the "Start Now" button.
 * 
 * All slideshow logic is in SlideshowController.
 * This is just a thin wrapper for starting as a regular Activity.
 * 
 * Intent actions:
 * - de.koshi.photodream.START_SLIDESHOW
 * - android.intent.action.MAIN (with category LAUNCHER_ALTERNATE)
 */
class SlideshowActivity : AppCompatActivity() {
    
    companion object {
        const val ACTION_START_SLIDESHOW = "de.koshi.photodream.START_SLIDESHOW"
        const val ACTION_EXIT_SLIDESHOW = "de.koshi.photodream.EXIT_SLIDESHOW"
        
        fun start(context: Context) {
            val intent = Intent(context, SlideshowActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        }
    }
    
    private lateinit var controller: SlideshowController
    
    private val exitReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_EXIT_SLIDESHOW) {
                // Move to background instead of finishing to MainActivity
                moveTaskToBack(true)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Fullscreen immersive mode
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
        )
        
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Create container and controller
        val container = FrameLayout(this)
        setContentView(container)
        
        controller = SlideshowController(this, container) { finish() }
        controller.start()
        
        // Register broadcast receiver for remote exit
        val filter = IntentFilter(ACTION_EXIT_SLIDESHOW)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(exitReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(exitReceiver, filter)
        }
    }
    
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (::controller.isInitialized && controller.onTouchEvent(event)) {
            return true
        }
        return super.dispatchTouchEvent(event)
    }
    
    override fun onDestroy() {
        try {
            unregisterReceiver(exitReceiver)
        } catch (e: Exception) {
            // Receiver might not be registered
        }
        if (::controller.isInitialized) {
            controller.stop()
        }
        super.onDestroy()
    }
}
