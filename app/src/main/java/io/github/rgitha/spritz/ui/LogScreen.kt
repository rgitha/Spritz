package io.github.rgitha.spritz.ui

import android.content.Intent
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.github.rgitha.spritz.BuildConfig
import io.github.rgitha.spritz.ble.BleLog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LogScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val lines by BleLog.lines.collectAsState()
    val list = rememberLazyListState()
    LaunchedEffect(lines.size) { if (lines.isNotEmpty()) list.scrollToItem(lines.size - 1) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Connection log") },
                navigationIcon = { BackButton(onBack) },
                actions = {
                    IconButton(onClick = {
                        val text = "Spritz ${BuildConfig.VERSION_NAME}, Android ${android.os.Build.VERSION.RELEASE}, " +
                            "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}\n" + lines.joinToString("\n")
                        val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
                        context.startActivity(Intent.createChooser(send, "Share log"))
                    }) { Icon(Icons.Default.Share, contentDescription = "Share") }
                    IconButton(onClick = BleLog::clear) { Icon(Icons.Default.Delete, contentDescription = "Clear") }
                },
            )
        },
    ) { padding ->
        if (lines.isEmpty()) {
            Text(
                "Nothing yet. Go back and refresh a device, then return here.",
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(state = list, contentPadding = PaddingValues(12.dp), modifier = Modifier.padding(padding)) {
                items(lines) {
                    Text(it, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}
