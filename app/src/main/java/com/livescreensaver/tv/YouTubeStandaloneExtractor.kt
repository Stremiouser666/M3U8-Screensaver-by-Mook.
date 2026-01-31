package com.livescreensaver.tv

import android.content.Context
import android.util.Log
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.VideoStream
import java.util.concurrent.TimeUnit

data class ExtractionResult(
    val success: Boolean,
    val streamUrl: String?,
    val quality: String?,
    val errorMessage: String? = null
)

class YouTubeStandaloneExtractor(
    private val context: Context,
    private val httpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "YouTubeStandaloneExtractor"
    }

    /**
     * Extract YouTube stream URL.
     * @param url YouTube video URL
     * @param progressiveMode true for 360p progressive, false for video-only 480p+
     */
    fun extractStream(url: String, progressiveMode: Boolean): ExtractionResult {
        return try {
            // Initialize NewPipe
            org.schabi.newpipe.extractor.NewPipe.init(DownloaderImpl())
            val info = StreamInfo.getInfo(url)

            // Filter streams based on progressiveMode
            val streams: List<VideoStream> = if (progressiveMode) {
                info.streams.filterIsInstance<VideoStream>()
                    .filter { it.isProgressive && it.videoHeight == 360 }
            } else {
                info.streams.filterIsInstance<VideoStream>()
                    .filter { !it.isAudioOnly && it.videoHeight >= 480 }
            }

            if (streams.isEmpty()) {
                Log.w(TAG, "No suitable stream found for progressiveMode=$progressiveMode")
                return ExtractionResult(false, null, null, "No suitable stream")
            }

            // Select the highest bitrate stream
            val selected = streams.maxByOrNull { it.bitrate }!!

            ExtractionResult(
                success = true,
                streamUrl = selected.url,
                quality = "${selected.videoHeight}p"
            )
        } catch (e: Exception) {
            Log.e(TAG, "Standalone extraction failed", e)
            ExtractionResult(false, null, null, e.message)
        }
    }
}