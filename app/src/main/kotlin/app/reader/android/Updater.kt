package app.reader.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import app.reader.AppController
import app.reader.UpdateState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Updates from the app's GitHub releases: finds the latest release, downloads the APK built for this
 * phone's processor, checks it against the release's SHA256SUMS.txt and hands it to Android's
 * package installer. Every release is signed with the same key, so it installs as an update.
 */
class Updater(private val activity: ComponentActivity, private val controller: AppController) {
    private class Release(val version: String, val code: Int, val notes: String, val assets: Map<String, String>)

    private var release: Release? = null
    private val action = "${activity.packageName}.UPDATE_STATUS"

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val version = release?.version.orEmpty()
            when (intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
                PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                    @Suppress("DEPRECATION")
                    val confirm = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
                    else intent.getParcelableExtra(Intent.EXTRA_INTENT)
                    confirm?.let { runCatching { activity.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
                }
                PackageInstaller.STATUS_SUCCESS -> controller.update = UpdateState.Idle
                PackageInstaller.STATUS_FAILURE_ABORTED -> controller.update = UpdateState.Available(version, release?.notes.orEmpty())
                else -> controller.update = UpdateState.Failed(
                    intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)?.let { "Couldn't install the update: $it" }
                        ?: "Couldn't install the update.")
            }
        }
    }

    fun start() {
        ContextCompat.registerReceiver(activity, receiver, IntentFilter(action), ContextCompat.RECEIVER_NOT_EXPORTED)
        controller.checkForUpdates = { manual -> check(manual) }
        controller.installUpdate = { install() }
        check(manual = false)
    }

    fun stop() {
        runCatching { activity.unregisterReceiver(receiver) }
    }

    private val installedCode: Long
        get() {
            val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
            @Suppress("DEPRECATION")
            return if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()
        }
    private val installedName: String
        get() = activity.packageManager.getPackageInfo(activity.packageName, 0).versionName.orEmpty()

    /** Looks for a newer release. A check at launch stays quiet unless there is one. */
    private fun check(manual: Boolean) {
        val busy = controller.update
        if (busy is UpdateState.Downloading || busy is UpdateState.Installing) return
        if (manual) toast("Checking for updates…")
        activity.lifecycleScope.launch {
            val found = withContext(Dispatchers.IO) { runCatching { latest() } }
            found.onSuccess { r ->
                release = r
                controller.update = when {
                    r != null && r.code > installedCode -> UpdateState.Available(r.version, r.notes)
                    else -> {
                        if (manual) toast("JustRead is up to date ($installedName)")
                        UpdateState.Idle
                    }
                }
            }.onFailure {
                if (manual) toast("Couldn't check for updates. Check your connection.")
            }
        }
    }

    private fun toast(text: String) = android.widget.Toast.makeText(activity, text, android.widget.Toast.LENGTH_SHORT).show()

    private fun latest(): Release? {
        val json = JSONObject(get(URL("https://api.github.com/repos/$REPO/releases/latest"), json = true).decodeToString())
        val version = json.optString("tag_name").removePrefix("v")
        val code = versionCode(version) ?: return null
        val assets = json.optJSONArray("assets") ?: return null
        val map = (0 until assets.length()).associate { i ->
            val a = assets.getJSONObject(i); a.optString("name") to a.optString("browser_download_url")
        }
        return Release(version, code, json.optString("body"), map)
    }

    /** Downloads, checks and installs the release found by the last check. */
    private fun install() {
        val r = release ?: return
        val name = Build.SUPPORTED_ABIS.map { "JustRead-${r.version}-$it.apk" }.firstOrNull { it in r.assets }
            ?: "JustRead-${r.version}-universal.apk"
        val url = r.assets[name]
        if (url == null) { controller.update = UpdateState.Failed("This release has no APK for this phone."); return }
        controller.update = UpdateState.Downloading(r.version, 0f)
        activity.lifecycleScope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val sums = r.assets["SHA256SUMS.txt"]?.let { get(URL(it)).decodeToString() }
                    val expected = sums?.lineSequence()?.map { it.trim().split(Regex("\\s+")) }
                        ?.firstOrNull { it.size == 2 && it[1].removePrefix("*") == name }?.get(0)
                    val dir = File(activity.cacheDir, "updates").apply { deleteRecursively(); mkdirs() }
                    val out = File(dir, name)
                    download(URL(url), out) { f -> activity.runOnUiThread { controller.update = UpdateState.Downloading(r.version, f) } }
                    if (expected != null && !sha256(out).equals(expected, ignoreCase = true)) {
                        out.delete(); error("The download was damaged. Try again.")
                    }
                    out
                }
            }
            file.onSuccess { f ->
                controller.update = UpdateState.Installing(r.version)
                runCatching { commit(f) }.onFailure { controller.update = UpdateState.Failed("Couldn't install the update: ${it.message}") }
            }.onFailure {
                controller.update = UpdateState.Failed(it.message?.takeIf { m -> m.startsWith("The download") } ?: "Couldn't download the update. Check your connection and try again.")
            }
        }
    }

    private fun commit(apk: File) {
        val installer = activity.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
            .apply { setAppPackageName(activity.packageName) }
        val id = installer.createSession(params)
        installer.openSession(id).use { s ->
            s.openWrite("base.apk", 0, apk.length()).use { out -> apk.inputStream().use { it.copyTo(out) }; s.fsync(out) }
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            val status = PendingIntent.getBroadcast(activity, id, Intent(action).setPackage(activity.packageName), flags)
            s.commit(status.intentSender)
        }
    }

    private fun open(url: URL, json: Boolean): HttpURLConnection = (url.openConnection() as HttpURLConnection).apply {
        connectTimeout = 15_000; readTimeout = 30_000; instanceFollowRedirects = true
        setRequestProperty("User-Agent", "JustRead-Android")
        if (json) setRequestProperty("Accept", "application/vnd.github+json")
    }

    private fun get(url: URL, json: Boolean = false): ByteArray {
        val c = open(url, json)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            return c.inputStream.use { it.readBytes() }
        } finally { c.disconnect() }
    }

    private fun download(url: URL, to: File, progress: (Float) -> Unit) {
        val c = open(url, false)
        try {
            if (c.responseCode !in 200..299) error("HTTP ${c.responseCode}")
            val total = c.contentLengthLong
            var done = 0L; var shown = -1
            c.inputStream.use { input ->
                to.outputStream().use { out ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        val n = input.read(buf); if (n < 0) break
                        out.write(buf, 0, n); done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt()
                            if (pct != shown) { shown = pct; progress(pct / 100f) }
                        }
                    }
                }
            }
        } finally { c.disconnect() }
    }

    private fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) { val n = input.read(buf); if (n < 0) break; md.update(buf, 0, n) }
        }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    companion object {
        const val REPO = "JobinBiju/JustRead"

        /** "1.2.3" → 10203, the same scheme release builds use for their version code. */
        fun versionCode(v: String): Int? {
            val p = v.split('.').map { it.toIntOrNull() ?: return null }
            if (p.size != 3) return null
            return p[0] * 10000 + p[1] * 100 + p[2]
        }
    }
}
