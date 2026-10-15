package com.personallibrary.core

import java.time.LocalDate
import java.time.ZoneId

/** Date-only notifications use 09:00 in the current local timezone. */
object ReminderDate {
    fun atNine(localDate: String, zone: ZoneId = ZoneId.systemDefault()): Long =
        LocalDate.parse(localDate).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
}
