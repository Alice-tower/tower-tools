package dev.towertools.naibox

import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

data class ChunkInfo(
    val type: String, val length: Long, val index: Int, val head: Int = -1,
    var kind: String = "binary", var note: String = "", var crcOk: Boolean? = null,
    var error: String? = null,
)

data class DecodedPayload(
    val bytes: ByteArray, val kind: String, val json: JsonElement? = null,
    val preview: String = "",
)

data class TextInfo(
    val keyword: String, val text: String, val encoding: String,
    val chunkIndex: Int, val chunkType: String, val entries: List<Pair<String, String>> = emptyList(),
    var decoded: DecodedPayload? = null, var decodeError: String? = null,
)

data class CharacterCaption(val source: String, val caption: String, val centers: List<Pair<String, String>>, val uc: String = "")

data class NovelAiInfo(
    val isNai: Boolean = false, val software: String = "", val source: String = "",
    val model: String = "", val hash: String = "", val description: String = "",
    val commentRaw: String = "", val params: JsonObject? = null,
    val characters: List<CharacterCaption> = emptyList(), val requestBody: String? = null,
)

data class A1111Info(val positive: String, val negative: String, val fields: Map<String, String>)

data class CardInfo(
    val spec: String, val specLabel: String, val sourceChunk: String,
    val name: String, val creator: String, val characterVersion: String,
    val creatorNotes: String, val tags: List<String>, val worldBookEntries: Int,
    val alternateGreetings: Int, val fields: List<Pair<String, String>>,
    val raw: String, val description: String, val firstMessage: String, val example: String,
)

data class EmbeddedPng(
    val offset: Int, val end: Int, val chunks: Int,
    val parsed: ImageInfo? = null, val error: String? = null,
)

data class ContainerInfo(
    val iendEnd: Int, val trailingSize: Int, val trailingHead: String,
    val trailingAscii: String, val signature: String?, val pngs: List<EmbeddedPng>,
    val gaps: List<Pair<Int, Int>>,
)

data class ImageInfo(
    val fileName: String, val fileSize: Int, val format: String, val head: String,
    var width: Long? = null, var height: Long? = null,
    val header: MutableMap<String, String> = linkedMapOf(),
    val chunks: MutableList<ChunkInfo> = mutableListOf(),
    val texts: MutableList<TextInfo> = mutableListOf(),
    val warnings: MutableList<String> = mutableListOf(),
    var novelAi: NovelAiInfo = NovelAiInfo(), var a1111: A1111Info? = null,
    var card: CardInfo? = null, var container: ContainerInfo? = null,
)
