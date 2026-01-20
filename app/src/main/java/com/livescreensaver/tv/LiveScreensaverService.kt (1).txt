package com.livescreensaver.tv

import android.content.ComponentCallbacks2
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.StrictMode
import android.service.dreams.DreamService
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.widget.FrameLayout
import android.widget.TextView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import androidx.preference.PreferenceManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request as NPRequest
import org.schabi.newpipe.extractor.downloader.Response as NPResponse
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

class LiveScreensaverService : DreamService(), SurfaceHolder.Callback {

    companion object {
        private const val TAG = "LiveScreensaverService"
        private const val PREF_VIDEO_URL = "video_url"
        const val DEFAULT_VIDEO_URL = "https://test-streams.mux.dev/x36xhzz/x36xhzz.m3u8"

        private const val CACHE_DURATION = 300L
        private const val MAX_RETRIES = 3
        private const val STALL_TIMEOUT_MS = 10_000L
        private const val STALL_CHECK_INTERVAL_MS = 1_000L
        
        private const val PREFS_NAME = "stream_cache_prefs"
        private const val RESUME_CACHE_PREFS = "resume_cache"
        private const val STATS_PREFS = "usage_stats"
        private const val KEY_ORIGINAL_URL = "original_url"
        private const val KEY_EXTRACTED_URL = "extracted_url"
        private const val KEY_URL_TYPE = "url_type"
        private const val KEY_RESUME_POSITION = "resume_position"
        private const val KEY_RESUME_URL = "resume_url"
        private const val KEY_RESUME_TIMESTAMP = "resume_timestamp"
        private const val RESUME_TIMEOUT_MS = 5 * 60 * 1000
        private const val KEY_USAGE_PREFIX = "usage_"
        
        private val secureRandom = java.security.SecureRandom()
    }
    
    private data class PreferenceCache(
        val audioEnabled: Boolean,
        val audioVolume: Int,
        val videoScalingMode: String,
        val speedEnabled: Boolean,
        val playbackSpeed: Float,
        val introEnabled: Boolean,
        val introDuration: Long,
        val skipBeginningEnabled: Boolean,
        val skipBeginningDuration: Long,
        val randomSeekEnabled: Boolean,
        val scheduleEnabled: Boolean,
        val scheduleRandomMode: Boolean,
        val clockEnabled: Boolean,
        val clockPosition: String,
        val clockSize: Int,
        val timeFormat: String,
        val pixelShiftInterval: Long,
        val statsEnabled: Boolean,
        val statsPosition: String,
        val statsInterval: Long,
        val resumeEnabled: Boolean,
        val preferredResolution: String
    )
    
    private var prefCache: PreferenceCache? = null
    private var player: ExoPlayer? = null
    private var surfaceView: SurfaceView? = null
    private var containerLayout: FrameLayout? = null
    private var clockTextView: TextView? = null
    private var statsTextView: TextView? = null
    private var loadingOverlay: LoadingAnimationOverlay? = null
    private var loadingTextView: TextView? = null
    
    private lateinit var streamExtractor: StreamExtractor
    private lateinit var preferences: SharedPreferences
    private lateinit var cachePrefs: SharedPreferences
    private lateinit var resumeCache: SharedPreferences
    private lateinit var statsPrefs: SharedPreferences
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var retryCount = 0
    private var currentSourceUrl: String? = null
    private var stallDetectionTime: Long = 0
    private val handler = Handler(Looper.getMainLooper())
    private var surfaceReady = false
    private var isRetrying = false
    private var hasProcessedPlayback = false
    
    private var clockShiftX = 0f
    private var clockShiftY = 0f
    private var lastClockMinute = -1
    private val statsBuilder = StringBuilder(256)

    private val stallCheckRunnable = object : Runnable {
        override fun run() {
            checkForStall()
            handler.postDelayed(this, STALL_CHECK_INTERVAL_MS)
        }
    }
    
    private val clockUpdateRunnable = object : Runnable {
        override fun run() {
            updateClock()
            val now = Calendar.getInstance()
            val secondsUntilNextMinute = 60 - now.get(Calendar.SECOND)
            val msUntilNextMinute = secondsUntilNextMinute * 1000L
            handler.postDelayed(this, msUntilNextMinute)
        }
    }
    
    private val statsUpdateRunnable = object : Runnable {
        override fun run() {
            updateStats()
            trackPlaybackUsage()
            val interval = prefCache?.statsInterval ?: 1000
            handler.postDelayed(this, interval)
        }
    }
    
    private val pixelShiftRunnable = object : Runnable {
        override fun run() {
            shiftClock()
            val interval = prefCache?.pixelShiftInterval ?: 300000
            if (interval > 0) {
                handler.postDelayed(this, interval)
            }
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()

        try {
            if (BuildConfig.DEBUG) {
                StrictMode.setThreadPolicy(
                    StrictMode.ThreadPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .penaltyFlashScreen()
                        .build()
                )
                StrictMode.setVmPolicy(
                    StrictMode.VmPolicy.Builder()
                        .detectAll()
                        .penaltyLog()
                        .build()
                )
            }

            isInteractive = false
            isFullscreen = true
            isScreenBright = true

            preferences = PreferenceManager.getDefaultSharedPreferences(this)
            cachePrefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
            resumeCache = getSharedPreferences(RESUME_CACHE_PREFS, MODE_PRIVATE)
            statsPrefs = getSharedPreferences(STATS_PREFS, MODE_PRIVATE)
            
            loadPreferenceCache()
            streamExtractor = StreamExtractor(this, cachePrefs)
            refreshUrlIfNeeded()
            setupSurface()
            
            Log.d(TAG, "✅ Service initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Fatal startup error - falling back to safe state", e)
            try {
                preferences.edit().putString(PREF_VIDEO_URL, DEFAULT_VIDEO_URL).apply()
                setupSurface()
                initializePlayer()
            } catch (fallbackError: Exception) {
                Log.e(TAG, "Fallback initialization also failed", fallbackError)
            }
        }
    }
    
    private fun loadPreferenceCache() {
        prefCache = PreferenceCache(
            audioEnabled = preferences.getBoolean("audio_enabled", false),
            audioVolume = preferences.getString("audio_volume", "50")?.toIntOrNull() ?: 50,
            videoScalingMode = preferences.getString("video_scaling_mode", "scale_to_fit") ?: "scale_to_fit",
            speedEnabled = preferences.getBoolean("speed_enabled", false),
            playbackSpeed = preferences.getString("playback_speed", "1.0")?.toFloatOrNull() ?: 1.0f,
            introEnabled = preferences.getBoolean("intro_enabled", true),
            introDuration = (preferences.getString("intro_duration", "7")?.toIntOrNull() ?: 7) * 1000L,
            skipBeginningEnabled = preferences.getBoolean("skip_beginning_enabled", false),
            skipBeginningDuration = (preferences.getString("skip_beginning_duration", "0")?.toIntOrNull() ?: 0) * 1000L,
            randomSeekEnabled = preferences.getBoolean("random_seek_enabled", true),
            scheduleEnabled = preferences.getBoolean("schedule_enabled", false),
            scheduleRandomMode = preferences.getBoolean("schedule_random_mode", false),
            clockEnabled = preferences.getBoolean("clock_enabled", false),
            clockPosition = preferences.getString("clock_position", "top_right") ?: "top_right",
            clockSize = preferences.getString("clock_size", "64")?.toIntOrNull() ?: 64,
            timeFormat = preferences.getString("time_format", "12h") ?: "12h",
            pixelShiftInterval = preferences.getString("pixel_shift_interval", "300000")?.toLongOrNull() ?: 300000,
            statsEnabled = preferences.getBoolean("stats_enabled", false),
            statsPosition = preferences.getString("stats_position", "top_left") ?: "top_left",
            statsInterval = preferences.getString("stats_interval", "1000")?.toLongOrNull() ?: 1000,
            resumeEnabled = preferences.getBoolean("resume_enabled", false),
            preferredResolution = preferences.getString("preferred_resolution", "auto") ?: "auto"
        )
        
        Log.d(TAG, "✅ Preferences cached at startup")
    }

    private fun refreshUrlIfNeeded() {
        val originalUrl = cachePrefs.getString(KEY_ORIGINAL_URL, null)
        val extractedUrl = cachePrefs.getString(KEY_EXTRACTED_URL, null)
        val urlType = cachePrefs.getString(KEY_URL_TYPE, null)

        if (originalUrl == null || urlType == null) {
            Log.d(TAG, "No cached URL - will extract on first playback")
            return
        }
        
        val currentMainUrl = preferences.getString(PREF_VIDEO_URL, DEFAULT_VIDEO_URL) ?: DEFAULT_VIDEO_URL
        
        if (currentMainUrl.contains(".m3u8")) {
            Log.d(TAG, "✅ Direct HLS URL - no refresh needed")
            return
        }

        Log.d(TAG, "🔄 Refreshing $urlType URL on startup...")

        serviceScope.launch {
            try {
                when (urlType) {
                    "rutube" -> {
                        val refreshedUrl = streamExtractor.extractRutubeUrl(originalUrl)
                        if (refreshedUrl != null) {
                            saveRefreshedUrl(originalUrl, refreshedUrl, "rutube")
                            preferences.edit().putString(PREF_VIDEO_URL, refreshedUrl).apply()
                            Log.d(TAG, "✅ Rutube URL refreshed")
                        } else {
                            Log.w(TAG, "⚠️ Rutube refresh failed - will try cached URL")
                        }
                    }
                    "youtube" -> {
                        Log.d(TAG, "YouTube URL will be extracted by NewPipe on playback")
                    }
                    else -> {
                        Log.d(TAG, "Unknown URL type: $urlType")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "URL refresh error - will try cached/original URL", e)
            }
        }
    }

    private fun saveRefreshedUrl(originalUrl: String, extractedUrl: String, urlType: String) {
        cachePrefs.edit()
            .putString(KEY_ORIGINAL_URL, originalUrl)
            .putString(KEY_EXTRACTED_URL, extractedUrl)
            .putString(KEY_URL_TYPE, urlType)
            .apply()
    }

    private fun setupSurface() {
        containerLayout = FrameLayout(this)
        
        surfaceView = SurfaceView(this).apply {
            holder.addCallback(this@LiveScreensaverService)
        }
        
        containerLayout?.addView(surfaceView, FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT,
            FrameLayout.LayoutParams.MATCH_PARENT
        ))
        
        if (prefCache?.clockEnabled == true) {
            setupClock()
        }
        
        if (prefCache?.statsEnabled == true) {
            setupStats()
        }
        
        setContentView(containerLayout)
    }

    private fun setupClock() {
        try {
            val cache = prefCache ?: return
            
            clockTextView = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, cache.clockSize.toFloat())
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                setPadding(24, 24, 24, 24)
            }
            
            val gravity = when (cache.clockPosition) {
                "top_left" -> Gravity.TOP or Gravity.START
                "top_right" -> Gravity.TOP or Gravity.END
                "bottom_left" -> Gravity.BOTTOM or Gravity.START
                "bottom_right" -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.END
            }
            
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = gravity
            }
            
            containerLayout?.addView(clockTextView, params)
            handler.post(clockUpdateRunnable)
            
            if (cache.pixelShiftInterval > 0) {
                handler.postDelayed(pixelShiftRunnable, cache.pixelShiftInterval)
            }
            
            Log.d(TAG, "✅ Clock initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Clock initialization failed - disabling overlay", e)
            clockTextView = null
        }
    }
    
    private fun setupStats() {
        try {
            val cache = prefCache ?: return
            
            statsTextView = TextView(this).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setShadowLayer(8f, 0f, 0f, Color.BLACK)
                setPadding(16, 16, 16, 16)
                typeface = android.graphics.Typeface.MONOSPACE
                setBackgroundColor(Color.argb(128, 0, 0, 0))
            }
            
            val gravity = when (cache.statsPosition) {
                "top_left" -> Gravity.TOP or Gravity.START
                "top_right" -> Gravity.TOP or Gravity.END
                "bottom_left" -> Gravity.BOTTOM or Gravity.START
                "bottom_right" -> Gravity.BOTTOM or Gravity.END
                else -> Gravity.TOP or Gravity.START
            }
            
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                this.gravity = gravity
            }
            
            containerLayout?.addView(statsTextView, params)
            handler.postDelayed(statsUpdateRunnable, cache.statsInterval)
            
            Log.d(TAG, "✅ Stats initialized successfully")
        } catch (e: Exception) {
            Log.e(TAG, "Stats initialization failed - disabling overlay", e)
            statsTextView = null
        }
    }
    
    private fun updateClock() {
        val cache = prefCache ?: return
        val now = Calendar.getInstance()
        val currentMinute = now.get(Calendar.MINUTE)
        
        if (currentMinute != lastClockMinute) {
            val pattern = if (cache.timeFormat == "12h") "h:mm a" else "HH:mm"
            val sdf = SimpleDateFormat(pattern, Locale.getDefault())
            clockTextView?.text = sdf.format(now.time)
            lastClockMinute = currentMinute
        }
    }
    
    private fun updateStats() {
        player?.let { p ->
            statsBuilder.clear()
            statsBuilder.setLength(0)
            
            statsBuilder.append(getUsageStats()).append("\n\n")
            
            p.videoFormat?.let { format ->
                statsBuilder.append("Resolution: ")
                    .append(format.width)
                    .append('x')
                    .append(format.height)
                    .append('\n')
                
                statsBuilder.append("Bitrate: ")
                    .append(format.bitrate / 1000)
                    .append(" kbps\n")
                
                statsBuilder.append("FPS: ")
                    .append(String.format("%.2f", format.frameRate))
                    .append('\n')
            }
            
            statsBuilder.append("Buffer: ")
                .append(p.bufferedPercentage)
                .append("%\n")
            
            val position = p.currentPosition / 1000
            val duration = if (p.duration != C.TIME_UNSET) p.duration / 1000 else 0
            val posMin = position / 60
            val posSec = position % 60
            val durMin = duration / 60
            val durSec = duration % 60
            
            statsBuilder.append("Position: ")
            if (duration > 0) {
                statsBuilder.append(posMin)
                    .append(':')
                    .append(String.format("%02d", posSec))
                    .append(" / ")
                    .append(durMin)
                    .append(':')
                    .append(String.format("%02d", durSec))
                    .append('\n')
            } else {
                statsBuilder.append(posMin)
                    .append(':')
                    .append(String.format("%02d", posSec))
                    .append(" / LIVE\n")
            }
            
            statsBuilder.append("State: ")
            val state = when (p.playbackState) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            statsBuilder.append(state)
            
            statsTextView?.text = statsBuilder.toString()
        }
    }
    
    private fun shiftClock() {
        clockTextView?.let { clock ->
            val shiftX = (secureRandom.nextInt(61) - 30).toFloat()
            val shiftY = (secureRandom.nextInt(61) - 30).toFloat()
            
            clockShiftX += shiftX
            clockShiftY += shiftY
            
            clockShiftX = clockShiftX.coerceIn(-60f, 60f)
            clockShiftY = clockShiftY.coerceIn(-60f, 60f)
            
            clock.animate()
                .translationX(clockShiftX)
                .translationY(clockShiftY)
                .setDuration(500)
                .start()
        }
    }
    
    private fun getTodayDateKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Calendar.getInstance().time)
    }
    
    private fun trackPlaybackUsage() {
        try {
            val dateKey = KEY_USAGE_PREFIX + getTodayDateKey()
            val currentMinutes = statsPrefs.getLong(dateKey, 0)
            statsPrefs.edit().putLong(dateKey, currentMinutes + 1).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to track usage", e)
        }
    }
    
    private fun getUsageStats(): String {
        try {
            val calendar = Calendar.getInstance()
            val sdf = SimpleDateFormat("M/d", Locale.getDefault())
            val stats = StringBuilder()
            var totalMinutes = 0L
            
            for (i in 0..6) {
                val date = calendar.time
                val dateStr = sdf.format(date)
                val dateKey = KEY_USAGE_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
                val minutes = statsPrefs.getLong(dateKey, 0)
                
                totalMinutes += minutes
                val hours = minutes / 60
                val mins = minutes % 60
                
                stats.append(dateStr).append(": ")
                if (hours > 0) stats.append(hours).append("h ")
                stats.append(mins).append("m\n")
                
                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }
            
            stats.insert(0, "--- 7 DAY USAGE ---\n")
            stats.append("--- TOTAL: ")
            val totalHours = totalMinutes / 60
            val totalMins = totalMinutes % 60
            if (totalHours > 0) stats.append(totalHours).append("h ")
            stats.append(totalMins).append("m ---")
            
            return stats.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage stats", e)
            return "Usage stats unavailable"
        }
    }

    override fun surfaceCreated(holder: SurfaceHolder) {
        surfaceReady = true
        initializePlayer()
    }

    override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {}

    override fun surfaceDestroyed(holder: SurfaceHolder) {
        surfaceReady = false
        releasePlayer()
    }

    private fun initializePlayer() {
        if (player != null || !surfaceReady) return

        try {
            showLoadingAnimation()
            
            val videoUrl = getVideoUrl()
            val cache = prefCache ?: return

            val trackSelector = DefaultTrackSelector(this)
            val preferredResolution = cache.preferredResolution

            if (preferredResolution != "auto") {
                val height = preferredResolution.toIntOrNull() ?: 1080
                trackSelector.setParameters(
                    trackSelector.buildUponParameters()
                        .setMinVideoSize(0, height)
                        .setMaxVideoSize(Int.MAX_VALUE, height)
                        .setForceHighestSupportedBitrate(true)
                        .setAllowVideoMixedMimeTypeAdaptiveness(false)
                        .setAllowVideoNonSeamlessAdaptiveness(false)
                        .setAllowAudioMixedMimeTypeAdaptiveness(false)
                        .setAllowAudioNonSeamlessAdaptiveness(false)
                )
            }

            player = ExoPlayer.Builder(this)
                .setTrackSelector(trackSelector)
                .build()
                .apply {
                    volume = if (cache.audioEnabled) (cache.audioVolume / 100f) else 0f
                    repeatMode = Player.REPEAT_MODE_ONE
                    setVideoSurfaceView(surfaceView)
                    
                    videoScalingMode = when (cache.videoScalingMode) {
                        "scale_to_fill" -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING
                        "default" -> C.VIDEO_SCALING_MODE_DEFAULT
                        else -> C.VIDEO_SCALING_MODE_SCALE_TO_FIT
                    }
                    
                    if (cache.speedEnabled) {
                        setPlaybackSpeed(cache.playbackSpeed)
                    }

                    addListener(PlayerEventListener())
                }

            loadStream(videoUrl)
            handler.post(stallCheckRunnable)
        } catch (e: Exception) {
            hideLoadingAnimation()
            Log.e(TAG, "Player initialization failed", e)
            handler.postDelayed({ initializePlayer() }, 5000)
        }
    }
    
    private inner class PlayerEventListener : Player.Listener {
        override fun onPlaybackStateChanged(state: Int) {
            val stateName = when (state) {
                Player.STATE_IDLE -> "IDLE"
                Player.STATE_BUFFERING -> "BUFFERING"
                Player.STATE_READY -> "READY"
                Player.STATE_ENDED -> "ENDED"
                else -> "UNKNOWN"
            }
            
            Log.d(TAG, "🎬 Playback state: $stateName")
            
            if (state == Player.STATE_READY) {
                hideLoadingAnimation()
                if (!hasProcessedPlayback) {
                    stallDetectionTime = 0
                    retryCount = 0
                    hasProcessedPlayback = true
                    
                    val cache = prefCache ?: return
                    val duration = player?.duration ?: 0
                    val skipDuration = if (cache.skipBeginningEnabled) cache.skipBeginningDuration else 0L
                    val introDuration = if (cache.introEnabled) cache.introDuration else 0L
                    
                    val resumedPosition = attemptResume(duration, skipDuration)
                    if (resumedPosition != null) {
                        player?.seekTo(resumedPosition)
                        Log.d(TAG, "▶️ Resumed playback from ${resumedPosition / 1000}s")
                        return
                    }
                    
                    if (skipDuration > 0) {
                        player?.seekTo(skipDuration)
                        Log.d(TAG, "Skipped to ${skipDuration / 1000}s")
                    }
                    
                    if (cache.introEnabled && introDuration > 0) {
                        val randomDelay = secureRandom.nextInt(3000).toLong()
                        handler.postDelayed({
                            if (player != null && player!!.playbackState == Player.STATE_READY && cache.randomSeekEnabled) {
                                val randomPos = calculateRandomPosition(duration, skipDuration)
                                player!!.seekTo(randomPos)
                                Log.d(TAG, "🎯 After intro, seeking to ${randomPos / 1000}s")
                            }
                        }, introDuration + randomDelay)
                    } else if (cache.randomSeekEnabled) {
                        val randomDelay = secureRandom.nextInt(3000).toLong()
                        handler.postDelayed({
                            if (player != null && player!!.playbackState == Player.STATE_READY) {
                                val randomPos = calculateRandomPosition(duration, skipDuration)
                                player!!.seekTo(randomPos)
                                Log.d(TAG, "🎯 Seeking to ${randomPos / 1000}s")
                            }
                        }, randomDelay)
                    }
                }
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            hideLoadingAnimation()
            Log.e(TAG, "Playback error", error)
            handlePlaybackFailure()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            if (isPlaying) {
                stallDetectionTime = 0
            } else if (player?.playbackState == Player.STATE_READY && stallDetectionTime == 0L) {
                stallDetectionTime = System.currentTimeMillis()
            }
        }
    }
    
    private fun savePlaybackPosition() {
        try {
            player?.let { p ->
                val currentUrl = currentSourceUrl ?: return
                val position = p.currentPosition
                val timestamp = System.currentTimeMillis()
                
                resumeCache.edit()
                    .putLong(KEY_RESUME_POSITION, position)
                    .putString(KEY_RESUME_URL, currentUrl)
                    .putLong(KEY_RESUME_TIMESTAMP, timestamp)
                    .apply()
                
                Log.d(TAG, "💾 Saved position: ${position / 1000}s")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save playback position", e)
        }
    }
    
    private fun getBaseUrl(url: String): String {
        return url.split("&sign=")[0]
    }
    
    private fun attemptResume(duration: Long, skipOffset: Long): Long? {
        try {
            val cache = prefCache ?: return null
            if (!cache.resumeEnabled) return null
            
            val savedPosition = resumeCache.getLong(KEY_RESUME_POSITION, -1)
            val savedUrl = resumeCache.getString(KEY_RESUME_URL, null)
            val savedTimestamp = resumeCache.getLong(KEY_RESUME_TIMESTAMP, -1)
            
            if (savedPosition < 0 || savedUrl == null || savedTimestamp < 0) return null
            
            val currentUrl = currentSourceUrl ?: return null
            val savedBaseUrl = getBaseUrl(savedUrl)
            val currentBaseUrl = getBaseUrl(currentUrl)
            
            if (savedBaseUrl != currentBaseUrl) return null
            
            val timeSinceStop = System.currentTimeMillis() - savedTimestamp
            if (cache.randomSeekEnabled && timeSinceStop > RESUME_TIMEOUT_MS) return null
            
            resumeCache.edit().clear().apply()
            return savedPosition
        } catch (e: Exception) {
            Log.e(TAG, "Resume attempt failed", e)
            return null
        }
    }
    
    private fun getVideoUrl(): String {
        val cache = prefCache ?: return preferences.getString(PREF_VIDEO_URL, DEFAULT_VIDEO_URL) ?: DEFAULT_VIDEO_URL
        
        if (cache.scheduleEnabled) {
            val days = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
            
            if (cache.scheduleRandomMode) {
                val urls = mutableListOf<String>()
                
                val mainUrl = preferences.getString(PREF_VIDEO_URL, null)
                if (!mainUrl.isNullOrEmpty() && !preferences.getBoolean("disable_video_url", false)) {
                    urls.add(mainUrl)
                }
                
                urls.addAll(days.mapNotNull { day ->
                    val url = preferences.getString("url_$day", null)
                    val isDisabled = preferences.getBoolean("disable_url_$day", false)
                    url?.takeIf { it.isNotEmpty() && !isDisabled }
                })
                
                if (urls.isNotEmpty()) {
                    return urls.random()
                }
            } else {
                val calendar = Calendar.getInstance()
                val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
                
                val dayIndex = when (dayOfWeek) {
                    Calendar.MONDAY -> 0
                    Calendar.TUESDAY -> 1
                    Calendar.WEDNESDAY -> 2
                    Calendar.THURSDAY -> 3
                    Calendar.FRIDAY -> 4
                    Calendar.SATURDAY -> 5
                    Calendar.SUNDAY -> 6
                    else -> 0
                }
                
                val dayUrl = preferences.getString("url_${days[dayIndex]}", null)
                val isDisabled = preferences.getBoolean("disable_url_${days[dayIndex]}", false)
                
                if (!dayUrl.isNullOrEmpty() && !isDisabled) {
                    return dayUrl
                }
            }
        }
        
        val mainUrl = preferences.getString(PREF_VIDEO_URL, DEFAULT_VIDEO_URL) ?: DEFAULT_VIDEO_URL
        val isMainDisabled = preferences.getBoolean("disable_video_url", false)
        
        return if (isMainDisabled) DEFAULT_VIDEO_URL else mainUrl
    }
    
    private fun calculateRandomPosition(duration: Long, skipOffset: Long): Long {
        return if (duration != C.TIME_UNSET && duration > 0) {
            val usableRange = duration - skipOffset
            if (usableRange <= 0) skipOffset else skipOffset + (secureRandom.nextDouble() * usableRange).toLong()
        } else {
            val maxRandomMs = 180 * 60 * 1000L
            skipOffset + (secureRandom.nextDouble() * maxRandomMs).toLong()
        }
    }

    private fun loadStream(sourceUrl: String) {
        currentSourceUrl = sourceUrl
        Log.d(TAG, "🔄 Loading stream: $sourceUrl")

        serviceScope.launch {
            try {
                val streamUrl = if (streamExtractor.needsExtraction(sourceUrl)) {
                    val cachedUrl = cachePrefs.getString(KEY_EXTRACTED_URL, null)
                    if (cachedUrl != null && cachedUrl.isNotEmpty()) {
                        Log.d(TAG, "✅ Trying cached extracted URL first")
                        cachedUrl
                    } else {
                        streamExtractor.extractStreamUrl(sourceUrl, false, CACHE_DURATION)
                            ?: streamExtractor.extractStreamUrl(sourceUrl, true, CACHE_DURATION)
                    }
                } else {
                    sourceUrl
                }

                if (streamUrl != null) {
                    Log.d(TAG, "✅ Stream URL resolved successfully")
                    playStream(streamUrl)
                } else {
                    Log.e(TAG, "Failed to load stream - using default")
                    playStream(DEFAULT_VIDEO_URL)
                }
            } catch (e: Exception) {
                Log.e(TAG, "Stream loading error", e)
                playStream(DEFAULT_VIDEO_URL)
            }
        }
    }

    private fun playStream(streamUrl: String) {
        hideLoadingAnimation()
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player?.apply {
            hasProcessedPlayback = false
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    private fun checkForStall() {
        if (stallDetectionTime > 0 &&
            System.currentTimeMillis() - stallDetectionTime > STALL_TIMEOUT_MS
        ) {
            handlePlaybackFailure()
        }
    }

    private fun handlePlaybackFailure() {
        if (isRetrying || retryCount >= MAX_RETRIES) return

        isRetrying = true
        retryCount++
        
        Log.w(TAG, "⚠️ Playback failed - attempt $retryCount of $MAX_RETRIES")
        
        if (retryCount == 1) {
            Log.w(TAG, "🔄 Attempting URL refresh...")
            
            serviceScope.launch {
                try {
                    val originalUrl = cachePrefs.getString(KEY_ORIGINAL_URL, null)
                    val urlType = cachePrefs.getString(KEY_URL_TYPE, null)
                    
                    if (originalUrl != null && urlType == "rutube") {
                        val refreshedUrl = streamExtractor.extractRutubeUrl(originalUrl)
                        if (refreshedUrl != null) {
                            saveRefreshedUrl(originalUrl, refreshedUrl, "rutube")
                            currentSourceUrl = refreshedUrl
                            Log.d(TAG, "✅ URL refreshed after playback failure")
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "URL refresh on failure error", e)
                }
            }
        }

        handler.postDelayed({
            retryPlayback()
        }, (Math.pow(2.0, (retryCount - 1).toDouble()) * 1000).toLong())
    }

    private fun retryPlayback() {
        stallDetectionTime = 0
        isRetrying = false
        hasProcessedPlayback = false

        player?.stop()
        player?.clearMediaItems()
        currentSourceUrl?.let { loadStream(it) }
    }

    private fun setupLoadingAnimation() {
        try {
            val animationType = when (preferences.getString("loading_animation_type", "spinning_dots")) {
                "pulsing" -> LoadingAnimationOverlay.AnimationType.PULSING
                "progress_bar" -> LoadingAnimationOverlay.AnimationType.PROGRESS_BAR
                "wave" -> LoadingAnimationOverlay.AnimationType.WAVE
                else -> LoadingAnimationOverlay.AnimationType.SPINNING_DOTS
            }
            
            val customText = preferences.getString("loading_animation_text", "Loading") ?: "Loading"
            
            loadingOverlay = LoadingAnimationOverlay(this)
            loadingTextView = loadingOverlay?.createLoadingView(animationType, customText)
            
            val params = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
            ).apply {
                gravity = Gravity.CENTER
            }
            
            containerLayout?.addView(loadingTextView, params)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to setup loading animation", e)
        }
    }

    private fun hideLoadingAnimation() {
        loadingOverlay?.stop()
        containerLayout?.removeView(loadingTextView)
        loadingTextView = null
        loadingOverlay = null
    }

    private fun showLoadingAnimation() {
        handler.post {
            setupLoadingAnimation()
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                Log.w(TAG, "🔶 Moderate memory pressure detected")
                handler.removeCallbacks(statsUpdateRunnable)
                if (prefCache?.statsEnabled == true) {
                    handler.postDelayed(statsUpdateRunnable, 2000)
                }
            }
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> {
                Log.e(TAG, "🔴 Critical memory pressure - reducing features")
                handler.removeCallbacks(pixelShiftRunnable)
                statsTextView?.visibility = View.GONE
                handler.removeCallbacks(statsUpdateRunnable)
            }
        }
    }

    private fun releasePlayer() {
        try {
            hideLoadingAnimation()
            savePlaybackPosition()
            handler.removeCallbacksAndMessages(null)
            player?.release()
            player = null
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing player", e)
        }
    }

    override fun onDreamingStopped() {
        super.onDreamingStopped()
        releasePlayer()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        try {
            serviceScope.cancel()
            releasePlayer()
            prefCache = null
            statsBuilder.clear()
            statsBuilder.setLength(0)
        } catch (e: Exception) {
            Log.e(TAG, "Error in onDetachedFromWindow", e)
        }
    }

    private class LoadingAnimationOverlay(private val context: Context) {
        private var textView: TextView? = null
        private var animationHandler: Handler? = null
        private var currentFrame = 0
        private var isAnimating = false
        
        enum class AnimationType {
            SPINNING_DOTS, PULSING, PROGRESS_BAR, WAVE
        }
        
        fun createLoadingView(animationType: AnimationType = AnimationType.SPINNING_DOTS, customText: String = "Loading"): TextView {
            textView = TextView(context).apply {
                setTextColor(Color.WHITE)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 32f)
                setShadowLayer(12f, 0f, 0f, Color.BLACK)
                textAlignment = TextView.TEXT_ALIGNMENT_CENTER
                gravity = Gravity.CENTER
            }
            
            animationHandler = Handler(Looper.getMainLooper())
            isAnimating = true
            
            when (animationType) {
                AnimationType.SPINNING_DOTS -> startSpinningDotsAnimation(customText)
                AnimationType.PULSING -> startPulsingAnimation(customText)
                AnimationType.PROGRESS_BAR -> startProgressBarAnimation(customText)
                AnimationType.WAVE -> startWaveAnimation(customText)
            }
            
            return textView!!
        }
        
        private fun startSpinningDotsAnimation(customText: String) {
            val spinningFrames = listOf(
                "⠋ $customText", "⠙ $customText", "⠹ $customText", "⠸ $customText",
                "⠼ $customText", "⠴ $customText", "⠦ $customText", "⠧ $customText",
                "⠇ $customText", "⠏ $customText"
            )
            
            val spinRunnable = object : Runnable {
                override fun run() {
                    if (isAnimating && textView != null) {
                        textView!!.text = spinningFrames[currentFrame % spinningFrames.size]
                        currentFrame++
                        animationHandler?.postDelayed(this, 80)
                    }
                }
            }
            animationHandler?.post(spinRunnable)
        }
        
        private fun startPulsingAnimation(customText: String) {
            val pulsingRunnable = object : Runnable {
                override fun run() {
                    if (isAnimating && textView != null) {
                        val dots = when ((currentFrame / 5) % 4) {
                            0 -> "$customText ●"
                            1 -> "$customText ● ●"
                            2 -> "$customText ● ● ●"
                            else -> customText
                        }
                        textView!!.text = dots
                        val alpha = 0.3f + (0.7f * (currentFrame % 20) / 20f)
                        textView!!.alpha = alpha
                        currentFrame++
                        animationHandler?.postDelayed(this, 50)
                    }
                }
            }
            animationHandler?.post(pulsingRunnable)
        }
        
        private fun startProgressBarAnimation(customText: String) {
            val progressRunnable = object : Runnable {
                override fun run() {
                    if (isAnimating && textView != null) {
                        val barLength = 20
                        val progress = currentFrame % (barLength * 2)
                        val position = if (progress < barLength) progress else barLength * 2 - progress
                        
                        val bar = StringBuilder("[")
                        for (i in 0 until barLength) {
                            bar.append(if (i == position || i == position - 1) "█" else "░")
                        }
                        bar.append("] $customText")
                        textView!!.text = bar.toString()
                        currentFrame++
                        animationHandler?.postDelayed(this, 100)
                    }
                }
            }
            animationHandler?.post(progressRunnable)
        }
        
        private fun startWaveAnimation(customText: String) {
            val waveFrames = listOf(
                "▁ $customText", "▃ $customText", "▄ $customText", "▅ $customText",
                "▆ $customText", "▇ $customText", "▆ $customText", "▅ $customText",
                "▄ $customText", "▃ $customText"
            )
            
            val waveRunnable = object : Runnable {
                override fun run() {
                    if (isAnimating && textView != null) {
                        textView!!.text = waveFrames[currentFrame % waveFrames.size]
                        currentFrame++
                        animationHandler?.postDelayed(this, 100)
                    }
                }
            }
            animationHandler?.post(waveRunnable)
        }
        
        fun stop() {
            isAnimating = false
            animationHandler?.removeCallbacksAndMessages(null)
            textView = null
        }
    }

    private class StreamExtractor(
        private val context: Context,
        private val cachePrefs: SharedPreferences
    ) {
        companion object {
            private const val TAG = "StreamExtractor"
            private const val CACHE_DIR = "stream_cache"
        }

        private val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }
        private val httpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(15, TimeUnit.SECONDS)
            .build()

        fun needsExtraction(url: String): Boolean {
            return !url.contains(".m3u8") && 
                   (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("rutube.ru"))
        }

        private fun isNetworkAvailable(): Boolean {
            try {
                val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
                val activeNetwork = connectivityManager.activeNetwork ?: return false
                val caps = connectivityManager.getNetworkCapabilities(activeNetwork) ?: return false
                return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            } catch (e: Exception) {
                Log.e(TAG, "Error checking network availability", e)
                return false
            }
        }

        suspend fun extractStreamUrl(sourceUrl: String, forceRefresh: Boolean, cacheExpirationSeconds: Long): String? = withContext(Dispatchers.IO) {
            try {
                if (!isNetworkAvailable()) {
                    Log.w(TAG, "📵 No network available - using cached URL")
                    return@withContext cachePrefs.getString("extracted_url", null) ?: sourceUrl
                }

                if (sourceUrl.contains("rutube.ru", ignoreCase = true)) {
                    Log.d(TAG, "🎬 Extracting Rutube URL...")
                    val extractedUrl = extractRutubeUrl(sourceUrl)
                    if (extractedUrl != null) {
                        cachePrefs.edit()
                            .putString("original_url", sourceUrl)
                            .putString("extracted_url", extractedUrl)
                            .putString("url_type", "rutube")
                            .apply()
                    }
                    return@withContext extractedUrl
                }
                
                Log.d(TAG, "🎬 Extracting YouTube URL...")
                NewPipe.init(DownloaderImpl())
                val info = StreamInfo.getInfo(sourceUrl)
                val extractedUrl = info.hlsUrl
                
                if (extractedUrl != null) {
                    cachePrefs.edit()
                        .putString("original_url", sourceUrl)
                        .putString("extracted_url", extractedUrl)
                        .putString("url_type", "youtube")
                        .apply()
                }
                
                extractedUrl
            } catch (e: Exception) {
                Log.e(TAG, "Extraction failed: ${e.message}", e)
                null
            }
        }

        suspend fun extractRutubeUrl(rutubeUrl: String): String? = withContext(Dispatchers.IO) {
            try {
                val videoId = extractRutubeVideoId(rutubeUrl) ?: return@withContext null

                val apiUrl = "https://rutube.ru/api/play/options/$videoId/?no_404=true&referer=https%3A%2F%2Frutube.ru"
                val request = Request.Builder()
                    .url(apiUrl)
                    .addHeader("Referer", "https://rutube.ru")
                    .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36")
                    .build()

                val response = httpClient.newCall(request).execute()
                if (!response.isSuccessful) {
                    Log.e(TAG, "Rutube API failed: ${response.code}")
                    return@withContext null
                }

                val json = response.body?.string()
                if (json.isNullOrEmpty()) {
                    Log.e(TAG, "Empty Rutube response")
                    return@withContext null
                }

                val jsonObject = JSONObject(json)
                val videoBalancer = jsonObject.optJSONObject("video_balancer")
                    ?: return@withContext null

                val m3u8Url = videoBalancer.optString("m3u8")
                    .ifEmpty { videoBalancer.optString("default") }

                if (m3u8Url.isNotEmpty()) {
                    Log.d(TAG, "✅ Rutube M3U8 extracted successfully")
                } else {
                    Log.e(TAG, "No M3U8 URL found in Rutube response")
                }

                m3u8Url.takeIf { it.isNotEmpty() }
            } catch (e: Exception) {
                Log.e(TAG, "Rutube extraction error", e)
                null
            }
        }

        private fun extractRutubeVideoId(url: String): String? {
            val regex = "rutube\\.ru/video/([a-f0-9]+)".toRegex(RegexOption.IGNORE_CASE)
            return regex.find(url)?.groupValues?.get(1)
        }
    }

    private class DownloaderImpl : Downloader() {
        private val client = OkHttpClient.Builder()
            .readTimeout(30, TimeUnit.SECONDS)
            .build()

        override fun execute(request: NPRequest): NPResponse {
            val builder = Request.Builder().url(request.url())
            val response = client.newCall(builder.build()).execute()

            if (response.code == 429) {
                throw ReCaptchaException("Captcha required", request.url())
            }

            return NPResponse(
                response.code,
                response.message,
                mutableMapOf(),
                response.body?.string() ?: "",
                request.url()
            )
        }
    }
}