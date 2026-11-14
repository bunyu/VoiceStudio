package com.voicestudio.reader.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voicestudio.reader.R
import com.voicestudio.reader.ReaderApp
import com.voicestudio.reader.text.Dialect
import com.voicestudio.reader.vieneu.ReleaseFile
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(modifier: Modifier) {
    val app = ReaderApp.get(LocalContext.current)
    val settings by app.settings.flow.collectAsState()
    var words by remember { mutableStateOf(settings.pronunciations) }
    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_settings)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item { Text(stringResource(R.string.engines), style = MaterialTheme.typography.titleMedium) }
            item { EngineCard() }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.fast_mode))
                        Text(stringResource(R.string.fast_mode_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(settings.fast, { on -> app.settings.update { it.copy(fast = on) } })
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.mobile_data), Modifier.weight(1f))
                    Switch(settings.allowMobileData, { on -> app.settings.update { it.copy(allowMobileData = on) } })
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.vietnamese_reading), style = MaterialTheme.typography.titleMedium)
                        Text(stringResource(R.string.dialect), style = MaterialTheme.typography.labelLarge, modifier = Modifier.padding(top = 8.dp))
                        for ((dialect, label) in listOf(Dialect.NORTH to R.string.dialect_north, Dialect.SOUTH to R.string.dialect_south)) {
                            Row(Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(dialect = dialect) } }, verticalAlignment = Alignment.CenterVertically) {
                                RadioButton(settings.dialect == dialect, { app.settings.update { it.copy(dialect = dialect) } })
                                Text(stringResource(label))
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.pronunciations), style = MaterialTheme.typography.labelLarge)
                        Text(stringResource(R.string.pronunciations_hint), style = MaterialTheme.typography.bodySmall)
                        OutlinedTextField(words, { words = it }, minLines = 4, maxLines = 10, modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
                            placeholder = { Text("Lâm Phong = Lâm Phông\nAI = ây ai") })
                        TextButton(onClick = { app.settings.update { it.copy(pronunciations = words) } }, enabled = words != settings.pronunciations) { Text(stringResource(R.string.save)) }
                    }
                }
            }
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.prepare_ahead, settings.prepareAhead), style = MaterialTheme.typography.titleSmall)
                        Slider(settings.prepareAhead.toFloat(), { v -> app.settings.update { it.copy(prepareAhead = v.toInt()) } }, valueRange = 10f..200f)
                        Text(stringResource(R.string.prepare_ahead_hint), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            item { Text(stringResource(R.string.privacy), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun EngineCard() {
    val app = ReaderApp.get(LocalContext.current)
    val vieneu = app.vieneu
    val settings by app.settings.flow.collectAsState()
    val state by vieneu.assets.state.collectAsState()
    var job by remember { mutableStateOf<Job?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    val installed = remember(state.running, state.received) { vieneu.ready }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(vieneu.release.model, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.engine_about), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.engine_licence, vieneu.release.licence, vieneu.release.credit), style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
            Spacer(Modifier.height(8.dp))
            when {
                state.running -> {
                    LinearProgressIndicator(progress = { if (state.total > 0) state.received.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.engine_downloading, state.received / 1_000_000, state.total / 1_000_000), style = MaterialTheme.typography.labelSmall)
                    TextButton({ job?.cancel() }) { Text(stringResource(R.string.cancel)) }
                }
                installed -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.engine_ready), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    OutlinedButton({ confirmDelete = true }) { Text(stringResource(R.string.delete)) }
                }
                else -> {
                    Button(onClick = {
                        job = app.scope.launch { runCatching { vieneu.assets.download(ReleaseFile.SPEECH, settings.allowMobileData) } }
                    }) { Text(stringResource(R.string.engine_download_size, vieneu.release.bytes(ReleaseFile.SPEECH) / 1_000_000)) }
                    state.error?.let {
                        Text(stringResource(when (it) { "wifi" -> R.string.engine_wifi; "storage" -> R.string.engine_storage; else -> R.string.engine_failed }),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
    if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false },
        text = { Text(stringResource(R.string.engine_delete_confirm)) },
        confirmButton = { TextButton({ confirmDelete = false; app.scope.launch { app.narration.stop(); vieneu.unload(delete = true) } }) { Text(stringResource(R.string.delete)) } },
        dismissButton = { TextButton({ confirmDelete = false }) { Text(stringResource(R.string.cancel)) } })
}
