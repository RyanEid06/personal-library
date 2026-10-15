package com.personallibrary.core

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.ZoneId

class ReminderDateTest {
    @Test fun dateOnlyUsesNineLocalAndRecomputesOnTimezoneChange() {
        assertEquals(Instant.parse("2026-10-25T13:00:00Z").toEpochMilli(), ReminderDate.atNine("2026-10-25", ZoneId.of("America/New_York")))
        assertEquals(Instant.parse("2026-10-25T08:00:00Z").toEpochMilli(), ReminderDate.atNine("2026-10-25", ZoneId.of("Europe/Berlin")))
    }
    @Test fun dateOnlyRejectsInvalidCalendarDate() {
        assertThrows(java.time.DateTimeException::class.java) { ReminderDate.atNine("2026-02-30", ZoneId.of("UTC")) }
    }
}
