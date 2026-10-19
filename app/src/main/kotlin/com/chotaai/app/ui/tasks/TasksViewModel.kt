package com.chotaai.app.ui.tasks

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chotaai.app.AppContainer
import com.chotaai.app.ai.DateParser
import com.chotaai.app.data.Priority
import com.chotaai.app.data.TaskEntity
import com.chotaai.app.data.isOverdue
import com.chotaai.app.data.toEpochMillis
import com.chotaai.app.data.toLocalDateTime
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime

data class TaskSection(val title: String, val tasks: List<TaskEntity>)

class TasksViewModel(container: AppContainer) : ViewModel() {
    private val dao = container.db.tasks()

    val sections: StateFlow<List<TaskSection>?> = dao.observeAll()
        .map(::group)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggle(task: TaskEntity) = viewModelScope.launch {
        dao.update(task.copy(done = !task.done, completedAt = if (task.done) null else System.currentTimeMillis()))
    }

    fun delete(task: TaskEntity) = viewModelScope.launch { dao.delete(task) }

    fun clearCompleted() = viewModelScope.launch { dao.deleteCompleted() }

    fun add(title: String, dueText: String, priority: Priority) = viewModelScope.launch {
        val due = DateParser.parse(dueText)
        dao.insert(
            TaskEntity(
                title = title.trim().replaceFirstChar { it.uppercase() },
                dueAt = due?.dateTime?.toEpochMillis(),
                dueHasTime = due?.hasTime ?: false,
                priority = priority,
                createdAt = System.currentTimeMillis(),
            ),
        )
    }

    private fun group(tasks: List<TaskEntity>): List<TaskSection> {
        val now = LocalDateTime.now()
        val today = LocalDate.now()
        val (done, open) = tasks.partition { it.done }
        val overdue = open.filter { it.isOverdue(now) }
        val rest = open - overdue.toSet()
        val dueDate = { t: TaskEntity -> t.dueAt?.toLocalDateTime()?.toLocalDate() }
        return listOf(
            TaskSection("Overdue", overdue),
            TaskSection("Today", rest.filter { dueDate(it) == today }),
            TaskSection("Upcoming", rest.filter { dueDate(it)?.isAfter(today) == true }),
            TaskSection("Someday", rest.filter { it.dueAt == null }),
            TaskSection("Done", done.sortedByDescending { it.completedAt }),
        ).filter { it.tasks.isNotEmpty() }
    }
}
