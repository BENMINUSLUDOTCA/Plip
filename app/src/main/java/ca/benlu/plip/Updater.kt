package ca.benlu.plip

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class Release(val version: String, val apkUrl: String)

/** Finds new releases on GitHub and installs them over the running app. */
object Updater {
    const val ACTION_INSTALL_STATUS = "ca.benlu.plip.INSTALL_STATUS"
    private const val LATEST = "https://api.github.com/repos/BENMINUSLUDOTCA/Plip/releases/latest"
    private const val CHANNEL = "updates"
    private const val NOTIFICATION_ID = 4
    private const val CHECK_EVERY = 6 * 60 * 60_000L

    sealed interface Install {
        data object Idle : Install
        /** 0..1, or null while the size is unknown. */
        data class Downloading(val progress: Float?) : Install
        data object Failed : Install
    }

    private val _available = MutableStateFlow<Release?>(null)
    val available: StateFlow<Release?> = _available.asStateFlow()
    private val _install = MutableStateFlow<Install>(Install.Idle)
    val install: StateFlow<Install> = _install.asStateFlow()

    fun createChannel(context: Context) {
        val channel = NotificationChannel(CHANNEL, context.getString(R.string.channel_updates), NotificationManager.IMPORTANCE_DEFAULT)
        channel.description = context.getString(R.string.channel_updates_desc)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    /** Remembered result of the last check, so the card shows up right away on start. */
    fun load(context: Context) {
        if (BuildConfig.TEST_BUILD) return
        val prefs = prefs(context)
        val version = prefs.getString("version", null) ?: return
        val url = prefs.getString("url", null) ?: return
        _available.value = Release(version, url).takeIf { isNewer(it.version, installedVersion(context)) }
    }

    /**
     * Asks GitHub for the latest release. Skips the request when the last one is recent, unless [force].
     * Returns the newer release, or null when up to date. Throws when GitHub can't be reached.
     */
    suspend fun check(context: Context, force: Boolean = false): Release? = withContext(Dispatchers.IO) {
        // The test version is a separate app; GitHub releases are for the beta.
        if (BuildConfig.TEST_BUILD) return@withContext null
        val prefs = prefs(context)
        if (!force && System.currentTimeMillis() - prefs.getLong("checkedAt", 0) < CHECK_EVERY) {
            return@withContext _available.value
        }
        val json = (URL(LATEST).openConnection() as HttpURLConnection).run {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 15_000
            readTimeout = 15_000
            try {
                if (responseCode != 200) throw java.io.IOException("GitHub answered $responseCode")
                JSONObject(inputStream.bufferedReader().readText())
            } finally {
                disconnect()
            }
        }
        val assets = json.getJSONArray("assets")
        val apk = (0 until assets.length()).map(assets::getJSONObject).firstOrNull { it.getString("name").endsWith(".apk") }
        val release = apk?.let { Release(json.getString("tag_name").removePrefix("v"), it.getString("browser_download_url")) }
        prefs.edit()
            .putLong("checkedAt", System.currentTimeMillis())
            .putString("version", release?.version)
            .putString("url", release?.apkUrl)
            .apply()
        val newer = release?.takeIf { isNewer(it.version, installedVersion(context)) }
        _available.value = newer
        newer
    }

    /** Background check: posts a notification once per new version. */
    suspend fun checkAndNotify(context: Context) {
        val release = runCatching { check(context) }.getOrNull() ?: return
        val prefs = prefs(context)
        if (prefs.getString("notified", null) == release.version) return
        prefs.edit().putString("notified", release.version).apply()
        notify(context, release)
    }

    private fun notify(context: Context, release: Release) {
        if (!Plip.canNotify(context)) return
        val open = PendingIntent.getActivity(
            context,
            9,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_drop)
            .setContentTitle(context.getString(R.string.update_notif_title, release.version))
            .setContentText(context.getString(R.string.update_notif_text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        context.getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification)
    }

    /** Android asks once whether Plip may install apps; after that updates go through without a prompt. */
    fun canInstall(context: Context) = context.packageManager.canRequestPackageInstalls()

    /** Streams the APK straight into a package installer session and commits it. */
    suspend fun install(context: Context, release: Release) = withContext(Dispatchers.IO) {
        context.getSystemService(NotificationManager::class.java).cancel(NOTIFICATION_ID)
        _install.value = Install.Downloading(null)
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        params.setAppPackageName(context.packageName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            // Allowed without a prompt because Plip updates itself.
            params.setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
        }
        val id = installer.createSession(params)
        try {
            installer.openSession(id).use { session ->
                val connection = URL(release.apkUrl).openConnection() as HttpURLConnection
                try {
                    connection.connectTimeout = 15_000
                    connection.readTimeout = 30_000
                    val size = connection.contentLengthLong
                    session.openWrite("plip.apk", 0, size).use { out ->
                        connection.inputStream.use { input ->
                            val buffer = ByteArray(64 * 1024)
                            var done = 0L
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                out.write(buffer, 0, n)
                                done += n
                                if (size > 0) _install.value = Install.Downloading(done.toFloat() / size)
                            }
                        }
                        session.fsync(out)
                    }
                } finally {
                    connection.disconnect()
                }
                val status = PendingIntent.getBroadcast(
                    context,
                    8,
                    Intent(context, PlipReceiver::class.java).setAction(ACTION_INSTALL_STATUS),
                    // The installer adds the result as extras.
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
                )
                session.commit(status.intentSender)
            }
        } catch (e: Exception) {
            installer.abandonSession(id)
            _install.value = Install.Failed
        }
    }

    /** Result from the package installer. On success Android restarts Plip, so there is nothing to do. */
    fun onInstallStatus(context: Context, intent: Intent) {
        when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                @Suppress("DEPRECATION")
                val confirm = intent.getParcelableExtra<Intent>(Intent.EXTRA_INTENT) ?: return
                context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }
            PackageInstaller.STATUS_SUCCESS -> _install.value = Install.Idle
            else -> _install.value = Install.Failed
        }
    }

    private fun installedVersion(context: Context): String =
        context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0"

    /** Compares dotted version numbers like 1.9 and 1.10 part by part. */
    private fun isNewer(version: String, than: String): Boolean {
        val a = version.split('.').map { it.toIntOrNull() ?: 0 }
        val b = than.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val diff = a.getOrElse(i) { 0 } - b.getOrElse(i) { 0 }
            if (diff != 0) return diff > 0
        }
        return false
    }

    private fun prefs(context: Context) = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
}
