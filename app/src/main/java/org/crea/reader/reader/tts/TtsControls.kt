/*
 * Copyright 2021 Readium Foundation. All rights reserved.
 * Use of this source code is governed by the BSD-style license
 * available in the top-level LICENSE file of the project.
 */

package org.crea.reader.reader.tts

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.crea.reader.R
import org.crea.reader.utils.extensions.asStateWhenStarted

/** Colours of the read-aloud controls, chosen by the reader to match the page under them. */
data class TtsControlsColors(
    val content: Color,
    val accent: Color,
    val onAccent: Color,
)

/**
 * Read-aloud controls shown over the book while it is spoken: a floating glass capsule like the
 * reader's bars. [glass] draws the capsule (the reader's Liquid Glass), [colors] follow the page.
 */
@Composable
fun TtsControls(
    model: TtsViewModel,
    onPreferences: () -> Unit,
    glass: Modifier,
    colors: TtsControlsColors,
    modifier: Modifier = Modifier,
) {
    val showControls by model.showControls.asStateWhenStarted()
    val isPlaying by model.isPlaying.asStateWhenStarted()

    if (showControls) {
        TtsControls(
            playing = isPlaying,
            onPlayPause = { if (isPlaying) model.pause() else model.play() },
            onStop = model::stop,
            onPrevious = model::previous,
            onNext = model::next,
            onPreferences = onPreferences,
            glass = glass,
            colors = colors,
            modifier = modifier,
        )
    }
}

@Composable
fun TtsControls(
    playing: Boolean,
    onPlayPause: () -> Unit,
    onStop: () -> Unit,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onPreferences: () -> Unit,
    glass: Modifier,
    colors: TtsControlsColors,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .then(glass)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ControlButton(R.drawable.ic_reader_previous, R.string.tts_previous, colors.content, onPrevious)

        // Play / pause: the main action, a solid CREA-blue circle.
        IconButton(
            onClick = onPlayPause,
            modifier = Modifier.size(52.dp),
            colors = IconButtonDefaults.iconButtonColors(containerColor = colors.accent, contentColor = colors.onAccent),
        ) {
            Icon(
                painter = painterResource(if (playing) R.drawable.ic_reader_pause else R.drawable.ic_reader_play),
                contentDescription = stringResource(if (playing) R.string.tts_pause else R.string.tts_play),
                modifier = Modifier.size(28.dp),
            )
        }

        ControlButton(R.drawable.ic_reader_next, R.string.tts_next, colors.content, onNext)

        // A thin divider between playback and the other actions.
        Spacer(Modifier.width(4.dp))
        Box(
            Modifier
                .width(1.dp)
                .height(24.dp)
                .background(colors.content.copy(alpha = 0.2f))
        )
        Spacer(Modifier.width(4.dp))

        ControlButton(R.drawable.ic_reader_stop, R.string.tts_stop, colors.content, onStop)
        ControlButton(R.drawable.ic_reader_voice_settings, R.string.tts_settings, colors.content, onPreferences)
    }
}

@Composable
private fun ControlButton(
    @DrawableRes icon: Int,
    @StringRes label: Int,
    tint: Color,
    onClick: () -> Unit,
    size: Dp = 24.dp,
) {
    IconButton(onClick = onClick, modifier = Modifier.size(48.dp).clip(CircleShape)) {
        Icon(
            painter = painterResource(icon),
            contentDescription = stringResource(label),
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}
