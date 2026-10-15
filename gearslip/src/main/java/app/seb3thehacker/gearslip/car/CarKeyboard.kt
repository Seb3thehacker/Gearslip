package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.LaunchedEffect

/**
 * Whether a [CarKeyboard] is on screen right now, so [CarUi] can give it the row of height the
 * bottom nav bar normally holds instead of squeezing it in above - a driver typing has no use
 * for Home/Apps/Settings shortcuts anyway, and the screen is small enough that the space matters.
 */
object CarKeyboardVisibility {
    val state = mutableStateOf(false)
}

/**
 * A keyboard the host draws itself.
 *
 * Text entry in a car can't use the phone's IME: the car UI lives in a Presentation on a
 * virtual display, where a system keyboard has nowhere sensible to appear, and a phone keyboard
 * is the wrong shape for a screen a driver glances at anyway. Android Auto draws its own for
 * the same reasons, so this does too - large keys, no long-press, no prediction.
 *
 * It is a pure function of [text]: every key reports the new string and holds no state beyond
 * which layer is showing. Laid out like a PC's QWERTY keyboard rather than a phone's: shift and
 * backspace flank the letter block (top-right and bottom-left of it, same as a physical board),
 * not the space bar, so a fumbled reach for space never lands on delete instead.
 *
 * [onDismiss] is null wherever there's no keyboard-shown state independent of the screen itself
 * (a search template, sign-in) - there, the header's own back action is the only way out and no
 * dismiss button is drawn.
 */
@Composable
fun CarKeyboard(
    text: String,
    onTextChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    submitIcon: Boolean = true,
    submitImage: ImageVector? = null,
    onDismiss: (() -> Unit)? = null,
) {
    var symbols by remember { mutableStateOf(false) }
    var shifted by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        CarKeyboardVisibility.state.value = true
        onDispose { CarKeyboardVisibility.state.value = false }
    }

    // The same field on the phone, for typing on its own keyboard: see [PhoneTyping].
    val latestChange by rememberUpdatedState(onTextChange)
    val latestSubmit by rememberUpdatedState(onSubmit)
    val latestDismiss by rememberUpdatedState(onDismiss)
    val phoneField = remember {
        PhoneTyping.open(text, { latestChange(it) }, { latestSubmit() }, onDismiss?.let { { latestDismiss?.invoke() } })
    }
    DisposableEffect(phoneField) { onDispose { PhoneTyping.close(phoneField) } }
    LaunchedEffect(text) { PhoneTyping.carText(phoneField, text) }

    val rows = if (symbols) SYMBOL_ROWS else LETTER_ROWS
    val keyHeight = keyHeight(LocalConfiguration.current.screenHeightDp)

    CompositionLocalProvider(LocalKeyHeight provides keyHeight) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = modifier.fillMaxWidth(),
        ) {
            Column(
                Modifier.padding(horizontal = 6.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                rows.dropLast(1).forEach { row ->
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        row.forEach { key ->
                            val label = if (shifted && !symbols) key.uppercase() else key
                            Key(label, Modifier.weight(1f)) {
                                onTextChange(text + label)
                                shifted = false
                            }
                        }
                    }
                }

                // The bottom letter row: shift and backspace bookend it, exactly where a physical
                // keyboard puts them relative to the letter block, not down beside the space bar.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    if (!symbols) {
                        Key(if (shifted) "SHIFT" else "shift", Modifier.weight(1.6f)) { shifted = !shifted }
                    }
                    rows.last().forEach { key ->
                        val label = if (shifted && !symbols) key.uppercase() else key
                        Key(label, Modifier.weight(1f)) {
                            onTextChange(text + label)
                            shifted = false
                        }
                    }
                    Key("⌫", Modifier.weight(1.6f)) {
                        if (text.isNotEmpty()) onTextChange(text.dropLast(1))
                    }
                }

                // Control row: no backspace here - it stays with the letters above, not next to the
                // key most likely to be hit right after it by mistake. Search/enter takes the
                // bottom-right corner, where a thumb expects it; dismiss (when offered) sits left of it.
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Key(if (symbols) "abc" else "?123", Modifier.weight(1.4f)) { symbols = !symbols }
                    Key("space", Modifier.weight(if (onDismiss != null) 3.2f else 4f)) { onTextChange("$text ") }
                    if (onDismiss != null) {
                        DismissKey(onDismiss, Modifier.weight(1.4f))
                    }
                    SubmitKey(Modifier.weight(1.6f), submitIcon, submitImage, onSubmit)
                }
            }
        }
    }
}

@Composable
private fun Key(label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(LocalKeyHeight.current),
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurface),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
            )
        }
    }
}

@Composable
private fun DismissKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(LocalKeyHeight.current),
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHighest, MaterialTheme.colorScheme.onSurfaceVariant),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Filled.Close, contentDescription = "Hide keyboard")
        }
    }
}

@Composable
private fun SubmitKey(modifier: Modifier, search: Boolean, image: ImageVector?, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(LocalKeyHeight.current),
        colors = GsColors(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.onPrimary),
        shape = RoundedCornerShape(8.dp),
    ) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                image ?: if (search) Icons.Filled.Search else Icons.Filled.ArrowBack,
                contentDescription = "Submit",
            )
        }
    }
}

/**
 * Big enough to hit on a bumpy road, small enough that four rows plus a field plus a few results
 * still fit. A 480-tall screen sets the floor; a taller one (LIVI's 1280x720) gets bigger keys.
 */
private fun keyHeight(screenHeightDp: Int): Dp = (screenHeightDp * 0.09f).dp.coerceIn(44.dp, 64.dp)

private val LocalKeyHeight = compositionLocalOf { 44.dp }

private val LETTER_ROWS = listOf(
    "qwertyuiop".map { it.toString() },
    "asdfghjkl".map { it.toString() },
    "zxcvbnm".map { it.toString() },
)

private val SYMBOL_ROWS = listOf(
    "1234567890".map { it.toString() },
    listOf("-", "/", ":", ";", "(", ")", "$", "&", "@", "\""),
    listOf(".", ",", "?", "!", "'", "#", "%", "*", "+", "="),
)
