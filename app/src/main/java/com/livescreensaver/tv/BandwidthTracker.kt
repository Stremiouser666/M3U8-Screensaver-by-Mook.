package com.livescreensaver.tv

import android.content.SharedPreferences
import android.util.Log
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.analytics.AnalyticsListener
import java.text.SimpleDateFormat
import java.util.*

class BandwidthTracker(private val bandwidthPrefs: SharedPreferences) : AnalyticsListener {
    companion object {
        private const val TAG = "BandwidthTracker"
        private const val KEY_BANDWIDTH_PREFIX = "bandwidth_"
        private const val BITRATE_TIMEOUT_MS = 5000L
    }

    private var currentSessionBytes = 0L
    private var sessionStartTimeMs = 0L
    private var lastCheckTimeMs = 0L
    private var currentBitrateKbps = 0
    private var totalPlayedTimeMs = 0L
    private var bitrateUnavailable = false
    private var totalBytesLoaded = 0L

    override fun onLoadCompleted(
        eventTime: AnalyticsListener.EventTime,
        loadEventInfo: androidx.media3.exoplayer.source.LoadEventInfo,
        mediaLoadData: androidx.media3.exoplayer.source.MediaLoadData
    ) {
        // Track actual bytes loaded from network
        totalBytesLoaded += loadEventInfo.bytesLoaded
    }

    fun trackBandwidth(player: ExoPlayer?) {
        try {
            player?.let { p ->
                val currentTimeMs = System.currentTimeMillis()
                
                // Initialize session start time on first call
                if (sessionStartTimeMs == 0L) {
                    sessionStartTimeMs = currentTimeMs
                    // Attach this as an analytics listener to track loaded bytes
                    p.addAnalyticsListener(this)
                }
                
                // Calculate elapsed time since last check
                if (lastCheckTimeMs > 0) {
                    val elapsedMs = currentTimeMs - lastCheckTimeMs
                    totalPlayedTimeMs += elapsedMs
                    
                    // Update session bytes from actual loaded data
                    currentSessionBytes = totalBytesLoaded
                    
                    // Calculate actual average bitrate from total data / total time
                    if (totalPlayedTimeMs > 1000 && currentSessionBytes > 0) {
                        val totalSeconds = totalPlayedTimeMs / 1000.0
                        // bitrate (kbps) = (bytes * 8) / seconds / 1000
                        currentBitrateKbps = ((currentSessionBytes * 8.0) / totalSeconds / 1000.0).toInt()
                    }
                    
                    // Save every ~1MB to avoid data loss
                    if (currentSessionBytes > 1_000_000) {
                        saveDailyBandwidth()
                    }
                }
                
                // Check if timeout reached without any bitrate data
                if (currentBitrateKbps == 0 && currentTimeMs - sessionStartTimeMs > BITRATE_TIMEOUT_MS) {
                    bitrateUnavailable = true
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

    fun isBitrateUnavailable(): Boolean {
        return bitrateUnavailable
    }

    fun saveDailyBandwidth() {
        try {
            if (currentSessionBytes == 0L) return

            val dateKey = KEY_BANDWIDTH_PREFIX + getTodayDateKey()
            val currentTotal = bandwidthPrefs.getLong(dateKey, 0)
            val newTotal = currentTotal + currentSessionBytes
            bandwidthPrefs.edit().putLong(dateKey, newTotal).apply()
            Log.d(TAG, "Saved ${currentSessionBytes / 1_000_000}MB to daily bandwidth (avg bitrate: $currentBitrateKbps kbps)")
            
            // Reset session bytes but keep totalBytesLoaded for continued tracking
            currentSessionBytes = 0L
            totalBytesLoaded = 0L
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
        sessionStartTimeMs = 0L
        lastCheckTimeMs = 0L
        currentBitrateKbps = 0
        totalPlayedTimeMs = 0L
        bitrateUnavailable = false
        totalBytesLoaded = 0L
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
