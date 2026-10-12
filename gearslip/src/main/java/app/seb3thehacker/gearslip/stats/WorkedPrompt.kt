package app.seb3thehacker.gearslip.stats

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import app.seb3thehacker.gearslip.GearslipActivity
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.ServiceDiscovery
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONObject

/**
 * The one-time "Gearslip worked with your car. Share that?" ask, for drivers who don't share
 * usage notes. It comes after the first drive that worked, when a driver is happiest to help, as
 * a notification the moment the car leaves and as a card on Home. Answering either clears both,
 * and it never comes back.
 *
 * Saying yes turns on the same usage notes switch as Settings, and that drive's car goes in the
 * first note. Until the driver answers, the car waits here on the phone and is sent nowhere.
 */
object WorkedPrompt {

    private const val PREFS = "worked_prompt"
    private const val KEY_DONE = "done"
    private const val KEY_CAR = "car"
    private const val CHANNEL = "worked"
    private const val NOTIFICATION_ID = 4_311
    private const val ACTION_SHARE = "app.seb3thehacker.gearslip.WORKED_SHARE"
    private const val ACTION_NOT_NOW = "app.seb3thehacker.gearslip.WORKED_NOT_NOW"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private var loaded = false
    private val flow = MutableStateFlow<String?>(null)

    /** The car's name while the ask is waiting for an answer, otherwise null. */
    fun pending(context: Context): StateFlow<String?> {
        if (!loaded) {
            loaded = true
            flow.value = saved(context)?.let(::carName)
        }
        return flow
    }

    /** A drive that reached the car's screen ended cleanly. Asks, if it's never asked before. */
    fun onDriveWorked(context: Context, info: ServiceDiscovery.HeadUnitInfo?, protocol: String, screen: String, dpi: Int) {
        val p = prefs(context)
        if (UsageStats.enabled(context) || p.getBoolean(KEY_DONE, false) || p.contains(KEY_CAR)) return
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
        notify(context, carName(car))
    }

    /**
     * True once a driver turns sharing on from the ask, until Home has thrown its confetti. It
     * waits for Home: a Share tapped in the notification celebrates the next time Gearslip opens.
     */
    private val celebrateFlow = MutableStateFlow(false)
    val celebrate: StateFlow<Boolean> = celebrateFlow

    fun celebrated() { celebrateFlow.value = false }

    /** The driver answered, here or on Home. Either way it's never asked again. */
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
        NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
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

    private fun notify(context: Context, car: String) {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "After a drive", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, GearslipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val text = "Share that so the next driver knows it works? No name, account, or location."
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_menu_send)
            .setContentTitle(TITLE_START + car)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Not now", broadcast(context, ACTION_NOT_NOW))
            .addAction(0, "Share", broadcast(context, ACTION_SHARE))
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onSuccess { GearslipLog.i("worked prompt: asked after the first good drive") }
            .onFailure { GearslipLog.w("worked prompt: could not post the notification (${it.message})") }
    }

    private fun broadcast(context: Context, action: String): PendingIntent =
        PendingIntent.getBroadcast(
            context, action.hashCode(),
            Intent(context, WorkedPromptReceiver::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE,
        )

    internal fun onAction(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_SHARE -> answer(context, share = true)
            ACTION_NOT_NOW -> answer(context, share = false)
        }
    }

    const val TITLE_START = "Gearslip worked with "
}

/** The notification's Share and Not now buttons land here. */
class WorkedPromptReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) = WorkedPrompt.onAction(context, intent)
}
