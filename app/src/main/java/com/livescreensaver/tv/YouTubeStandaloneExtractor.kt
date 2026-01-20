package com.livescreensaver.tv

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import java.util.regex.Pattern

class YouTubeStandaloneExtractor(httpClient: OkHttpClient? = null) {

    companion object {
        private const val TAG = "YouTubeStandaloneExtractor"
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