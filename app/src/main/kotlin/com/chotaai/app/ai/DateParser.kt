package com.chotaai.app.ai

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.Month
import java.time.format.DateTimeParseException
import java.time.temporal.TemporalAdjusters

data class ParsedDate(val dateTime: LocalDateTime, val hasTime: Boolean)

/**
 * Turns phrases like "tomorrow 5pm", "next friday", "in 2 hours" or "oct 25" into a date.
 *
 * Small models are bad at date arithmetic, so the tools ask the model to pass the user's own words
 * and this does the math. Returns null when nothing date-like is recognized.
 */
object DateParser {
    private val weekdays = mapOf(
        "mon" to DayOfWeek.MONDAY, "monday" to DayOfWeek.MONDAY,
        "tue" to DayOfWeek.TUESDAY, "tues" to DayOfWeek.TUESDAY, "tuesday" to DayOfWeek.TUESDAY,
        "wed" to DayOfWeek.WEDNESDAY, "wednesday" to DayOfWeek.WEDNESDAY,
        "thu" to DayOfWeek.THURSDAY, "thur" to DayOfWeek.THURSDAY, "thurs" to DayOfWeek.THURSDAY,
        "thursday" to DayOfWeek.THURSDAY,
        "fri" to DayOfWeek.FRIDAY, "friday" to DayOfWeek.FRIDAY,
        "sat" to DayOfWeek.SATURDAY, "saturday" to DayOfWeek.SATURDAY,
        "sun" to DayOfWeek.SUNDAY, "sunday" to DayOfWeek.SUNDAY,
    )
    private val months = Month.entries.flatMap { m ->
        val full = m.name.lowercase()
        listOf(full to m, full.take(3) to m) + if (m == Month.SEPTEMBER) listOf("sept" to m) else emptyList()
    }.toMap()

    private val monthNames = months.keys.sortedByDescending { it.length }.joinToString("|")
    private val weekdayNames = weekdays.keys.sortedByDescending { it.length }.joinToString("|")

    private val isoDate = Regex("""\b(\d{4})-(\d{1,2})-(\d{1,2})\b""")
    private val slashDate = Regex("""\b(\d{1,2})/(\d{1,2})(?:/(\d{2,4}))?\b""") // day/month[/year]
    private val monthDay = Regex("""\b($monthNames)\.?\s+(\d{1,2})(?:st|nd|rd|th)?\b""")
    private val dayMonth = Regex("""\b(\d{1,2})(?:st|nd|rd|th)?\s+(?:of\s+)?($monthNames)\b""")
    private val relative = Regex("""\bin\s+(an?|\d+)\s+(minute|min|hour|hr|day|week|month)s?\b""")
    private val weekday = Regex("""\b(?:(next|this|coming)\s+)?($weekdayNames)\b""")
    private val clock = Regex("""\b(?:at\s+)?(\d{1,2})(?::(\d{2}))?\s*(am|pm|a\.m\.|p\.m\.)?(?=\s|$|[,.!?])""")

    fun parse(input: String, now: LocalDateTime = LocalDateTime.now()): ParsedDate? {
        val text = input.trim().lowercase()
        if (text.isEmpty()) return null
        parseIso(text)?.let { return it }

        // "in 2 hours" carries its own date and time.
        relative.find(text)?.let { m ->
            val n = m.groupValues[1].let { if (it.startsWith("a")) 1L else it.toLong() }
            return when (m.groupValues[2]) {
                "minute", "min" -> ParsedDate(now.plusMinutes(n), true)
                "hour", "hr" -> ParsedDate(now.plusHours(n), true)
                "day" -> ParsedDate(now.plusDays(n), false)
                "week" -> ParsedDate(now.plusWeeks(n), false)
                else -> ParsedDate(now.plusMonths(n), false)
            }
        }

        var rest = text
        val date = findDate(text, now.toLocalDate())?.let { (d, matched) ->
            rest = rest.replace(matched, " ")
            d
        }
        val time = findClockTime(rest) ?: findPeriodTime(text)

        return when {
            date != null && time != null -> ParsedDate(date.atTime(time), true)
            date != null -> ParsedDate(date.atStartOfDay(), false)
            time != null -> {
                // A bare time means the next occurrence of it.
                val today = now.toLocalDate().atTime(time)
                ParsedDate(if (today.isAfter(now)) today else today.plusDays(1), true)
            }
            else -> null
        }
    }

    private fun parseIso(text: String): ParsedDate? {
        val candidate = text.replace(' ', 'T')
        return try {
            ParsedDate(LocalDateTime.parse(candidate.take(16).let { if (it.length == 13) "$it:00" else it }), true)
        } catch (_: DateTimeParseException) {
            try {
                if (text.length == 10) ParsedDate(LocalDate.parse(text).atStartOfDay(), false) else null
            } catch (_: DateTimeParseException) {
                null
            }
        }
    }

    /** Returns the date and the substring it was found in, so the time search can skip it. */
    private fun findDate(text: String, today: LocalDate): Pair<LocalDate, String>? {
        Regex("""\bday after tomorrow\b""").find(text)?.let { return today.plusDays(2) to it.value }
        Regex("""\b(today|tonight|tomorrow|tmrw|tmr)\b""").find(text)?.let {
            val date = if (it.value == "today" || it.value == "tonight") today else today.plusDays(1)
            return date to it.value
        }
        Regex("""\bnext week\b""").find(text)?.let {
            return today.with(TemporalAdjusters.next(DayOfWeek.MONDAY)) to it.value
        }
        Regex("""\b(this )?weekend\b""").find(text)?.let {
            return today.with(TemporalAdjusters.nextOrSame(DayOfWeek.SATURDAY)) to it.value
        }
        isoDate.find(text)?.let { m ->
            val (y, mo, d) = m.destructured
            return safeDate(y.toInt(), mo.toInt(), d.toInt())?.let { it to m.value }
        }
        monthDay.find(text)?.let { m ->
            return upcoming(today, months.getValue(m.groupValues[1]), m.groupValues[2].toInt())?.let { it to m.value }
        }
        dayMonth.find(text)?.let { m ->
            return upcoming(today, months.getValue(m.groupValues[2]), m.groupValues[1].toInt())?.let { it to m.value }
        }
        slashDate.find(text)?.let { m ->
            val day = m.groupValues[1].toInt()
            val month = m.groupValues[2].toInt()
            val yearText = m.groupValues[3]
            if (yearText.isEmpty()) {
                if (month in 1..12) return upcoming(today, Month.of(month), day)?.let { it to m.value }
            } else {
                val year = yearText.toInt().let { if (it < 100) 2000 + it else it }
                return safeDate(year, month, day)?.let { it to m.value }
            }
        }
        weekday.find(text)?.let { m ->
            val day = weekdays.getValue(m.groupValues[2])
            val date = if (m.groupValues[1] == "next" && today.dayOfWeek == day) {
                today.plusWeeks(1)
            } else {
                today.with(TemporalAdjusters.next(day))
            }
            return date to m.value
        }
        return null
    }

    private fun findClockTime(text: String): LocalTime? {
        when {
            Regex("""\bnoon\b|\bmidday\b""").containsMatchIn(text) -> return LocalTime.NOON
            Regex("""\bmidnight\b""").containsMatchIn(text) -> return LocalTime.of(23, 59)
        }
        for (m in clock.findAll(text)) {
            val hourText = m.groupValues[1]
            val minuteText = m.groupValues[2]
            val suffix = m.groupValues[3].replace(".", "")
            // A bare number is only a time if it was written as one ("at 5", "17:30", "5pm").
            if (minuteText.isEmpty() && suffix.isEmpty() && !m.value.trimStart().startsWith("at")) continue
            var hour = hourText.toInt()
            val minute = minuteText.ifEmpty { "0" }.toInt()
            if (suffix == "pm" && hour < 12) hour += 12
            if (suffix == "am" && hour == 12) hour = 0
            // "at 5" with no am/pm: assume the afternoon for 1-7, like people usually mean.
            if (suffix.isEmpty() && minuteText.isEmpty() && hour in 1..7) hour += 12
            if (hour in 0..23 && minute in 0..59) return LocalTime.of(hour, minute)
        }
        return null
    }

    /** "tonight", "morning", ... Checked against the whole phrase, since "tonight" is also a date. */
    private fun findPeriodTime(text: String): LocalTime? {
        return when {
            Regex("""\btonight\b""").containsMatchIn(text) -> LocalTime.of(20, 0)
            Regex("""\bmorning\b""").containsMatchIn(text) -> LocalTime.of(9, 0)
            Regex("""\bafternoon\b""").containsMatchIn(text) -> LocalTime.of(15, 0)
            Regex("""\bevening\b""").containsMatchIn(text) -> LocalTime.of(18, 0)
            Regex("""\bnight\b""").containsMatchIn(text) -> LocalTime.of(21, 0)
            else -> null
        }
    }

    private fun upcoming(today: LocalDate, month: Month, day: Int): LocalDate? {
        val thisYear = safeDate(today.year, month.value, day) ?: return null
        return if (thisYear.isBefore(today)) safeDate(today.year + 1, month.value, day) else thisYear
    }

    private fun safeDate(year: Int, month: Int, day: Int): LocalDate? =
        runCatching { LocalDate.of(year, month, day) }.getOrNull()
}
