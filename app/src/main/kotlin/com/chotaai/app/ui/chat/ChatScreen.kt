package com.chotaai.app.ui.chat

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chotaai.app.ai.EngineState
import com.chotaai.app.ui.components.EmptyState
import com.chotaai.app.ui.components.ScreenScaffold
import com.chotaai.app.ui.components.appViewModel
import com.chotaai.llm.EngineType

private val suggestions = listOf(
    "Add buy milk tomorrow at 5pm",
    "What's on my list today?",
    "Start a habit: drink 8 glasses of water",
    "I drank my water today",
)

@Composable
fun ChatScreen(onOpenModels: () -> Unit) {
    val vm = appViewModel { ChatViewModel(it) }
    val state by vm.state.collectAsStateWithLifecycle()

    ScreenScaffold(
        title = "Assistant",
        subtitle = { EngineStatus(state.engine) },
        actions = {
            if (state.engine is EngineState.Ready) {
                IconButton(onClick = vm::newChat) { Icon(Icons.Outlined.AddComment, contentDescription = "New chat") }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().imePadding()) {
            Box(Modifier.weight(1f)) {
                when (val engine = state.engine) {
                    EngineState.NoModel -> EmptyState(
                        Icons.Outlined.Memory,
                        "No model loaded",
                        "Download a small model or import your own .gguf or .litertlm file. Everything runs on this phone.",
                    ) { Button(onClick = onOpenModels) { Text("Choose a model") } }
                    is EngineState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                            CircularProgressIndicator()
                            Text("Loading ${engine.model.displayName}…", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    is EngineState.Failed -> EmptyState(
                        Icons.Outlined.ErrorOutline,
                        "Couldn't load the model",
                        engine.message,
                    ) { Button(onClick = onOpenModels) { Text("Open models") } }
                    is EngineState.Ready ->
                        if (state.items.isEmpty()) Suggestions(onPick = vm::send) else Messages(state.items)
                }
            }
            InputBar(
                enabled = state.engine is EngineState.Ready,
                generating = state.generating,
                onSend = vm::send,
                onStop = vm::stop,
            )
        }
    }
}

@Composable
private fun EngineStatus(engine: EngineState) {
    val (dot, text) = when (engine) {
        EngineState.NoModel -> MaterialTheme.colorScheme.outline to "No model"
        is EngineState.Loading -> MaterialTheme.colorScheme.secondary to "Loading ${engine.model.displayName}"
        is EngineState.Failed -> MaterialTheme.colorScheme.error to "Failed: ${engine.model.displayName}"
        is EngineState.Ready -> MaterialTheme.colorScheme.primary to
            "${engine.model.displayName} · ${EngineType.valueOf(engine.model.engine).displayName}"
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(7.dp).clip(CircleShape).background(dot))
        Text(
            text,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Suggestions(onPick: (String) -> Unit) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Bottom,
    ) {
        Text("What can I do for you?", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.size(6.dp))
        Text(
            "I manage your tasks and habits, entirely offline.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.size(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            suggestions.forEach { SuggestionChip(onClick = { onPick(it) }, label = { Text(it) }) }
        }
    }
}

@Composable
private fun Messages(items: List<ChatItem>) {
    val listState = rememberLazyListState()
    val lastText = (items.lastOrNull() as? ChatItem.Assistant)?.text?.length ?: 0
    LaunchedEffect(items.size, lastText) {
        if (items.isNotEmpty()) listState.animateScrollToItem(items.lastIndex)
    }
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize(),
    ) {
        items(items, key = { it.id }) { item ->
            when (item) {
                is ChatItem.User -> UserBubble(item.text)
                is ChatItem.Assistant -> AssistantBubble(item)
                is ChatItem.ToolActivity -> ToolRow(item)
                is ChatItem.Notice -> Text(
                    item.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                )
            }
        }
    }
}

@Composable
private fun UserBubble(text: String) {
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
        Surface(
            color = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary,
            shape = RoundedCornerShape(18.dp, 18.dp, 4.dp, 18.dp),
            modifier = Modifier.widthIn(max = 300.dp),
        ) {
            Text(text, Modifier.padding(horizontal = 14.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyLarge)
        }
    }
}

@Composable
private fun AssistantBubble(item: ChatItem.Assistant) {
    Column(Modifier.fillMaxWidth().animateContentSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        if (item.reasoning.isNotBlank()) {
            Text(
                item.reasoning.trim(),
                style = MaterialTheme.typography.bodySmall.copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = if (item.streaming && item.text.isEmpty()) 6 else 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            shape = RoundedCornerShape(18.dp, 18.dp, 18.dp, 4.dp),
            modifier = Modifier.widthIn(max = 320.dp),
        ) {
            Text(
                item.text.trim().ifEmpty { "…" } + if (item.streaming && item.text.isNotEmpty()) " ▍" else "",
                Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                style = MaterialTheme.typography.bodyLarge,
            )
        }
        item.stats?.let {
            Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline, modifier = Modifier.padding(start = 6.dp))
        }
    }
}

@Composable
private fun ToolRow(item: ChatItem.ToolActivity) {
    var expanded by rememberSaveable(item.id) { mutableStateOf(false) }
    val running = item.summary == null
    val accent = if (item.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).clickable { expanded = !expanded },
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp).animateContentSize()) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                when {
                    running -> CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    item.isError -> Icon(Icons.Outlined.ErrorOutline, null, tint = accent, modifier = Modifier.size(18.dp))
                    else -> Icon(Icons.Outlined.CheckCircle, null, tint = accent, modifier = Modifier.size(18.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(item.summary ?: "Running…", style = MaterialTheme.typography.bodyMedium)
                    Text(item.toolName, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
                }
                Icon(Icons.Outlined.Bolt, null, tint = MaterialTheme.colorScheme.outline, modifier = Modifier.size(16.dp))
            }
            if (expanded) {
                Text(
                    "${item.toolName}(${item.arguments})",
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

@Composable
private fun InputBar(enabled: Boolean, generating: Boolean, onSend: (String) -> Unit, onStop: () -> Unit) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onSend(text)
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            enabled = enabled,
            placeholder = { Text(if (enabled) "Ask or tell me something…" else "Load a model first") },
            shape = RoundedCornerShape(24.dp),
            maxLines = 4,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
            keyboardActions = KeyboardActions(onSend = { if (!generating) submit() }),
            colors = OutlinedTextFieldDefaults.colors(
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerLowest,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
            modifier = Modifier.weight(1f),
        )
        if (generating) {
            FilledIconButton(onClick = onStop) { Icon(Icons.Outlined.Stop, contentDescription = "Stop") }
        } else {
            FilledIconButton(onClick = submit, enabled = enabled && text.isNotBlank()) {
                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send")
            }
        }
    }
}
