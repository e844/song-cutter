package com.king400.songcutter.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put

/** פריט בהיסטוריית החיתוכים */
data class HistoryItem(
    val id: String,
    val sourceUri: String,
    val sourceName: String,
    val outputUri: String,
    val outputName: String,
    val start: Double,
    val end: Double,
    val ringtone: Boolean,
    val fadeIn: Double,
    val fadeOut: Double,
    val at: Long,
)

object History {
    const val MAX = 200

    fun add(list: List<HistoryItem>, item: HistoryItem): List<HistoryItem> =
        (listOf(item) + list.filter { it.id != item.id }).take(MAX)

    fun encode(list: List<HistoryItem>): String = buildJsonArray {
        for (h in list) add(buildJsonObject {
            put("id", h.id); put("sourceUri", h.sourceUri); put("sourceName", h.sourceName)
            put("outputUri", h.outputUri); put("outputName", h.outputName)
            put("start", h.start); put("end", h.end); put("ringtone", h.ringtone)
            put("fadeIn", h.fadeIn); put("fadeOut", h.fadeOut); put("at", h.at)
        })
    }.toString()

    /** קובץ פגום / שדות חסרים → מדלגים על הפריט, לא קורסים */
    fun decode(text: String): List<HistoryItem> = try {
        ((Json.parseToJsonElement(text) as? JsonArray) ?: JsonArray(emptyList())).mapNotNull { el ->
            try {
                val o: JsonObject = el.jsonObject
                fun s(k: String) = o[k]!!.jsonPrimitive.content
                HistoryItem(
                    id = s("id"), sourceUri = s("sourceUri"), sourceName = s("sourceName"),
                    outputUri = s("outputUri"), outputName = s("outputName"),
                    start = o["start"]!!.jsonPrimitive.double, end = o["end"]!!.jsonPrimitive.double,
                    ringtone = s("ringtone") == "true",
                    fadeIn = o["fadeIn"]?.jsonPrimitive?.double ?: 0.0, fadeOut = o["fadeOut"]?.jsonPrimitive?.double ?: 0.0,
                    at = o["at"]!!.jsonPrimitive.long,
                )
            } catch (e: Exception) { null }
        }.take(MAX)
    } catch (e: Exception) { emptyList() }
}
