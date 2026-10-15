package app.seb3thehacker.gearslip.car

import androidx.annotation.MainThread
import androidx.car.app.CarToast
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One toast at a time across navigation and browse apps. A newer one waits its turn instead of
 * replacing the one on screen, which gets at least [MIN_SHOWN_MS] before it gives way.
 */
@MainThread
internal object CarToasts {
    // Identity matters: repeating identical text must restart its display timer.
    class Message(val owner: Any, val text: String, val durationMs: Long)

    /** Long enough to read a short toast at a glance; a waiting toast cuts the rest short. */
    const val MIN_SHOWN_MS = 2_000L

    /** A burst beyond this many is stale by the time it would show; the oldest waiting go first. */
    private const val MAX_WAITING = 3

    private val current = MutableStateFlow<Message?>(null)
    val message: StateFlow<Message?> = current.asStateFlow()

    private val queue = ArrayDeque<Message>()
    private val _waiting = MutableStateFlow(0)
    val waiting: StateFlow<Int> = _waiting.asStateFlow()

    fun show(owner: Any, text: String, duration: Int) {
        if (text.isBlank()) return
        // Longer than a phone toast: the driver only glances at the screen now and then.
        val durationMs = if (duration == CarToast.LENGTH_LONG) 7_000L else 4_000L
        val next = Message(owner, text, durationMs)
        val shown = current.value
        when {
            shown == null -> current.value = next
            // The same words again restart the one on screen rather than queueing a copy.
            shown.text == text -> current.value = next
            queue.lastOrNull()?.text == text -> Unit
            else -> {
                queue.addLast(next)
                while (queue.size > MAX_WAITING) queue.removeFirst()
            }
        }
        _waiting.value = queue.size
    }

    fun dismiss(message: Message) {
        // An old timer must not dismiss a newer toast, even if its text is identical.
        if (current.value === message) advance()
    }

    fun clear(owner: Any) {
        // Disconnecting the map must not clear a toast belonging to the browse app.
        queue.removeAll { it.owner === owner }
        if (current.value?.owner === owner) advance() else _waiting.value = queue.size
    }

    private fun advance() {
        current.value = queue.removeFirstOrNull()
        _waiting.value = queue.size
    }
}
