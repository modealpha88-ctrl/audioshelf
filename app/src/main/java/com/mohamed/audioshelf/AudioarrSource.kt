package com.mohamed.audioshelf

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import java.net.URI

private fun serverAddress(input: String): Uri? {
    val address = runCatching { URI(input.trim()) }.getOrNull() ?: return null
    if (address.scheme?.lowercase() !in setOf("http", "https") || address.host.isNullOrBlank() ||
        address.rawUserInfo != null || address.rawQuery != null || address.rawFragment != null ||
        address.port == 0 || address.port > 65535) return null
    return Uri.parse(address.toASCIIString()).normalizeScheme()
}

@Composable
internal fun AudioarrSource() {
    val context = LocalContext.current
    val prefs = remember(context) { context.getSharedPreferences("audioarr", Context.MODE_PRIVATE) }
    var saved by remember { mutableStateOf(prefs.getString("server", "").orEmpty()) }
    var address by rememberSaveable { mutableStateOf(saved) }
    var error by rememberSaveable { mutableStateOf<String?>(null) }
    Text("Audioarr", style = MaterialTheme.typography.titleMedium)
    Text("Search and monitor audiobooks on your server. Downloads stay on the server.", style = MaterialTheme.typography.bodySmall)
    Spacer(Modifier.height(8.dp))
    OutlinedTextField(
        value = address,
        onValueChange = { address = it; error = null },
        label = { Text("Audioarr server address") },
        placeholder = { Text("http://192.168.1.10:9797") },
        singleLine = true,
        modifier = Modifier.fillMaxWidth(),
        isError = error != null,
    )
    error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(enabled = address.isNotBlank(), onClick = {
            val uri = serverAddress(address)
            if (uri == null) {
                error = "Enter an HTTP or HTTPS server address without credentials, a query, or a fragment."
            } else {
                try {
                    CustomTabsIntent.Builder().setShowTitle(true).build().launchUrl(context, uri)
                    saved = uri.toString()
                    prefs.edit().putString("server", saved).apply()
                    error = null
                } catch (e: ActivityNotFoundException) {
                    error = "Install or enable a browser to open Audioarr."
                }
            }
        }) { Text("Open Audioarr") }
        if (saved.isNotEmpty()) {
            TextButton(onClick = {
                prefs.edit().remove("server").apply()
                saved = ""
                address = ""
                error = null
            }) { Text("Forget server") }
        }
    }
}
