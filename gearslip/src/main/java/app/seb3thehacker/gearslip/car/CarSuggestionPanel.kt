package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsIconButton
import app.seb3thehacker.gearslip.car.theme.GsTone

/** Opened explicitly over the map; neither suggestion updates nor this panel resize its surface. */
@Composable
internal fun CarSuggestionPanel(navigator: CarNavigator) {
    val shortcuts by CarSuggestions.shortcuts.collectAsState()
    LaunchedEffect(shortcuts.isEmpty()) {
        if (shortcuts.isEmpty()) navigator.dismissSuggestions()
    }
    if (!navigator.suggestionsOpen || navigator.current != CarScreen.Home || shortcuts.isEmpty()) return

    BoxWithConstraints(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.35f))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = navigator::dismissSuggestions,
                ),
        )
        Surface(
            modifier = Modifier.align(Alignment.BottomStart).padding(12.dp)
                .widthIn(max = 420.dp).fillMaxWidth()
                .heightIn(max = (maxHeight - 24.dp).coerceAtLeast(0.dp))
                .pointerInput(Unit) { detectTapGestures { } },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Suggested destinations",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.titleMedium,
                    )
                    GsIconButton(Icons.Filled.Close, "Close suggestions", navigator::dismissSuggestions)
                }
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(shortcuts, key = { it.id }) { shortcut ->
                        GsButton(
                            onClick = {
                                if (CarSuggestions.launch(shortcut)) navigator.dismissSuggestions()
                            },
                            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp),
                            tone = GsTone.Neutral,
                            contentPadding = PaddingValues(12.dp),
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp),
                            ) {
                                shortcut.icon?.let { CarGlyph(it, Modifier.size(32.dp)) }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Text(
                                        shortcut.title, style = MaterialTheme.typography.titleSmall,
                                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                                    )
                                    shortcut.subtitle?.takeIf { it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
