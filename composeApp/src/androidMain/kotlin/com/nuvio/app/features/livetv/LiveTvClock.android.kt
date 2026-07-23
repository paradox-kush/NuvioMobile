package com.nuvio.app.features.livetv

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val hourMinute = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
private val dayName = DateTimeFormatter.ofPattern("EEE", Locale.getDefault())

actual fun liveClockLabel(epochMs: Long): String =
    Instant.ofEpochMilli(epochMs).atZone(ZoneId.systemDefault()).format(hourMinute)

actual fun liveDayLabel(epochMs: Long): String {
    val zone = ZoneId.systemDefault()
    val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
    val today = Instant.ofEpochMilli(System.currentTimeMillis()).atZone(zone).toLocalDate()
    return when (day) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        else -> Instant.ofEpochMilli(epochMs).atZone(zone).format(dayName)
    }
}
