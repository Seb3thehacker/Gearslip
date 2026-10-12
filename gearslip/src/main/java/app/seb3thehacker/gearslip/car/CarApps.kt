package app.seb3thehacker.gearslip.car

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import app.seb3thehacker.gearslip.ProjectedContent

/** One tile in the launcher and the screen it opens. */
class CarApp(
    val id: String,
    val label: String,
    val icon: ImageVector,
    val content: @Composable () -> Unit,
)

/** Built-in apps. Third-party (Android Auto) apps are a later step and will register here too. */
object CarApps {

    val all: List<CarApp> = listOf(
        CarApp("web", "Browser", Icons.Filled.Search) {
            WebApp(content = ProjectedContent.resolve(LocalContext.current))
        },
        CarApp("phone", "Screen sharing", Icons.Filled.Share) {
            PhoneAppsScreen()
        },
        CarApp("dialer", "Phone", Icons.Filled.Call) {
            PhoneDialerScreen()
        },
        CarApp("settings", "Settings", Icons.Filled.Settings) {
            CarSettingsScreen()
        },
    )

    fun find(id: String): CarApp? = all.firstOrNull { it.id == id }
}
