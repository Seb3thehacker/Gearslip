package app.seb3thehacker.gearslip.car

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import app.seb3thehacker.gearslip.GearslipLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * With Bluetooth audio chosen, music only reaches the car if the phone is connected to it over
 * Bluetooth. Twenty seconds after the car shows Gearslip, long enough for a car to connect on its
 * own, this checks. If the phone isn't connected, the car and the phone's Home screen both say so,
 * until it connects or the drive ends.
 *
 * When the car named its Bluetooth address, only that device counts. Otherwise any audio
 * connection does, since there's no telling which device is the car.
 */
object BluetoothAudioCheck {

    private val _missing = MutableStateFlow(false)
    /** True while Bluetooth audio is chosen and the phone isn't connected to the car for it. */
    val missing: StateFlow<Boolean> = _missing

    private val _carDismissed = MutableStateFlow(false)
    /** The driver tapped OK on the car's card; it stays away for the rest of this drive. */
    val carDismissed: StateFlow<Boolean> = _carDismissed

    private val log = GearslipLog.tagged("bluetooth")
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
        _missing.value = false
        _carDismissed.value = false
    }

    fun dismissOnCar() {
        _carDismissed.value = true
    }

    /** "Don't show again" on the car: turns the warning off in Settings, for every car. */
    fun neverAgain() {
        CarSettings.setBluetoothWarning(false)
        log.i("Bluetooth audio warning turned off")
        _carDismissed.value = true
        _missing.value = false
    }

    private fun start() {
        if (!armed) return
        check()
        // Connecting later clears the warning, and losing the connection brings it back.
        val context = appContext ?: return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                main.postDelayed(::check, SETTLE_MS)
            }
        }
        context.registerReceiver(
            r,
            IntentFilter(BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED).apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            },
            Context.RECEIVER_EXPORTED,
        )
        receiver = r
    }

    @SuppressLint("MissingPermission")
    private fun check() {
        if (!armed) return
        val context = appContext ?: return
        if (CarSettings.pipeAudio.value) {
            set(false, "USB audio is on")
            return
        }
        if (!CarSettings.bluetoothWarning.value) {
            set(false, "the warning is turned off")
            return
        }
        if (context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            log.i("can't check the audio connection: the Bluetooth permission isn't granted")
            return
        }
        val adapter = context.getSystemService(BluetoothManager::class.java)?.adapter ?: return
        if (!adapter.isEnabled) {
            set(true, "Bluetooth is off")
            return
        }
        val car = CarBluetooth.carAddress?.uppercase()
        adapter.getProfileProxy(context, object : BluetoothProfile.ServiceListener {
            override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
                val connected = runCatching { proxy.connectedDevices }.getOrDefault(emptyList())
                adapter.closeProfileProxy(profile, proxy)
                val ok = if (car != null) connected.any { it.address.uppercase() == car } else connected.isNotEmpty()
                main.post {
                    set(!ok, if (ok) "connected to the car" else "not connected to the car")
                }
            }

            override fun onServiceDisconnected(profile: Int) {}
        }, BluetoothProfile.A2DP)
    }

    private fun set(missing: Boolean, why: String) {
        if (!armed || _missing.value == missing) return
        _missing.value = missing
        log.i("Bluetooth audio: $why")
    }

    private fun stopWatching() {
        val r = receiver ?: return
        receiver = null
        runCatching { appContext?.unregisterReceiver(r) }
    }

    private const val WAIT_MS = 20_000L
    private const val SETTLE_MS = 1_000L
}
