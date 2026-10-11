package app.seb3thehacker.gearslip.car

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Place
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.seb3thehacker.gearslip.car.theme.GsButton
import app.seb3thehacker.gearslip.car.theme.GsTone

/** Direct destination shortcuts in the column beside the map, above any docked player. */
@Composable
internal fun CarSuggestionCard(
    shortcuts: List<CarSuggestions.Shortcut>,
    navigator: CarNavigator,
    modifier: Modifier = Modifier,
    compactRows: Boolean = false,
) {
    val collapsed = navigator.suggestionsCollapsed
    val expanded = navigator.suggestionsExpanded
    val listState = rememberLazyListState()
    LaunchedEffect(expanded, collapsed) { listState.scrollToItem(0) }

    Surface(modifier, shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Column(Modifier.padding(if (compactRows) 4.dp else 8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Row(
                    Modifier.weight(1f).heightIn(min = 48.dp)
                        .clickable(onClick = navigator::toggleSuggestionCard)
                        .semantics { contentDescription = if (collapsed) "Show destinations" else "Collapse destinations" }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("Destinations", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Icon(if (collapsed) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp, null)
                }
                if (!collapsed && shortcuts.size > 2) {
                    TextButton(onClick = navigator::toggleAllSuggestions) {
                        Text(if (expanded) "Less" else "All (${shortcuts.size})")
                    }
                }
            }
            if (!collapsed) {
                LazyColumn(
                    Modifier.weight(1f, fill = false),
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    items(if (expanded) shortcuts else shortcuts.take(2), key = { it.id }) { shortcut ->
                        GsButton(
                            onClick = { if (CarSuggestions.launch(shortcut)) navigator.collapseSuggestions() },
                            modifier = Modifier.fillMaxWidth().heightIn(min = if (compactRows) 48.dp else 64.dp),
                            tone = GsTone.Neutral,
                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = if (compactRows) 6.dp else 10.dp),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (shortcut.icon != null) CarGlyph(shortcut.icon, Modifier.size(28.dp))
                                else Icon(Icons.Filled.Place, null, Modifier.size(28.dp))
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                                    Text(shortcut.title, style = MaterialTheme.typography.titleSmall,
                                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                                    shortcut.subtitle?.takeIf { !compactRows && it.isNotBlank() }?.let {
                                        Text(it, style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1, overflow = TextOverflow.Ellipsis)
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
