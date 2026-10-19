package com.chotaai.app.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "tasks")
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val notes: String? = null,
    /** Epoch millis. When [dueHasTime] is false only the date part is meaningful. */
    val dueAt: Long? = null,
    val dueHasTime: Boolean = false,
    val priority: Priority = Priority.NONE,
    val done: Boolean = false,
    val createdAt: Long,
    val completedAt: Long? = null,
)

enum class Priority { NONE, LOW, MEDIUM, HIGH }

@Entity(tableName = "habits")
data class HabitEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
)

/** One row per day a habit was done. [day] is `LocalDate.toEpochDay()`. */
@Entity(
    tableName = "habit_logs",
    primaryKeys = ["habitId", "day"],
    foreignKeys = [ForeignKey(HabitEntity::class, ["id"], ["habitId"], onDelete = ForeignKey.CASCADE)],
    indices = [Index("habitId")],
)
data class HabitLogEntity(
    val habitId: Long,
    val day: Long,
)

/** A model file stored in the app's private `models/` directory. */
@Entity(tableName = "models")
data class ModelEntity(
    @PrimaryKey val fileName: String,
    val displayName: String,
    val engine: String,
    val sizeBytes: Long,
    val sourceUrl: String? = null,
    val addedAt: Long,
)
