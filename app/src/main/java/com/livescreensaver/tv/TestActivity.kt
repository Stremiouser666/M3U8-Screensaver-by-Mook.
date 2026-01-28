package com.livescreensaver.tv

import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.preference.PreferenceManager

/**
 * TestActivity - Allows testing screensaver functionality without waiting for system idle
 * 
 * This activity mimics the behavior of LiveScreensaverService but runs as a normal activity
 * that can be launched on demand.
 */
class TestActivity : AppCompatActivity(), SurfaceHolder.Callback, PlayerManager.PlayerEventListener {

    companion object {
        private const val TAG = "TestActivity"
        private const val DEFAULT_VIDEO_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"
    }

    private lateinit var playerManager: PlayerManager
    private lateinit var uiOverlayManager: UIOverlayManager
    private lateinit var containerLayout: FrameLayout
    private lateinit var surfaceView: SurfaceView
    private lateinit var preferences: SharedPreferences
    private lateinit var preferenceManager: AppPreferenceManager
    private lateinit var scheduleManager: ScheduleManager
    
    private val handler = Handler(Looper.getMainLooper())
    private var prefCache: PreferenceCache? = null
    private var surfaceReady = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Setup fullscreen
        setupFullscreen()
        
        // Initialize preferences
        preferences = PreferenceManager.getDefaultSharedPreferences(this)
        preferenceManager = AppPreferenceManager(this)
        scheduleManager = ScheduleManager(this)
        prefCache = PreferenceCache.from(preferences)
        
        // Create container layout
        containerLayout = FrameLayout(this)
        setContentView(containerLayout)
        
        // Create surface view for video playback
        surfaceView = SurfaceView(this)
        surfaceView.holder.addCallback(this)
        
        containerLayout.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        // Initialize managers
        playerManager = PlayerManager(this, this)
        uiOverlayManager = UIOverlayManager(this, containerLayout, handler)
        
        // Setup overlays
        prefCache?.let { cache ->
            if (cache.clockEnabled) {
                uiOverlayManager.setupClock(cache)
            }
            if (cache.statsEnabled) {
                uiOverlayManager.setupStats(cache)
            }
        }
        
        Log.d(TAG, "Test activity created")
    }

    private fun setupFullscreen() {
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        supportActionBar?.hide()
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

    // SurfaceHolder.Callback
    override fun surfaceCreated(holder: SurfaceHolder) {
        Log.d(TAG, "Surface created")
        surfaceReady = true
        startPlayback(holder.surface)
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
        Log.d(TAG, "Surface changed: ${width}x${height}")
    }

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        Log.d(TAG, "Surface destroyed")
        surfaceReady = false
        playerManager.release()
    }

    private fun startPlayback(surface: android.view.Surface) {
        try {
            playerManager.initialize(surface)
            
            // Get URL from preferences or use default
            val url = scheduleManager.getCurrentUrl() ?: DEFAULT_VIDEO_URL
            
            Log.d(TAG, "Starting playback: $url")
            playerManager.playStream(url)
            
            // Set volume
            prefCache?.let { cache ->
                if (cache.audioEnabled) {
                    val volume = cache.audioVolume / 100f
                    playerManager.setVolume(volume)
                } else {
                    playerManager.setVolume(0f)
                }
            }
            
        } catch (e: Exception) {
            Log.e(TAG, "Error starting playback", e)
            finish()
        }
    }

    // PlayerEventListener
    override fun onPlaybackStateChanged(state: Int) {
        Log.d(TAG, "Playback state: $state")
    }

    override fun onPlayerError(error: Exception) {
        Log.e(TAG, "Player error", error)
        finish()
    }

    override fun onResume() {
        super.onResume()
        hideSystemUI()
        if (surfaceReady) {
            playerManager.resume()
        }
    }

    override fun onPause() {
        super.onPause()
        playerManager.pause()
    }

    override fun onDestroy() {
        super.onDestroy()
        uiOverlayManager.cleanup()
        playerManager.release()
        handler.removeCallbacksAndMessages(null)
        Log.d(TAG, "Test activity destroyed")
    }

    // Exit on any key press
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        Log.d(TAG, "Key pressed, exiting")
        finish()
        return true
    }

    // Exit on any touch
    override fun onTouchEvent(event: MotionEvent?): Boolean {
        if (event?.action == MotionEvent.ACTION_DOWN) {
            Log.d(TAG, "Touch detected, exiting")
            finish()
            return true
        }
        return super.onTouchEvent(event)
    }
}
