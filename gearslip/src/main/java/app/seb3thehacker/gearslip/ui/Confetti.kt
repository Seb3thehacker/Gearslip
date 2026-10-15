package app.seb3thehacker.gearslip.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.sin
import kotlin.random.Random

private class Piece(
    val x: Float,          // across the screen, 0..1
    val delay: Float,      // seconds before it starts falling
    val speed: Float,      // screen heights per second
    val sway: Float,       // how far it drifts side to side, in screen widths
    val swaySpeed: Float,
    val spin: Float,       // degrees per second
    val width: Float,      // dp
    val height: Float,
    val color: Color,
)

/**
 * Confetti falling from the top of the screen once, for about three seconds. Draws over whatever
 * is under it and takes no touches; [onDone] fires when the last piece has left the screen.
 */
@Composable
internal fun ConfettiFall(onDone: () -> Unit, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val colors = listOf(
        scheme.primary, scheme.tertiary, scheme.secondary,
        Color(0xFFFFC107), Color(0xFFE91E63), Color(0xFF4CAF50), Color(0xFF2196F3),
    )
    val pieces = remember {
        List(PIECES) {
            Piece(
                x = Random.nextFloat(),
                delay = Random.nextFloat() * 0.9f,
                speed = 0.35f + Random.nextFloat() * 0.35f,
                sway = 0.01f + Random.nextFloat() * 0.03f,
                swaySpeed = 2f + Random.nextFloat() * 4f,
                spin = (Random.nextFloat() - 0.5f) * 720f,
                width = 4f + Random.nextFloat() * 4f,
                height = 8f + Random.nextFloat() * 6f,
                color = colors[Random.nextInt(colors.size)],
            )
        }
    }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameNanos { it }
        // The slowest piece, starting last, clears the screen by this time.
        val end = pieces.maxOf { it.delay + 1.1f / it.speed }
        while (t < end) {
            withFrameNanos { t = (it - start) / 1e9f }
        }
        onDone()
    }
    Canvas(modifier.fillMaxSize()) {
        val density = density
        for (p in pieces) {
            val age = t - p.delay
            if (age < 0f) continue
            val y = (age * p.speed - 0.05f) * size.height
            if (y > size.height + 40f) continue
            val x = (p.x + sin(age * p.swaySpeed) * p.sway) * size.width
            rotate(age * p.spin, pivot = Offset(x, y)) {
                drawRect(
                    p.color,
                    topLeft = Offset(x - p.width * density / 2, y - p.height * density / 2),
                    size = Size(p.width * density, p.height * density),
                )
            }
        }
    }
}

private const val PIECES = 140
