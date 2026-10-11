package app.seb3thehacker.gearslip

import android.app.Application
import java.util.concurrent.Executors

class GearslipApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        // Refresh in the background; cached identities remain available to connections immediately.
        Executors.newSingleThreadExecutor { Thread(it, "gearslip-certificates") }.apply {
            execute { ProjectionCertificates.refresh(applicationContext) }
            shutdown()
        }
    }
}
