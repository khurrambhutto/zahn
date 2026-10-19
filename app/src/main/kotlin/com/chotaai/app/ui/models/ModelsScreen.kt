package com.chotaai.app.ui.models

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.FileOpen
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chotaai.app.ai.EngineState
import com.chotaai.app.data.ModelEntity
import com.chotaai.app.models.CatalogModel
import com.chotaai.app.models.Transfer
import com.chotaai.app.ui.components.ScreenScaffold
import com.chotaai.app.ui.components.Tag
import com.chotaai.app.ui.components.appViewModel
import com.chotaai.app.ui.components.formatBytes
import com.chotaai.llm.Accelerator
import com.chotaai.llm.EngineType

@Composable
fun ModelsScreen() {
    val vm = appViewModel { ModelsViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmDelete by remember { mutableStateOf<ModelEntity?>(null) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::import) }

    ScreenScaffold(title = "Models") { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            state.message?.let { msg ->
                item(key = "message") {
                    Surface(color = MaterialTheme.colorScheme.errorContainer, shape = RoundedCornerShape(12.dp)) {
                        Row(Modifier.fillMaxWidth().padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(msg, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                            IconButton(onClick = vm::dismissMessage) { Icon(Icons.Outlined.Close, "Dismiss") }
                        }
                    }
                }
            }

            item(key = "h-installed") { Header("On this phone") }
            if (state.installed.isEmpty()) {
                item(key = "none") {
                    Text(
                        "No models yet. Download one below, or import a .gguf / .litertlm file.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(state.installed, key = { "m-" + it.fileName }) { model ->
                InstalledModelRow(
                    model = model,
                    active = state.settings.activeModel == model.fileName,
                    engine = state.engine,
                    onSelect = { if (state.settings.activeModel == model.fileName) vm.deactivate() else vm.activate(model) },
                    onDelete = { confirmDelete = model },
                )
            }
            items(state.transfers.values.toList(), key = { "t-" + it.fileName }) { t ->
                TransferRow(t, onCancel = { vm.cancel(t.fileName) }, onDismiss = { vm.dismissTransfer(t.fileName) })
            }

            item(key = "h-catalog") { Header("Get a model") }
            items(state.catalog, key = { "c-" + it.file }) { model ->
                CatalogRow(
                    model = model,
                    installed = state.isInstalled(model.file),
                    downloading = model.file in state.transfers,
                    onDownload = { vm.download(model) },
                )
            }

            item(key = "h-own") { Header("Add your own") }
            item(key = "own") { AddOwnModel(onDownloadUrl = vm::downloadUrl, onImport = { picker.launch(arrayOf("*/*")) }) }

            item(key = "h-settings") { Header("Settings") }
            item(key = "settings") {
                SettingsCard(
                    accelerator = state.settings.accelerator,
                    contextSize = state.settings.contextSize,
                    temperature = state.settings.temperature,
                    hfToken = state.settings.hfToken,
                    onAccelerator = vm::setAccelerator,
                    onContextSize = vm::setContextSize,
                    onTemperature = vm::setTemperature,
                    onHfToken = vm::setHfToken,
                )
            }
        }
    }

    confirmDelete?.let { model ->
        AlertDialog(
            onDismissRequest = { confirmDelete = null },
            title = { Text("Delete ${model.displayName}?") },
            text = { Text("Frees ${formatBytes(model.sizeBytes)}. You can download it again later.") },
            confirmButton = {
                TextButton(onClick = {
                    vm.delete(model)
                    confirmDelete = null
                }) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Header(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 20.dp, bottom = 2.dp),
    )
}

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier.fillMaxWidth(),
        content = content,
    )
}

@Composable
private fun EngineTag(engine: EngineType) = Tag(
    engine.displayName,
    container = if (engine == EngineType.LITERT) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.primaryContainer,
    content = if (engine == EngineType.LITERT) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onPrimaryContainer,
)

@Composable
private fun InstalledModelRow(model: ModelEntity, active: Boolean, engine: EngineState, onSelect: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.clickable(onClick = onSelect)) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected = active, onClick = onSelect)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.displayName, style = MaterialTheme.typography.titleMedium)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                    EngineTag(EngineType.valueOf(model.engine))
                    Tag(formatBytes(model.sizeBytes))
                }
                if (active) {
                    val status = when (engine) {
                        is EngineState.Loading -> "Loading…"
                        is EngineState.Ready -> "Ready · loaded in %.1f s".format(engine.loadMillis / 1000.0)
                        is EngineState.Failed -> "Failed: ${engine.message}"
                        EngineState.NoModel -> null
                    }
                    status?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (engine is EngineState.Failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                        )
                    }
                }
            }
            if (active && engine is EngineState.Loading) {
                CircularProgressIndicator(Modifier.size(20.dp).padding(end = 4.dp), strokeWidth = 2.dp)
            }
            IconButton(onClick = onDelete) { Icon(Icons.Outlined.DeleteOutline, "Delete", tint = MaterialTheme.colorScheme.outline) }
        }
    }
}

@Composable
private fun TransferRow(t: Transfer, onCancel: () -> Unit, onDismiss: () -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.fileName, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                if (t.error == null) {
                    TextButton(onClick = onCancel) { Text("Cancel") }
                } else {
                    IconButton(onClick = onDismiss) { Icon(Icons.Outlined.Close, "Dismiss") }
                }
            }
            if (t.error != null) {
                Text(t.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            } else {
                val progress = t.progress
                if (progress != null) LinearProgressIndicator(progress = { progress }, Modifier.fillMaxWidth())
                else LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(
                    formatBytes(t.bytes) + (t.totalBytes?.let { " of ${formatBytes(it)}" } ?: ""),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
private fun CatalogRow(model: CatalogModel, installed: Boolean, downloading: Boolean, onDownload: () -> Unit) {
    Card {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(model.name, style = MaterialTheme.typography.titleMedium)
                Text(model.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    EngineTag(model.engine)
                    Tag(formatBytes(model.sizeBytes))
                }
            }
            Spacer(Modifier.size(8.dp))
            when {
                installed -> Text("Installed", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                downloading -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                else -> IconButton(onClick = onDownload) { Icon(Icons.Outlined.Download, "Download") }
            }
        }
    }
}

@Composable
private fun AddOwnModel(onDownloadUrl: (String) -> Boolean, onImport: () -> Unit) {
    var url by rememberSaveable { mutableStateOf("") }
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(
                url,
                { url = it },
                label = { Text("Hugging Face file URL") },
                placeholder = { Text("https://huggingface.co/user/repo/resolve/main/model.gguf") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { if (onDownloadUrl(url)) url = "" }, enabled = url.isNotBlank()) {
                    Icon(Icons.Outlined.Download, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Download")
                }
                OutlinedButton(onClick = onImport) {
                    Icon(Icons.Outlined.FileOpen, null, Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Import file")
                }
            }
            Text(
                "Use .gguf for llama.cpp (most Hugging Face models) or .litertlm for LiteRT-LM.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingsCard(
    accelerator: Accelerator,
    contextSize: Int,
    temperature: Float,
    hfToken: String,
    onAccelerator: (Accelerator) -> Unit,
    onContextSize: (Int) -> Unit,
    onTemperature: (Float) -> Unit,
    onHfToken: (String) -> Unit,
) {
    var temp by remember(temperature) { mutableFloatStateOf(temperature) }
    var token by rememberSaveable(hfToken) { mutableStateOf(hfToken) }
    LaunchedEffect(token) { if (token != hfToken) onHfToken(token) }

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Setting("LiteRT-LM accelerator", "GPU is often faster; not every phone supports it. llama.cpp uses the CPU.") {
                val options = listOf(Accelerator.CPU, Accelerator.GPU)
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    options.forEachIndexed { i, option ->
                        SegmentedButton(
                            selected = accelerator == option,
                            onClick = { onAccelerator(option) },
                            shape = SegmentedButtonDefaults.itemShape(i, options.size),
                        ) { Text(option.name) }
                    }
                }
            }
            Setting("Context size", "How much conversation the model remembers. Bigger uses more memory.") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(2048, 4096, 8192).forEach { size ->
                        FilterChip(selected = contextSize == size, onClick = { onContextSize(size) }, label = { Text("${size / 1024}K") })
                    }
                }
            }
            Setting("Temperature  %.2f".format(temp), "Low is precise and repeatable, which suits tool calling.") {
                Slider(value = temp, onValueChange = { temp = it }, onValueChangeFinished = { onTemperature(temp) }, valueRange = 0f..1f)
            }
            Setting("Hugging Face token", "Only needed for gated models. Stored on this phone.") {
                OutlinedTextField(
                    token,
                    { token = it },
                    placeholder = { Text("hf_…") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

@Composable
private fun Setting(title: String, help: String, control: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Text(help, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        control()
    }
}
