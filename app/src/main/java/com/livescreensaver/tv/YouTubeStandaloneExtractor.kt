package com.livescreensaver.tv

import android.content.Context import android.util.Log import kotlinx.coroutines.Dispatchers import kotlinx.coroutines.withContext import okhttp3.MediaType.Companion.toMediaTypeOrNull import okhttp3.OkHttpClient import okhttp3.Request import okhttp3.RequestBody.Companion.toRequestBody import org.json.JSONObject import java.io.File import java.text.SimpleDateFormat import java.util.* import java.util.concurrent.TimeUnit import java.util.regex.Pattern

class YouTubeStandaloneExtractor( private val context: Context, httpClient: OkHttpClient? = null ) {

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

private fun getQualityMode(): String {
    val prefs = context.getSharedPreferences("com.livescreensaver.tv_preferences", Context.MODE_PRIVATE)
    return prefs.getString("youtube_quality_mode", MODE_360_PROGRESSIVE) ?: MODE_360_PROGRESSIVE
}

private val httpClient = httpClient ?: OkHttpClient.Builder()
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(15, TimeUnit.SECONDS)
    .build()

private val signatureDecryptor = YouTubeSignatureDecryptor(context, httpClient)

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

        // Try TV/Android SDK-less client FIRST
        val tvResult = tryInnerTubeExtraction(videoId, clientName = "TVHTML5_SIMPLY_EMBEDDED_PLAYER", clientVersion = "2.0", apiKey = TV_API_KEY, androidSdkVersion = null, embedUrl = "https://www.youtube.com/watch?v=$videoId")
        if (tvResult != null) return@withContext tvResult

        // Android client fallback
        val androidResult = tryInnerTubeExtraction(videoId, clientName = "ANDROID", clientVersion = "20.10.38", apiKey = ANDROID_API_KEY, androidSdkVersion = 11, embedUrl = null)
        if (androidResult != null) return@withContext androidResult

        // Web client fallback
        val webResult = tryInnerTubeExtraction(videoId, clientName = "WEB", clientVersion = "2.20240304.00.00", apiKey = WEB_API_KEY, androidSdkVersion = null, embedUrl = null)
        if (webResult != null) return@withContext webResult

        debugLog("❌ All extraction methods failed")
        ExtractionResult(success = false, errorMessage = "All InnerTube methods failed. Video may be age-restricted or geo-blocked.")

    } catch (e: Exception) {
        debugLog("❌ Exception: ${e.message}")
        debugLog("Stack trace: ${e.stackTraceToString()}")
        ExtractionResult(success = false, errorMessage = "Exception: ${e.message}")
    }
}

private suspend fun tryInnerTubeExtraction(videoId: String, clientName: String, clientVersion: String, apiKey: String, androidSdkVersion: Int?, embedUrl: String? = null): ExtractionResult? = withContext(Dispatchers.IO) {
    try {
        val contextJson = JSONObject().apply {
            put("client", JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", "US")
                if (androidSdkVersion != null) {
                    put("androidSdkVersion", androidSdkVersion)
                    put("osName", "Android")
                    put("osVersion", "11")
                }
                if (clientName.contains("TV")) put("clientScreen", "EMBED")
            })
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
                    put("signatureTimestamp", 0)
                })
            })
        }

        val request = Request.Builder()
            .url("$PLAYER_ENDPOINT?key=$apiKey")
            .post(requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
            .addHeader("Content-Type", "application/json")
            .addHeader("User-Agent", when {
                clientName == "ANDROID" -> "com.google.android.youtube/$clientVersion (Linux; U; Android 11) gzip"
                clientName.contains("TV") -> "Mozilla/5.0 (ChromiumStylePlatform) Cobalt/Version,gzip(gfe)"
                else -> "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36"
            })
            .addHeader("X-YouTube-Client-Name", if (clientName == "ANDROID") "3" else if (clientName.contains("TV")) "85" else "1")
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

        // Check playability
        val status = jsonObject.optJSONObject("playabilityStatus")?.optString("status", "UNKNOWN")
        if (status != "OK") return@withContext null

        // Parse streaming data
        parseStreamingData(jsonObject)

    } catch (e: Exception) {
        debugLog("❌ $clientName exception: ${e.message}")
        null
    }
}

private fun parseStreamingData(jsonObject: JSONObject): ExtractionResult? {
    try {
        val streamingData = jsonObject.optJSONObject("streamingData") ?: return null
        val qualityMode = getQualityMode()

        // Progressive formats first (360p with audio or fallback)
        val progressiveUrl = tryProgressiveFormat(streamingData)
        if (progressiveUrl != null) return ExtractionResult(true, streamUrl = progressiveUrl.first, quality = progressiveUrl.second, hasAudio = true)

        // Video-only DASH for 480p+
        val dashResult = buildCustomDashManifest(streamingData)
        if (dashResult != null) return ExtractionResult(true, streamUrl = dashResult.first, quality = dashResult.second, hasAudio = false, isDashManifest = true)

        // HLS fallback
        val hlsUrl = streamingData.optString("hlsManifestUrl", "")
        if (hlsUrl.isNotEmpty()) return ExtractionResult(true, streamUrl = hlsUrl, quality = "HLS manifest", hasAudio = true, isLive = true)

        return null
    } catch (e: Exception) {
        debugLog("❌ Error parsing streamingData: ${e.message}")
        return null
    }
}

private fun extractVideoId(url: String): String? {
    val patterns = listOf(
        "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|m\\.youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})",
        "v=([a-zA-Z0-9_-]{11})"
    )
    for (patternStr in patterns) {
        val matcher = Pattern.compile(patternStr).matcher(url)
        if (matcher.find()) return matcher.group(1)
    }
    return null
}

fun isYouTubeUrl(url: String) = url.contains("youtube.com", true) || url.contains("youtu.be", true)

private fun debugLog(message: String) {
    Log.d(TAG, message)
    try {
        val file = File(context.getExternalFilesDir(null), "youtube_extraction_log.txt")
        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        file.appendText("[$timestamp] $message\n")
    } catch (_: Exception) {}
}

private fun buildCustomDashManifest(streamingData: JSONObject): Pair<String, String>? {
    val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats") ?: return null
    val qualityMode = getQualityMode()
    val targetHeight = when (qualityMode) {
        MODE_480_VIDEO_ONLY -> 480
        MODE_720_VIDEO_ONLY -> 720
        MODE_1080_VIDEO_ONLY -> 1080
        MODE_1440_VIDEO_ONLY -> 1440
        MODE_2160_VIDEO_ONLY -> 2160
        else -> 720
    }
    for (i in 0 until adaptiveFormats.length()) {
        val format = adaptiveFormats.getJSONObject(i)
        var url = format.optString("url", "")
        if (url.isEmpty() && format.has("signatureCipher")) {
            val cipher = format.getString("signatureCipher")
            url = kotlinx.coroutines.runBlocking { signatureDecryptor.decryptSignature(cipher, extractVideoId(streamingData.toString()) ?: "") ?: "" }
            if (url.isEmpty()) continue
        }
        if (!format.optString("mimeType", "").startsWith("video/")) continue
        if (format.optInt("height", 0) != targetHeight) continue
        return Pair("VIDEO_ONLY|||$url", "${targetHeight}p video-only")
    }
    return null
}

private fun tryProgressiveFormat(streamingData: JSONObject): Pair<String, String>? {
    val formats = streamingData.optJSONArray("formats") ?: return null
    for (i in 0 until formats.length()) {
        val format = formats.getJSONObject(i)
        val mimeType = format.optString("mimeType", "")
        if (!mimeType.startsWith("video/")) continue
        val codecs = extractCodecs(mimeType)
        if (!codecs.contains("avc1")) continue
        val url = format.optString("url", "")
        if (url.isNotEmpty()) return Pair(url, format.optInt("height", 360).toString() + "p progressive")
    }
    return null
}

private fun extractCodecs(mimeType: String): String {
    val match = Regex("codecs=\\\"(.*?)\\\"").find(mimeType)
    return match?.groups?.get(1)?.value ?: ""
}

}