package com.chotaai.app.data

import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val dayFormat = DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault())
private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

fun Long.toLocalDateTime(): LocalDateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(this), ZoneId.systemDefault())

fun LocalDateTime.toEpochMillis(): Long = atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

/** "Today 17:00", "Tomorrow", "Wed 22 Oct 09:30". */
fun TaskEntity.dueLabel(today: LocalDate = LocalDate.now()): String? {
    val due = dueAt?.toLocalDateTime() ?: return null
    val day = when (due.toLocalDate()) {
        today -> "Today"
        today.plusDays(1) -> "Tomorrow"
        today.minusDays(1) -> "Yesterday"
        else -> due.format(dayFormat)
    }
    return if (dueHasTime) "$day ${due.format(timeFormat)}" else day
}

fun TaskEntity.isOverdue(now: LocalDateTime = LocalDateTime.now()): Boolean {
    if (done) return false
    val due = dueAt?.toLocalDateTime() ?: return false
    return if (dueHasTime) due.isBefore(now) else due.toLocalDate().isBefore(now.toLocalDate())
}

object HabitStats {
    /** Consecutive days done, ending today (or yesterday, if today isn't done yet). */
    fun streak(days: Set<Long>, today: Long = LocalDate.now().toEpochDay()): Int {
        var day = if (today in days) today else today - 1
        var count = 0
        while (day in days) {
            count++
            day--
        }
        return count
    }
}
