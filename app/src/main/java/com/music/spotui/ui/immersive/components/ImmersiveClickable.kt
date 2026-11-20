/*
 * WaveBits Immersive press-spring Clickable modifiers.
 */
package com.music.spotui.ui.immersive.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.SuspendingPointerInputModifierNode
import androidx.compose.ui.layout.Measurable
import androidx.compose.ui.layout.MeasureResult
import androidx.compose.ui.layout.MeasureScope
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.node.CompositionLocalConsumerModifierNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.LayoutModifierNode
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.node.PointerInputModifierNode
import androidx.compose.ui.node.currentValueOf
import androidx.compose.ui.platform.InspectorInfo
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.IntSize
import com.music.spotui.ui.look.LocalReduceMotion
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Tap and long-press for cards, buttons and rows of the Immersive look: no ripple, the element
 * shrinks to [pressedScale] under the finger and springs back.
 *
 * Implemented with pure [Modifier.Node]s (no `Modifier.composed`, no per-item state allocations
 * at rest) so scrolling long lists causes zero composition/GC hitching.
 */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.immersiveClickable(
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    pressedScale: Float = 0.97f,
    enabled: Boolean = true,
    role: Role? = Role.Button,
): Modifier {
    val scaled = if (enabled && pressedScale != 1f) {
        this.then(PressScalePointerElement(pressedScale, stiffness = 1500f))
    } else {
        this
    }
    return if (onLongClick == null) {
        scaled.clickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
        )
    } else {
        scaled.combinedClickable(
            interactionSource = null,
            indication = null,
            enabled = enabled,
            role = role,
            onClick = onClick,
            onLongClick = onLongClick,
        )
    }
}

/**
 * Lightweight press-scale modifier driven directly by initial-pass pointer events: scales down
 * under finger and immediately releases if the finger lifts or moves past touch slop (scrolling).
 */
internal fun Modifier.pressScalePointer(
    pressedScale: Float = 0.97f,
    stiffness: Float = 1500f,
): Modifier = if (pressedScale == 1f) this else this.then(PressScalePointerElement(pressedScale, stiffness))

internal data class PressScalePointerElement(
    val pressedScale: Float,
    val stiffness: Float,
) : ModifierNodeElement<PressScalePointerNode>() {
    override fun create() = PressScalePointerNode(pressedScale, stiffness)
    override fun update(node: PressScalePointerNode) {
        node.pressedScale = pressedScale
        node.stiffness = stiffness
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "pressScalePointer"
        properties["pressedScale"] = pressedScale
        properties["stiffness"] = stiffness
    }
}

internal class PressScalePointerNode(
    var pressedScale: Float,
    var stiffness: Float,
) : DelegatingNode(), LayoutModifierNode, PointerInputModifierNode, CompositionLocalConsumerModifierNode {

    private var scaleAnim: Animatable<Float, *>? = null
    private var animJob: Job? = null
    private var currentScale by mutableFloatStateOf(1f)

    private val pointerNode = delegate(
        SuspendingPointerInputModifierNode {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val slop = viewConfiguration.touchSlop
                setPressed(true)
                try {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == down.id }
                        if (change == null || !change.pressed) {
                            break
                        }
                        if ((change.position - down.position).getDistance() > slop) {
                            break
                        }
                    }
                } finally {
                    setPressed(false)
                }
            }
        },
    )

    override fun onPointerEvent(
        pointerEvent: androidx.compose.ui.input.pointer.PointerEvent,
        pass: PointerEventPass,
        bounds: IntSize,
    ) {
        pointerNode.onPointerEvent(pointerEvent, pass, bounds)
    }

    override fun onCancelPointerInput() {
        pointerNode.onCancelPointerInput()
        setPressed(false)
    }

    override fun onDetach() {
        animJob?.cancel()
        currentScale = 1f
    }

    private fun setPressed(pressed: Boolean) {
        val reduce = currentValueOf(LocalReduceMotion)
        val target = if (pressed) pressedScale else 1f
        if (reduce) {
            animJob?.cancel()
            currentScale = target
            scaleAnim?.let { anim -> animJob = coroutineScope.launch { anim.snapTo(target) } }
            return
        }
        val anim = scaleAnim ?: Animatable(1f).also { scaleAnim = it }
        animJob?.cancel()
        animJob = coroutineScope.launch {
            anim.animateTo(
                targetValue = target,
                animationSpec = if (pressed) {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)
                } else {
                    spring(dampingRatio = 0.85f, stiffness = (stiffness * 0.55f).coerceAtLeast(650f))
                },
            ) {
                currentScale = value
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val s = currentScale
                if (s != 1f) {
                    scaleX = s
                    scaleY = s
                }
            }
        }
    }
}

/**
 * Lightweight press-scale + click modifier implemented with a shared [PressInteraction] collector
 * node when invoked from Compose scopes that pass an explicit [InteractionSource].
 */
internal fun Modifier.pressScaleFromInteraction(
    interactionSource: InteractionSource,
    pressedScale: Float = 0.97f,
    stiffness: Float = 900f,
): Modifier = this.then(InteractionPressScaleElement(interactionSource, pressedScale, stiffness))

internal data class InteractionPressScaleElement(
    val interactionSource: InteractionSource,
    val pressedScale: Float,
    val stiffness: Float,
) : ModifierNodeElement<InteractionPressScaleNode>() {
    override fun create() = InteractionPressScaleNode(interactionSource, pressedScale, stiffness)
    override fun update(node: InteractionPressScaleNode) {
        node.updateSource(interactionSource, pressedScale, stiffness)
    }
    override fun InspectorInfo.inspectableProperties() {
        name = "pressScaleFromInteraction"
        properties["pressedScale"] = pressedScale
    }
}

internal class InteractionPressScaleNode(
    private var interactionSource: InteractionSource,
    private var pressedScale: Float,
    private var stiffness: Float,
) : Modifier.Node(), LayoutModifierNode, CompositionLocalConsumerModifierNode {
    private var scaleAnim: Animatable<Float, *>? = null
    private var collectJob: Job? = null
    private var animJob: Job? = null
    private var currentScale by mutableFloatStateOf(1f)

    override fun onAttach() {
        startCollecting()
    }

    override fun onDetach() {
        collectJob?.cancel()
        animJob?.cancel()
        currentScale = 1f
    }

    fun updateSource(source: InteractionSource, targetScale: Float, targetStiffness: Float) {
        val sourceChanged = source != interactionSource
        interactionSource = source
        pressedScale = targetScale
        stiffness = targetStiffness
        if (sourceChanged && isAttached) {
            startCollecting()
        }
    }

    private fun startCollecting() {
        collectJob?.cancel()
        collectJob = coroutineScope.launch {
            var pressCount = 0
            interactionSource.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> {
                        if (pressCount == 0) animatePressed(true)
                        pressCount++
                    }
                    is PressInteraction.Release, is PressInteraction.Cancel -> {
                        if (pressCount > 0) pressCount--
                        if (pressCount == 0) animatePressed(false)
                    }
                }
            }
        }
    }

    private fun animatePressed(pressed: Boolean) {
        val reduce = currentValueOf(LocalReduceMotion)
        val target = if (pressed) pressedScale else 1f
        if (reduce) {
            animJob?.cancel()
            currentScale = target
            scaleAnim?.let { anim -> animJob = coroutineScope.launch { anim.snapTo(target) } }
            return
        }
        val anim = scaleAnim ?: Animatable(1f).also { scaleAnim = it }
        animJob?.cancel()
        animJob = coroutineScope.launch {
            anim.animateTo(
                targetValue = target,
                animationSpec = if (pressed) {
                    spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = stiffness)
                } else {
                    spring(dampingRatio = 0.85f, stiffness = (stiffness * 0.55f).coerceAtLeast(650f))
                },
            ) {
                currentScale = value
            }
        }
    }

    override fun MeasureScope.measure(measurable: Measurable, constraints: Constraints): MeasureResult {
        val placeable = measurable.measure(constraints)
        return layout(placeable.width, placeable.height) {
            placeable.placeWithLayer(0, 0) {
                val s = currentScale
                if (s != 1f) {
                    scaleX = s
                    scaleY = s
                }
            }
        }
    }
}


