package org.crea.reader.ui.glass

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.colorResource
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.crea.reader.R

/** One tab of the bar: an outline icon, a filled one for when it is selected, and its name. */
data class LiquidNavItem(
    @DrawableRes val icon: Int,
    @DrawableRes val iconSelected: Int,
    @StringRes val label: Int,
)

private val PillShape = RoundedCornerShape(percent = 50)

/**
 * Floating navigation bar made of Liquid Glass: it blurs and refracts what is behind it, has a soft
 * luminous edge, and the selected tab expands to show its name with a spring animation.
 *
 * With [effects] off (battery saver) the same translucent surface is drawn without the shaders.
 */
@Composable
fun LiquidNavigationBar(
    items: List<LiquidNavItem>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    backdrop: ViewBackdrop,
    effects: Boolean,
    modifier: Modifier = Modifier,
) {
    val surface = liquidGlassSurfaceColor()

    Row(
        modifier = modifier
            .liquidGlass(backdrop, PillShape, surface, effects)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        items.forEachIndexed { index, item ->
            NavTab(item, selected = index == selectedIndex, onClick = { onSelect(index) })
        }
    }
}

@Composable
private fun NavTab(item: LiquidNavItem, selected: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.9f else 1f,
        animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium),
        label = "tabPress",
    )
    val label = stringResource(item.label)
    val tint = if (selected) colorResource(R.color.crea_primary) else colorResource(R.color.crea_on_surface_variant)
    val fill = if (selected) colorResource(R.color.crea_primary).copy(alpha = 0.16f) else Color.Transparent

    Row(
        modifier = Modifier
            .padding(horizontal = 1.dp)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(PillShape)
            .background(fill)
            .heightIn(min = 48.dp)
            .widthIn(min = 48.dp)
            .semantics {
                this.selected = selected
                contentDescription = label
            }
            .clickable(
                interactionSource = interaction,
                indication = LocalIndication.current,
                role = Role.Tab,
                onClick = onClick,
            )
            .padding(horizontal = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
    ) {
        Icon(
            painter = painterResource(if (selected) item.iconSelected else item.icon),
            contentDescription = null,
            tint = tint,
            modifier = Modifier.size(26.dp),
        )
        AnimatedVisibility(
            visible = selected,
            enter = expandHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeIn(),
            exit = shrinkHorizontally(spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessMediumLow)) + fadeOut(),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(8.dp))
                Text(
                    text = label,
                    color = tint,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
