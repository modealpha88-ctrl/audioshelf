package com.mohamed.audioshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.mohamed.audioshelf.data.SpineBindingMode
import com.mohamed.audioshelf.data.SpineBindingStore
import com.mohamed.audioshelf.model.AudioBook
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Book-specific choices never change the cover, audio or the listening position. */
@Composable
internal fun SpineAppearanceDialog(
    book: AudioBook,
    revision: Int,
    onDismiss: () -> Unit,
    onMode: (SpineBindingMode) -> Unit,
    onPickCustom: () -> Unit,
) {
    val context = LocalContext.current
    val store = remember(context) { SpineBindingStore(context) }
    val mode = remember(book.sourceId, book.id, revision) { store.mode(book) }
    val hasCustom = remember(book.sourceId, book.id, revision) {
        store.customArtwork(book) != null
    }
    var hasOriginal by remember(book.sourceId, book.id) { mutableStateOf(false) }
    LaunchedEffect(book.sourceId, book.id) {
        hasOriginal = withContext(Dispatchers.IO) { store.bundledArtwork(book) != null }
    }
    val options = listOf(
        Triple(SpineBindingMode.AUTOMATIC, "Automatic (recommended)",
            "Real spine when supplied; otherwise an original decorative binding."),
        Triple(SpineBindingMode.ORIGINAL, "Supplied edition spine",
            "Only if the audiobook includes an identifiable spine image."),
        Triple(SpineBindingMode.DECORATIVE, "Decorative binding",
            "Original clothbound ornaments, with colours from the front cover."),
        Triple(SpineBindingMode.CLASSIC, "Classic cloth",
            "Quiet printed cloth, readable title and no illustrative pattern."),
        Triple(SpineBindingMode.CUSTOM, "My spine artwork",
            "Show an image you've selected from your device."),
    )
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Book appearance") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Changes apply only to this audiobook. New books use Automatic.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 7.dp),
                )
                for ((choice, title, description) in options) {
                    val enabled = when (choice) {
                        SpineBindingMode.ORIGINAL -> hasOriginal
                        SpineBindingMode.CUSTOM -> hasCustom
                        else -> true
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth()
                            .clickable(enabled = enabled, role = Role.RadioButton) {
                                onMode(choice)
                                onDismiss()
                            }
                            .padding(vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = mode == choice, onClick = null, enabled = enabled)
                        Spacer(Modifier.width(8.dp))
                        Column {
                            Text(title, style = MaterialTheme.typography.bodyMedium,
                                color = if (enabled) MaterialTheme.colorScheme.onSurface
                                else MaterialTheme.colorScheme.onSurface.copy(alpha = .45f))
                            Text(
                                if (choice == SpineBindingMode.ORIGINAL && !hasOriginal)
                                    "No matching spine scan was found in these files."
                                else if (choice == SpineBindingMode.CUSTOM && !hasCustom)
                                    "Import a spine image below to use this option."
                                else description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onPickCustom) {
                Text(if (hasCustom) "Replace spine image" else "Import spine image")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Close") } },
    )
}
