package com.livescreensaver.tv

import android.content.Context
import android.media.MediaPlayer
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener
) {
    private var exoPlayer: ExoPlayer? = null
    private var musicPlayer: MediaPlayer? = null
    private var streamStartTime: Long = 0

    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()

        // BALANCED buffering - build proper buffer before starting
        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                5000,   // min buffer (5s) - build buffer first
                20000,  // max buffer (20s) - longer for stability
                500,    // START PLAYBACK at 500ms
                2000    // rebuffer threshold (2s)
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .setTargetBufferBytes(-1)  // No size limit, focus on time
            .build()

        exoPlayer = ExoPlayer.Builder(context)
            .setLoadControl(loadControl)
            .build()
            .apply {
                setVideoSurface(surface)
                addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY && streamStartTime > 0) {
                            val latency = System.currentTimeMillis() - streamStartTime
                            FileLogger.log("⚡ PLAYBACK STARTED in ${latency}ms", "PlayerManager")
                            streamStartTime = 0
                        }
                        eventListener.onPlaybackStateChanged(playbackState)
                    }

                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        eventListener.onPlayerError(Exception(error))
                    }
                })
            }
    }

    /**
     * Play a stream URL
     * Supports:
     * - Regular URLs (HLS, DASH, MP4)
     * - Dual URLs in format: "video_url|||audio_url" for merging
     * - Video-only URLs in format: "VIDEO_ONLY|||video_url" (triggers music playback)
     */
    fun playStream(url: String) {
        val player = exoPlayer ?: return

        try {
            // Stop any existing music
            stopMusic()

            // Check if this is a video-only URL (triggers background music)
            if (url.startsWith("VIDEO_ONLY|||")) {
                val videoUrl = url.substringAfter("VIDEO_ONLY|||")
                FileLogger.log("🎬 Loading video-only stream with music", "PlayerManager")
                playVideoOnly(videoUrl)
                return
            }

            // Check if this is a dual-stream URL (video|||audio)
            if (url.contains("|||")) {
                val parts = url.split("|||")
                if (parts.size == 2) {
                    val videoUrl = parts[0]
                    val audioUrl = parts[1]

                    FileLogger.log("🎬 Merging video + audio streams", "PlayerManager")
                    playMergedStream(videoUrl, audioUrl)
                    return
                }
            }

            // Single URL - play normally
            FileLogger.log("🎬 Loading single stream: ${url.take(100)}...", "PlayerManager")
            val mediaItem = MediaItem.fromUri(url)
            player.setMediaItem(mediaItem)
            player.prepare()
            player.play()

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading stream: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    /**
     * Play video-only with optional background music
     */
    private fun playVideoOnly(videoUrl: String) {
        val player = exoPlayer ?: return

        try {
            // Load and play video (muted)
            val mediaItem = MediaItem.fromUri(videoUrl)
            player.setMediaItem(mediaItem)
            player.volume = 0f  // Mute video
            player.prepare()
            player.play()

            FileLogger.log("✅ Video-only stream loaded (muted)", "PlayerManager")

            // Try to start background music
            startMusic()

        } catch (e: Exception) {
            FileLogger.log("❌ Error loading video-only stream: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    /**
     * Start background music if available
     */
    private fun startMusic() {
        try {
            val musicResId = context.resources.getIdentifier("ambient_music", "raw", context.packageName)
            
            if (musicResId == 0) {
                FileLogger.log("ℹ️ No background music file found (res/raw/ambient_music.mp3)", "PlayerManager")
                return
            }

            musicPlayer = MediaPlayer.create(context, musicResId)?.apply {
                isLooping = true
                setVolume(0.3f, 0.3f)  // 30% volume for subtle background
                start()
                FileLogger.log("🎵 Background music started", "PlayerManager")
            }

        } catch (e: Exception) {
            FileLogger.log("⚠️ Could not start background music: ${e.message}", "PlayerManager")
        }
    }

    /**
     * Stop background music
     */
    private fun stopMusic() {
        musicPlayer?.apply {
            if (isPlaying) {
                stop()
            }
            release()
        }
        musicPlayer = null
    }

    /**
     * Merge video and audio using MergingMediaSource
     * With balanced buffering, should build 5s buffer before starting
     */
    private fun playMergedStream(videoUrl: String, audioUrl: String) {
        val player = exoPlayer ?: return

        try {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
                .setConnectTimeoutMs(5000)
                .setReadTimeoutMs(5000)

            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(videoUrl))

            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(MediaItem.fromUri(audioUrl))

            val merged = MergingMediaSource(videoSource, audioSource)

            FileLogger.log("✅ Merged source created (buffering ~5s before start)", "PlayerManager")

            player.setMediaSource(merged)
            player.prepare()
            player.play()

        } catch (e: Exception) {
            FileLogger.log("❌ Error merging: ${e.message}", "PlayerManager")
            eventListener.onPlayerError(e)
        }
    }

    fun pause() {
        exoPlayer?.pause()
    }

    fun resume() {
        exoPlayer?.play()
    }

    fun seekTo(positionMs: Long) {
        exoPlayer?.seekTo(positionMs)
    }

    fun getCurrentPosition(): Long {
        return exoPlayer?.currentPosition ?: 0
    }

    fun getDuration(): Long {
        return exoPlayer?.duration ?: 0
    }

    fun release() {
        stopMusic()
        exoPlayer?.release()
        exoPlayer = null
    }

    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }

    fun getPlayer(): ExoPlayer? = exoPlayer
}