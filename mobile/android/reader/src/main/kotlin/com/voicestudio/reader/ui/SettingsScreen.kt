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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voicestudio.mobile.local.PhoneModel
import com.voicestudio.mobile.network.ClientFailure
import com.voicestudio.reader.R
import com.voicestudio.reader.ReaderApp
import com.voicestudio.reader.text.Dialect
import kotlinx.coroutines.Job
import kotlinx.serialization.json.JsonPrimitive
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
            item { EngineCard(app.engines.omnivoice, R.string.engine_omnivoice) }
            item { EngineCard(app.engines.supertonic, R.string.engine_supertonic) }
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
private fun EngineCard(model: PhoneModel, description: Int) {
    val app = ReaderApp.get(LocalContext.current)
    val engines = app.engines
    val scope = rememberCoroutineScope()
    val progress by engines.runtime.downloads.progress.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var refresh by remember { mutableIntStateOf(0) }
    var job by remember { mutableStateOf<Job?>(null) }
    var failure by remember { mutableStateOf<String?>(null) }
    var agree by remember { mutableStateOf(false) }
    val state = progress[model.id]
    val installed = remember(refresh, state?.running) { engines.runtime.downloads.installed(model) }
    val accepted = remember(refresh, state?.running) { engines.runtime.licence.accepted(model) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(model.name, style = MaterialTheme.typography.titleMedium)
            Text(stringResource(description), style = MaterialTheme.typography.bodyMedium)
            Text(stringResource(R.string.engine_size, "%.1f".format(model.totalBytes / 1e9), model.minRamGb.toInt()), style = MaterialTheme.typography.labelMedium, modifier = Modifier.padding(top = 4.dp))
            if (!engines.fitsPhone(model)) Text(stringResource(R.string.engine_low_ram), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            Spacer(Modifier.height(8.dp))
            when {
                installed && accepted -> Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.engine_ready), Modifier.weight(1f), color = MaterialTheme.colorScheme.primary)
                    OutlinedButton({ scope.launch { engines.runtime.downloads.delete(model); refresh++ } }) { Text(stringResource(R.string.delete)) }
                }
                state?.running == true -> {
                    LinearProgressIndicator(progress = { if (state.total > 0) state.received.toFloat() / state.total else 0f }, modifier = Modifier.fillMaxWidth())
                    Text(stringResource(R.string.engine_downloading, state.received / 1_000_000, state.total / 1_000_000), style = MaterialTheme.typography.labelSmall)
                    TextButton({ job?.cancel(); engines.runtime.downloads.paused(model.id) }) { Text(stringResource(R.string.cancel)) }
                }
                else -> {
                    // The model's own terms, shown before anything is downloaded.
                    Text(stringResource(R.string.engine_licence, model.licence.id, model.licence.credit), style = MaterialTheme.typography.bodySmall)
                    (model.licence.terms["notes"] as? JsonPrimitive)?.content?.takeIf { it.isNotBlank() }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                    if (!accepted) Row(Modifier.clickable { agree = !agree }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(agree, { agree = it }); Text(stringResource(R.string.engine_agree), style = MaterialTheme.typography.bodyMedium)
                    }
                    Button(enabled = accepted || agree, onClick = {
                        failure = null
                        if (!accepted) engines.runtime.licence.record(model, true)
                        engines.runtime.downloads.scheduled(model.id, model.totalBytes)
                        job = app.scope.launch {
                            try { engines.runtime.downloads.download(model, settings.allowMobileData) }
                            catch (e: ClientFailure) { failure = e.key; engines.runtime.downloads.paused(model.id, error = e.key) }
                            catch (e: kotlinx.coroutines.CancellationException) { throw e }
                            catch (e: Exception) { failure = "download"; engines.runtime.downloads.paused(model.id, error = "download") }
                            refresh++
                        }
                    }) { Text(stringResource(if (installed) R.string.engine_accept else R.string.engine_download)) }
                    failure?.let {
                        Text(stringResource(if (it == "mobile.local.wifi_required") R.string.engine_wifi else if (it == "mobile.local.storage_required") R.string.engine_storage else R.string.engine_failed),
                            color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}
