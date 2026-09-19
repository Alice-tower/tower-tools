package dev.towertools.naibox

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

internal fun JsonElement?.obj(): JsonObject? = this as? JsonObject
internal fun JsonElement?.array(): JsonArray? = this as? JsonArray
internal fun JsonElement?.value(): String = when (this) {
    null, JsonNull -> ""
    is JsonPrimitive -> content
    else -> toString()
}

internal object SemanticParser {
    fun novelAi(texts: List<TextInfo>): NovelAiInfo {
        val byKey = texts.reversed().associateBy { it.keyword }
        val software = byKey["Software"]?.text.orEmpty()
        val source = byKey["Source"]?.text.orEmpty()
        val description = byKey["Description"]?.text.orEmpty()
        val comment = byKey["Comment"]?.text ?: byKey["parameters"]?.text.orEmpty()
        val params = if (comment.trimStart().startsWith('{')) parseJson(comment).obj() else null
        val isNai = software.contains("novelai", true) || source.startsWith("novelai", true) || params != null
        if (!isNai) return NovelAiInfo()
        val sourceParts = Regex("^(.*?)\\s+([0-9A-Fa-f]{8})$").matchEntire(source)
        val model = sourceParts?.groupValues?.get(1)?.trim() ?: source.trim()
        val hash = sourceParts?.groupValues?.get(2)?.uppercase().orEmpty()
        val characters = mutableListOf<CharacterCaption>()
        val v4 = params?.get("v4_prompt").obj()?.get("caption").obj()
        val captions = v4?.get("char_captions").array()
        if (!captions.isNullOrEmpty()) {
            for (entry in captions) {
                val cc = entry.obj() ?: continue
                val centers = cc["centers"].array()?.mapNotNull { p -> p.obj()?.let { it["x"].value() to it["y"].value() } }.orEmpty()
                characters += CharacterCaption("v4_prompt", cc["char_caption"].value(), centers)
            }
        } else {
            for (entry in params?.get("characterPrompts").array().orEmpty()) {
                val cc = entry.obj() ?: continue
                val center = cc["center"].obj()?.let { listOf(it["x"].value() to it["y"].value()) }.orEmpty()
                characters += CharacterCaption("characterPrompts", cc["prompt"].value(), center, cc["uc"].value())
            }
        }
        val request = params?.let { JsonObject(linkedMapOf(
            "input" to JsonPrimitive(description),
            "model" to JsonPrimitive("REPLACE_ME_nai-diffusion-4-5-full"),
            "action" to JsonPrimitive("generate"),
            "parameters" to it,
        )).pretty() }
        return NovelAiInfo(true, software, source, model, hash, description, comment, params, characters, request)
    }

    fun a1111(texts: List<TextInfo>): A1111Info? {
        val text = texts.firstOrNull { it.keyword == "parameters" && Regex("Steps:\\s*\\d+").containsMatchIn(it.text) }?.text ?: return null
        val lines = text.lines()
        val last = lines.indexOfLast { Regex("Steps:\\s*\\d+").containsMatchIn(it) }
        val negative = lines.indexOfFirst { it.startsWith("Negative prompt:") }
        val positive = lines.take(if (negative >= 0) negative else last).joinToString("\n").trim()
        val fields = linkedMapOf<String, String>()
        lines[last].split(Regex(",\\s*(?=[A-Za-z][A-Za-z0-9 _/+.-]*:)")) .forEach { part ->
            val i = part.indexOf(':')
            if (i >= 0) fields[part.take(i).trim()] = part.drop(i + 1).trim()
        }
        return A1111Info(positive, if (negative >= 0) lines[negative].removePrefix("Negative prompt:").trim() else "", fields)
    }

    fun card(texts: List<TextInfo>): CardInfo? {
        for (t in texts) {
            val json = t.decoded?.json.obj() ?: continue
            val spec = json["spec"].value()
            val data = json["data"].obj() ?: json
            if (!spec.startsWith("chara_card") || data.isEmpty()) continue
            val book = data["character_book"].obj()
            val bookEntries = book?.get("entries").array()?.size ?: 0
            val tags = data["tags"].array()?.map(JsonElement::value).orEmpty()
            val alt = data["alternate_greetings"].array()?.size ?: 0
            val fields = mutableListOf<Pair<String, String>>()
            for (name in listOf("name", "creator", "character_version", "description", "personality", "scenario", "first_mes", "mes_example", "system_prompt", "post_history_instructions")) {
                val value = data[name].value()
                if (value.isNotEmpty()) fields += name to (if (name in setOf("description", "personality", "scenario", "first_mes", "mes_example", "system_prompt", "post_history_instructions")) "${value.length} 字符" else value)
            }
            fields += "spec" to spec
            json["spec_version"].value().takeIf(String::isNotEmpty)?.let { fields += "spec_version" to it }
            fields += "alternate_greetings" to "$alt 条"
            fields += "character_book" to "$bookEntries 条世界书条目"
            return CardInfo(
                spec, when (spec) { "chara_card_v2" -> "SillyTavern 角色卡 v2"; "chara_card_v3" -> "SillyTavern 角色卡 v3"; else -> spec },
                t.keyword, data["name"].value(), data["creator"].value(), data["character_version"].value(),
                data["creator_notes"].value(), tags, bookEntries, alt, fields, json.pretty(),
                data["description"].value(), data["first_mes"].value(), data["mes_example"].value(),
            )
        }
        return null
    }
}
