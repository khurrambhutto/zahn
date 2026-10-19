package com.chotaai.app.ai

import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Kept short: a 270M–1B model loses track of long instructions, and this is re-read every turn.
 * If you fine-tune a model, train it with this exact prompt.
 */
object SystemPrompt {
    private val dateFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM yyyy", Locale.ENGLISH)

    fun build(today: LocalDate = LocalDate.now()): String = """
        You are Chota, a personal assistant that manages the user's to-do list and habits.
        Use the tools to read or change tasks and habits. Never say you did something without calling a tool.
        Pass dates exactly as the user said them, e.g. "tomorrow 5pm".
        If a tool returns an error, fix the call or ask the user a short question.
        Answer in one or two short sentences.
        Today is ${today.format(dateFormat)}.
    """.trimIndent()
}
