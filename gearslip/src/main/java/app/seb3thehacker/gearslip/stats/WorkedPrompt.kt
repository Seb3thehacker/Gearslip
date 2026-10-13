package app.seb3thehacker.gearslip.stats

import android.content.Context
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.ServiceDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The one-time "Gearslip worked with your car. Share that?" ask, for drivers who never answered
 * the usage notes question in setup, either way. It comes after the first drive that worked, when a driver is happiest to help, as
 * a card on Home. Answering it clears it, and it never comes back.
 *
 * Saying yes turns on the same usage notes switch as Settings, and that drive's car goes in the
 * first note. Until the driver answers, the car waits here on the phone and is sent nowhere.
 */
object WorkedPrompt {

    private const val PREFS = "worked_prompt"
    private const val KEY_DONE = "done"
    private const val KEY_CAR = "car"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var loaded = false
    private val flow = MutableStateFlow<String?>(null)

    /** The car's name while the ask is waiting for an answer, otherwise null. */
    fun pending(context: Context): StateFlow<String?> {
        if (!loaded) {
            loaded = true
            flow.value = saved(context)?.takeUnless { UsageStats.asked(context) }?.let(::carName)
        }
        return flow
    }

    /** A drive that reached the car's screen ended cleanly. Asks, if it's never asked before. */
    fun onDriveWorked(context: Context, info: ServiceDiscovery.HeadUnitInfo?, protocol: String, screen: String, dpi: Int) {
        val p = prefs(context)
        // Asked once only: a driver who answered in setup already said yes or no.
        if (UsageStats.asked(context) || p.getBoolean(KEY_DONE, false) || p.contains(KEY_CAR)) return
        val car = JSONObject()
            .put("name", info?.headUnitName.orEmpty())
            .put("car", info?.carModel.orEmpty())
            .put("year", info?.carYear.orEmpty())
            .put("make", info?.headUnitMake.orEmpty())
            .put("model", info?.headUnitModel.orEmpty())
            .put("protocol", protocol)
            .put("screen", screen)
            .put("dpi", dpi)
        p.edit().putString(KEY_CAR, car.toString()).apply()
        loaded = true
        flow.value = carName(car)
    }

    /** True once a driver turns sharing on from the ask, until Home has thrown its confetti. */
    private val celebrateFlow = MutableStateFlow(false)
    val celebrate: StateFlow<Boolean> = celebrateFlow

    fun celebrated() { celebrateFlow.value = false }

    /** The driver answered on Home. Either way it's never asked again. */
    fun answer(context: Context, share: Boolean) {
        if (share) {
            // Turning sharing on lands in [onSharingOn], which clears the ask.
            UsageStats.set(context, true)
            return
        }
        clear(context)
        GearslipLog.i("worked prompt: not now")
    }

    /**
     * Usage notes were just turned on, from the ask or anywhere else. With the ask still waiting,
     * that answers it, and the car it was about goes in the first note.
     */
    internal fun onSharingOn(context: Context) {
        val car = saved(context) ?: return
        clear(context)
        celebrateFlow.value = true
        GearslipLog.i("worked prompt: usage notes turned on")
        val info = ServiceDiscovery.HeadUnitInfo(
            car.optString("name"), car.optString("car"), car.optString("year"),
            car.optString("make"), car.optString("model"),
        )
        UsageStats.recordSession(
            context, info, car.optString("protocol"), "connected",
            car.optString("screen"), car.optInt("dpi"),
        )
    }

    private fun clear(context: Context) {
        prefs(context).edit().remove(KEY_CAR).putBoolean(KEY_DONE, true).apply()
        loaded = true
        flow.value = null
    }

    private fun saved(context: Context): JSONObject? =
        prefs(context).getString(KEY_CAR, null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** "your 2019 Clio", "your Renault Media Nav", or "your car" when it didn't say. */
    private fun carName(car: JSONObject): String {
        val model = car.optString("car").trim()
        val year = car.optString("year").trim().takeIf { it.isNotEmpty() && it != "0" }
        val unit = "${car.optString("make")} ${car.optString("model")}".trim()
        return when {
            model.isNotEmpty() -> "your " + listOfNotNull(year, model).joinToString(" ")
            unit.isNotEmpty() -> "your $unit"
            else -> "your car"
        }
    }

    const val TITLE_START = "Gearslip worked with "
}
