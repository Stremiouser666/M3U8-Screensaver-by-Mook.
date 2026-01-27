package com.livescreensaver.tv

import android.content.SharedPreferences
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import java.text.SimpleDateFormat
import java.util.*

class BandwidthTracker(private val bandwidthPrefs: SharedPreferences) {
    companion object {
        private const val TAG = "BandwidthTracker"
        private const val KEY_BANDWIDTH_PREFIX = "bandwidth_"
        private const val FALLBACK_BITRATE_KBPS = 4000 // Average for 720p-1080p
    }

    private var currentSessionBytes = 0L
    private var lastCheckTimeMs = 0L
    private var currentBitrateKbps = 0
    private var bitrateInitialized = false

    fun trackBandwidth(player: ExoPlayer?) {
        try {
            player?.let { p ->
                val currentTimeMs = System.currentTimeMillis()
                
                // Get bitrate from video format
                val videoFormat = p.videoFormat
                if (videoFormat != null && videoFormat.bitrate > 0) {
                    currentBitrateKbps = videoFormat.bitrate / 1000
                    bitrateInitialized = true
                } else if (!bitrateInitialized) {
                    // Use fallback bitrate if format bitrate unavailable
                    currentBitrateKbps = FALLBACK_BITRATE_KBPS
                    bitrateInitialized = true
                    Log.d(TAG, "Using fallback bitrate: $FALLBACK_BITRATE_KBPS kbps")
                }
                
                // Calculate bytes consumed based on playback time
                if (lastCheckTimeMs > 0 && currentBitrateKbps > 0) {
                    val elapsedSeconds = (currentTimeMs - lastCheckTimeMs) / 1000.0
                    if (elapsedSeconds > 0) {
                        // bytes = (kbps * 1000 / 8) * seconds
                        val bytesConsumed = ((currentBitrateKbps * 1000.0 / 8.0) * elapsedSeconds).toLong()
                        currentSessionBytes += bytesConsumed
                        
                        // Save every ~1MB to avoid data loss
                        if (currentSessionBytes > 1_000_000) {
                            saveDailyBandwidth()
                        }
                    }
                }
                
                lastCheckTimeMs = currentTimeMs
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to track bandwidth", e)
        }
    }

    fun getCurrentBitrateKbps(): Int {
        return currentBitrateKbps
    }

    fun saveDailyBandwidth() {
        try {
            if (currentSessionBytes == 0L) return

            val dateKey = KEY_BANDWIDTH_PREFIX + getTodayDateKey()
            val currentTotal = bandwidthPrefs.getLong(dateKey, 0)
            bandwidthPrefs.edit().putLong(dateKey, currentTotal + currentSessionBytes).apply()
            Log.d(TAG, "Saved ${currentSessionBytes / 1_000_000}MB to daily bandwidth")
            currentSessionBytes = 0L
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save daily bandwidth", e)
        }
    }

    fun getBandwidthStats(): String {
        try {
            val calendar = Calendar.getInstance()
            val sdf = SimpleDateFormat("M/d", Locale.getDefault())
            val stats = StringBuilder("--- 7 DAY BANDWIDTH ---\n")
            var totalBytes = 0L

            for (i in 0..6) {
                val dateStr = sdf.format(calendar.time)
                val dateKey = KEY_BANDWIDTH_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(calendar.time)
                val bytes = bandwidthPrefs.getLong(dateKey, 0)

                totalBytes += bytes
                val (value, unit) = formatBytes(bytes)

                stats.append(dateStr).append(": ")
                    .append(String.format("%.2f", value))
                    .append(unit)
                    .append('\n')

                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }

            val (totalValue, totalUnit) = formatBytes(totalBytes)
            stats.append("--- TOTAL: ")
                .append(String.format("%.2f", totalValue))
                .append(totalUnit)
                .append(" ---")

            return stats.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get bandwidth stats", e)
            return "Bandwidth stats unavailable"
        }
    }

    fun reset() {
        currentSessionBytes = 0L
        lastCheckTimeMs = 0L
        currentBitrateKbps = 0
        bitrateInitialized = false
    }

    private fun formatBytes(bytes: Long): Pair<Double, String> {
        return when {
            bytes >= 1_073_741_824 -> Pair(bytes.toDouble() / 1_073_741_824, " GB")
            bytes >= 1_048_576 -> Pair(bytes.toDouble() / 1_048_576, " MB")
            bytes >= 1_024 -> Pair(bytes.toDouble() / 1_024, " KB")
            else -> Pair(bytes.toDouble(), " B")
        }
    }

    private fun getTodayDateKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Calendar.getInstance().time)
    }
}
