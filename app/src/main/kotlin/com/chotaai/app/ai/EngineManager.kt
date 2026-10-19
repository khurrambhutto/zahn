package com.chotaai.app.ai

import com.chotaai.app.data.ModelEntity
import com.chotaai.app.data.Settings
import com.chotaai.llm.Accelerator
import com.chotaai.llm.EngineType
import com.chotaai.llm.LlmEngine
import com.chotaai.llm.ModelConfig
import com.chotaai.llm.litert.LiteRtEngine
import com.chotaai.llm.llamacpp.LlamaCppEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

sealed interface EngineState {
    data object NoModel : EngineState
    data class Loading(val model: ModelEntity) : EngineState
    data class Ready(val model: ModelEntity, val engine: LlmEngine, val loadMillis: Long) : EngineState
    data class Failed(val model: ModelEntity, val message: String) : EngineState
}

/**
 * Owns the one model that is loaded at a time, and picks the runtime from the file type:
 * `.gguf` → llama.cpp, `.litertlm` → LiteRT-LM.
 */
class EngineManager(
    private val modelsDir: File,
    private val nativeLibraryDir: String,
    private val cacheDir: File,
) {
    private val mutex = Mutex()
    private val _state = MutableStateFlow<EngineState>(EngineState.NoModel)
    val state: StateFlow<EngineState> = _state.asStateFlow()

    /** The settings the current model was loaded with; a change means a reload. */
    private var loadedWith: Pair<String, Settings>? = null

    suspend fun load(model: ModelEntity, settings: Settings) = mutex.withLock {
        val key = model.fileName to settings.copy(activeModel = null, temperature = 0f, hfToken = "")
        if (loadedWith == key && _state.value is EngineState.Ready) return@withLock

        unloadLocked()
        _state.value = EngineState.Loading(model)
        val engineType = EngineType.valueOf(model.engine)
        val engine: LlmEngine = when (engineType) {
            EngineType.LITERT -> LiteRtEngine(nativeLibraryDir, cacheDir.absolutePath)
            EngineType.LLAMA_CPP -> LlamaCppEngine(nativeLibraryDir)
        }
        val started = System.currentTimeMillis()
        try {
            engine.load(
                ModelConfig(
                    path = File(modelsDir, model.fileName).absolutePath,
                    contextSize = settings.contextSize,
                    accelerator = if (engineType == EngineType.LITERT) settings.accelerator else Accelerator.CPU,
                ),
            )
            loadedWith = key
            _state.value = EngineState.Ready(model, engine, System.currentTimeMillis() - started)
        } catch (e: CancellationException) {
            engine.close()
            _state.value = EngineState.NoModel
            throw e
        } catch (e: Throwable) {
            engine.close()
            _state.value = EngineState.Failed(model, e.message ?: e::class.java.simpleName)
        }
    }

    suspend fun unload() = mutex.withLock { unloadLocked() }

    private fun unloadLocked() {
        (_state.value as? EngineState.Ready)?.engine?.close()
        loadedWith = null
        _state.value = EngineState.NoModel
    }
}
