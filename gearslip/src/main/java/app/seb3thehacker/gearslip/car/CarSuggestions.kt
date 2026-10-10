package app.seb3thehacker.gearslip.car

import androidx.annotation.MainThread
import androidx.car.app.CarToast
import androidx.car.app.model.CarIcon
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Suggested destinations belong to the live navigation session, never to a saved app catalog. */
@MainThread
internal object CarSuggestions {
    class Shortcut(
        val id: String,
        val title: String,
        val subtitle: String? = null,
        val icon: CarIcon? = null,
        val action: () -> Unit,
    )

    private var owner: Any? = null
    private val current = MutableStateFlow<List<Shortcut>>(emptyList())
    val shortcuts = current.asStateFlow()

    fun begin(owner: Any) {
        this.owner = owner
        current.value = emptyList()
    }

    fun replace(owner: Any, shortcuts: List<Shortcut>) {
        if (this.owner !== owner) return
        // Keep app order, but bound the list and avoid ambiguous duplicate shortcut IDs.
        current.value = shortcuts.asSequence()
            .filter { it.id.isNotBlank() && it.title.isNotBlank() }
            .distinctBy { it.id }
            .take(6)
            .toList()
    }

    fun launch(shortcut: Shortcut): Boolean {
        val owner = owner ?: return false
        // A tap queued before a refresh must not invoke an action the app has withdrawn.
        if (current.value.none { it === shortcut }) return false
        return try {
            shortcut.action()
            true
        } catch (error: Exception) {
            // Destinations and intent contents can be private; log only the failure type.
            GearslipLog.w("host: suggestion action failed (${error.javaClass.simpleName})")
            current.value = current.value.filterNot { it === shortcut }
            CarToasts.show(owner, "Could not open this suggestion.", CarToast.LENGTH_LONG)
            false
        }
    }

    fun clear(owner: Any) {
        if (this.owner !== owner) return
        this.owner = null
        current.value = emptyList()
    }
}
