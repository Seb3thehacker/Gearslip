package app.seb3thehacker.gearslip.update

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.FileProvider
import androidx.core.graphics.drawable.toBitmap
import app.seb3thehacker.gearslip.BuildConfig
import app.seb3thehacker.gearslip.GearslipActivity
import app.seb3thehacker.gearslip.GearslipLog
import app.seb3thehacker.gearslip.SessionStatus
import app.seb3thehacker.gearslip.notify.CarMotion
import app.seb3thehacker.gearslip.notify.CarNotification
import app.seb3thehacker.gearslip.notify.CarNotifications
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Looks for a newer Gearslip on GitHub and installs it through Android's own installer.
 *
 * Which releases count depends on the driver's channel. Stable takes only GitHub's "latest"
 * release, which leaves out pre-releases and drafts. Beta adds pre-releases tagged "beta", and
 * Alpha adds every pre-release. The app checks at most once a day: when
 * it opens, and when a drive ends. With automatic checks off it never asks GitHub on its own,
 * only when the driver taps Check for updates. After a drive a new version also gets
 * a notification, once per version, since the driver is parked and the app is likely closed. The
 * car screen shows the same news once per version, at plug-in and only while parked.
 * Nothing runs in the background otherwise, and nothing else is sent: the request is the same one
 * a browser makes for the release page. Dev builds never check on their own.
 *
 * The APK is checked against the size and SHA-256 GitHub lists for it, and Android refuses an
 * update signed with a different key, so a bad download can't replace the app.
 */
object UpdateChecker {

    data class Release(val version: String, val apkUrl: String, val size: Long, val sha256: String?, val pageUrl: String)

    sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val release: Release) : State
        data class Downloading(val release: Release, val fraction: Float) : State
        data class Ready(val release: Release, val apk: File) : State
        data class Failed(val message: String, val release: Release? = null) : State
    }

    private val flow = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = flow.asStateFlow()

    private const val PREFS = "updates"
    private const val KEY_CHECKED_AT = "checked_at"
    private const val KEY_FOUND = "found"
    /** Named for the notifications toggle it grew out of; it now covers every automatic check. */
    private const val KEY_AUTO = "notify"
    /** The old on/off switch for pre-releases. On meant every pre-release, which is now Alpha. */
    private const val KEY_BETA = "beta"
    private const val KEY_CHANNEL = "channel"
    private const val KEY_NOTIFIED = "notified_version"
    private const val KEY_CAR_NOTIFIED = "car_notified_version"
    /** Lets the car screen settle after plug-in, so the card isn't lost behind the first frames. */
    private const val CAR_DELAY_MS = 8_000L
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4100
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val LATEST = "https://api.github.com/repos/Seb3thehacker/gearslip/releases/latest"
    /** Newest first, pre-releases included. Ten covers any run of betas between two releases. */
    private const val ALL = "https://api.github.com/repos/Seb3thehacker/gearslip/releases?per_page=10"

    /** Set when the driver tapped Install but first had to allow installs from Gearslip. */
    @Volatile private var installWhenAllowed = false

    private fun prefs(context: Context) = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /**
     * Whether Gearslip checks on its own, and tells the driver what it finds on the phone and the
     * car screen. On by default: the check sends nothing about the phone.
     */
    fun autoCheckEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO, true)

    fun setAutoCheckEnabled(context: Context, on: Boolean) {
        prefs(context).edit().putBoolean(KEY_AUTO, on).apply()
        if (!on) NotificationManagerCompat.from(context).cancel(NOTIFICATION_ID)
    }

    /** Which releases count as updates. Each channel also takes everything the one before it does. */
    enum class Channel { STABLE, BETA, ALPHA }

    fun channel(context: Context): Channel {
        val p = prefs(context)
        p.getString(KEY_CHANNEL, null)?.let { saved -> Channel.entries.firstOrNull { it.name == saved }?.let { return it } }
        return if (p.getBoolean(KEY_BETA, false)) Channel.ALPHA else Channel.STABLE
    }

    /**
     * Switching channels forgets what the old one found: an alpha found earlier shouldn't still be
     * offered on Stable. Checks again right away if automatic checks are on.
     */
    fun setChannel(context: Context, channel: Channel) {
        prefs(context).edit().putString(KEY_CHANNEL, channel.name).remove(KEY_BETA).remove(KEY_FOUND).remove(KEY_CHECKED_AT).apply()
        val current = flow.value
        if (current is State.Checking || current is State.Downloading) return
        updatesDir(context).deleteRecursively()
        flow.value = State.Idle
        if (autoCheckEnabled(context) && !BuildConfig.DEBUG) check(context)
    }

    private fun dueForCheck(context: Context) =
        !BuildConfig.DEBUG && autoCheckEnabled(context) && System.currentTimeMillis() - prefs(context).getLong(KEY_CHECKED_AT, 0) >= DAY_MS

    /** Gearslip came to the front: check if a day has passed, and finish an install that waited on permission. */
    fun onAppOpened(context: Context) {
        val current = flow.value
        if (installWhenAllowed && current is State.Ready && context.packageManager.canRequestPackageInstalls()) {
            installWhenAllowed = false
            install(context)
            return
        }
        if (current != State.Idle) return
        // A release found earlier still shows after Android closed the app.
        savedRelease(context)?.let { flow.value = State.Available(it) }
        if (dueForCheck(context)) check(context)
    }

    /**
     * A car session ended, so the driver is parked. Checks if a day has passed, then posts a
     * notification for a new version they haven't been told about yet.
     */
    fun onDriveEnded(context: Context) {
        if (!autoCheckEnabled(context)) return
        val app = context.applicationContext
        Thread({
            val current = flow.value
            if (dueForCheck(app) && current !is State.Checking && current !is State.Downloading) {
                flow.value = State.Checking
                flow.value = checkNow(app)
            }
            val release = foundRelease(app) ?: return@Thread
            if (prefs(app).getString(KEY_NOTIFIED, null) == release.version) return@Thread
            if (notify(app, release)) prefs(app).edit().putString(KEY_NOTIFIED, release.version).apply()
        }, "gearslip-update").start()
    }

    /**
     * The car screen just came up. A new version gets a card in the top right, like a message,
     * once per version. Skipped while moving; the next plug-in tries again.
     */
    fun onCarConnected(context: Context) {
        if (!autoCheckEnabled(context)) return
        val app = context.applicationContext
        Thread({
            val current = flow.value
            if (dueForCheck(app) && current !is State.Checking && current !is State.Downloading) {
                flow.value = State.Checking
                flow.value = checkNow(app)
            }
            val release = foundRelease(app) ?: return@Thread
            if (prefs(app).getString(KEY_CAR_NOTIFIED, null) == release.version) return@Thread
            Thread.sleep(CAR_DELAY_MS)
            if (!carConnected || CarMotion.moving) return@Thread
            CarNotifications.post(
                CarNotification(
                    key = "gearslip-update",
                    id = CarNotifications.nextId(),
                    packageName = app.packageName,
                    appLabel = "Gearslip",
                    title = "Gearslip ${release.version} is out",
                    text = "Update in the Gearslip app or from GitHub",
                    postedAt = System.currentTimeMillis(),
                    icon = runCatching { app.packageManager.getApplicationIcon(app.packageName).toBitmap(96, 96) }.getOrNull(),
                    reply = null,
                ),
                quiet = false,
            )
            prefs(app).edit().putString(KEY_CAR_NOTIFIED, release.version).apply()
            GearslipLog.i("update: showed ${release.version} on the car screen")
        }, "gearslip-update").start()
    }

    private fun foundRelease(app: Context): Release? = when (val s = flow.value) {
        is State.Available -> s.release
        is State.Downloading -> s.release
        is State.Ready -> s.release
        else -> savedRelease(app)
    }

    /** Posts "Gearslip X is out"; tapping it opens the app, where the Home card has the button. */
    private fun notify(context: Context, release: Release): Boolean {
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return false
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Updates", NotificationManager.IMPORTANCE_DEFAULT),
        )
        val open = PendingIntent.getActivity(
            context, NOTIFICATION_ID,
            Intent(context, GearslipActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Gearslip ${release.version} is out")
            .setContentText("Tap to update.")
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        return runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification) }
            .onSuccess { GearslipLog.i("update: told the driver about ${release.version}") }
            .onFailure { GearslipLog.w("update: could not post the notification (${it.message})") }
            .isSuccess
    }

    /** Asks GitHub now. Ignored while a check or download is under way. */
    fun check(context: Context) {
        val current = flow.value
        if (current is State.Checking || current is State.Downloading) return
        val app = context.applicationContext
        flow.value = State.Checking
        Thread({ flow.value = checkNow(app) }, "gearslip-update").start()
    }

    private fun checkNow(app: Context): State = runCatching { fetchNewest(channel(app)) }.fold(
        onSuccess = { release ->
            prefs(app).edit().putLong(KEY_CHECKED_AT, System.currentTimeMillis()).apply()
            if (release != null && isNewer(release.version, BuildConfig.VERSION_NAME)) {
                GearslipLog.i("update: ${release.version} is out (this is ${BuildConfig.VERSION_NAME})")
                save(app, release)
                State.Available(release)
            } else {
                prefs(app).edit().remove(KEY_FOUND).apply()
                updatesDir(app).deleteRecursively()
                State.UpToDate
            }
        },
        onFailure = {
            GearslipLog.w("update: could not check: ${it.message}")
            State.Failed("Couldn't reach GitHub. Check the connection and try again.")
        },
    )

    /** Downloads [release], then opens the installer once the file checks out. */
    fun download(context: Context, release: Release) {
        if (flow.value is State.Downloading) return
        val app = context.applicationContext
        flow.value = State.Downloading(release, 0f)
        Thread({
            val dir = updatesDir(app).apply { deleteRecursively(); mkdirs() }
            val apk = File(dir, "Gearslip-${release.version}.apk")
            flow.value = runCatching {
                fetchApk(release, apk)
                GearslipLog.i("update: downloaded ${release.version}")
                State.Ready(release, apk)
            }.getOrElse {
                apk.delete()
                GearslipLog.w("update: download failed: ${it.message}")
                State.Failed(it.message ?: "The download failed. Try again.", release)
            }
            if (flow.value is State.Ready) install(app)
        }, "gearslip-update").start()
    }

    /**
     * Hands the downloaded APK to Android's installer. The first time, Android wants the driver to
     * allow installs from Gearslip; that opens its settings page, and the install carries on when
     * they come back.
     */
    fun install(context: Context) {
        val ready = flow.value as? State.Ready ?: return
        // Installing closes Gearslip, which would drop the car screen mid-drive.
        if (carConnected) return
        val pm = context.packageManager
        if (!pm.canRequestPackageInstalls()) {
            installWhenAllowed = true
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
            return
        }
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.logshare", ready.apk)
        context.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** The newest release with an APK that [channel] takes. Drafts never count. */
    private fun fetchNewest(channel: Channel): Release? {
        val stable = channel == Channel.STABLE
        val conn = (URL(if (stable) LATEST else ALL).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 15_000
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "Gearslip/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode == 404) return null // no stable release yet
            if (conn.responseCode !in 200..299) error("GitHub answered ${conn.responseCode}")
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            if (stable) return release(JSONObject(body))
            val list = org.json.JSONArray(body)
            return (0 until list.length()).map { list.getJSONObject(it) }
                .filterNot { it.optBoolean("draft") }
                .filter {
                    channel == Channel.ALPHA || !it.optBoolean("prerelease") ||
                        it.optString("tag_name").contains("beta", ignoreCase = true)
                }
                .mapNotNull(::release)
                .reduceOrNull { best, next -> if (isNewer(next.version, best.version)) next else best }
        } finally {
            conn.disconnect()
        }
    }

    /** A release as GitHub lists it, or null when it has no APK attached. */
    private fun release(json: JSONObject): Release? {
        val assets = json.optJSONArray("assets") ?: return null
        val apk = (0 until assets.length()).map { assets.getJSONObject(it) }
            .firstOrNull { it.optString("name").endsWith(".apk") && !it.optString("name").contains("debug") }
            ?: return null
        return Release(
            version = json.getString("tag_name").removePrefix("v"),
            apkUrl = apk.getString("browser_download_url"),
            size = apk.optLong("size", -1),
            sha256 = apk.optString("digest").takeIf { it.startsWith("sha256:") }?.removePrefix("sha256:"),
            pageUrl = json.optString("html_url"),
        )
    }

    private fun fetchApk(release: Release, into: File) {
        val conn = (URL(release.apkUrl).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("User-Agent", "Gearslip/${BuildConfig.VERSION_NAME}")
        }
        try {
            if (conn.responseCode !in 200..299) error("GitHub answered ${conn.responseCode}")
            val total = release.size.takeIf { it > 0 } ?: conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var shown = -1
            conn.inputStream.use { input ->
                into.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        output.write(buffer, 0, n)
                        digest.update(buffer, 0, n)
                        done += n
                        // Whole percents only, so the screen isn't redrawn for every buffer.
                        val percent = if (total > 0) (done * 100 / total).toInt() else 0
                        if (percent != shown) {
                            shown = percent
                            flow.value = State.Downloading(release, percent / 100f)
                        }
                    }
                }
            }
            if (release.size > 0 && done != release.size) error("The download stopped early. Try again.")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (release.sha256 != null && !hex.equals(release.sha256, ignoreCase = true)) {
                error("The download came out damaged. Try again.")
            }
        } finally {
            conn.disconnect()
        }
    }

    /** Installing waits until the car is unplugged. */
    val carConnected: Boolean
        get() = SessionStatus.state.value.phase.let { it == SessionStatus.Phase.CONNECTING || it == SessionStatus.Phase.PROJECTING }

    private fun updatesDir(context: Context) = File(context.cacheDir, "updates")

    private fun save(context: Context, release: Release) {
        val json = JSONObject()
            .put("version", release.version)
            .put("apkUrl", release.apkUrl)
            .put("size", release.size)
            .put("sha256", release.sha256 ?: "")
            .put("pageUrl", release.pageUrl)
        prefs(context).edit().putString(KEY_FOUND, json.toString()).apply()
    }

    /** The release found last time, if it is still newer than this build (it isn't after updating). */
    private fun savedRelease(context: Context): Release? {
        val json = prefs(context).getString(KEY_FOUND, null)?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val release = Release(
            version = json.optString("version"),
            apkUrl = json.optString("apkUrl"),
            size = json.optLong("size", -1),
            sha256 = json.optString("sha256").ifBlank { null },
            pageUrl = json.optString("pageUrl"),
        )
        return release.takeIf { isNewer(it.version, BuildConfig.VERSION_NAME) }
    }

    /**
     * "0.2.00-alpha" beats "0.1.10-alpha": the numbers before the dash compare one by one. With the
     * same numbers, a plain release beats a beta, and a beta beats an alpha, so "0.3.0" reaches
     * someone on "0.3.0-beta".
     */
    internal fun isNewer(candidate: String, installed: String): Boolean {
        fun parts(v: String) = v.removePrefix("v").substringBefore('-').split('.').map { it.toIntOrNull() ?: 0 }
        fun stage(v: String): Int {
            val tag = v.substringAfter('-', "").lowercase()
            return when {
                tag.isEmpty() -> 3
                "beta" in tag -> 2
                "alpha" in tag -> 1
                else -> 0
            }
        }
        val a = parts(candidate)
        val b = parts(installed)
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return stage(candidate) > stage(installed)
    }
}
