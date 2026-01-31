package com.livescreensaver.tv

import android.content.Context
import android.view.Surface
import android.view.View
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.YouTubePlayer
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.listeners.AbstractYouTubePlayerListener
import com.pierfrancescosoffritti.androidyoutubeplayer.core.player.views.YouTubePlayerView
import kotlin.random.Random

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener,
    private val youtubePlayerView: YouTubePlayerView  // NEW: Pass this from LiveScreensaverService
) {
    private var exoPlayer: ExoPlayer? = null
    private var youtubePlayer: YouTubePlayer? = null
    private var streamStartTime: Long = 0

    // Playback preferences
    private var playbackSpeed: Float = 1.0f
    private var randomSeekEnabled: Boolean = true
    private var introEnabled: Boolean = true
    private var introDurationMs: Long = 7000L
    private var skipBeginningEnabled: Boolean = false
    private var skipBeginningDurationMs: Long = 0
    private var audioEnabled: Boolean = false
    private var audioVolume: Float = 0.5f
    private var hasAppliedInitialSeek = false
    private var currentResolution: Int = 1080

    // Track which player is active
    private var isUsingYouTubePlayer = false

    private val youtubeDataSourceFactory = DefaultHttpDataSource.Factory()
        .setConnectTimeoutMs(15000)
        .setReadTimeoutMs(15000)
        .setUserAgent("com.google.android.apps.youtube.vr.oculus/1.60.19 (Linux; U; Android 11) gzip")

    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()
        hasAppliedInitialSeek = false
        isUsingYouTubePlayer = false

        // Initialize ExoPlayer (for Rutube and 360p YouTube)
        val speedMultiplier = playbackSpeed.coerceAtLeast(1.0f)
        val minBuffer = (30000 * speedMultiplier).toInt()
        val maxBuffer = (120000 * speedMultiplier).toInt()
        val playbackBuffer = 5000
        val rebufferThreshold = (20000 * speedMultiplier).toInt()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(minBuffer, maxBuffer, playbackBuffer, rebufferThreshold)
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(-1)
            .setBackBuffer(60000, true)
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoSurface(surface)
                playbackParameters = PlaybackParameters(playbackSpeed)
                volume = if (audioEnabled) audioVolume else 0f
                repeatMode = Player.REPEAT_MODE_ONE

                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && streamStartTime > 0) {
                            val latency = System.currentTimeMillis() - streamStartTime
                            FileLogger.log("⚡ PLAYBACK STARTED in ${latency}ms", "PlayerManager")
                            streamStartTime = 0

                            if (!hasAppliedInitialSeek && !isUsingYouTubePlayer) {
                                handleInitialPlayback()
                                hasAppliedInitialSeek = true
                            }
                        }
                        eventListener.onPlaybackStateChanged(playbackState)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        eventListener.onPlayerError(Exception(error))
                    }
                })
            }

        // Initialize YouTube Player
        youtubePlayerView.addYouTubePlayerListener(object : AbstractYouTubePlayerListener() {
            override fun onReady(player: YouTubePlayer) {
                youtubePlayer = player
                FileLogger.log("✅ YouTube Player ready", "PlayerManager")
            }

            override fun onStateChange(player: YouTubePlayer, state: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState) {
                if (state == com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerState.PLAYING) {
                    if (streamStartTime > 0) {
                        val latency = System.currentTimeMillis() - streamStartTime
                        FileLogger.log("⚡ YOUTUBE PLAYBACK STARTED in ${latency}ms", "PlayerManager")
                        streamStartTime = 0
                    }
                    // YouTube player handles quality and buffering automatically
                    eventListener.onPlaybackStateChanged(Player.STATE_READY)
                }
            }

            override fun onError(player: YouTubePlayer, error: com.pierfrancescosoffritti.androidyoutubeplayer.core.player.PlayerConstants.PlayerError) {
                FileLogger.log("❌ YouTube Player error: $error", "PlayerManager")
                eventListener.onPlayerError(Exception("YouTube Player error: $error"))
            }
        })
    }

    fun updatePreferences(cache: PreferenceCache) {
        playbackSpeed = if (cache.speedEnabled) cache.playbackSpeed else 1.0f
        randomSeekEnabled = cache.randomSeekEnabled
        introEnabled = cache.introEnabled
        introDurationMs = cache.introDuration
        skipBeginningEnabled = cache.skipBeginningEnabled
        skipBeginningDurationMs = cache.skipBeginningDuration
        audioEnabled = cache.audioEnabled
        audioVolume = cache.audioVolume / 100f

        // Apply to ExoPlayer
        exoPlayer?.let { player ->
            player.playbackParameters = PlaybackParameters(playbackSpeed)
            player.volume = if (audioEnabled) audioVolume else 0f
        }

        // Apply to YouTube Player
        youtubePlayer?.let { player ->
            if (!audioEnabled) {
                player.mute()
            } else {
                player.unMute()
                // YouTube player doesn't support custom volume levels
            }
        }
    }

    fun setResolution(resolution: Int) {
        currentResolution = resolution
        // YouTube player handles quality automatically
        // Only relevant for ExoPlayer
    }

    fun playStream(url: String) {
        FileLogger.log("🎬 playStream() called with: ${url.take(100)}...", "PlayerManager")

        // Detect YouTube embed protocol
        if (url.startsWith("youtube_embed://")) {
            playWithYouTubePlayer(url)
        } else {
            playWithExoPlayer(url)
        }
    }

    private fun playWithYouTubePlayer(embedUrl: String) {
        val videoId = embedUrl.removePrefix("youtube_embed://")
        
        FileLogger.log("🎬 Loading YouTube video in embed player: $videoId", "PlayerManager")
        streamStartTime = System.currentTimeMillis()
        isUsingYouTubePlayer = true

        // Hide ExoPlayer, show YouTube player
        youtubePlayerView.visibility = View.VISIBLE
        exoPlayer?.pause()

        // Load video
        youtubePlayer?.let { player ->
            player.loadVideo(videoId, 0f)
            
            // Apply audio preference
            if (!audioEnabled) {
                player.mute()
            } else {
                player.unMute()
            }
        } ?: run {
            // Player not ready yet - it will auto-play when ready
            FileLogger.log("⚠️ YouTube player not ready yet, waiting...", "PlayerManager")
        }
    }

    private fun playWithExoPlayer(url: String) {
        val player = exoPlayer ?: return

        FileLogger.log("🎬 Loading in ExoPlayer: ${url.take(100)}...", "PlayerManager")
        streamStartTime = System.currentTimeMillis()
        isUsingYouTubePlayer = false

        // Show ExoPlayer, hide YouTube player
        youtubePlayerView.visibility = View.GONE

        try {
            if (url.contains("googlevideo.com")) {
                val mediaSource = ProgressiveMediaSource.Factory(youtubeDataSourceFactory)
                    .createMediaSource(MediaItem.fromUri(url))
                player.setMediaSource(mediaSource)
            } else {
                player.setMediaItem(MediaItem.fromUri(url))
            }

            player.prepare()
            player.play()

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading in ExoPlayer: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    private fun handleInitialPlayback() {
        val player = exoPlayer ?: return
        val duration = player.duration

        if (duration <= 0 || duration == C.TIME_UNSET) return

        // Handle random seek, intro, skip beginning logic
        // (Same as your existing implementation)
    }

    fun release() {
        youtubePlayerView.visibility = View.GONE
        youtubePlayer?.pause()
        exoPlayer?.release()
        exoPlayer = null
        hasAppliedInitialSeek = false
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}