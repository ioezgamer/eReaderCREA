package org.crea.reader.sync

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

/** A reading reported by another device of the same Google account. */
@Serializable
data class RemoteReading(
    val deviceId: String,
    val deviceName: String,
    val driveFileId: String,
    val percent: Int,
    val updatedAt: Long,
)

/**
 * Local copy of what the account's other devices are reading, saved at each sync so the catalog
 * can show it offline. This device's own reading is always taken live from its own data.
 */
class DeviceReadingsStore(context: Context) {
    private val preferences = context.getSharedPreferences("device-readings", Context.MODE_PRIVATE)
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(RemoteReading.serializer())

    private val _readings = MutableStateFlow(load())
    val readings: StateFlow<List<RemoteReading>> = _readings.asStateFlow()

    fun replace(readings: List<RemoteReading>) {
        if (readings == _readings.value) return
        preferences.edit().putString(KEY, json.encodeToString(serializer, readings)).apply()
        _readings.value = readings
    }

    private fun load(): List<RemoteReading> =
        preferences.getString(KEY, null)
            ?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }
            .orEmpty()

    private companion object {
        const val KEY = "readings"
    }
}
