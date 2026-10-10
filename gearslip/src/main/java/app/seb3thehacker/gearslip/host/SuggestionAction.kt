package app.seb3thehacker.gearslip.host

import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Bundle
import androidx.car.app.CarContext
import androidx.car.app.IStartCarApp
import java.util.concurrent.atomic.AtomicBoolean

/** CarPendingIntent broadcasts need this callback to deliver their destination to the host. */
@SuppressLint("RestrictedApi") // IStartCarApp is the host half of CarPendingIntent's public contract.
internal fun sendSuggestionAction(context: Context, action: PendingIntent, onCarIntent: (Intent) -> Unit) {
    val pending = AtomicBoolean(true)
    val receiver = object : IStartCarApp.Stub() {
        override fun startCarApp(intent: Intent?) {
            // The capability belongs to this tapped action's creator, and can be used only once.
            if (Binder.getCallingUid() != action.creatorUid || intent == null) return
            if (pending.compareAndSet(true, false)) onCarIntent(Intent(intent))
        }
    }
    val fillIn = Intent().putExtras(Bundle().apply {
        putBinder(CarContext.EXTRA_START_CAR_APP_BINDER_KEY, receiver.asBinder())
    })
    val options = if (Build.VERSION.SDK_INT >= 34) {
        ActivityOptions.makeBasic().apply {
            // This is a driver tap on the projected display, even when the phone UI is hidden.
            setPendingIntentBackgroundActivityStartMode(
                if (Build.VERSION.SDK_INT >= 36) ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOW_ALWAYS
                else ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED,
            )
        }.toBundle()
    } else null
    action.send(context, 0, fillIn, null, null, null, options)
}
