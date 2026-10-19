package com.chotaai.app.ui.models

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.chotaai.app.AppContainer
import com.chotaai.app.ai.EngineState
import com.chotaai.app.data.ModelEntity
import com.chotaai.app.data.Settings
import com.chotaai.app.models.CatalogModel
import com.chotaai.app.models.ModelCatalog
import com.chotaai.app.models.Transfer
import com.chotaai.llm.Accelerator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class ModelsUiState(
    val installed: List<ModelEntity> = emptyList(),
    val transfers: Map<String, Transfer> = emptyMap(),
    val engine: EngineState = EngineState.NoModel,
    val settings: Settings = Settings(),
    val message: String? = null,
) {
    val catalog: List<CatalogModel> get() = ModelCatalog.models
    fun isInstalled(fileName: String) = installed.any { it.fileName == fileName }
}

class ModelsViewModel(private val container: AppContainer) : ViewModel() {
    private val store = container.modelStore
    private val message = MutableStateFlow<String?>(null)

    val state: StateFlow<ModelsUiState> = combine(
        store.models,
        store.transfers,
        container.engineManager.state,
        container.settings.settings,
        message,
    ) { installed, transfers, engine, settings, msg ->
        ModelsUiState(installed, transfers, engine, settings, msg)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), ModelsUiState())

    fun activate(model: ModelEntity) = viewModelScope.launch { container.settings.setActiveModel(model.fileName) }

    fun deactivate() = viewModelScope.launch { container.settings.setActiveModel(null) }

    fun delete(model: ModelEntity) = viewModelScope.launch { store.delete(model) }

    fun download(model: CatalogModel) = show(store.download(model.url, model.name))

    fun downloadUrl(url: String): Boolean {
        val error = store.download(url)
        show(error)
        return error == null
    }

    fun import(uri: Uri) = show(store.import(uri))

    fun cancel(fileName: String) = store.cancel(fileName)

    fun dismissTransfer(fileName: String) = store.dismissError(fileName)

    fun dismissMessage() {
        message.value = null
    }

    fun setAccelerator(value: Accelerator) = viewModelScope.launch { container.settings.setAccelerator(value) }
    fun setContextSize(value: Int) = viewModelScope.launch { container.settings.setContextSize(value) }
    fun setTemperature(value: Float) = viewModelScope.launch { container.settings.setTemperature(value) }
    fun setHfToken(value: String) = viewModelScope.launch { container.settings.setHfToken(value) }

    private fun show(error: String?) {
        if (error != null) message.value = error
    }
}
