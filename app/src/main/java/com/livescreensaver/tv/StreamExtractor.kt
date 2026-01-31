package com.livescreensaver.tv

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.math.abs

class StreamExtractor(
    private val context: Context,
    private val cachePrefs: SharedPreferences
) {

    companion object {
        private const val TAG = "StreamExtractor"
        private const val CACHE_DIR = "stream_cache"
        private const val KEY_ORIGINAL_URL = "original_url"
        private const val KEY_EXTRACTED_URL = "extracted_url"
        private const val KEY_URL_TYPE = "url_type"
        private const val KEY_QUALITY_MODE = "quality_mode"
    }

    private val cacheDir = File(context.cacheDir, CACHE_DIR).apply { mkdirs() }

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val standaloneExtractor = YouTubeStandaloneExtractor(context, httpClient)

    fun needsExtraction(url: String): Boolean =
        !url.contains(".m3u8") &&
            (url.contains("youtube.com") || url.contains("youtu.be") || url.contains("rutube.ru"))

    private fun isNetworkAvailable(): Boolean {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val net = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(net) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun getQualityMode(): String {
        val prefs = context.getSharedPreferences(
            "com.livescreensaver.tv_preferences",
            Context.MODE_PRIVATE
        )
        return prefs.getString("youtube_quality_mode", "360_progressive") ?: "360_progressive"
    }

    private fun getTargetHeight(): Int = when (getQualityMode()) {
        "360_progressive" -> 360
        "480_video_only" -> 480
        "720_video_only" -> 720
        "1080_video_only" -> 1080
        "1440_video_only" -> 1440
        "2160_video_only" -> 2160
        else -> 720
    }

    private fun isProgressive(): Boolean =
        getQualityMode() == "360_progressive"

    suspend fun extractStreamUrl(
        sourceUrl: String,
        forceRefresh: Boolean,
        cacheExpirationSeconds: Long
    ): String? = withContext(Dispatchers.IO) {

        FileLogger.log("🎬 Extracting: $sourceUrl", TAG)

        if (!isNetworkAvailable()) {
            return@withContext cachePrefs.getString(KEY_EXTRACTED_URL, null)
        }

        // --- Standalone extractor (preferred)
        try {
            val result = standaloneExtractor.extractStream(
                sourceUrl,
                isProgressive()
            )
            if (result.success && result.streamUrl != null) {
                saveToCache(sourceUrl, result.streamUrl, "youtube")
                return@withContext result.streamUrl
            }
        } catch (e: Exception) {
            FileLogger.logError("Standalone extractor failed", e, TAG)
        }

        // --- NewPipe fallback (fixed logic)
        try {
            NewPipe.init(DownloaderImpl())
            val info = StreamInfo.getInfo(sourceUrl)
            val targetHeight = getTargetHeight()

            val selected = if (isProgressive()) {
                // ✅ Progressive ONLY for 360p
                info.streams
                    .filter { it.isProgressive && it.videoHeight > 0 }
                    .minWithOrNull(
                        compareBy(
                            { abs(it.videoHeight - targetHeight) },
                            { -it.bitrate }
                        )
                    )
            } else {
                // ✅ Video-only for 480p+
                info.videoOnlyStreams
                    .filter { it.videoHeight > 0 && it.videoHeight <= targetHeight }
                    .maxByOrNull { it.bitrate }
            }

            if (selected != null) {
                FileLogger.log(
                    "✅ Selected ${selected.videoHeight}p " +
                        "progressive=${selected.isProgressive}",
                    TAG
                )
                saveToCache(sourceUrl, selected.url, "youtube")
                return@withContext selected.url
            }

            // --- Final fallback
            FileLogger.log("⚠️ Falling back to HLS", TAG)
            info.hlsUrl?.let {
                saveToCache(sourceUrl, it, "youtube")
                return@withContext it
            }

        } catch (e: Exception) {
            FileLogger.logError("NewPipe extraction failed", e, TAG)
        }

        null
    }

    private fun saveToCache(originalUrl: String, extractedUrl: String, type: String) {
        cachePrefs.edit()
            .putString(KEY_ORIGINAL_URL, originalUrl)
            .putString(KEY_EXTRACTED_URL, extractedUrl)
            .putString(KEY_URL_TYPE, type)
            .putString(KEY_QUALITY_MODE, getQualityMode())
            .apply()

        FileLogger.log("💾 Cached (${getQualityMode()}): $extractedUrl", TAG)
    }

    fun getCachedUrl(): String? {
        val cachedQuality = cachePrefs.getString(KEY_QUALITY_MODE, null)
        return if (cachedQuality == getQualityMode())
            cachePrefs.getString(KEY_EXTRACTED_URL, null)
        else null
    }
}