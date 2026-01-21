package com.livescreensaver.tv

import android.content.Context
import android.view.Surface
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MergingMediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import androidx.media3.datasource.DefaultHttpDataSource

class PlayerManager(
    private val context: Context,
    private val eventListener: PlayerEventListener
) {
    private var exoPlayer: ExoPlayer? = null
    
    interface PlayerEventListener {
        fun onPlaybackStateChanged(state: Int)
        fun onPlayerError(error: Exception)
    }

    fun initialize(surface: Surface) {
        release()
        
        exoPlayer = ExoPlayer.Builder(context).build().apply {
            setVideoSurface(surface)
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
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
     */
    fun playStream(url: String) {
        val player = exoPlayer ?: return
        
        try {
            // Check if this is a dual-stream URL (video|||audio)
            if (url.contains("|||")) {
                val parts = url.split("|||")
                if (parts.size == 2) {
                    val videoUrl = parts[0]
                    val audioUrl = parts[1]
                    
                    FileLogger.log("🎬 Loading dual-stream (video + audio)", "PlayerManager")
                    FileLogger.log("📹 Video: ${videoUrl.take(100)}...", "PlayerManager")
                    FileLogger.log("🔊 Audio: ${audioUrl.take(100)}...", "PlayerManager")
                    
                    playDualStream(videoUrl, audioUrl)
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
     * Play separate video and audio streams merged together
     */
    private fun playDualStream(videoUrl: String, audioUrl: String) {
        val player = exoPlayer ?: return
        
        try {
            val dataSourceFactory = DefaultHttpDataSource.Factory()
            
            // Create video source
            val videoItem = MediaItem.fromUri(videoUrl)
            val videoSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(videoItem)
            
            // Create audio source
            val audioItem = MediaItem.fromUri(audioUrl)
            val audioSource = ProgressiveMediaSource.Factory(dataSourceFactory)
                .createMediaSource(audioItem)
            
            // Merge them
            val mergedSource = MergingMediaSource(videoSource, audioSource)
            
            FileLogger.log("✅ Created merged media source", "PlayerManager")
            
            player.setMediaSource(mergedSource)
            player.prepare()
            player.play()
            
        } catch (e: Exception) {
            FileLogger.log("❌ Error merging streams: ${e.message}", "PlayerManager")
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
        exoPlayer?.release()
        exoPlayer = null
    }
    
    fun setVolume(volume: Float) {
        exoPlayer?.volume = volume
    }
    
    fun getPlayer(): ExoPlayer? = exoPlayer
}