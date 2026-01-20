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

class YouTubeStandaloneExtractor(
    private val context: Context,
    httpClient: OkHttpClient? = null
) {
    companion object {
        private const val TAG = "YouTubeExtractor"
        private const val ANDROID_API_KEY = "AIzaSyA8eiZmM1FaDVjRy-df2KTyQ_vz_yYM39w"
        private const val WEB_API_KEY = "AIzaSyAO_FJ2SlqU8Q4STEHLGCilw_Y9_11qcW8"
        private const val PLAYER_ENDPOINT = "https://www.youtube.com/youtubei/v1/player"
    }

    private val httpClient = httpClient ?: OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    data class ExtractionResult(
        val success: Boolean,
        val streamUrl: String? = null,
        val videoUrl: String? = null,
        val audioUrl: String? = null,
        val quality: String? = null,
        val hasAudio: Boolean = false,
        val isLive: Boolean = false,
        val errorMessage: String? = null
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
            
            // Method 1: Android InnerTube
            debugLog(">>> Attempting Method 1: Android InnerTube API")
            val androidResult = tryInnerTubeExtraction(
                videoId,
                clientName = "ANDROID",
                clientVersion = "19.09.37",
                apiKey = ANDROID_API_KEY,
                androidSdkVersion = 33
            )
            
            if (androidResult != null) {
                debugLog("✅ SUCCESS via Android InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = androidResult,
                    quality = "Adaptive (InnerTube)",
                    hasAudio = true
                )
            }
            
            debugLog("⚠️ Android InnerTube failed, trying Web InnerTube...")
            
            // Method 2: Web InnerTube
            val webResult = tryInnerTubeExtraction(
                videoId,
                clientName = "WEB",
                clientVersion = "2.20240304.00.00",
                apiKey = WEB_API_KEY,
                androidSdkVersion = null
            )
            
            if (webResult != null) {
                debugLog("✅ SUCCESS via Web InnerTube!")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = webResult,
                    quality = "Adaptive (InnerTube)",
                    hasAudio = true
                )
            }
            
            debugLog("❌ All extraction methods failed")
            ExtractionResult(
                success = false,
                errorMessage = "All InnerTube methods failed. Video may be age-restricted or geo-blocked."
            )
            
        } catch (e: Exception) {
            debugLog("❌ Exception: ${e.message}")
            Log.e(TAG, "YouTube extraction error", e)
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
        androidSdkVersion: Int?
    ): String? = withContext(Dispatchers.IO) {
        try {
            debugLog("Building $clientName client request...")
            
            val clientContext = JSONObject().apply {
                put("clientName", clientName)
                put("clientVersion", clientVersion)
                put("hl", "en")
                put("gl", "US")
                put("utcOffsetMinutes", 0)
                
                if (androidSdkVersion != null) {
                    put("androidSdkVersion", androidSdkVersion)
                }
            }
            
            val contextJson = JSONObject().apply {
                put("client", clientContext)
            }
            
            val requestBody = JSONObject().apply {
                put("videoId", videoId)
                put("context", contextJson)
                put("contentCheckOk", true)
                put("racyCheckOk", true)
            }
            
            val userAgent = if (clientName == "ANDROID") {
                "com.google.android.youtube/$clientVersion (Linux; U; Android 13) gzip"
            } else {
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            }
            
            val request = Request.Builder()
                .url("$PLAYER_ENDPOINT?key=$apiKey")
                .post(requestBody.toString().toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull()))
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", userAgent)
                .addHeader("X-YouTube-Client-Name", if (clientName == "ANDROID") "3" else "1")
                .addHeader("X-YouTube-Client-Version", clientVersion)
                .build()
            
            debugLog("Sending API request...")
            val response = httpClient.newCall(request).execute()
            debugLog("Response code: ${response.code}")
            
            if (!response.isSuccessful) {
                debugLog("❌ API request failed with HTTP ${response.code}")
                return@withContext null
            }
            
            val json = response.body?.string()
            if (json.isNullOrEmpty()) {
                debugLog("❌ Empty response body")
                return@withContext null
            }
            
            val jsonObject = JSONObject(json)
            
            // Check playability
            if (jsonObject.has("playabilityStatus")) {
                val playability = jsonObject.getJSONObject("playabilityStatus")
                val status = playability.optString("status", "UNKNOWN")
                val reason = playability.optString("reason", "No reason provided")
                
                debugLog("Playability status: $status")
                if (status != "OK") {
                    debugLog("Reason: $reason")
                    return@withContext null
                }
            }
            
            parseStreamingData(jsonObject)
            
        } catch (e: Exception) {
            debugLog("❌ $clientName exception: ${e.message}")
            null
        }
    }

    private fun parseStreamingData(jsonObject: JSONObject): String? {
        try {
            if (!jsonObject.has("streamingData")) {
                debugLog("⚠️ No streamingData in response")
                return null
            }
            
            val streamingData = jsonObject.getJSONObject("streamingData")
            
            // Priority 1: HLS manifest
            val hlsUrl = streamingData.optString("hlsManifestUrl")
            if (hlsUrl.isNotEmpty()) {
                debugLog("✓ Found HLS manifest URL")
                return hlsUrl
            }
            
            // Priority 2: DASH manifest
            val dashUrl = streamingData.optString("dashManifestUrl")
            if (dashUrl.isNotEmpty()) {
                debugLog("✓ Found DASH manifest URL")
                return dashUrl
            }
            
            // Priority 3: Progressive formats
            val formats = streamingData.optJSONArray("formats")
            if (formats != null && formats.length() > 0) {
                var bestUrl: String? = null
                var bestHeight = 0
                
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    val url = format.optString("url")
                    val height = format.optInt("height", 0)
                    
                    if (url.isNotEmpty() && height > bestHeight) {
                        bestUrl = url
                        bestHeight = height
                    }
                }
                
                if (bestUrl != null) {
                    debugLog("✓ Found progressive format: ${bestHeight}p")
                    return bestUrl
                }
            }
            
            debugLog("❌ No usable stream URL found")
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
            val pattern = java.util.regex.Pattern.compile(patternStr)
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
               url.contains("youtu.be", ignoreCase = true)
    }

    private fun debugLog(message: String) {
        Log.d(TAG, message)
        FileLogger.log(message, TAG)
        
        try {
            val file = File(context.getExternalFilesDir(null), "youtube_extraction_log.txt")
            val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
            file.appendText("[$timestamp] $message\n")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to write debug log", e)
        }
    }
}

    data class ExtractionResult(
        val success: Boolean,
        val streamUrl: String? = null,
        val videoUrl: String? = null,
        val audioUrl: String? = null,
        val quality: String? = null,
        val hasAudio: Boolean = false,
        val isLive: Boolean = false,
        val errorMessage: String? = null
    )

    suspend fun extractStream(youtubeUrl: String): ExtractionResult = withContext(Dispatchers.IO) {
        try {
            val videoId = extractVideoId(youtubeUrl)

            if (videoId == null) {
                return@withContext ExtractionResult(
                    success = false,
                    errorMessage = "Could not extract video ID from YouTube URL"
                )
            }

            val request = Request.Builder()
                .url("https://www.youtube.com/watch?v=$videoId")
                .addHeader("User-Agent", "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/91.0.4472.120 Mobile Safari/537.36")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val response = httpClient.newCall(request).execute()

            if (!response.isSuccessful) {
                return@withContext ExtractionResult(
                    success = false,
                    errorMessage = "YouTube page request failed: ${response.code}"
                )
            }

            val html = response.body?.string()

            if (html.isNullOrEmpty()) {
                return@withContext ExtractionResult(
                    success = false,
                    errorMessage = "Empty response from YouTube"
                )
            }

            val playerResponse = extractPlayerResponse(html)

            if (playerResponse == null) {
                return@withContext ExtractionResult(
                    success = false,
                    errorMessage = "Could not find player response in YouTube page"
                )
            }

            val jsonObject = JSONObject(playerResponse)
            val streamingData = jsonObject.optJSONObject("streamingData")

            if (streamingData == null) {
                return@withContext ExtractionResult(
                    success = false,
                    errorMessage = "No streaming data found in player response"
                )
            }

            val videoDetails = jsonObject.optJSONObject("videoDetails")
            val isLive = videoDetails?.optBoolean("isLiveContent", false) ?: false

            var hlsUrl = streamingData.optString("hlsManifestUrl")

            if (hlsUrl.isNotEmpty()) {
                Log.d(TAG, "✅ Found HLS manifest URL")
                return@withContext ExtractionResult(
                    success = true,
                    streamUrl = hlsUrl,
                    hasAudio = true,
                    isLive = isLive,
                    quality = "Adaptive (HLS)"
                )
            }

            var videoUrl: String? = null
            var audioUrl: String? = null
            var combinedUrl: String? = null
            var bestHeight = 0
            var combinedHeight = 0

            val formats = streamingData.optJSONArray("formats")
            if (formats != null && formats.length() > 0) {
                for (i in 0 until formats.length()) {
                    val format = formats.getJSONObject(i)
                    val url = format.optString("url")
                    val height = format.optInt("height", 0)
                    val mimeType = format.optString("mimeType", "")

                    if (url.isNotEmpty() && mimeType.contains("video") && height > combinedHeight) {
                        combinedUrl = url
                        combinedHeight = height
                    }
                }
            }

            val adaptiveFormats = streamingData.optJSONArray("adaptiveFormats")
            if (adaptiveFormats != null && adaptiveFormats.length() > 0) {
                var bestAudioBitrate = 0

                for (i in 0 until adaptiveFormats.length()) {
                    val format = adaptiveFormats.getJSONObject(i)
                    val url = format.optString("url")
                    val height = format.optInt("height", 0)
                    val mimeType = format.optString("mimeType", "")
                    val audioBitrate = format.optInt("bitrate", 0)
                    val hasAudio = format.has("audioQuality")

                    if (url.isNotEmpty() &&
                        mimeType.contains("video") &&
                        !hasAudio &&
                        height > bestHeight) {
                        videoUrl = url
                        bestHeight = height
                    }

                    if (url.isNotEmpty() &&
                        mimeType.contains("audio") &&
                        audioBitrate > bestAudioBitrate) {
                        audioUrl = url
                        bestAudioBitrate = audioBitrate
                    }
                }
            }

            val qualityLabel = when {
                bestHeight >= 2160 -> "4K (${bestHeight}p)"
                bestHeight >= 1440 -> "2K (${bestHeight}p)"
                bestHeight >= 1080 -> "Full HD (${bestHeight}p)"
                bestHeight >= 720 -> "HD (${bestHeight}p)"
                combinedHeight >= 720 -> "HD (${combinedHeight}p)"
                combinedHeight >= 480 -> "SD (${combinedHeight}p)"
                else -> "${maxOf(bestHeight, combinedHeight)}p"
            }

            when {
                videoUrl != null && audioUrl != null -> {
                    Log.d(TAG, "✅ Extracted separate video ($qualityLabel) and audio streams")
                    ExtractionResult(
                        success = true,
                        videoUrl = videoUrl,
                        audioUrl = audioUrl,
                        quality = qualityLabel,
                        hasAudio = false,
                        isLive = isLive
                    )
                }
                combinedUrl != null -> {
                    Log.d(TAG, "✅ Using combined video+audio format: $qualityLabel")
                    ExtractionResult(
                        success = true,
                        streamUrl = combinedUrl,
                        quality = qualityLabel,
                        hasAudio = true,
                        isLive = isLive
                    )
                }
                videoUrl != null -> {
                    Log.d(TAG, "✅ Using video-only format: $qualityLabel")
                    ExtractionResult(
                        success = true,
                        streamUrl = videoUrl,
                        quality = qualityLabel,
                        hasAudio = false,
                        isLive = isLive
                    )
                }
                else -> {
                    ExtractionResult(
                        success = false,
                        errorMessage = "No stream found for this video"
                    )
                }
            }

        } catch (e: Exception) {
            Log.e(TAG, "YouTube extraction exception", e)
            ExtractionResult(
                success = false,
                errorMessage = "YouTube extraction exception: ${e.message}"
            )
        }
    }

    private fun extractVideoId(url: String): String? {
        val patterns = listOf(
            "(?:youtube\\.com/watch\\?v=|youtu\\.be/|youtube\\.com/embed/|m\\.youtube\\.com/watch\\?v=)([a-zA-Z0-9_-]{11})",
            "v=([a-zA-Z0-9_-]{11})"
        )

        for (patternStr in patterns) {
            val pattern = Pattern.compile(patternStr)
            val matcher = pattern.matcher(url)
            if (matcher.find()) {
                return matcher.group(1)
            }
        }
        return null
    }

    private fun extractPlayerResponse(html: String): String? {
        var pattern = Pattern.compile("ytInitialPlayerResponse\\s*=\\s*(\\{.+?\\});", Pattern.DOTALL)
        var matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)
        }

        pattern = Pattern.compile("\"streamingData\":\\{[^}]*\"hlsManifestUrl\":\"([^\"]+)\"")
        matcher = pattern.matcher(html)
        if (matcher.find()) {
            val url = matcher.group(1)?.replace("\\/", "/")
            if (url != null) {
                return """{"streamingData":{"hlsManifestUrl":"$url"}}"""
            }
        }

        pattern = Pattern.compile("\"player_response\"\\s*:\\s*\"(\\{.+?\\})\"", Pattern.DOTALL)
        matcher = pattern.matcher(html)
        if (matcher.find()) {
            return matcher.group(1)?.replace("\\\"", "\"")
        }

        return null
    }

    fun isYouTubeUrl(url: String): Boolean {
        return url.contains("youtube.com", ignoreCase = true) ||
               url.contains("youtu.be", ignoreCase = true)
    }
}