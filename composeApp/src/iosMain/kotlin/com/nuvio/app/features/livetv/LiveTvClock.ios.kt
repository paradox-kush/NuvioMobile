package com.nuvio.app.features.livetv

import platform.Foundation.NSCalendar
import platform.Foundation.NSCalendarUnitDay
import platform.Foundation.NSDate
import platform.Foundation.NSDateFormatter
import platform.Foundation.dateWithTimeIntervalSince1970

private val hourMinuteFormatter = NSDateFormatter().apply { dateFormat = "HH:mm" }
private val dayFormatter = NSDateFormatter().apply { dateFormat = "EEE" }

private fun dateOf(epochMs: Long): NSDate =
    NSDate.dateWithTimeIntervalSince1970(epochMs.toDouble() / 1000.0)

actual fun liveClockLabel(epochMs: Long): String =
    hourMinuteFormatter.stringFromDate(dateOf(epochMs))

actual fun liveDayLabel(epochMs: Long): String {
    val calendar = NSCalendar.currentCalendar
    val target = dateOf(epochMs)
    val now = NSDate()
    val targetDay = calendar.component(NSCalendarUnitDay, fromDate = target)
    val todayDay = calendar.component(NSCalendarUnitDay, fromDate = now)
    return if (targetDay == todayDay) "Today" else dayFormatter.stringFromDate(target)
}
