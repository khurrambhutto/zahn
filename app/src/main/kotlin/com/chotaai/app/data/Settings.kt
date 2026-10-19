package com.chotaai.app.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.chotaai.llm.Accelerator
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class Settings(
    val activeModel: String? = null,
    /** LiteRT-LM only; llama.cpp always runs on the CPU here. */
    val accelerator: Accelerator = Accelerator.CPU,
    val contextSize: Int = 4096,
    val temperature: Float = 0.2f,
    val hfToken: String = "",
)

private val Context.dataStore by preferencesDataStore("settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val activeModel = stringPreferencesKey("active_model")
        val accelerator = stringPreferencesKey("accelerator")
        val contextSize = intPreferencesKey("context_size")
        val temperature = floatPreferencesKey("temperature")
        val hfToken = stringPreferencesKey("hf_token")
    }

    val settings: Flow<Settings> = context.dataStore.data.map { p ->
        val defaults = Settings()
        Settings(
            activeModel = p[Keys.activeModel],
            accelerator = p[Keys.accelerator]?.let { runCatching { Accelerator.valueOf(it) }.getOrNull() }
                ?: defaults.accelerator,
            contextSize = p[Keys.contextSize] ?: defaults.contextSize,
            temperature = p[Keys.temperature] ?: defaults.temperature,
            hfToken = p[Keys.hfToken] ?: defaults.hfToken,
        )
    }

    suspend fun current(): Settings = settings.first()

    suspend fun setActiveModel(fileName: String?) = context.dataStore.edit {
        if (fileName == null) it.remove(Keys.activeModel) else it[Keys.activeModel] = fileName
    }

    suspend fun setAccelerator(value: Accelerator) = context.dataStore.edit { it[Keys.accelerator] = value.name }
    suspend fun setContextSize(value: Int) = context.dataStore.edit { it[Keys.contextSize] = value }
    suspend fun setTemperature(value: Float) = context.dataStore.edit { it[Keys.temperature] = value }
    suspend fun setHfToken(value: String) = context.dataStore.edit { it[Keys.hfToken] = value.trim() }
}
