package com.voicestudio.reader.ui

import android.media.MediaPlayer
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicestudio.mobile.network.ClientFailure
import com.voicestudio.reader.R
import com.voicestudio.reader.ReaderApp
import com.voicestudio.reader.data.VoiceChoice
import com.voicestudio.reader.voice.BundleVoice
import com.voicestudio.reader.voice.ReaderVoice
import com.voicestudio.reader.voice.VoiceBundleException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VoicesScreen(modifier: Modifier, incoming: Uri?, consumed: () -> Unit) {
    val context = LocalContext.current
    val app = ReaderApp.get(context)
    val scope = rememberCoroutineScope()
    val voices by app.voices.voices.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var review by remember { mutableStateOf<BundleVoice?>(null) }
    var problem by remember { mutableStateOf<Int?>(null) }
    var busy by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ReaderVoice?>(null) }
    val player = remember { mutableStateOf<MediaPlayer?>(null) }
    DisposableEffect(Unit) { onDispose { player.value?.release() } }
    fun play(file: File?) {
        player.value?.release(); player.value = null
        if (file != null) player.value = runCatching { MediaPlayer().apply { setDataSource(file.path); prepare(); start() } }.getOrNull()
    }

    fun inspect(uri: Uri) {
        busy = true; problem = null
        scope.launch {
            val workspace = File(context.cacheDir, "voice-import-" + UUID.randomUUID())
            try {
                val bundle = withContext(Dispatchers.IO) {
                    val copy = File(workspace, "bundle.zip").apply { parentFile!!.mkdirs() }
                    (context.contentResolver.openInputStream(uri) ?: error("unreadable")).use { input ->
                        copy.outputStream().use { out ->
                            val buffer = ByteArray(262_144); var total = 0L
                            while (true) { val n = input.read(buffer); if (n < 0) break; total += n; if (total > 100L * 1024 * 1024) throw VoiceBundleException(VoiceBundleException.Reason.TOO_LARGE); out.write(buffer, 0, n) }
                        }
                    }
                    app.voices.inspect(copy, File(workspace, "members"))
                }
                review = bundle
            } catch (e: VoiceBundleException) {
                problem = when (e.reason) { VoiceBundleException.Reason.NO_AUDIO -> R.string.voice_error_no_audio; VoiceBundleException.Reason.TOO_LARGE -> R.string.voice_error_large; else -> R.string.voice_error_format }
            } catch (e: Exception) { problem = R.string.voice_error_format }
            busy = false
        }
    }
    LaunchedEffect(incoming) { incoming?.let { consumed(); inspect(it) } }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(::inspect) }

    Scaffold(modifier = modifier, topBar = { TopAppBar(title = { Text(stringResource(R.string.tab_voices)) }) }) { padding ->
        LazyColumn(Modifier.padding(padding), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text(stringResource(R.string.voices_your), style = MaterialTheme.typography.titleMedium)
                        Spacer(Modifier.height(4.dp))
                        Text(stringResource(R.string.voices_how), style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(12.dp))
                        Button(onClick = { pick.launch(arrayOf("*/*")) }, enabled = !busy) { Icon(Icons.Filled.Add, null); Text(stringResource(R.string.voice_import)) }
                        if (busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 8.dp))
                        problem?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }
                    }
                }
            }
            items(voices, key = { it.id }) { voice ->
                VoiceRow(voice.name, voice.transcript, settings.voice == VoiceChoice.Cloned(voice.id), onSelect = { app.settings.update { it.copy(voice = VoiceChoice.Cloned(voice.id)) } },
                    onPreview = { play(app.voices.preview(voice)) }, onEdit = { editing = voice },
                    onDelete = { scope.launch { app.voices.delete(voice); if (app.settings.value.voice == VoiceChoice.Cloned(voice.id)) app.settings.update { it.copy(voice = null) } } })
            }
            item {
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.voices_presets), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.voices_presets_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            items((0 until app.engines.presetCount).toList()) { sid ->
                Row(Modifier.fillMaxWidth().clickable { app.settings.update { it.copy(voice = VoiceChoice.Preset(sid)) } }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(settings.voice == VoiceChoice.Preset(sid) || (settings.voice == null && sid == 0), { app.settings.update { it.copy(voice = VoiceChoice.Preset(sid)) } })
                    Text(stringResource(R.string.voice_preset, sid + 1))
                }
            }
        }
    }

    review?.let { bundle ->
        VoiceReviewDialog(bundle.name, bundle.transcript, confirmLabel = R.string.voice_import, needsConsent = true, onDismiss = { bundle.audio.parentFile?.parentFile?.deleteRecursively(); review = null }) { name, transcript ->
            busy = true
            scope.launch {
                runCatching { app.voices.install(bundle, name, transcript) }
                    .onSuccess { voice -> review = null; if (app.settings.value.voice !is VoiceChoice.Cloned) app.settings.update { it.copy(voice = VoiceChoice.Cloned(voice.id)) } }
                    .onFailure { e -> review = null; problem = if ((e as? ClientFailure)?.key == "mobile.simple.audio_long") R.string.voice_error_long else R.string.voice_error_audio }
                bundle.audio.parentFile?.parentFile?.deleteRecursively()
                busy = false
            }
        }
    }
    editing?.let { voice ->
        VoiceReviewDialog(voice.name, voice.transcript, confirmLabel = R.string.save, needsConsent = false, onDismiss = { editing = null }) { name, transcript ->
            scope.launch { app.voices.rename(voice, name, transcript); editing = null }
        }
    }
}

@Composable
private fun VoiceRow(name: String, transcript: String, selected: Boolean, onSelect: () -> Unit, onPreview: () -> Unit, onEdit: () -> Unit, onDelete: () -> Unit) {
    Card(Modifier.fillMaxWidth().clickable(onClick = onSelect)) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onSelect)
            Column(Modifier.weight(1f)) {
                Text(name, style = MaterialTheme.typography.titleSmall)
                Text(transcript, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onPreview) { Icon(painterResource(R.drawable.ic_play), stringResource(R.string.voice_preview)) }
            IconButton(onClick = onEdit) { Icon(Icons.Filled.Edit, stringResource(R.string.edit)) }
            IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, stringResource(R.string.delete)) }
        }
    }
}

@Composable
private fun VoiceReviewDialog(name: String, transcript: String, confirmLabel: Int, needsConsent: Boolean, onDismiss: () -> Unit, onConfirm: (String, String) -> Unit) {
    var title by remember { mutableStateOf(name) }
    var text by remember { mutableStateOf(transcript) }
    var consent by remember { mutableStateOf(!needsConsent) }
    AlertDialog(onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.voice_review)) },
        text = {
            Column {
                OutlinedTextField(title, { title = it }, label = { Text(stringResource(R.string.voice_name)) }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(text, { text = it }, label = { Text(stringResource(R.string.voice_transcript)) }, minLines = 3, maxLines = 6, modifier = Modifier.fillMaxWidth())
                Text(stringResource(R.string.voice_transcript_hint), style = MaterialTheme.typography.bodySmall)
                if (needsConsent) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { consent = !consent }) {
                    Checkbox(consent, { consent = it })
                    Text(stringResource(R.string.voice_consent), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = { TextButton({ onConfirm(title, text) }, enabled = consent && title.isNotBlank() && text.isNotBlank()) { Text(stringResource(confirmLabel)) } },
        dismissButton = { TextButton(onDismiss) { Text(stringResource(R.string.cancel)) } })
}
