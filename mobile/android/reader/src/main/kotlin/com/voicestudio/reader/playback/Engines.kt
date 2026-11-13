package com.voicestudio.reader.playback

import android.content.Context
import com.voicestudio.mobile.local.ModelCatalog
import com.voicestudio.mobile.local.PhoneModel
import com.voicestudio.mobile.local.PhoneRuntime

/**
 * The two engines this app uses, from VoiceStudio's pinned phone catalogue:
 * OmniVoice clones an imported voice and reads Vietnamese (it is trained on 600+ languages; the catalogue
 * lists the languages VoiceStudio's main app has verified). Supertonic 3 lists Vietnamese and is much faster,
 * but has preset voices only, so it is the fallback when a phone cannot run OmniVoice.
 */
class Engines(context: Context) {
    val runtime = PhoneRuntime.get(context)
    private val catalog = context.assets.open("models.json").bufferedReader().use { ModelCatalog.decode(it.readText()) }
    val omnivoice: PhoneModel = catalog.models.first { it.id == OMNIVOICE }
    val supertonic: PhoneModel = catalog.models.first { it.id == SUPERTONIC }
    val all = listOf(omnivoice, supertonic)

    fun ready(model: PhoneModel) = runtime.downloads.installed(model) && runtime.licence.accepted(model)
    fun fitsPhone(model: PhoneModel) = model.fits(runtime.ramBytes)
    val presetCount: Int get() = supertonic.option("num_speakers", "10").toIntOrNull()?.coerceIn(1, 64) ?: 10

    companion object {
        const val OMNIVOICE = "omnivoice-q4-k-m"
        const val SUPERTONIC = "supertonic3-int8"
    }
}
