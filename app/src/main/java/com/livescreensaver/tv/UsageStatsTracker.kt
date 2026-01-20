package com.livescreensaver.tv

import android.content.SharedPreferences
import android.util.Log
import java.text.SimpleDateFormat
import java.util.*

class UsageStatsTracker(private val statsPrefs: SharedPreferences) {
    companion object {
        private const val TAG = "UsageStatsTracker"
        private const val KEY_USAGE_PREFIX = "usage_"
    }

    fun trackPlaybackUsage() {
        try {
            val dateKey = KEY_USAGE_PREFIX + getTodayDateKey()
            val currentMinutes = statsPrefs.getLong(dateKey, 0)
            statsPrefs.edit().putLong(dateKey, currentMinutes + 1).apply()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to track usage", e)
        }
    }

    fun getUsageStats(): String {
        try {
            val calendar = Calendar.getInstance()
            val sdf = SimpleDateFormat("M/d", Locale.getDefault())
            val stats = StringBuilder()
            var totalMinutes = 0L
            
            for (i in 0..6) {
                val date = calendar.time
                val dateStr = sdf.format(date)
                val dateKey = KEY_USAGE_PREFIX + SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(date)
                val minutes = statsPrefs.getLong(dateKey, 0)
                
                totalMinutes += minutes
                val hours = minutes / 60
                val mins = minutes % 60
                
                stats.append(dateStr).append(": ")
                if (hours > 0) stats.append(hours).append("h ")
                stats.append(mins).append("m\n")
                
                calendar.add(Calendar.DAY_OF_MONTH, -1)
            }
            
            stats.insert(0, "--- 7 DAY USAGE ---\n")
            stats.append("--- TOTAL: ")
            val totalHours = totalMinutes / 60
            val totalMins = totalMinutes % 60
            if (totalHours > 0) stats.append(totalHours).append("h ")
            stats.append(totalMins).append("m ---")
            
            return stats.toString()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get usage stats", e)
            return "Usage stats unavailable"
        }
    }

    private fun getTodayDateKey(): String {
        val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
        return sdf.format(Calendar.getInstance().time)
    }
}