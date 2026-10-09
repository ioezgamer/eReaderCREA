package org.crea.reader.utils

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object ReadingProgress {
    /** 0..100 from a stored Readium locator (JSON), 0 when there is none. */
    fun percent(rawLocator: String?): Int = runCatching {
        val progression = Json.parseToJsonElement(rawLocator ?: "{}").jsonObject["locations"]
            ?.jsonObject?.get("totalProgression")?.jsonPrimitive?.doubleOrNull ?: 0.0
        (progression * 100.0).toInt().coerceIn(0, 100)
    }.getOrDefault(0)
}
