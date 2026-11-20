/*
 * WaveBits Immersive Home Filter Chips.
 */
package com.music.spotui.ui.immersive.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.music.spotui.ui.immersive.Immersive
import com.music.spotui.ui.immersive.components.pressScalePointer
import com.music.spotui.ui.look.rememberReduceMotion

/** Rounded, but well short of a capsule: the corner reads as a cut, not a curve. */
private val ChipShape = RoundedCornerShape(12.dp)

/**
 * Filter pills rather than a tab row: squarish rounded rectangles, the selected one inverted.
 * They scroll sideways so a long label set never squeezes the text, and the gutter padding sits
 * inside the scroll so it scrolls with them. Shared by the Home facets, the Search filters and
 * the Library kinds.
 */
@Composable
internal fun ImmersiveChipRow(
    modifier: Modifier = Modifier,
    content: @Composable RowScope.() -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(PaddingValues(horizontal = Immersive.Gutter, vertical = 2.dp)),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = content,
    )
}

/** One pill of an [ImmersiveChipRow]. */
@Composable
internal fun ImmersiveChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val haptics = LocalHapticFeedback.current
    val reduceMotion = rememberReduceMotion()
    val colorSpec = remember(reduceMotion) {
        if (reduceMotion) snap<Color>() else tween<Color>(durationMillis = 130)
    }
    val bg by animateColorAsState(
        targetValue = if (selected) Immersive.Text else Immersive.Raised,
        animationSpec = colorSpec,
        label = "chipBg",
    )
    val fg by animateColorAsState(
        targetValue = if (selected) Immersive.Page else Immersive.Text,
        animationSpec = colorSpec,
        label = "chipFg",
    )
    Box(
        modifier = Modifier
            .semantics { this.selected = selected }
            .pressScalePointer(0.94f)
            .clickable(interactionSource = null, indication = null, role = Role.Tab) {
                if (!selected) haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                onClick()
            }
            .minimumInteractiveComponentSize(),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            modifier = Modifier
                .clip(ChipShape)
                .background(bg)
                .padding(horizontal = 12.dp, vertical = 6.dp),
        ) {
            Text(
                text = label,
                style = Immersive.Subhead.copy(
                    color = fg,
                    fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
                ),
                maxLines = 1,
            )
        }
    }
}
