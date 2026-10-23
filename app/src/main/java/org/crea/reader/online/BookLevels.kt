package org.crea.reader.online

import android.content.Context
import org.crea.reader.R

/** Label of a reading level the AI suggested ("secundaria" → "Secundaria"), or null if unknown. */
internal fun levelLabel(level: String): Int? = when (level) {
    "inicial" -> R.string.level_inicial
    "primaria_baja" -> R.string.level_primaria_baja
    "primaria_alta" -> R.string.level_primaria_alta
    "secundaria" -> R.string.level_secundaria
    "bachillerato" -> R.string.level_bachillerato
    "adultos" -> R.string.level_adultos
    else -> null
}

/** "Resumen generado automáticamente · Nivel sugerido: Secundaria". */
internal fun levelNote(context: Context, level: String): String {
    val label = levelLabel(level) ?: return context.getString(R.string.insight_note)
    return context.getString(R.string.insight_note_level, context.getString(label))
}
