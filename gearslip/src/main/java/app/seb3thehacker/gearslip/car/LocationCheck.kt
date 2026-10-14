package app.seb3thehacker.gearslip.car

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.os.Handler
import android.os.Looper
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Map apps can't find the car with the phone's location turned off. Ten seconds after the car
 * shows Gearslip, this checks, and the car says so until location comes on or the drive ends.
 */
object LocationCheck {

    private val _off = MutableStateFlow(false)
    /** True while the phone's location is turned off during a drive. */
    val off: StateFlow<Boolean> = _off

    private val _carDismissed = MutableStateFlow(false)
    /** The driver tapped OK on the car's card; it stays away for the rest of this drive. */
    val carDismissed: StateFlow<Boolean> = _carDismissed

    private val log = GearslipLog.tagged("location")
    private val main = Handler(Looper.getMainLooper())
    private var appContext: Context? = null
    private var armed = false
    private var receiver: BroadcastReceiver? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The car shows Gearslip. Called again whenever the video restarts, so it arms once per drive. */
    fun onProjecting() = main.post {
        if (armed) return@post
        armed = true
        main.postDelayed(::start, WAIT_MS)
    }

    fun onCarDisconnected() = main.post {
        armed = false
        main.removeCallbacksAndMessages(null)
        stopWatching()
        _off.value = false
        _carDismissed.value = false
    }

    fun dismissOnCar() {
        _carDismissed.value = true
    }

    /** "Don't show again" on the car: turns the warning off in Settings, for every car. */
    fun neverAgain() {
        CarSettings.setLocationWarning(false)
        log.i("location warning turned off")
        _carDismissed.value = true
        _off.value = false
    }

    private fun start() {
        if (!armed) return
        check()
        // Turning location on clears the warning, and turning it off again brings it back.
        val context = appContext ?: return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = check()
        }
        context.registerReceiver(r, IntentFilter(LocationManager.MODE_CHANGED_ACTION), Context.RECEIVER_EXPORTED)
        receiver = r
    }

    private fun check() {
        if (!armed) return
        val manager = appContext?.getSystemService(LocationManager::class.java) ?: return
        val off = CarSettings.locationWarning.value && !manager.isLocationEnabled
        if (_off.value == off) return
        _off.value = off
        log.i(if (off) "the phone's location is off" else "the phone's location is on")
    }

    private fun stopWatching() {
        val r = receiver ?: return
        receiver = null
        runCatching { appContext?.unregisterReceiver(r) }
    }

    private const val WAIT_MS = 10_000L
}
