package com.voicestudio.reader.vieneu

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.float
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.io.File

/** A voice shipped with the model. [description] is upstream's Vietnamese label: gender, region and style. */
class NanoPreset(val name: String, val description: String, val voice: NanoVoice)

/** The preset voices file of the upstream SDK (`voices_v3_nano.json`): a speaker vector and style tokens per voice. */
class NanoPresets(val voices: List<NanoPreset>, val defaultName: String?) {
    val default: NanoPreset? get() = voices.firstOrNull { it.name == defaultName } ?: voices.firstOrNull()
    operator fun get(name: String) = voices.firstOrNull { it.name == name }

    companion object {
        fun read(file: File): NanoPresets {
            val root = Json.parseToJsonElement(file.readText()).jsonObject
            val voices = (root["presets"] as? JsonObject).orEmpty().mapNotNull { (name, value) ->
                runCatching {
                    val entry = value.jsonObject
                    val rows = entry.getValue("style").jsonArray
                    val width = rows[0].jsonArray.size
                    val style = FloatArray(rows.size * width)
                    rows.forEachIndexed { r, row -> require(row.jsonArray.size == width); row.jsonArray.forEachIndexed { c, x -> style[r * width + c] = x.jsonPrimitive.float } }
                    val speaker = entry.getValue("speaker_emb").jsonArray.map { it.jsonPrimitive.float }.toFloatArray()
                    NanoPreset(name, entry["description"]?.jsonPrimitive?.contentOrNull.orEmpty(), NanoVoice(speaker, style, rows.size))
                }.getOrNull()
            }
            return NanoPresets(voices, root["default_voice"]?.jsonPrimitive?.contentOrNull)
        }
    }
}
