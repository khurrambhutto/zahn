package com.chotaai.app

import android.app.Application
import android.content.Context
import com.chotaai.app.ai.AssistantTools
import com.chotaai.app.ai.EngineManager
import com.chotaai.app.data.AppDatabase
import com.chotaai.app.data.SettingsRepository
import com.chotaai.app.models.ModelStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class ChotaApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

/** Manual dependency injection: one instance of each long-lived object. */
class AppContainer(context: Context) {
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val db = AppDatabase.create(context)
    val settings = SettingsRepository(context)
    val modelStore = ModelStore(context, db.models(), settings, appScope)
    val engineManager = EngineManager(
        modelsDir = modelStore.dir,
        nativeLibraryDir = context.applicationInfo.nativeLibraryDir,
        cacheDir = context.cacheDir,
    )
    val tools = AssistantTools(db.tasks(), db.habits())

    init {
        // Keep the loaded model in sync with the chosen model and the settings that affect loading.
        appScope.launch {
            settings.settings
                .map { it.copy(temperature = 0f, hfToken = "") }
                .distinctUntilChanged()
                .collectLatest { s ->
                    val model = s.activeModel?.let { db.models().get(it) }
                    if (model == null) engineManager.unload() else engineManager.load(model, s)
                }
        }
    }
}

val Context.container: AppContainer get() = (applicationContext as ChotaApp).container
