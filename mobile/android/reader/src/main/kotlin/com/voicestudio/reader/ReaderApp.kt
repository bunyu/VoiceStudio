package com.voicestudio.reader

import android.app.Application
import android.content.Context
import com.voicestudio.reader.data.Library
import com.voicestudio.reader.data.ReaderSettings
import com.voicestudio.reader.playback.Engines
import com.voicestudio.reader.playback.Narration
import com.voicestudio.reader.playback.Renderer
import com.voicestudio.reader.voice.ReaderVoices
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

class ReaderApp : Application() {
    /** Work that must outlive a screen, such as model downloads. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val settings by lazy { ReaderSettings(this) }
    val library by lazy { Library(this) }
    val voices by lazy { ReaderVoices(File(filesDir, "voices")) }
    val engines by lazy { Engines(this) }
    val narration by lazy { Narration(this, library, settings, Renderer(engines, voices)) }

    override fun onCreate() {
        super.onCreate()
        scope.launch { library.refresh(); voices.refresh() }
    }

    companion object {
        fun get(context: Context) = context.applicationContext as ReaderApp
    }
}
