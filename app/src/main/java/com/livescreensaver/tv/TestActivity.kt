package com.livescreensaver.tv

import android.os.Bundle
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import androidx.appcompat.app.AppCompatActivity

/**
 * TestActivity - Allows testing screensaver functionality without waiting for system idle
 * 
 * This activity mimics the behavior of LiveScreensaverService but runs as a normal activity
 * that can be launched on demand. It uses the same PlayerManager and rendering logic.
 */
class TestActivity : AppCompatActivity() {

    private lateinit var playerManager: PlayerManager
    private lateinit var uiOverlayManager: UIOverlayManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Make fullscreen and keep screen on
        setupFullscreen()
        
        // Initialize managers
        playerManager = PlayerManager(this)
        uiOverlayManager = UIOverlayManager(this)
        
        // Set content view to player view
        setContentView(playerManager.getPlayerView())
        
        // Add UI overlays on top
        uiOverlayManager.attachToWindow(window)
        
        // Start playback
        playerManager.startPlayback()
    }

    private fun setupFullscreen() {
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Hide action bar
        supportActionBar?.hide()
        
        // Hide system UI (navigation and status bars)
        hideSystemUI()
    }

    private fun hideSystemUI() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_FULLSCREEN
        )
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
    }

    override fun onPause() {
        super.onPause()
        playerManager.pausePlayback()
    }

    override fun onDestroy() {
        super.onDestroy()
        playerManager.cleanup()
        uiOverlayManager.cleanup()
    }

    // Exit on any key press
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        finish()
        return true
    }

    // Exit on any touch
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }
}
