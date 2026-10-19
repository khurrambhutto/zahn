package com.chotaai.app.ui.tasks

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.DeleteSweep
import androidx.compose.material.icons.outlined.Flag
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chotaai.app.ai.DateParser
import com.chotaai.app.data.Priority
import com.chotaai.app.data.TaskEntity
import com.chotaai.app.data.dueLabel
import com.chotaai.app.data.isOverdue
import com.chotaai.app.data.toEpochMillis
import com.chotaai.app.ui.components.EmptyState
import com.chotaai.app.ui.components.ScreenScaffold
import com.chotaai.app.ui.components.appViewModel

@Composable
fun TasksScreen() {
    val vm = appViewModel { TasksViewModel(it) }
    val sections by vm.sections.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = "Tasks",
        actions = {
            if (sections?.any { it.title == "Done" } == true) {
                IconButton(onClick = vm::clearCompleted) { Icon(Icons.Outlined.DeleteSweep, contentDescription = "Clear completed") }
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Task") })
        },
    ) { padding ->
        val list = sections ?: return@ScreenScaffold
        if (list.isEmpty()) {
            EmptyState(
                Icons.Outlined.CheckCircle,
                "Nothing to do",
                "Add a task here, or just tell the assistant: “remind me to call mom tomorrow”.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 96.dp),
            ) {
                for (section in list) {
                    item(key = "header-${section.title}") { SectionHeader(section.title, section.tasks.size) }
                    items(section.tasks, key = { it.id }) { task ->
                        TaskRow(task, onToggle = { vm.toggle(task) }, onDelete = { vm.delete(task) })
                    }
                }
            }
        }
    }

    if (adding) {
        AddTaskDialog(onDismiss = { adding = false }, onAdd = { title, due, priority ->
            vm.add(title, due, priority)
            adding = false
        })
    }
}

@Composable
private fun SectionHeader(title: String, count: Int) {
    Row(Modifier.padding(top = 20.dp, bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(),
            style = MaterialTheme.typography.labelSmall,
            color = if (title == "Overdue") MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text("  $count", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun TaskRow(task: TaskEntity, onToggle: () -> Unit, onDelete: () -> Unit) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp),
    ) {
        Row(Modifier.padding(start = 4.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = task.done, onCheckedChange = { onToggle() })
            Column(Modifier.weight(1f).padding(vertical = 10.dp)) {
                Text(
                    task.title,
                    style = MaterialTheme.typography.bodyLarge,
                    textDecoration = if (task.done) TextDecoration.LineThrough else null,
                    color = if (task.done) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.onSurface,
                )
                val due = task.dueLabel()
                if (due != null || task.priority != Priority.NONE) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                        due?.let {
                            Text(
                                it,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (task.isOverdue()) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (task.priority != Priority.NONE) PriorityFlag(task.priority)
                    }
                }
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Outlined.DeleteOutline, contentDescription = "Delete", tint = MaterialTheme.colorScheme.outline)
            }
        }
    }
}

@Composable
private fun PriorityFlag(priority: Priority) {
    val color = when (priority) {
        Priority.HIGH -> MaterialTheme.colorScheme.error
        Priority.MEDIUM -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Flag, null, tint = color, modifier = Modifier.size(14.dp))
        Text(priority.name.lowercase(), style = MaterialTheme.typography.bodySmall, color = color)
    }
}

@Composable
private fun AddTaskDialog(onDismiss: () -> Unit, onAdd: (String, String, Priority) -> Unit) {
    var title by rememberSaveable { mutableStateOf("") }
    var due by rememberSaveable { mutableStateOf("") }
    var priority by rememberSaveable { mutableStateOf(Priority.NONE) }
    val parsed = remember(due) { DateParser.parse(due) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New task") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(title, { title = it }, label = { Text("Title") }, singleLine = true)
                OutlinedTextField(
                    due,
                    { due = it },
                    label = { Text("Due (optional)") },
                    placeholder = { Text("e.g. fri 9am, in 2 days") },
                    singleLine = true,
                    supportingText = {
                        when {
                            due.isBlank() -> Unit
                            parsed == null -> Text("Not understood", color = MaterialTheme.colorScheme.error)
                            else -> Text(
                                TaskEntity(title = "", dueAt = parsed.dateTime.toEpochMillis(), dueHasTime = parsed.hasTime, createdAt = 0)
                                    .dueLabel().orEmpty(),
                            )
                        }
                    },
                )
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(Priority.LOW, Priority.MEDIUM, Priority.HIGH).forEach { p ->
                        FilterChip(
                            selected = priority == p,
                            onClick = { priority = if (priority == p) Priority.NONE else p },
                            label = { Text(p.name.lowercase()) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onAdd(title, due, priority) }, enabled = title.isNotBlank() && (due.isBlank() || parsed != null)) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
