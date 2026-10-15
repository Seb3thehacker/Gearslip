package app.seb3thehacker.gearslip.car.theme

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * How a control says it also does something when held. [GsIconBox] draws it on its own for any
 * control given an onLongClick, so callers never place it by hand.
 */
enum class HoldMarkerStyle {
    /** The top layer peels back from the top-right corner, further while held. Needs depth. */
    PEEL,

    /** A plain dot in the top-right corner. It doesn't react to a hold. */
    DOT,

    /** Nothing: the hold stays undiscovered. For themes that mark it some other way. */
    NONE,
}

/**
 * The hold marker for one control, drawn under its content. [container] is the control's own
 * colour, so the peel's flap matches whatever it sits on. Round and pill-shaped controls get the
 * dot whatever the theme says: a circle has no corner to peel.
 */
private const val SHOW_HOLD_DOT = false

@Composable
internal fun BoxScope.GsHoldMarker(style: HoldMarkerStyle, container: Color, shape: Shape, press: InteractionSource) {
    if (style == HoldMarkerStyle.NONE) return
    val pressed by press.collectIsPressedAsState()
    val timeout = LocalViewConfiguration.current.longPressTimeoutMillis.toInt()
    val lift = remember { Animatable(0f) }
    if (style == HoldMarkerStyle.PEEL) {
        LaunchedEffect(pressed) {
            if (pressed) lift.animateTo(1f, tween(timeout, easing = LinearEasing)) else lift.animateTo(0f, tween(220))
        }
    }
    val ink = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.primaryContainer
    val onAccent = MaterialTheme.colorScheme.onPrimaryContainer
    Canvas(Modifier.matchParentSize()) {
        val side = min(size.width, size.height)
        val r = (shape as? CornerBasedShape)?.topEnd?.toPx(size, this) ?: 0f
        val round = r >= side * 0.4f
        // The dot is off until it's redesigned: flat themes and round controls show nothing.
        if (style == HoldMarkerStyle.DOT || round) {
            if (SHOW_HOLD_DOT) drawHoldDot(r, side, ink)
        } else {
            // Drawn for the bottom-right corner, then flipped up to the top-right.
            scale(1f, -1f, pivot = center) {
                drawPeel(r, side, container, accent, onAccent, lift.value)
            }
        }
    }
}

/** Depth of a corner's curve along its diagonal: a fold shallower than this only shaves the curve. */
private fun curveDepth(r: Float) = r * (2f - 1.41421f)

private fun DrawScope.drawHoldDot(r: Float, side: Float, ink: Color) {
    val dot = (side * 0.06f).coerceIn(5.dp.toPx(), 7.dp.toPx())
    // Tucked into the corner, clear of its curve.
    val inset = r * (1f - 0.7071f) + (side * 0.05f).coerceIn(4.dp.toPx(), 6.dp.toPx()) + dot / 2
    drawCircle(ink.copy(alpha = 0.6f), radius = dot / 2, center = Offset(size.width - inset, inset))
}

/** The part of a rounded rectangle beyond the fold line x + y = c, as a closed outline. */
private fun cornerRegion(w: Float, h: Float, r: Float, c: Float): List<Offset> {
    // Walk the bottom-right boundary: down the right edge, round the corner, along the bottom.
    val reach = (h + w - c) + 1f
    val edge = ArrayList<Offset>()
    edge += Offset(w, h - maxOf(r, reach))
    if (r > 0f) {
        for (i in 0..24) {
            val a = (i / 24f) * (PI / 2).toFloat()
            edge += Offset(w - r + r * cos(a), h - r + r * sin(a))
        }
    } else {
        edge += Offset(w, h)
    }
    edge += Offset(w - maxOf(r, reach), h)
    // Keep what lies beyond the fold, adding the exact crossing points.
    val out = ArrayList<Offset>()
    for (i in 0 until edge.size - 1) {
        val p = edge[i]
        val q = edge[i + 1]
        val ps = p.x + p.y - c
        val qs = q.x + q.y - c
        if (ps >= 0) out += p
        if ((ps >= 0) != (qs >= 0)) {
            val t = ps / (ps - qs)
            out += Offset(p.x + (q.x - p.x) * t, p.y + (q.y - p.y) * t)
        }
    }
    val last = edge.last()
    if (last.x + last.y - c >= 0) out += last
    return out
}

private fun pathOf(points: List<Offset>): Path = Path().apply {
    if (points.isEmpty()) return@apply
    moveTo(points[0].x, points[0].y)
    for (p in points.drop(1)) lineTo(p.x, p.y)
    close()
}

/**
 * The top layer peeling back from the corner, showing a second layer underneath with the same
 * rounded corner, so the control's outline never changes. Sized from the control: a launcher
 * tile folds about 27dp at rest and 64dp held, a nav bar button far less.
 */
private fun DrawScope.drawPeel(r: Float, side: Float, face: Color, accent: Color, onAccent: Color, lift: Float) {
    val w = size.width
    val h = size.height
    val rest = curveDepth(r) + (side * 0.1f).coerceIn(4.dp.toPx(), 11.dp.toPx())
    val held = rest + min(37.dp.toPx(), side * 0.35f)
    val e = rest + (held - rest) * lift
    val c = w + h - e
    val region = cornerRegion(w, h, r, c)
    if (region.size < 3) return
    val mid = Offset(w - e / 2, h - e / 2)
    // 1. The second layer, turning the accent colour as the hold completes.
    drawPath(pathOf(region), lerp(lerp(face, Color.Black, 0.26f), accent, lift))
    // The lifted edge of the top layer shades the second layer just beside it.
    drawPath(
        pathOf(region),
        Brush.linearGradient(
            listOf(Color.Black.copy(alpha = 0.35f), Color.Transparent),
            start = mid,
            end = mid + Offset(e * 0.25f, e * 0.25f),
        ),
    )
    // 2. "More" waits on the second layer, readable once the peel is well back.
    if (lift > 0.35f && side > 80.dp.toPx()) {
        val a = ((lift - 0.35f) / 0.65f).coerceIn(0f, 1f)
        val centre = Offset(w - e * 0.3f, h - e * 0.3f)
        val gap = 5.5.dp.toPx()
        for (k in -1..1) {
            drawCircle(onAccent.copy(alpha = a), radius = 2.2.dp.toPx(), center = centre + Offset(k * gap * 0.71f, -k * gap * 0.71f))
        }
    }
    // 3. The flap: the same corner folded back over the face, its rounded tip and all.
    val flap = region.map { Offset(c - it.y, c - it.x) }
    translate(-1.5.dp.toPx(), -1.5.dp.toPx()) {
        drawPath(pathOf(flap), Color.Black.copy(alpha = 0.30f))
    }
    drawPath(
        pathOf(flap),
        Brush.linearGradient(
            listOf(lerp(face, Color.White, 0.30f), lerp(face, Color.White, 0.08f)),
            start = Offset(c - h, c - w),
            end = mid,
        ),
    )
}
