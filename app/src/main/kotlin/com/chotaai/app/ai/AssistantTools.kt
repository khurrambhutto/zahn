package com.chotaai.app.ai

import com.chotaai.app.data.HabitDao
import com.chotaai.app.data.HabitEntity
import com.chotaai.app.data.HabitLogEntity
import com.chotaai.app.data.HabitStats
import com.chotaai.app.data.Priority
import com.chotaai.app.data.TaskDao
import com.chotaai.app.data.TaskEntity
import com.chotaai.app.data.dueLabel
import com.chotaai.app.data.isOverdue
import com.chotaai.app.data.toEpochMillis
import com.chotaai.app.data.toLocalDateTime
import com.chotaai.llm.ParamType
import com.chotaai.llm.ToolParam
import com.chotaai.llm.ToolSpec
import com.chotaai.llm.agent.Tool
import com.chotaai.llm.agent.ToolError
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.time.LocalDate
import java.time.LocalDateTime

/** Key in every tool result: a one-line, human-readable description of what happened, shown in the chat. */
const val SUMMARY_KEY = "summary"

/**
 * The functions the assistant can call. Descriptions are short on purpose: every word is in the
 * prompt of every turn, and small models follow short, concrete descriptions best.
 *
 * Keep names and parameters stable: fine-tuned models learn them by heart.
 */
class AssistantTools(
    private val tasks: TaskDao,
    private val habits: HabitDao,
    private val now: () -> LocalDateTime = LocalDateTime::now,
) {
    val all: List<Tool> = listOf(
        tool(
            "add_task", "Add a task to the to-do list.",
            ToolParam("title", ParamType.STRING, "What needs to be done, e.g. 'Buy milk'"),
            ToolParam("due", ParamType.STRING, "When it is due, in the user's words, e.g. 'tomorrow 5pm'", required = false),
            ToolParam("priority", ParamType.STRING, "Task priority", required = false, enumValues = PRIORITIES),
        ) { args ->
            val title = args.string("title").replaceFirstChar { it.uppercase() }
            val due = args.stringOrNull("due")?.let(::parseDue)
            val priority = args.stringOrNull("priority")?.let(::parsePriority) ?: Priority.NONE
            val task = TaskEntity(
                title = title,
                dueAt = due?.dateTime?.toEpochMillis(),
                dueHasTime = due?.hasTime ?: false,
                priority = priority,
                createdAt = System.currentTimeMillis(),
            )
            val saved = task.copy(id = tasks.insert(task))
            result("Added “${saved.title}”" + (saved.dueLabel()?.let { " · $it" } ?: "")) { put("task", saved.toJson()) }
        },
        tool(
            "list_tasks", "List the user's tasks.",
            ToolParam("filter", ParamType.STRING, "Which tasks to list (default: pending)", required = false, enumValues = FILTERS),
        ) { args ->
            val filter = args.stringOrNull("filter") ?: "pending"
            val today = now().toLocalDate()
            val selected = tasks.all().filter { task ->
                when (filter) {
                    "done" -> task.done
                    "all" -> true
                    "today" -> !task.done && task.dueAt?.toLocalDateTime()?.toLocalDate()?.let { !it.isAfter(today) } == true
                    "overdue" -> task.isOverdue(now())
                    else -> !task.done
                }
            }
            result("Listed ${selected.size} $filter task(s)") {
                put("count", selected.size)
                putJsonArray("tasks") { selected.take(MAX_LISTED).forEach { add(it.toJson()) } }
            }
        },
        tool(
            "complete_task", "Mark a task as done.",
            ToolParam("task", ParamType.STRING, "The task's title (or part of it) or id"),
        ) { args ->
            val task = findTask(args.string("task"), includeDone = false)
            tasks.update(task.copy(done = true, completedAt = System.currentTimeMillis()))
            result("Completed “${task.title}”") { put("task", task.toJson()) }
        },
        tool(
            "reopen_task", "Mark a done task as not done.",
            ToolParam("task", ParamType.STRING, "The task's title (or part of it) or id"),
        ) { args ->
            val task = findTask(args.string("task"), includeDone = true)
            tasks.update(task.copy(done = false, completedAt = null))
            result("Reopened “${task.title}”") { put("task", task.toJson()) }
        },
        tool(
            "update_task", "Change a task's title, due date or priority.",
            ToolParam("task", ParamType.STRING, "The task's current title (or part of it) or id"),
            ToolParam("title", ParamType.STRING, "New title", required = false),
            ToolParam("due", ParamType.STRING, "New due date in the user's words, or 'none' to clear it", required = false),
            ToolParam("priority", ParamType.STRING, "New priority", required = false, enumValues = PRIORITIES),
        ) { args ->
            val task = findTask(args.string("task"), includeDone = true)
            var updated = task
            args.stringOrNull("title")?.let { updated = updated.copy(title = it.replaceFirstChar { c -> c.uppercase() }) }
            args.stringOrNull("due")?.let { text ->
                updated = if (text.lowercase() in NO_DATE) {
                    updated.copy(dueAt = null, dueHasTime = false)
                } else {
                    val due = parseDue(text)
                    updated.copy(dueAt = due.dateTime.toEpochMillis(), dueHasTime = due.hasTime)
                }
            }
            args.stringOrNull("priority")?.let { updated = updated.copy(priority = parsePriority(it)) }
            if (updated == task) throw ToolError("Nothing to change: pass title, due or priority")
            tasks.update(updated)
            result("Updated “${updated.title}”" + (updated.dueLabel()?.let { " · $it" } ?: "")) {
                put("task", updated.toJson())
            }
        },
        tool(
            "delete_task", "Delete a task permanently.",
            ToolParam("task", ParamType.STRING, "The task's title (or part of it) or id"),
        ) { args ->
            val task = findTask(args.string("task"), includeDone = true)
            tasks.delete(task)
            result("Deleted “${task.title}”") { put("task", task.toJson()) }
        },
        tool(
            "add_habit", "Start tracking a daily habit.",
            ToolParam("name", ParamType.STRING, "The habit, e.g. 'Drink water' or 'Read 10 pages'"),
        ) { args ->
            val name = args.string("name").replaceFirstChar { it.uppercase() }
            val existing = habits.habits().firstOrNull { it.name.equals(name, ignoreCase = true) }
            if (existing != null) throw ToolError("Habit “${existing.name}” already exists")
            val id = habits.insert(HabitEntity(name = name, createdAt = System.currentTimeMillis()))
            result("Tracking habit “$name”") { put("habit_id", id) }
        },
        tool(
            "log_habit", "Mark a habit as done for today (or another day).",
            ToolParam("habit", ParamType.STRING, "The habit's name (or part of it)"),
            ToolParam("day", ParamType.STRING, "Which day, e.g. 'yesterday' (default: today)", required = false),
        ) { args ->
            val habit = findHabit(args.string("habit"))
            val day = args.stringOrNull("day")?.let(::parseDay) ?: now().toLocalDate()
            habits.log(HabitLogEntity(habit.id, day.toEpochDay()))
            val streak = HabitStats.streak(habits.days(habit.id).toSet(), now().toLocalDate().toEpochDay())
            result("Logged “${habit.name}” · $streak-day streak") { put("streak_days", streak) }
        },
        tool(
            "unlog_habit", "Undo marking a habit as done for a day.",
            ToolParam("habit", ParamType.STRING, "The habit's name (or part of it)"),
            ToolParam("day", ParamType.STRING, "Which day (default: today)", required = false),
        ) { args ->
            val habit = findHabit(args.string("habit"))
            val day = args.stringOrNull("day")?.let(::parseDay) ?: now().toLocalDate()
            habits.unlog(habit.id, day.toEpochDay())
            result("Unmarked “${habit.name}”") {}
        },
        tool("list_habits", "List habits with today's status and streaks.") {
            val today = now().toLocalDate().toEpochDay()
            val rows = habits.habits().map { habit ->
                val days = habits.days(habit.id).toSet()
                buildJsonObject {
                    put("name", habit.name)
                    put("done_today", today in days)
                    put("streak_days", HabitStats.streak(days, today))
                }
            }
            result("Listed ${rows.size} habit(s)") {
                putJsonArray("habits") { rows.forEach { add(it) } }
            }
        },
        tool(
            "delete_habit", "Stop tracking a habit and delete its history.",
            ToolParam("habit", ParamType.STRING, "The habit's name (or part of it)"),
        ) { args ->
            val habit = findHabit(args.string("habit"))
            habits.delete(habit)
            result("Deleted habit “${habit.name}”") {}
        },
    )

    private suspend fun findTask(query: String, includeDone: Boolean): TaskEntity {
        val candidates = tasks.all().filter { includeDone || !it.done }
        return when (val m = NameMatcher.match(candidates, query, { it.id }, { it.title })) {
            is MatchResult.Found -> m.item
            is MatchResult.Ambiguous -> throw ToolError(
                "Several tasks match '$query': ${m.candidates.joinToString { "“${it.title}” (id ${it.id})" }}. Ask the user which one.",
            )
            MatchResult.NotFound -> throw ToolError("No task matches '$query'")
        }
    }

    private suspend fun findHabit(query: String): HabitEntity =
        when (val m = NameMatcher.match(habits.habits(), query, { it.id }, { it.name })) {
            is MatchResult.Found -> m.item
            is MatchResult.Ambiguous -> throw ToolError(
                "Several habits match '$query': ${m.candidates.joinToString { "“${it.name}”" }}. Ask the user which one.",
            )
            MatchResult.NotFound -> throw ToolError("No habit matches '$query'")
        }

    private fun parseDue(text: String): ParsedDate =
        DateParser.parse(text, now()) ?: throw ToolError("Couldn't understand the date '$text'")

    private fun parseDay(text: String): LocalDate = when (text.trim().lowercase()) {
        "today" -> now().toLocalDate()
        "yesterday" -> now().toLocalDate().minusDays(1)
        else -> parseDue(text).dateTime.toLocalDate()
    }

    private fun parsePriority(text: String): Priority = when (text.lowercase()) {
        "high", "urgent", "important" -> Priority.HIGH
        "medium", "normal" -> Priority.MEDIUM
        "low" -> Priority.LOW
        else -> Priority.NONE
    }

    private fun TaskEntity.toJson() = buildJsonObject {
        put("id", id)
        put("title", title)
        dueLabel(now().toLocalDate())?.let { put("due", it) }
        if (priority != Priority.NONE) put("priority", priority.name.lowercase())
        put("done", done)
    }

    private companion object {
        val PRIORITIES = listOf("low", "medium", "high")
        val FILTERS = listOf("pending", "today", "overdue", "done", "all")
        val NO_DATE = setOf("none", "no date", "never", "clear", "remove")
        const val MAX_LISTED = 20
    }
}

private fun tool(
    name: String,
    description: String,
    vararg params: ToolParam,
    run: suspend (JsonObject) -> JsonElement,
): Tool = object : Tool {
    override val spec = ToolSpec(name, description, params.toList())
    override suspend fun execute(args: JsonObject) = run(args)
}

private inline fun result(summary: String, body: JsonObjectBuilder.() -> Unit): JsonObject = buildJsonObject {
    put("ok", true)
    put(SUMMARY_KEY, summary)
    body()
}

private fun JsonObject.stringOrNull(key: String): String? = this[key]?.jsonPrimitive?.contentOrNull?.takeIf { it.isNotBlank() }

private fun JsonObject.string(key: String): String = stringOrNull(key) ?: throw ToolError("Missing '$key'")
