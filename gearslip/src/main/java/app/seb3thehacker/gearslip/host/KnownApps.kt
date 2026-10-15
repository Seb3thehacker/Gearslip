package app.seb3thehacker.gearslip.host

/**
 * Apps that have been run through Gearslip from start to finish and behave. The launcher marks
 * them so a driver can pick something that works without finding out at the wheel.
 *
 * An app belongs here only after a real session: the app starts, draws, takes touches and
 * answers its buttons. Apps that connect but show no library, or refuse Gearslip as a host, stay
 * off the list.
 */
object KnownApps {

    private val working = setOf(
        "app.organicmaps",       // Organic Maps: map, search, routing, settings
        "net.osmand.plus",       // OsmAnd: map, search, routing, settings
        "in.krosbits.musicolet", // Musicolet: browse, play, seek, lyrics
        "com.kododake.aabrowser", // Aardvark: browser template, starts, draws, takes touches
        "com.vivi.vivimusic", // Vivi Music: browse, play, seek, works well
        "com.metrolist.music", // Metrolist: browse, play, works
        "nl.flitsmeister", // Flitsmeister: map, routing, alerts - works well
        "com.mapquest.android.ace", // MapQuest: map, routing, navigation
        "com.mapswithme.maps.pro", // MAPS.ME: map, search, place pages, navigation
    )

    fun works(packageName: String): Boolean = packageName in working

    /**
     * Apps whose media player works through Gearslip's own player even though their car screen
     * doesn't. Only the player tile gets the check, not the "· Browse" one.
     */
    private val workingPlayer = setOf(
        "com.spotify.music", // Spotify: play, browse, seek through Gearslip's player
    )

    fun playerWorks(packageName: String): Boolean = packageName in workingPlayer

    /**
     * Apps whose car screen refuses Gearslip but whose player works. When the player covers the
     * app (see [playerWorks]) the launcher drops the dead "· Browse" tile entirely rather than
     * show it; otherwise that tile gets a red X. Car library 1.9 and later accepts only Google's
     * host on a phone.
     */
    private val brokenScreen = setOf(
        "com.spotify.music", // Spotify 9.1.86 and later: car library 1.9 rejects Gearslip
    )

    fun screenBroken(packageName: String): Boolean = packageName in brokenScreen

    /**
     * Registers a car template service but never gives Gearslip a usable screen - left off the
     * launcher entirely rather than shown as a tile that goes nowhere.
     */
    private val hidden = setOf(
        "com.google.android.dialer",               // Google Phone
        "com.google.android.googlequicksearchbox",  // Google Assistant driving surfaces
        "com.google.android.apps.maps",             // Google Maps: shows as a messenger, and its car UI is Android Auto's own
    )

    fun isHidden(packageName: String): Boolean = packageName in hidden

    /** Shown on the launcher with a red X: known broken, but expected enough that hiding it outright would look like a bug. */
    private val broken = setOf(
        "com.google.android.apps.messaging", // Google Messages
        "com.waze",                          // Waze
        "com.generalmagic.magicearth",       // Magic Earth: never gets past connecting
    )

    fun isBroken(packageName: String): Boolean = packageName in broken

    /**
     * Shown on the launcher with a yellow mark: starts and can be used, but not cleanly enough to
     * call working. Wins over [works] and [playerWorks] for the badge.
     */
    private val partial = setOf(
        "app.vela", // Vela Maps: connects and draws, but rough enough not to call it working yet
        "com.spotify.music",        // Spotify: plays through Gearslip's player, but the app has to be opened on the phone first
        "deezer.android.app",       // Deezer: the same - it has to be opened on the phone first
    )

    fun isPartial(packageName: String): Boolean = packageName in partial
}
