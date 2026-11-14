package com.voicestudio.reader.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.voicestudio.reader.R
import com.voicestudio.reader.ReaderApp
import com.voicestudio.reader.data.BookPiece
import kotlinx.coroutines.delay

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun ReaderScreen(bookId: String, onBack: () -> Unit, onSetup: (Tab) -> Unit) {
    val app = ReaderApp.get(LocalContext.current)
    val narration = app.narration
    val state by narration.state.collectAsState()
    val settings by app.settings.flow.collectAsState()
    var dialog by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(bookId) { narration.open(bookId) }
    val book = if (state.bookId == bookId) narration.current() else null

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(state.title, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(state.chapterTitle, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
                    }
                },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.back)) } },
                actions = { IconButton(onClick = { dialog = "chapters" }) { Icon(Icons.AutoMirrored.Filled.List, stringResource(R.string.chapters)) } },
            )
        },
        bottomBar = {
            Surface(tonalElevation = 3.dp) {
                // The app draws edge to edge, so the controls must stay above the system navigation bar.
                Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Status(state.waiting, state.readyAhead, state.skipped)
                    if (state.totalPieces > 0) LinearProgressIndicator(progress = { (state.piece + 1f) / state.totalPieces }, modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { narration.previous() }) { Icon(painterResource(R.drawable.ic_previous), stringResource(R.string.previous)) }
                        FilledIconButton(onClick = { narration.toggle() }, modifier = Modifier.size(64.dp), colors = IconButtonDefaults.filledIconButtonColors()) {
                            Icon(painterResource(if (state.playing) R.drawable.ic_pause else R.drawable.ic_play), stringResource(if (state.playing) R.string.pause else R.string.play), Modifier.size(36.dp))
                        }
                        IconButton(onClick = { narration.next() }) { Icon(painterResource(R.drawable.ic_next), stringResource(R.string.next)) }
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AssistChip(onClick = { dialog = "speed" }, label = { Text("%.2f×".format(settings.speed).replace(".00", "")) }, leadingIcon = { Icon(painterResource(R.drawable.ic_speed), null, Modifier.size(18.dp)) })
                        AssistChip(onClick = { dialog = "sleep" }, label = { SleepLabel(state.sleepAt, state.sleepAtChapterEnd) }, leadingIcon = { Icon(painterResource(R.drawable.ic_timer), null, Modifier.size(18.dp)) })
                        FilterChip(selected = settings.musicOn && settings.musicUri != null, onClick = { dialog = "music" }, label = { Text(stringResource(R.string.music)) }, leadingIcon = { Icon(painterResource(R.drawable.ic_music), null, Modifier.size(18.dp)) })
                        FilterChip(selected = state.preparingAll, onClick = { narration.prepareAll(!state.preparingAll) },
                            label = { Text(if (state.preparingAll) stringResource(R.string.preparing_all, state.preparedTotal, state.totalPieces) else stringResource(R.string.prepare_all)) },
                            leadingIcon = { Icon(painterResource(R.drawable.ic_prepare), null, Modifier.size(18.dp)) })
                    }
                }
            }
        },
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            state.error?.let { SetupBanner(it, onSetup) }
            if (book == null) { LinearProgressIndicator(Modifier.fillMaxWidth()); return@Column }
            val pieces = remember(book, state.chapter) { book.piecesOf(state.chapter) }
            val list = rememberLazyListState()
            LaunchedEffect(state.piece, pieces) {
                val at = pieces.indexOfFirst { it.index == state.piece }
                if (at >= 0 && list.layoutInfo.visibleItemsInfo.none { it.index == at && it.offset >= 0 }) list.animateScrollToItem((at - 1).coerceAtLeast(0))
            }
            LazyColumn(state = list, contentPadding = PaddingValues(20.dp, 12.dp, 20.dp, 24.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                itemsIndexed(pieces, key = { _, it -> it.index }) { _, piece -> PieceText(piece, piece.index == state.piece) { narration.seekPiece(piece.index) } }
            }
        }
    }

    when (dialog) {
        "chapters" -> book?.let { open ->
            AlertDialog(onDismissRequest = { dialog = null }, confirmButton = { TextButton({ dialog = null }) { Text(stringResource(R.string.close)) } },
                title = { Text(stringResource(R.string.chapters)) },
                text = {
                    val list = rememberLazyListState(initialFirstVisibleItemIndex = (state.chapter - 2).coerceAtLeast(0))
                    LazyColumn(state = list) {
                        itemsIndexed(open.book.chapters) { i, chapter ->
                            Text(chapter.title, Modifier.fillMaxWidth().clickable { narration.seekChapter(i); dialog = null }.padding(vertical = 12.dp),
                                color = if (i == state.chapter) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface)
                        }
                    }
                })
        }
        "speed" -> SpeedDialog(settings.speed, { s -> app.settings.update { it.copy(speed = s) } }) { dialog = null }
        "sleep" -> SleepDialog({ narration.sleepAfter(it) }, { narration.sleepAtChapterEnd() }) { dialog = null }
        "music" -> MusicDialog { dialog = null }
    }
}

@Composable
private fun PieceText(piece: BookPiece, current: Boolean, onClick: () -> Unit) {
    val highlight = if (current) MaterialTheme.colorScheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent
    Text(piece.text, style = MaterialTheme.typography.bodyLarge, color = if (current) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onBackground,
        modifier = Modifier.fillMaxWidth().background(highlight, RoundedCornerShape(8.dp)).clickable(onClick = onClick).padding(horizontal = 8.dp, vertical = 4.dp)
            .then(if (piece.pause.name != "SENTENCE") Modifier.padding(bottom = 10.dp) else Modifier))
}

@Composable
private fun Status(waiting: Boolean, readyAhead: Int, skipped: Int) {
    val text = when {
        waiting -> stringResource(R.string.status_waiting)
        readyAhead > 0 -> stringResource(R.string.status_ready, readyAhead)
        else -> stringResource(R.string.status_idle)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        if (skipped > 0) Text(stringResource(R.string.status_skipped, skipped), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun SetupBanner(error: String, onSetup: (Tab) -> Unit) {
    val (message, tab) = when (error) {
        "voice_missing" -> R.string.error_voice_missing to Tab.VOICES
        "omnivoice_missing", "supertonic_missing", "mobile.local.licence_required" -> R.string.error_engine_missing to Tab.SETTINGS
        "mobile.local.runtime_failed" -> R.string.error_runtime to Tab.SETTINGS
        else -> R.string.status_problem to null
    }
    Card(Modifier.fillMaxWidth().padding(12.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(message), Modifier.weight(1f), color = MaterialTheme.colorScheme.onErrorContainer)
            if (tab != null) TextButton({ onSetup(tab) }) { Text(stringResource(R.string.fix)) }
        }
    }
}

@Composable
private fun SleepLabel(at: Long?, chapterEnd: Boolean) {
    var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
    LaunchedEffect(at) { while (at != null) { now = System.currentTimeMillis(); delay(15_000) } }
    Text(when {
        chapterEnd -> stringResource(R.string.sleep_chapter_short)
        at != null -> stringResource(R.string.sleep_minutes_left, ((at - now) / 60_000 + 1).coerceAtLeast(1))
        else -> stringResource(R.string.sleep)
    })
}

@Composable
private fun SpeedDialog(speed: Float, onChange: (Float) -> Unit, onDismiss: () -> Unit) {
    var value by remember { mutableFloatStateOf(speed) }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.speed_title, "%.2f×".format(value))) },
        text = {
            Column {
                Slider(value, { value = kotlin.math.round(it * 20f) / 20f; onChange(value) }, valueRange = 0.5f..2f, steps = 29)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (preset in listOf(0.8f, 1f, 1.15f, 1.3f, 1.5f)) FilterChip(selected = value == preset, onClick = { value = preset; onChange(preset) }, label = { Text("$preset×".replace(".0×", "×")) })
                }
                Spacer(Modifier.height(8.dp))
                Text(stringResource(R.string.speed_hint), style = MaterialTheme.typography.bodySmall)
            }
        })
}

@Composable
private fun SleepDialog(onMinutes: (Int?) -> Unit, onChapterEnd: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.sleep)) },
        text = {
            Column {
                for (minutes in listOf(10, 15, 30, 45, 60, 90, 120)) Text(stringResource(R.string.sleep_after, minutes),
                    Modifier.fillMaxWidth().clickable { onMinutes(minutes); onDismiss() }.padding(vertical = 12.dp))
                Text(stringResource(R.string.sleep_chapter), Modifier.fillMaxWidth().clickable { onChapterEnd(); onDismiss() }.padding(vertical = 12.dp))
                Text(stringResource(R.string.sleep_off), Modifier.fillMaxWidth().clickable { onMinutes(null); onDismiss() }.padding(vertical = 12.dp), color = MaterialTheme.colorScheme.primary)
            }
        })
}

@Composable
private fun MusicDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    val app = ReaderApp.get(context)
    val settings by app.settings.flow.collectAsState()
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            app.settings.update { it.copy(musicUri = uri.toString(), musicName = displayName(context, uri).ifEmpty { null }, musicOn = true) }
        }
    }
    AlertDialog(onDismissRequest = onDismiss, confirmButton = { TextButton(onDismiss) { Text(stringResource(R.string.close)) } },
        title = { Text(stringResource(R.string.music)) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(settings.musicName ?: stringResource(R.string.music_none), Modifier.weight(1f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.width(8.dp))
                    Switch(settings.musicOn, { on -> app.settings.update { it.copy(musicOn = on) } }, enabled = settings.musicUri != null)
                }
                TextButton({ pick.launch(arrayOf("audio/*")) }) { Text(stringResource(R.string.music_choose)) }
                Text(stringResource(R.string.music_volume, (settings.musicVolume * 100).toInt()), style = MaterialTheme.typography.labelMedium)
                Slider(settings.musicVolume, { v -> app.settings.update { it.copy(musicVolume = v) } }, valueRange = 0f..0.6f)
                Text(stringResource(R.string.music_hint), style = MaterialTheme.typography.bodySmall)
            }
        })
}
