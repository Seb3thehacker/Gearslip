package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** Draw inside the encoded car display; a normal Android Toast would appear on the phone. */
@Composable
internal fun CarToastOverlay(modifier: Modifier = Modifier) {
    val current by CarToasts.message.collectAsState()
    val message = current ?: return
    val accessibility = LocalAccessibilityManager.current
    LaunchedEffect(message, accessibility) {
        val timeout = accessibility?.calculateRecommendedTimeoutMillis(
            message.durationMs, containsIcons = false, containsText = true, containsControls = false,
        ) ?: message.durationMs
        // The full time, unless another toast is waiting: then it gives way after the minimum.
        delay(minOf(timeout, CarToasts.MIN_SHOWN_MS))
        withTimeoutOrNull((timeout - CarToasts.MIN_SHOWN_MS).coerceAtLeast(0)) {
            CarToasts.waiting.first { it > 0 }
        }
        CarToasts.dismiss(message)
    }

    // A non-interactive overlay leaves the map's touch handling and focus intact.
    Box(
        modifier
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .widthIn(max = 560.dp)
            .clip(RoundedCornerShape(20.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHigh)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Text(
            message.text,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            color = MaterialTheme.colorScheme.onSurface,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 3,
            overflow = TextOverflow.Ellipsis,
        )
    }
}
