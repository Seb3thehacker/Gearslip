package app.seb3thehacker.gearslip.car

import app.seb3thehacker.gearslip.car.theme.*
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A phone contact with at least one number, as read off the device's contacts. */
internal data class Contact(val name: String, val number: String)

private val CALL_PERMISSIONS = arrayOf(Manifest.permission.CALL_PHONE, Manifest.permission.READ_CONTACTS)

/** Also used by [AssistantCommands] for "call <name>". */
internal fun hasCallPermissions(context: Context): Boolean =
    CALL_PERMISSIONS.all { context.checkSelfPermission(it) == PackageManager.PERMISSION_GRANTED }

internal fun loadContacts(context: Context): List<Contact> {
    if (context.checkSelfPermission(Manifest.permission.READ_CONTACTS) != PackageManager.PERMISSION_GRANTED) return emptyList()
    val out = mutableListOf<Contact>()
    val projection = arrayOf(
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
        ContactsContract.CommonDataKinds.Phone.NUMBER,
    )
    context.contentResolver.query(
        ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
        projection, null, null,
        "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} ASC",
    )?.use { cursor ->
        val nameIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME)
        val numberIndex = cursor.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
        while (cursor.moveToNext()) {
            val name = cursor.getString(nameIndex) ?: continue
            val number = cursor.getString(numberIndex) ?: continue
            out += Contact(name, number)
        }
    }
    return out.distinctBy { it.number }
}

internal fun placeCall(context: Context, number: String) {
    if (number.isBlank()) return
    if (context.checkSelfPermission(Manifest.permission.CALL_PHONE) != PackageManager.PERMISSION_GRANTED) return
    runCatching {
        context.startActivity(
            Intent(Intent.ACTION_CALL, Uri.parse("tel:${Uri.encode(number)}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }
}

/** A call another car screen wants to place - a number on a place page - waiting on the driver. */
internal object CallPrompt {
    val pending = kotlinx.coroutines.flow.MutableStateFlow<Contact?>(null)

    fun ask(name: String, number: String) {
        pending.value = Contact(name, number)
    }
}

/** The dialer's own confirmation, drawn over the whole car screen for [CallPrompt]. */
@Composable
internal fun CallPromptOverlay() {
    val context = LocalContext.current
    val contact by CallPrompt.pending.collectAsState()
    val c = contact ?: return
    ConfirmCallOverlay(
        c,
        onConfirm = {
            CallPrompt.pending.value = null
            placeCall(context, c.number)
        },
        onDismiss = { CallPrompt.pending.value = null },
    )
}

private const val TAB_CONTACTS = 0
private const val TAB_KEYPAD = 1

/** Dialing a number or calling a contact from the car screen: a contacts tab and a keypad tab. */
@Composable
fun PhoneDialerScreen() {
    val context = LocalContext.current
    val hasPermissions = hasCallPermissions(context)
    var tab by remember { mutableStateOf(TAB_CONTACTS) }

    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        // The tabs share the title's row, so the list and keypad get the height back.
        ScreenHeader("Phone") {
            if (hasPermissions) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialerTab("Contacts", tab == TAB_CONTACTS, Modifier.width(140.dp)) { tab = TAB_CONTACTS }
                    DialerTab("Keypad", tab == TAB_KEYPAD, Modifier.width(140.dp)) { tab = TAB_KEYPAD }
                }
            }
        }

        if (!hasPermissions) {
            MissingPermission(context)
            return@Column
        }

        Box(Modifier.weight(1f).fillMaxWidth()) {
            if (tab == TAB_CONTACTS) ContactsList(context) else Keypad(context)
        }
    }
}

@Composable
private fun MissingPermission(context: Context) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            "Gearslip needs the Phone and Contacts permissions to dial from here. Grant them " +
                "from the app's settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        GsButton(tone = GsTone.Tonal, onClick = {
            context.startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)),
            )
        }) { Text("Open app settings") }
    }
}

@Composable
private fun DialerTab(label: String, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick = onClick,
        modifier = modifier.height(44.dp),
        colors = if (selected) GsColors(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.onPrimaryContainer)
        else GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
        latched = selected,
    ) {
        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal)
    }
}

/**
 * A 3-column digit grid beside a fourth column holding backspace and call, stacked - four equal
 * columns in all. Every row and both side buttons take an equal weighted share of whatever height
 * is available, so this fits the car's short landscape frame without scrolling instead of
 * overflowing off the bottom the way fixed-height rows did.
 */
@Composable
private fun Keypad(context: Context) {
    var number by remember { mutableStateOf("") }
    val rows = listOf(
        listOf("1", "2", "3"),
        listOf("4", "5", "6"),
        listOf("7", "8", "9"),
        listOf("*", "0", "#"),
    )

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            number.ifEmpty { "Enter a number" },
            style = MaterialTheme.typography.headlineSmall,
            color = if (number.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(vertical = 6.dp),
        )
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                rows.forEach { row ->
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { digit ->
                            KeypadButton(digit, Modifier.weight(1f).fillMaxHeight()) { number += digit }
                        }
                    }
                }
            }
            Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                GsButton(
                    onClick = { if (number.isNotEmpty()) number = number.dropLast(1) },
                    tone = GsTone.Tonal,
                    enabled = number.isNotEmpty(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { Text("⌫", style = MaterialTheme.typography.titleLarge) }
                GsIconBox(
                    onClick = { placeCall(context, number) },
                    colors = gsColors(GsTone.Primary),
                    enabled = number.isNotEmpty(),
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                ) { Icon(Icons.Filled.Call, "Call") }
            }
        }
    }
}

@Composable
private fun KeypadButton(digit: String, modifier: Modifier, onClick: () -> Unit) {
    GsIconBox(
        onClick,
        modifier,
        colors = GsColors(MaterialTheme.colorScheme.surfaceContainerHigh, MaterialTheme.colorScheme.onSurface),
        shape = MaterialTheme.shapes.large,
    ) {
        Text(digit, style = MaterialTheme.typography.titleLarge)
    }
}

@Composable
private fun ContactsList(context: Context) {
    val contacts by produceState(emptyList<Contact>(), context) {
        value = withContext(Dispatchers.IO) { loadContacts(context) }
    }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var pendingCall by remember { mutableStateOf<Contact?>(null) }

    val filtered = remember(contacts, query) {
        if (query.isBlank()) contacts
        else contacts.filter { it.name.contains(query, ignoreCase = true) || it.number.contains(query) }
    }

    Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (contacts.isNotEmpty()) {
            ContactSearchField(query, searching, onToggle = { searching = !searching })
        }
        // The list (and its empty states) sit between the search field and the keyboard, never
        // under either - the keyboard is docked at the bottom instead of jammed in right below
        // the field, so results stay visible while typing rather than getting squeezed out.
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when {
                contacts.isEmpty() -> Text(
                    "No contacts with a phone number found.",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                filtered.isEmpty() -> Text(
                    "No contacts match \"$query\".",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                else -> LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(filtered) { contact ->
                        ContactRow(contact) { pendingCall = contact }
                    }
                }
            }
        }
        if (searching) {
            CarKeyboard(
                query,
                onTextChange = { query = it },
                onSubmit = { searching = false },
                onDismiss = { searching = false },
            )
        }
    }

    pendingCall?.let { contact ->
        ConfirmCallOverlay(
            contact = contact,
            onConfirm = { placeCall(context, contact.number); pendingCall = null },
            onDismiss = { pendingCall = null },
        )
    }
}

/**
 * A plain Compose overlay, not [androidx.compose.material3.AlertDialog] - that opens a real
 * Android Dialog/window, and this UI lives in a Presentation on a virtual display where a second
 * window of the wrong type crashes outright ("Window type mismatch"). Every other popup in the
 * car UI is drawn in-composition for the same reason; this just follows suit.
 */
@Composable
private fun ConfirmCallOverlay(contact: Contact, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.6f)).clickable(
            indication = null,
            interactionSource = remember { MutableInteractionSource() },
            onClick = onDismiss,
        ),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            onClick = {},
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
            modifier = Modifier.fillMaxWidth(0.85f).padding(16.dp),
        ) {
            Column(
                Modifier.padding(32.dp),
                verticalArrangement = Arrangement.spacedBy(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("Call ${contact.name}?", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                Text(contact.number, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    GsButton(onClick = onDismiss, tone = GsTone.Outline, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Cancel", style = MaterialTheme.typography.titleMedium)
                    }
                    GsButton(onClick = onConfirm, modifier = Modifier.weight(1f).height(56.dp)) {
                        Text("Call", style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        }
    }
}

@Composable
private fun ContactSearchField(query: String, active: Boolean, onToggle: () -> Unit) {
    Surface(
        onClick = onToggle,
        shape = MaterialTheme.shapes.large,
        color = if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text(
            query.ifEmpty { "Search contacts" },
            style = MaterialTheme.typography.bodyLarge,
            color = if (query.isEmpty()) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp),
        )
    }
}

@Composable
private fun ContactRow(contact: Contact, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(40.dp).background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    contact.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(Modifier.weight(1f)) {
                Text(contact.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(contact.number, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Icon(Icons.Filled.Call, contentDescription = "Call", tint = MaterialTheme.colorScheme.primary)
        }
    }
}
