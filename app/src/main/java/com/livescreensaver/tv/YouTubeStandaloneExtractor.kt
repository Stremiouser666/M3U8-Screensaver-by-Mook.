package com.livescreensaver.tv

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeStandaloneExtractor(
    private val context: Context,
    httpClient: OkHttpClient? = null
) {

    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val TV_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"

        // Quality mode constants
        const val MODE_360_PROGRESSIVE = "360_progressive"
        const val MODE_480_VIDEO_ONLY = "480_video_only"
        const val MODE_720_VIDEO_ONLY = "720_video_only"
        const val MODE_1080_VIDEO_ONLY = "1080_video_only"
        const val MODE_1440_VIDEO_ONLY = "1440_video_only"
        const val MODE_2160_VIDEO_ONLY = "2160_video_only"
    }

    private val httpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    private val signatureDecryptor = YouTubeSignatureDecryptor(context, httpClient)

    private fun getQualityMode(): String {
        val prefs = context.getSharedPreferences(
            "com.livescreensaver.tv_preferences",
            Context.MODE_PRIVATE
        )
        return prefs.getString("youtube_quality_mode", MODE_360_PROGRESSIVE) ?: MODE_360_PROGRESSIVE
    }

    data class ExtractionResult(
        val success: Boolean,
        val streamUrl: String? = null,
        val videoUrl: String? = null,
        val audioUrl: String? = null,
        val quality: String? = null,
        val hasAudio: Boolean = false,
        val isLive: Boolean = false,
        val isDashManifest: Boolean = false,
        val errorMessage: String? = null
    )

    data class VideoFormat(
        val url: String,
        val width: Int,
        val height: Int,
        val bitrate: Int,
        val mimeType: String,
        val codecs: String,
        val fps: Int = 30
    )

    data class AudioFormat(
        val url: String,
        val bitrate: Int,
        val mimeType: String,
        val codecs: String,
        val sampleRate: Int = 44100
    )

    suspend fun extractStream(youtubeUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            debugLog("==== YouTube Extraction Started ====")
            debugLog("Input URL: $youtubeUrl")

            val videoId = extractVideoId(youtubeUrl)
            if (videoId == null) {
                debugLog("❌ Failed to extract video ID from URL")
                return@withContext ExtractionResult(
                    success = false,
                    errorMessage = "Could not extract video ID from YouTube URL"
                )
            }
            debugLog("✓ Extracted video ID: $videoId")

            // Try TV client first
            debugLog(">>> Attempting TV client extraction")
            tryInnerTubeExtraction(videoId, "TVHTML5_SIMPLY_EMBEDDED_PLAYER", "2.0", TV_API_KEY, null, "https://www.youtube.com/watch?v=$videoId")?.let {
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = it.first,
                    quality = it.second,
                    hasAudio = true,
                    isDashManifest = it.second.contains("video-only")
                )
            }

            // Android client fallback
            debugLog(">>> Attempting Android client extraction")
            tryInnerTubeExtraction(videoId, "ANDROID", "20.10.38", ANDROID_API_KEY, 11)?.let {
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = it.first,
                    quality = it.second,
                    hasAudio = true,
                    isDashManifest = it.second.contains("video-only")
                )
            }

            // Web client fallback
            debugLog(">>> Attempting Web client extraction")
            tryInnerTubeExtraction(videoId, "WEB", "2.20240304.00.00", WEB_API_KEY, null)?.let {
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = it.first,
                    quality = it.second,
                    hasAudio = true,
                    isDashManifest = it.second.contains("video-only")
                )
            }

            debugLog("❌ All extraction methods failed")
            ExtractionResult(
                success = false,
                errorMessage = "All InnerTube methods failed. Video may be age-restricted or geo-blocked."
            )

        } catch (e: Exception) {
            debugLog("❌ Exception: ${e.message}")
            ExtractionResult(
                success = false,
                errorMessage = "Exception: ${e.message}"
            )
        }
    }

    private suspend fun tryInnerTubeExtraction(
        videoId: String,
        clientName: String,
        clientVersion: String,
        apiKey: String,
        androidSdkVersion: Int?,
        embedUrl: String? = null
    ): Pair<String, String>? = withContext(Dispatchers.IO) {
        try {
            val clientContext = JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", "US")
                put("utcOffsetMinutes", 0)
                androidSdkVersion?.let {
                    put("androidSdkVersion", it)
                    put("osName", "Android")
                    put("osVersion", "11")
                }
                if (clientName.contains("TV")) put("clientScreen", "EMBED")
            }

            val contextJson = JSONObject().apply {
                put("client", clientContext)
                if (clientName.contains("TV") && embedUrl != null) {
                    put("thirdParty", JSONObject().apply { put("embedUrl", embedUrl) })
                }
            }

            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", contextJson)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
                put("playbackContext", JSONObject().apply {
                    put("contentPlaybackContext", JSONObject().apply {
                        put("html5Preference", "HTML5_PREF_WANTS")
                        put("signatureTimestamp", 20458)
                    })
                })
            }

            val userAgent = when {
                clientName == "ANDROID" -> "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip"
                clientName.contains("TV") -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version,gzip(gfe)"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            }

            val request = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-YouTube-Client-Name", when {
                    clientName == "ANDROID" -> "3"
                    clientName.contains("TV") -> "85"
                    else -> "1"
                })
                .addHeader("X-YouTube-Client-Version", clientVersion)
                .apply {
                    if (clientName == "WEB" || clientName.contains("TV")) {
                        addHeader("Origin", "https://www.youtube.com")
                        addHeader("Referer", embedUrl ?: "https://www.youtube.com/watch?v=$videoId")
                    }
                }
                .build()

            val response = httpClient.newCall(request).execute()
            if (!response.isSuccessful) return@withContext null
            val json = response.body?.string() ?: return@withContext null
            val jsonObject = JSONObject(json)

            if (jsonObject.optJSONObject("playabilityStatus")?.optString("status") != "OK") return@withContext null

            parseStreamingData(jsonObject, videoId)

        } catch (e: Exception) {
            debugLog("❌ $clientName exception: ${e.message}")
            null
        }
    }

    private fun parseStreamingData(jsonObject: JSONObject, videoId: String): Pair<String, String>? {
        val streamingData = jsonObject.optJSONObject("streamingData") ?: return null
        val qualityMode = getQualityMode()

        // 360p progressive first
        if (qualityMode == MODE_360_PROGRESSIVE) tryProgressiveFormat(streamingData, videoId)?.let { return it }

        // Video-only DASH modes
        buildCustomDashManifest(streamingData, videoId)?.let { return it }

        // HLS fallback
        streamingData.optString("hlsManifestUrl").takeIf { it.isNotEmpty() }?.let {
            return Pair(it, "HLS Live Stream")
        }

        return null
    }

    private fun tryProgressiveFormat(streamingData: JSONObject, videoId: String): Pair<String, String>? {
        val formats = streamingData.optJSONArray("formats") ?: return null
        for (i in 0 until formats.length()) {
            val fmt = formats.getJSONObject(i)
            var url = fmt.optString("url", "")
            if (url.isEmpty() && fmt.has("signatureCipher")) {
                val cipher = fmt.getString("signatureCipher")
                url = kotlinx.coroutines.runBlocking { signatureDecryptor.decryptSignature(cipher, videoId) } ?: ""
            }
            if (url.isEmpty()) continue
            val height = fmt.optInt("height", 0)
            if (height != 360) continue
            return Pair(url, "360p progressive")
        }
        return null
    }

    private fun buildCustomDashManifest(streamingData: JSONObject, videoId: String): Pair<String, String>? {
        val qualityMode = getQualityMode()
        val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null

        val targetHeight = when (qualityMode) {
            MODE_480_VIDEO_ONLY -> 480
            MODE_720_VIDEO_ONLY -> 720
            MODE_1080_VIDEO_ONLY -> 1080
            MODE_1440_VIDEO_ONLY -> 1440
            MODE_2160_VIDEO_ONLY -> 2160
            else -> 720
        }

        var bestVideo: VideoFormat? = null
        for (i in 0 until adaptiveFormats.length()) {
            val fmt = adaptiveFormats.getJSONObject(i)
            var url = fmt.optString("url", "")
            if (url.isEmpty() && fmt.has("signatureCipher")) {
                val cipher = fmt.getString("signatureCipher")
                url = kotlinx.coroutines.runBlocking { signatureDecryptor.decryptSignature(cipher, videoId) } ?: ""
            }
            if (url.isEmpty()) continue
            val height = fmt.optInt("height", 0)
            val width = fmt.optInt("width", 0)
            val mime = fmt.optString("mimeType", "")
            if (!mime.startsWith("video/") || height == 0 || width == 0) continue
            if (height == targetHeight) {
                bestVideo = VideoFormat(url, width, height, fmt.optInt("bitrate", 0), mime, extractCodecs(mime))
                break
            }
        }
        return bestVideo?.let { Pair("VIDEO_ONLY|||${it.url}", "${it.height}p video-only (${it.width}x${it.height})") }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|m\\.youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})",
            "v=([a-zA-Z0-9_-]{11})"
        )
        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr)
            val matcher = pattern.matcher(url)
            if (matcher.find()) return matcher.group(1)
        }
        return null
    }

    fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
                url.contains("youtu.be", ignoreCase = true)
    }

    private fun extractCodecs(mime: String): String {
        val parts = mime.split(";")
        return if (parts.size > 1) parts[1].replace("codecs=", "") else ""
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        FileLogger.log(message, TAG)
        try {
            val file = File(context.getExternalFilesDir(null), "youtube_extraction_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            file.appendText("[$timestamp] $message\n")
        } catch (_: Exception) {}
    }
}