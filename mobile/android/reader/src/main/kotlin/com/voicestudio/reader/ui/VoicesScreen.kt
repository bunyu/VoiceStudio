package com.voicestudio.reader.ui

import android.net.Uri
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.voicestudio.reader.R
import com.voicestudio.reader.ReaderApp
import com.voicestudio.reader.data.VoiceChoice
import com.voicestudio.reader.vieneu.NanoPreset

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(modifier: Modifier, incoming: Uri?, consumed: () -> Unit, onSetup: () -> Unit) {
    val app = ReaderApp.get(LocalContext.current)
    val settings by app.settings.flow.collectAsState()
    val download by app.vieneu.assets.state.collectAsState()
    // Read again whenever a download starts or ends, so the list appears as soon as the model is installed.
    val presets by produceState<List<NanoPreset>?>(null, download.running) {
        value = if (app.vieneu.ready) runCatching { app.vieneu.presets() }.getOrNull()?.let { all ->
            val first = all.default
            listOfNotNull(first) + all.voices.filter { it !== first }
        } else null
    }
    LaunchedEffect(incoming) { if (incoming != null) consumed() }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_voices)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            val list = presets
            if (list == null) item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.voices_need_engine), style = MaterialTheme.typography.bodyMedium)
                        TextButton(onSetup) { Text(stringResource(R.string.tab_settings)) }
                    }
                }
            } else {
                item {
                    Text(stringResource(R.string.voices_presets), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.voices_presets_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                items(list, key = { it.name }) { preset ->
                    val choice = VoiceChoice.Preset(preset.name)
                    val selected = settings.voice == choice || (settings.voice !is VoiceChoice.Cloned && list.none { VoiceChoice.Preset(it.name) == settings.voice } && preset === list.first())
                    Row(Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(voice = choice) } }, verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected, { app.settings.update { it.copy(voice = choice) } })
                        Column {
                            Text(preset.name)
                            if (preset.description.isNotBlank()) Text(preset.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}
