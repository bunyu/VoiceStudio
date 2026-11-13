package com.voicestudio.reader.data

import android.content.Context
import com.voicestudio.reader.text.Dialect
import com.voicestudio.reader.text.VietnameseText
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Which voice reads: a voice imported from VoiceStudio (cloned with OmniVoice) or a Supertonic preset. */
sealed interface VoiceChoice {
    val key: String
    data class Cloned(val id: String) : VoiceChoice { override val key get() = "clone:$id" }
    data class Preset(val speaker: Int) : VoiceChoice { override val key get() = "preset:$speaker" }
    companion object {
        fun parse(value: String?): VoiceChoice? = when {
            value == null -> null
            value.startsWith("clone:") -> Cloned(value.removePrefix("clone:"))
            value.startsWith("preset:") -> value.removePrefix("preset:").toIntOrNull()?.takeIf { it in 0..63 }?.let(::Preset)
            else -> null
        }
    }
}

data class Settings(
    val voice: VoiceChoice? = null,
    val speed: Float = 1f,
    val musicUri: String? = null,
    val musicName: String? = null,
    val musicVolume: Float = 0.15f,
    val musicOn: Boolean = false,
    val dialect: Dialect = Dialect.NORTH,
    val pronunciations: String = "",
    val allowMobileData: Boolean = false,
    /** How many pieces (a few sentences each) to prepare ahead of the listening position. */
    val prepareAhead: Int = 40,
) {
    fun textRules() = VietnameseText(dialect, VietnameseText.parsePronunciations(pronunciations))
}

class ReaderSettings(context: Context) {
    private val prefs = context.getSharedPreferences("reader", Context.MODE_PRIVATE)
    private val state = MutableStateFlow(load())
    val flow: StateFlow<Settings> = state.asStateFlow()
    val value get() = state.value

    private fun load() = Settings(
        voice = VoiceChoice.parse(prefs.getString("voice", null)),
        speed = prefs.getFloat("speed", 1f).coerceIn(0.5f, 2f),
        musicUri = prefs.getString("musicUri", null),
        musicName = prefs.getString("musicName", null),
        musicVolume = prefs.getFloat("musicVolume", 0.15f).coerceIn(0f, 1f),
        musicOn = prefs.getBoolean("musicOn", false),
        dialect = runCatching { Dialect.valueOf(prefs.getString("dialect", null) ?: "NORTH") }.getOrDefault(Dialect.NORTH),
        pronunciations = prefs.getString("pronunciations", "").orEmpty(),
        allowMobileData = prefs.getBoolean("allowMobileData", false),
        prepareAhead = prefs.getInt("prepareAhead", 40).coerceIn(5, 400),
    )

    fun update(change: (Settings) -> Settings) {
        val next = change(state.value)
        prefs.edit()
            .putString("voice", next.voice?.key)
            .putFloat("speed", next.speed.coerceIn(0.5f, 2f))
            .putString("musicUri", next.musicUri)
            .putString("musicName", next.musicName)
            .putFloat("musicVolume", next.musicVolume.coerceIn(0f, 1f))
            .putBoolean("musicOn", next.musicOn)
            .putString("dialect", next.dialect.name)
            .putString("pronunciations", next.pronunciations.take(50_000))
            .putBoolean("allowMobileData", next.allowMobileData)
            .putInt("prepareAhead", next.prepareAhead.coerceIn(5, 400))
            .apply()
        state.value = next
    }
}
