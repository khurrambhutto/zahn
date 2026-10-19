package com.chotaai.app.data

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TaskDao {
    @Query("SELECT * FROM tasks ORDER BY done, dueAt IS NULL, dueAt, priority DESC, createdAt")
    fun observeAll(): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks ORDER BY done, dueAt IS NULL, dueAt, priority DESC, createdAt")
    suspend fun all(): List<TaskEntity>

    @Query("SELECT * FROM tasks WHERE id = :id")
    suspend fun get(id: Long): TaskEntity?

    @Insert
    suspend fun insert(task: TaskEntity): Long

    @Update
    suspend fun update(task: TaskEntity)

    @Delete
    suspend fun delete(task: TaskEntity)

    @Query("DELETE FROM tasks WHERE done = 1")
    suspend fun deleteCompleted()
}

@Dao
interface HabitDao {
    @Query("SELECT * FROM habits ORDER BY createdAt")
    fun observeHabits(): Flow<List<HabitEntity>>

    @Query("SELECT * FROM habits ORDER BY createdAt")
    suspend fun habits(): List<HabitEntity>

    @Query("SELECT * FROM habit_logs")
    fun observeLogs(): Flow<List<HabitLogEntity>>

    @Query("SELECT day FROM habit_logs WHERE habitId = :habitId")
    suspend fun days(habitId: Long): List<Long>

    @Insert
    suspend fun insert(habit: HabitEntity): Long

    @Delete
    suspend fun delete(habit: HabitEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun log(entry: HabitLogEntity)

    @Query("DELETE FROM habit_logs WHERE habitId = :habitId AND day = :day")
    suspend fun unlog(habitId: Long, day: Long)
}

@Dao
interface ModelDao {
    @Query("SELECT * FROM models ORDER BY addedAt DESC")
    fun observeAll(): Flow<List<ModelEntity>>

    @Query("SELECT * FROM models WHERE fileName = :fileName")
    suspend fun get(fileName: String): ModelEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(model: ModelEntity)

    @Delete
    suspend fun delete(model: ModelEntity)
}
