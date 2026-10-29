package io.github.rgitha.spritz.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.data.Naming
import io.github.rgitha.spritz.data.SavedDevice

/** A name as typed, tidied: trimmed with single spaces. */
fun cleanName(name: String) = name.trim().replace(Regex("\\s+"), " ")

/**
 * The name field, with the greyed number that keeps it unique, and the room picker:
 * no room, a room in use, or a new one. [others] are every saved device except this one.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameRoomFields(
    name: String,
    onName: (String) -> Unit,
    room: String?,
    onRoom: (String?) -> Unit,
    others: List<SavedDevice>,
    initialRoom: String?,
) {
    /** True while typing a new room name instead of picking one. */
    var newRoom by remember { mutableStateOf(false) }
    var roomText by remember { mutableStateOf("") }
    var roomsOpen by remember { mutableStateOf(false) }

    val rooms = Naming.rooms(others).let { existing ->
        if (initialRoom != null && existing.none { Naming.normalize(it) == Naming.normalize(initialRoom) }) {
            (existing + initialRoom).sortedWith(java.text.Collator.getInstance())
        } else existing
    }
    val base = cleanName(name)
    val suffix = if (base.isEmpty()) null else Naming.suffixFor(base, others)

    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        OutlinedTextField(
            value = name,
            onValueChange = { onName(it.take(Naming.MAX_NAME)) },
            label = { Text("Name") },
            singleLine = true,
            // The number keeping the name unique: shown, not editable.
            suffix = suffix?.let { { Text(" ($it)", color = MaterialTheme.colorScheme.outline) } },
            modifier = Modifier.fillMaxWidth(),
        )
        if (newRoom) {
            OutlinedTextField(
                value = roomText,
                onValueChange = {
                    roomText = it.take(Naming.MAX_NAME)
                    onRoom(cleanName(roomText).ifEmpty { null })
                },
                label = { Text("New room") },
                singleLine = true,
                trailingIcon = {
                    IconButton(onClick = { newRoom = false; roomText = ""; onRoom(initialRoom) }) {
                        Icon(Icons.Default.Close, contentDescription = "Pick an existing room")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )
        } else {
            ExposedDropdownMenuBox(expanded = roomsOpen, onExpandedChange = { roomsOpen = it }) {
                OutlinedTextField(
                    value = room ?: "No room",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Room") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = roomsOpen) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
                )
                ExposedDropdownMenu(expanded = roomsOpen, onDismissRequest = { roomsOpen = false }) {
                    DropdownMenuItem(text = { Text("No room") }, onClick = { onRoom(null); roomsOpen = false })
                    rooms.forEach { r ->
                        DropdownMenuItem(text = { Text(r) }, onClick = { onRoom(r); roomsOpen = false })
                    }
                    DropdownMenuItem(
                        text = { Text("New room…", color = MaterialTheme.colorScheme.primary) },
                        leadingIcon = { Icon(Icons.Default.Add, contentDescription = null) },
                        onClick = { newRoom = true; roomsOpen = false; onRoom(null) },
                    )
                }
            }
        }
    }
}

/** Names a device and picks its room when it's added. */
@Composable
fun SetupDialog(
    address: String,
    initialName: String,
    others: List<SavedDevice>,
    onDismiss: () -> Unit,
    onConfirm: (name: String, room: String?) -> Unit,
) {
    var name by remember { mutableStateOf(initialName.take(Naming.MAX_NAME)) }
    var room by remember { mutableStateOf<String?>(null) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text("Set up device")
                Text(
                    "ID $address",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        text = { NameRoomFields(name, { name = it }, room, { room = it }, others, initialRoom = null) },
        confirmButton = {
            TextButton(enabled = cleanName(name).isNotEmpty(), onClick = { onConfirm(cleanName(name), room) }) {
                Text("Add")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
