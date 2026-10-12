package app.seb3thehacker.gearslip.car

import android.content.Context
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Turns what [CarAssistant] heard into an action plus something to say back.
 *
 * Deliberately small and keyword-matched rather than run through any NLU or bundled model: a
 * fixed set of things a driver actually wants hands-free (media, calling, opening an app,
 * getting back Home), covering the same ground the rest of the car UI already does rather than
 * trying to be a general assistant.
 */
object AssistantCommands {

    suspend fun handle(context: Context, navigator: CarNavigator, frame: CarEnvironment.Frame, heardRaw: String): String {
        val heard = heardRaw.lowercase().trim().trimEnd('.', '?', '!')
        VoiceHelp.hide()
        if (heard in HELP_WORDS || heard.startsWith("what can i say") || heard.startsWith("what can you do")) {
            VoiceHelp.show()
            return "Here's what you can say."
        }

        navigateCommand(context, navigator, frame, heard)?.let { return it }
        playSearchCommand(heard)?.let { return it }
        mediaCommand(heard)?.let { return it }
        navCommand(navigator, heard)?.let { return it }
        infoCommand(heard)?.let { return it }
        callCommand(context, heard)?.let { return it }
        openAppCommand(context, navigator, frame, heard)?.let { return it }

        return "I don't know how to do that yet."
    }

    /**
     * "navigate to X [with|using|on|via Y]" - Y picks the nav app by (partial) label; without it,
     * whichever nav app was used last, or the only one installed, or the first one found. Starts
     * it straight into navigating there via [CarAppConnection]'s `ACTION_NAVIGATE` deep link -
     * the same one Android Auto's own Assistant integration uses - rather than opening the app
     * and leaving the driver to type the address in on the car keyboard.
     */
    private suspend fun navigateCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        val trigger = NAVIGATE_TRIGGERS.firstOrNull { heard.startsWith(it) } ?: return null
        var destination = heard.removePrefix(trigger).trim()
        if (destination.isBlank()) return "Where to?"

        var appName: String? = null
        for (sep in listOf(" with ", " using ", " on ", " via ")) {
            val at = destination.lastIndexOf(sep)
            if (at > 0) {
                appName = destination.substring(at + sep.length).trim()
                destination = destination.substring(0, at).trim()
                break
            }
        }
        if (destination.isBlank()) return "Where to?"

        val entries = LauncherCache.entries ?: withContext(Dispatchers.IO) { LauncherCache.load(context) }
        val navApps = entries.filter { it.template?.isNavigation == true }
        if (navApps.isEmpty()) return "You don't have any navigation apps installed."

        val requestedApp = appName
        val chosen = when {
            requestedApp != null -> navApps.firstOrNull { it.label.lowercase().contains(requestedApp) }
                ?: return "I couldn't find a navigation app called $requestedApp."
            navApps.size == 1 -> navApps.first()
            else -> navApps.firstOrNull { it.componentId == CarSettings.lastNav.value } ?: navApps.first()
        }
        val template = chosen.template ?: return "That's not a navigation app."

        CarServices.connectNav(template, frame, destination)
        navigator.home()
        return "Navigating to $destination with ${chosen.label}."
    }

    /**
     * "play X" with something after it: a song, artist, album or playlist, asked of the music app
     * that's open through its own search ([CarMedia.search]). A bare "play", or "play music",
     * falls through to resume.
     */
    private fun playSearchCommand(heard: String): String? {
        if (!heard.startsWith("play ")) return null
        val query = heard.removePrefix("play ").trim().removeSuffix(".").trim()
        if (query.isEmpty() || query in RESUME_WORDS) return null
        val media = CarServices.media
        val app = media.appLabel ?: return "Open a music app first, then ask again."
        if (!media.canSearchLibrary && !media.now.value.canSearch) {
            return "$app ignores voice searches. Pick something on its screen instead."
        }
        media.playBestMatch(query)
        return "Looking for $query on $app."
    }

    private fun mediaCommand(heard: String): String? {
        val media = CarServices.media
        return when {
            "pause" in heard || heard.startsWith("stop") -> {
                if (media.now.value.playing) media.togglePlay()
                "Paused."
            }
            "resume" in heard || (heard.startsWith("play") && "playing" !in heard) -> {
                if (!media.now.value.playing) media.togglePlay()
                "Playing."
            }
            "next" in heard || "skip" in heard -> {
                media.next()
                "Skipping."
            }
            "previous" in heard || "last song" in heard || "back song" in heard -> {
                media.previous()
                "Playing the previous track."
            }
            "what" in heard && "playing" in heard -> {
                val now = media.now.value
                if (now.isActive) listOf(now.title, now.artist).filter { it.isNotBlank() }.joinToString(" by ")
                else "Nothing's playing."
            }
            else -> null
        }
    }

    private fun navCommand(navigator: CarNavigator, heard: String): String? = when {
        "go home" in heard || heard == "home" -> {
            navigator.home()
            "Heading home."
        }
        "open apps" in heard || heard == "apps" || "app list" in heard -> {
            navigator.apps()
            "Here are your apps."
        }
        "settings" in heard -> {
            navigator.settings()
            "Opening settings."
        }
        "vehicle data" in heard || "car data" in heard -> {
            if (CarSettings.experimentalFeaturesEnabled.value && CarSettings.carSensorsEnabled.value) {
                navigator.vehicleData()
                "Opening vehicle data."
            } else {
                "Vehicle data is off. Turn it on under experimental features in settings."
            }
        }
        else -> null
    }

    private fun infoCommand(heard: String): String? = when {
        "what time" in heard || "what's the time" in heard ->
            "It's ${DateFormat.getTimeInstance(DateFormat.SHORT).format(Date())}."
        "weather" in heard -> {
            val data = (Weather.state.value as? WeatherState.Ready)?.data
            if (data != null) "${data.temp}${data.tempUnit} and ${Weather.describe(data.code)} in ${data.place}."
            else "I don't have the weather yet."
        }
        else -> null
    }

    private suspend fun callCommand(context: Context, heard: String): String? {
        val name = when {
            heard.startsWith("call ") -> heard.removePrefix("call ")
            heard.startsWith("phone ") -> heard.removePrefix("phone ")
            heard.startsWith("dial ") -> heard.removePrefix("dial ")
            else -> return null
        }.trim()
        if (name.isBlank()) return "Who should I call?"
        if (!hasCallPermissions(context)) return "Gearslip needs the phone and contacts permissions first, from the phone screen."

        val match = withContext(Dispatchers.IO) {
            loadContacts(context).firstOrNull { it.name.lowercase().contains(name) }
        } ?: return "I couldn't find $name in your contacts."

        placeCall(context, match.number)
        return "Calling ${match.name}."
    }

    private suspend fun openAppCommand(
        context: Context,
        navigator: CarNavigator,
        frame: CarEnvironment.Frame,
        heard: String,
    ): String? {
        val name = when {
            heard.startsWith("open ") -> heard.removePrefix("open ")
            heard.startsWith("launch ") -> heard.removePrefix("launch ")
            heard.startsWith("start ") -> heard.removePrefix("start ")
            else -> return null
        }.trim()
        if (name.isBlank()) return null

        val entries = LauncherCache.entries ?: withContext(Dispatchers.IO) { LauncherCache.load(context) }
        val match = entries.firstOrNull { it.label.lowercase().contains(name) }
            ?: return "I couldn't find an app called $name."

        launchEntry(match, navigator, frame)
        return "Opening ${match.label}."
    }

    private val NAVIGATE_TRIGGERS = listOf("navigate to ", "directions to ", "take me to ", "drive to ")

    private val HELP_WORDS = setOf("help", "commands", "voice commands", "list commands", "show commands")

    private val RESUME_WORDS = setOf("music", "the music", "something", "it", "again", "that")
}
