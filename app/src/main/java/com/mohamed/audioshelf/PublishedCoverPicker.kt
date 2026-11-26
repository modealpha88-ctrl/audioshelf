package com.mohamed.audioshelf

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mohamed.audioshelf.data.BookCoverLookup
import com.mohamed.audioshelf.data.CoverSuggestion
import com.mohamed.audioshelf.model.AudioBook

/**
 * Choose real bibliographic covers, not generated "official" editions. For box sets
 * and torrent collections the title may not correspond to one published jacket,
 * so the user explicitly chooses the image that matches their copy.
 */
@Composable
internal fun PublishedCoverPicker(
    book: AudioBook,
    onDismiss: () -> Unit,
    onChoose: (CoverSuggestion) -> Unit,
) {
    val lookup = remember { BookCoverLookup() }
    var loading by remember(book.sourceId, book.id) { mutableStateOf(true) }
    var suggestions by remember(book.sourceId, book.id) {
        mutableStateOf<List<CoverSuggestion>>(emptyList())
    }
    LaunchedEffect(book.sourceId, book.id) {
        loading = true
        suggestions = lookup.suggestions(book)
        loading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose a published cover") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    "Real catalogued artwork from Open Library. Check the book title " +
                        "and author before choosing an edition.",
                    style = MaterialTheme.typography.bodySmall,
                )
                if (book.title.contains("collection", ignoreCase = true) ||
                    book.title.contains("series", ignoreCase = true)) {
                    Text(
                        "For collections, covers may belong to individual books in the set.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                when {
                    loading -> {
                        Row(Modifier.fillMaxWidth().padding(18.dp),
                            horizontalArrangement = Arrangement.Center) {
                            CircularProgressIndicator()
                        }
                    }
                    suggestions.isEmpty() -> Text(
                        "No confidently related artwork was found. You can still use " +
                            "Add/Change cover image to pick the correct cover from your device.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    else -> LazyColumn(
                        Modifier.fillMaxWidth().heightIn(max = 425.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        items(suggestions, key = { it.coverUrl }) { option ->
                            Surface(
                                shape = RoundedCornerShape(10.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                            ) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable(onClickLabel = "Use published cover for ${option.title}") {
                                            onChoose(option)
                                        }
                                        .padding(8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                ) {
                                    AsyncImage(
                                        model = option.coverUrl,
                                        contentDescription = "Published cover for ${option.title}",
                                        contentScale = ContentScale.Fit,
                                        modifier = Modifier.size(width = 62.dp, height = 88.dp),
                                    )
                                    Column(Modifier.weight(1f)) {
                                        Text(option.title,
                                            style = MaterialTheme.typography.bodyMedium,
                                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        if (option.authors.isNotBlank()) {
                                            Text(option.authors,
                                                style = MaterialTheme.typography.bodySmall,
                                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                                        }
                                        if (option.firstPublished != null) {
                                            Text("First published ${option.firstPublished} · Open Library",
                                                style = MaterialTheme.typography.labelSmall)
                                        } else {
                                            Text("Open Library", style = MaterialTheme.typography.labelSmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) { Text("Close") }
        },
    )
}
