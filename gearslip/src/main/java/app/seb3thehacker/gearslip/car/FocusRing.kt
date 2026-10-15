package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.material3.ripple
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DelegatingNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * What every car button shows: the usual ripple when pressed, and a ring in the accent color
 * while the knob or D-pad is on it. Cars without a knob never see the ring, since nothing takes
 * focus while the screen is in touch mode.
 *
 * It replaces the theme's ripple (LocalIndication) for the whole car UI, so any `clickable` that
 * doesn't pass its own indication gets the ring for free.
 */
internal class FocusRingIndication(private val color: Color) : IndicationNodeFactory {
    private val ripple = ripple()

    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FocusRingNode(ripple.create(interactionSource), interactionSource, color)

    override fun equals(other: Any?) = other is FocusRingIndication && other.color == color
    override fun hashCode() = color.hashCode()
}

private class FocusRingNode(
    rippleNode: DelegatableNode,
    private val source: InteractionSource,
    private val color: Color,
) : DelegatingNode(), DrawModifierNode {
    private var focused = false

    init { delegate(rippleNode) }

    override fun onAttach() {
        coroutineScope.launch {
            var holding = 0
            source.interactions.collect {
                when (it) {
                    is FocusInteraction.Focus -> holding++
                    is FocusInteraction.Unfocus -> holding--
                }
                val now = holding > 0
                if (now != focused) {
                    focused = now
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (!focused) return
        // Inside the button's bounds, since most buttons clip to their shape: drawn outside, a
        // rounded tile would cut its own ring off.
        val width = RING_WIDTH.toPx()
        val radius = minOf(CORNER.toPx(), size.minDimension / 2)
        drawRoundRect(
            color,
            topLeft = Offset(width / 2, width / 2),
            size = Size(size.width - width, size.height - width),
            cornerRadius = CornerRadius(radius),
            style = Stroke(width),
        )
    }

    private companion object {
        val RING_WIDTH = 3.dp
        val CORNER = 16.dp
    }
}
