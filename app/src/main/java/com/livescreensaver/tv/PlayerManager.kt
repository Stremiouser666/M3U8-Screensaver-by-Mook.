package com.livescreensaver.tv

import android.content.Context
import android.net.Uri
import android.util.Log
import android.view.SurfaceView
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.trackselection.DefaultTrackSelector
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

class PlayerManager(
    private val context: Context,
    private val surfaceView: SurfaceView?,
    private val onReady: () -> Unit,
    private val onError: () -> Unit,
    private val onPlayingChanged: (Boolean) -> Unit
) {
    companion object {
        private const val TAG = "PlayerManager"
        private val secureRandom = java.security.SecureRandom()
    }

    private var player: ExoPlayer? = null
    var hasProcessedPlayback = false
        private set

    fun initializePlayer(cache: PreferenceCache): ExoPlayer {
        val trackSelector = DefaultTrackSelector(context)
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

        player = ExoPlayer.Builder(context)
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

        return player!!
    }

    fun playStream(streamUrl: String) {
        val mediaItem = MediaItem.fromUri(Uri.parse(streamUrl))
        player?.apply {
            hasProcessedPlayback = false
            setMediaItem(mediaItem)
            prepare()
            play()
        }
    }

    fun processPlayback(
        cache: PreferenceCache,
        duration: Long,
        resumePosition: Long?,
        onSeek: (Long) -> Unit
    ) {
        if (hasProcessedPlayback) return
        hasProcessedPlayback = true

        val skipDuration = if (cache.skipBeginningEnabled) cache.skipBeginningDuration else 0L
        
        if (resumePosition != null) {
            onSeek(resumePosition)
            Log.d(TAG, "▶️ Resumed playback from ${resumePosition / 1000}s")
            return
        }
        
        if (skipDuration > 0) {
            onSeek(skipDuration)
            Log.d(TAG, "Skipped to ${skipDuration / 1000}s")
        }
        
        if (cache.introEnabled && cache.introDuration > 0) {
            val randomDelay = secureRandom.nextInt(3000).toLong()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (player != null && player!!.playbackState == Player.STATE_READY && cache.randomSeekEnabled) {
                    val randomPos = calculateRandomPosition(duration, skipDuration)
                    onSeek(randomPos)
                    Log.d(TAG, "🎯 After intro, seeking to ${randomPos / 1000}s")
                }
            }, cache.introDuration + randomDelay)
        } else if (cache.randomSeekEnabled) {
            val randomDelay = secureRandom.nextInt(3000).toLong()
            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                if (player != null && player!!.playbackState == Player.STATE_READY) {
                    val randomPos = calculateRandomPosition(duration, skipDuration)
                    onSeek(randomPos)
                    Log.d(TAG, "🎯 Seeking to ${randomPos / 1000}s")
                }
            }, randomDelay)
        }
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

    fun stop() {
        player?.stop()
        player?.clearMediaItems()
    }

    fun resetProcessedFlag() {
        hasProcessedPlayback = false
    }

    fun release() {
        player?.release()
        player = null
    }

    fun getPlayer(): ExoPlayer? = player

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
                onReady()
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            Log.e(TAG, "Playback error", error)
            onError()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            onPlayingChanged(isPlaying)
        }
    }
}