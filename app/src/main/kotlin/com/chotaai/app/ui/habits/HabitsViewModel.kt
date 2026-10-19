package com.chotaai.app.ui.habits

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chotaai.app.AppContainer
import com.chotaai.app.data.HabitEntity
import com.chotaai.app.data.HabitLogEntity
import com.chotaai.app.data.HabitStats
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

data class HabitRow(
    val habit: HabitEntity,
    val doneToday: Boolean,
    val streak: Int,
    /** Last 7 days, oldest first; true = done. */
    val week: List<Boolean>,
)

class HabitsViewModel(container: AppContainer) : ViewModel() {
    private val dao = container.db.habits()

    val habits: StateFlow<List<HabitRow>?> = combine(dao.observeHabits(), dao.observeLogs()) { habits, logs ->
        val today = LocalDate.now().toEpochDay()
        val byHabit = logs.groupBy({ it.habitId }, { it.day }).mapValues { it.value.toSet() }
        habits.map { habit ->
            val days = byHabit[habit.id].orEmpty()
            HabitRow(
                habit = habit,
                doneToday = today in days,
                streak = HabitStats.streak(days, today),
                week = (6 downTo 0).map { today - it in days },
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun toggleToday(row: HabitRow) = viewModelScope.launch {
        val today = LocalDate.now().toEpochDay()
        if (row.doneToday) dao.unlog(row.habit.id, today) else dao.log(HabitLogEntity(row.habit.id, today))
    }

    fun add(name: String) = viewModelScope.launch {
        dao.insert(HabitEntity(name = name.trim().replaceFirstChar { it.uppercase() }, createdAt = System.currentTimeMillis()))
    }

    fun delete(habit: HabitEntity) = viewModelScope.launch { dao.delete(habit) }
}
