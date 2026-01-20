package com.livescreensaver.tv

import android.content.SharedPreferences
import java.util.*

class ScheduleManager(
    private val preferences: SharedPreferences,
    private val defaultUrl: String
) {
    companion object {
        private const val PREF_VIDEO_URL = "video_url"
        private val DAYS = listOf("monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday")
    }

    fun getScheduledUrl(cache: PreferenceCache): String {
        if (!cache.scheduleEnabled) {
            return getMainUrl()
        }

        return if (cache.scheduleRandomMode) {
            getRandomScheduledUrl()
        } else {
            getTodayScheduledUrl()
        }
    }

    private fun getRandomScheduledUrl(): String {
        val urls = mutableListOf<String>()
        
        val mainUrl = preferences.getString(PREF_VIDEO_URL, null)
        if (!mainUrl.isNullOrEmpty() && !preferences.getBoolean("disable_video_url", false)) {
            urls.add(mainUrl)
        }
        
        urls.addAll(DAYS.mapNotNull { day ->
            val url = preferences.getString("url_$day", null)
            val isDisabled = preferences.getBoolean("disable_url_$day", false)
            url?.takeIf { it.isNotEmpty() && !isDisabled }
        })
        
        return if (urls.isNotEmpty()) {
            urls.random()
        } else {
            getMainUrl()
        }
    }

    private fun getTodayScheduledUrl(): String {
        val calendar = Calendar.getInstance()
        val dayOfWeek = calendar.get(Calendar.DAY_OF_WEEK)
        
        val dayIndex = when (dayOfWeek) {
            Calendar.MONDAY -> 0
            Calendar.TUESDAY -> 1
            Calendar.WEDNESDAY -> 2
            Calendar.THURSDAY -> 3
            Calendar.FRIDAY -> 4
            Calendar.SATURDAY -> 5
            Calendar.SUNDAY -> 6
            else -> 0
        }
        
        val dayUrl = preferences.getString("url_${DAYS[dayIndex]}", null)
        val isDisabled = preferences.getBoolean("disable_url_${DAYS[dayIndex]}", false)
        
        return if (!dayUrl.isNullOrEmpty() && !isDisabled) {
            dayUrl
        } else {
            getMainUrl()
        }
    }

    private fun getMainUrl(): String {
        val mainUrl = preferences.getString(PREF_VIDEO_URL, defaultUrl) ?: defaultUrl
        val isMainDisabled = preferences.getBoolean("disable_video_url", false)
        
        return if (isMainDisabled) defaultUrl else mainUrl
    }
}