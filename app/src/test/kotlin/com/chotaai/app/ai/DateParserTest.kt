package com.chotaai.app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDateTime

class DateParserTest {
    // Monday afternoon
    private val now = LocalDateTime.of(2026, 10, 19, 14, 50)

    private fun check(input: String, expected: String, hasTime: Boolean) {
        val parsed = DateParser.parse(input, now) ?: throw AssertionError("'$input' not parsed")
        assertEquals(input, LocalDateTime.parse(expected), parsed.dateTime)
        assertEquals("$input hasTime", hasTime, parsed.hasTime)
    }

    @Test fun tomorrowWithTime() = check("tomorrow 5pm", "2026-10-20T17:00", true)
    @Test fun tomorrowAt() = check("tomorrow at 5:30 pm", "2026-10-20T17:30", true)
    @Test fun tomorrowOnly() = check("tomorrow", "2026-10-20T00:00", false)
    @Test fun todayMorningTimeStaysToday() = check("today at 9am", "2026-10-19T09:00", true)
    @Test fun bareAtFiveMeansAfternoon() = check("at 5", "2026-10-19T17:00", true)
    @Test fun pastTimeRollsToTomorrow() = check("9am", "2026-10-20T09:00", true)
    @Test fun twentyFourHourClock() = check("17:45", "2026-10-19T17:45", true)
    @Test fun noonRollsOver() = check("noon", "2026-10-20T12:00", true)
    @Test fun tonight() = check("tonight", "2026-10-19T20:00", true)
    @Test fun tomorrowMorning() = check("tomorrow morning", "2026-10-20T09:00", true)
    @Test fun weekday() = check("friday", "2026-10-23T00:00", false)
    @Test fun nextWeekday() = check("next friday 10am", "2026-10-23T10:00", true)
    @Test fun sameWeekdayMeansNextWeek() = check("monday", "2026-10-26T00:00", false)
    @Test fun nextWeek() = check("next week", "2026-10-26T00:00", false)
    @Test fun inHours() = check("in 2 hours", "2026-10-19T16:50", true)
    @Test fun inAnHour() = check("in an hour", "2026-10-19T15:50", true)
    @Test fun inDays() = check("in 3 days", "2026-10-22T14:50", false)
    @Test fun monthDay() = check("oct 25", "2026-10-25T00:00", false)
    @Test fun dayMonth() = check("25th of October at 6pm", "2026-10-25T18:00", true)
    @Test fun pastMonthDayIsNextYear() = check("jan 3", "2027-01-03T00:00", false)
    @Test fun slashDateIsDayFirst() = check("5/11", "2026-11-05T00:00", false)
    @Test fun isoDate() = check("2026-11-01", "2026-11-01T00:00", false)
    @Test fun isoDateTime() = check("2026-11-01 08:30", "2026-11-01T08:30", true)
    @Test fun isoDateTimeT() = check("2026-11-01T08:30:00", "2026-11-01T08:30", true)

    @Test fun nonsenseIsNull() = assertNull(DateParser.parse("whenever I feel like it", now))
    @Test fun blankIsNull() = assertNull(DateParser.parse("  ", now))
}
