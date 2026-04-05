package dev.heckr.comicconverter.updater

import android.content.Context
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

object UpdateChecker {

    var latestVersion: String? = null
        private set
    var latestApkUrl: String? = null
        private set
    var releaseBody: String? = null
        private set
    var apkSizeBytes: Long = 0L
        private set
    var updateAvailable: Boolean = false
        private set

    private var listeners = mutableListOf<() -> Unit>()
    private var checking = false

    fun addListener(listener: () -> Unit) { listeners.add(listener) }
    fun removeListener(listener: () -> Unit) { listeners.remove(listener) }

    fun check(context: Context) {
        if (checking || updateAvailable) return
        checking = true

        val currentVersion = try {
            context.packageManager
                .getPackageInfo(context.packageName, 0)
                .versionName ?: "0"
        } catch (_: Exception) { "0" }

        CoroutineScope(Dispatchers.IO).launch {
            try {
                val url = URL("https://api.github.com/repos/hecker-01/ComicConverter/releases/latest")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("Accept", "application/vnd.github.v3+json")
                conn.connectTimeout = 10_000
                conn.readTimeout = 10_000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    val json = JSONObject(conn.inputStream.bufferedReader().readText())
                    val tagVersion = json.getString("tag_name").removePrefix("v")

                    if (isNewerVersion(currentVersion, tagVersion)) {
                        val assets = json.getJSONArray("assets")
                        var apkUrl: String? = null
                        var apkSize = 0L
                        for (i in 0 until assets.length()) {
                            val asset = assets.getJSONObject(i)
                            if (asset.getString("name").endsWith(".apk")) {
                                apkUrl = asset.getString("browser_download_url")
                                apkSize = asset.optLong("size", 0L)
                                break
                            }
                        }
                        if (apkUrl != null) {
                            latestApkUrl = apkUrl
                            latestVersion = tagVersion
                            releaseBody = json.optString("body", "")
                            apkSizeBytes = apkSize
                            updateAvailable = true
                        }
                    }
                }
                conn.disconnect()
            } catch (_: Exception) { }

            checking = false
            withContext(Dispatchers.Main) { listeners.forEach { it() } }
        }
    }

    fun clear() {
        updateAvailable = false
        latestVersion = null
        latestApkUrl = null
        releaseBody = null
        apkSizeBytes = 0L
    }

    private fun isNewerVersion(current: String, latest: String): Boolean {
        try {
            val cur = current.split("-")[0].split(".")
            val lat = latest.split("-")[0].split(".")
            for (i in 0 until maxOf(cur.size, lat.size)) {
                val c = cur.getOrNull(i)?.toIntOrNull() ?: 0
                val l = lat.getOrNull(i)?.toIntOrNull() ?: 0
                if (l > c) return true
                if (l < c) return false
            }
        } catch (_: Exception) { }
        return false
    }
}
