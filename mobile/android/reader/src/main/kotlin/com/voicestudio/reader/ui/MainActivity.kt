package com.voicestudio.reader.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import com.voicestudio.reader.R
import com.voicestudio.reader.ReaderApp
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val incoming = mutableStateOf<Uri?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        incoming.value = sharedUri(intent)
        setContent { ReaderTheme { ReaderRoot(incoming.value) { incoming.value = null } } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        sharedUri(intent)?.let { incoming.value = it }
    }

    @Suppress("DEPRECATION")
    private fun sharedUri(intent: Intent?): Uri? = when (intent?.action) {
        Intent.ACTION_VIEW -> intent.data
        Intent.ACTION_SEND -> intent.getParcelableExtra(Intent.EXTRA_STREAM)
        else -> null
    }
}

enum class Tab { LIBRARY, VOICES, SETTINGS }

fun displayName(context: android.content.Context, uri: Uri): String =
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { if (it.moveToFirst()) it.getString(0) else null }.orEmpty()

fun isVoiceFile(name: String) = name.endsWith(".ovsvoice", true) || name.endsWith(".omnivoice", true)

@Composable
fun ReaderRoot(incoming: Uri?, consumed: () -> Unit) {
    val context = LocalContext.current
    val app = remember { ReaderApp.get(context) }
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var tab by rememberSaveable { mutableStateOf(Tab.LIBRARY) }
    var reading by rememberSaveable { mutableStateOf<String?>(null) }
    val importFailed = stringResource(R.string.import_failed)
    var voiceImport by remember { mutableStateOf<Uri?>(null) }
    var importing by remember { mutableStateOf(false) }
    suspend fun importBook(uri: Uri) {
        importing = true
        runCatching { app.library.import(uri) }.onSuccess { reading = it.id }.onFailure { snackbar.showSnackbar(importFailed) }
        importing = false
    }
    val notifications = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33) notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    LaunchedEffect(incoming) {
        val uri = incoming ?: return@LaunchedEffect
        consumed()
        if (isVoiceFile(displayName(context, uri))) { tab = Tab.VOICES; reading = null; voiceImport = uri }
        else importBook(uri)
    }

    val open = reading
    if (open != null) {
        BackHandler { reading = null }
        ReaderScreen(open, onBack = { reading = null }, onSetup = { reading = null; tab = it })
        return
    }
    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(tab == Tab.LIBRARY, { tab = Tab.LIBRARY }, { Icon(Icons.Filled.Home, null) }, label = { Text(stringResource(R.string.tab_library)) })
                NavigationBarItem(tab == Tab.VOICES, { tab = Tab.VOICES }, { Icon(painterResource(R.drawable.ic_voice), null) }, label = { Text(stringResource(R.string.tab_voices)) })
                NavigationBarItem(tab == Tab.SETTINGS, { tab = Tab.SETTINGS }, { Icon(Icons.Filled.Settings, null) }, label = { Text(stringResource(R.string.tab_settings)) })
            }
        },
    ) { padding ->
        val modifier = Modifier.padding(padding)
        when (tab) {
            Tab.LIBRARY -> LibraryScreen(modifier, importing, onOpen = { reading = it }, onImport = { uri -> scope.launch { importBook(uri) } })
            Tab.VOICES -> VoicesScreen(modifier, voiceImport) { voiceImport = null }
            Tab.SETTINGS -> SettingsScreen(modifier)
        }
    }
}
