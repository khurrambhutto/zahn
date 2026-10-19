package com.chotaai.app.ui.habits

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.LocalFireDepartment
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledIconToggleButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.chotaai.app.ui.components.EmptyState
import com.chotaai.app.ui.components.ScreenScaffold
import com.chotaai.app.ui.components.appViewModel

@Composable
fun HabitsScreen() {
    val vm = appViewModel { HabitsViewModel(it) }
    val habits by vm.habits.collectAsStateWithLifecycle()
    var adding by rememberSaveable { mutableStateOf(false) }

    ScreenScaffold(
        title = "Habits",
        subtitle = {
            habits?.takeIf { it.isNotEmpty() }?.let { rows ->
                Text(
                    "${rows.count { it.doneToday }} of ${rows.size} done today",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Outlined.Add, null) }, text = { Text("Habit") })
        },
    ) { padding ->
        val rows = habits ?: return@ScreenScaffold
        if (rows.isEmpty()) {
            EmptyState(
                Icons.Outlined.Repeat,
                "No habits yet",
                "Track something daily, or tell the assistant: “start a habit: read 10 pages”.",
                Modifier.padding(padding),
            )
        } else {
            LazyColumn(
                Modifier.padding(padding).fillMaxSize(),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(rows, key = { it.habit.id }) { row ->
                    HabitCard(row, onToggle = { vm.toggleToday(row) }, onDelete = { vm.delete(row.habit) })
                }
            }
        }
    }

    if (adding) {
        AddHabitDialog(onDismiss = { adding = false }, onAdd = {
            vm.add(it)
            adding = false
        })
    }
}

@Composable
private fun HabitCard(row: HabitRow, onToggle: () -> Unit, onDelete: () -> Unit) {
    var menu by rememberSaveable { mutableStateOf(false) }
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLowest, shape = RoundedCornerShape(16.dp)) {
        Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(row.habit.name, style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    WeekDots(row.week)
                    if (row.streak > 0) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Outlined.LocalFireDepartment, null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(16.dp))
                            Text("${row.streak}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.secondary)
                        }
                    }
                }
            }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Outlined.MoreVert, contentDescription = "More", tint = MaterialTheme.colorScheme.outline) }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Delete") },
                        leadingIcon = { Icon(Icons.Outlined.DeleteOutline, null) },
                        onClick = {
                            menu = false
                            onDelete()
                        },
                    )
                }
            }
            FilledIconToggleButton(checked = row.doneToday, onCheckedChange = { onToggle() }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Outlined.Check, contentDescription = if (row.doneToday) "Done today" else "Mark done today")
            }
        }
    }
}

@Composable
private fun WeekDots(week: List<Boolean>) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        week.forEachIndexed { i, done ->
            val isToday = i == week.lastIndex
            Box(
                Modifier
                    .size(if (isToday) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (done) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
            )
        }
    }
}

@Composable
private fun AddHabitDialog(onDismiss: () -> Unit, onAdd: (String) -> Unit) {
    var name by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("New daily habit") },
        text = { OutlinedTextField(name, { name = it }, label = { Text("Habit") }, placeholder = { Text("e.g. Read 10 pages") }, singleLine = true) },
        confirmButton = { TextButton(onClick = { onAdd(name) }, enabled = name.isNotBlank()) { Text("Add") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
