package org.crea.reader.reader.preferences

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt
import org.crea.reader.R
import org.crea.reader.reader.BarSize
import org.crea.reader.reader.BrightnessRules
import org.crea.reader.reader.ReaderLook

/** Screen brightness (automatic or manual) and the size of the reader's bars. */
@Composable
fun ReaderLookSection(look: ReaderLook) {
    val barSize by look.barSize.collectAsState()
    val brightness by look.brightness.collectAsState()

    OptionCard(R.string.look_brightness) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slider(
                value = BrightnessRules.toPercent(brightness ?: BrightnessRules.AUTO_THUMB).toFloat(),
                onValueChange = { look.setBrightness(BrightnessRules.fromPercent(it.roundToInt())) },
                valueRange = 0f..100f,
                modifier = Modifier.weight(1f),
            )
            FilterChip(
                selected = brightness == null,
                onClick = { look.setBrightness(null) },
                label = { Text(stringResource(R.string.look_auto)) },
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }

    OptionCard(R.string.look_bars) {
        DropdownField(
            options = BarSize.entries.map { it to stringResource(barSizeLabel(it)) },
            selected = barSize,
            onSelect = { look.setBarSize(it) },
        )
    }
}

private fun barSizeLabel(size: BarSize): Int = when (size) {
    BarSize.SMALL -> R.string.look_bars_small
    BarSize.MEDIUM -> R.string.look_bars_medium
    BarSize.LARGE -> R.string.look_bars_large
}
