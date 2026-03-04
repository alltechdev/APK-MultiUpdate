package com.example.installer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

data class ReleaseInfo(
    val version: String,
    val changelog: String,
    val downloadUrl: String,
    val releaseDate: String
)

object Updater {
    private var repo: String = ""
    private var currentVersion: String = ""
    private var assetName: String? = null  // null = first .apk found

    private const val CHECK_INTERVAL = 2 * 60 * 60 * 1000L
    private var lastCheckTime = -1L
    private var cachedRelease: ReleaseInfo? = null

    /**
     * Initialize the updater
     * @param repo GitHub repo in format "Owner/Repo"
     * @param currentVersion Your app's current version (e.g. "1.0.0" or "v1.0.0")
     * @param assetName Optional: specific APK filename to look for (null = first .apk)
     */
    fun init(repo: String, currentVersion: String, assetName: String? = null) {
        this.repo = repo
        this.currentVersion = currentVersion
        this.assetName = assetName
    }

    suspend fun checkForUpdate(forceRefresh: Boolean = false): Result<Pair<ReleaseInfo, Boolean>> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(repo.isNotEmpty()) { "Call Updater.init() first" }

                val shouldFetch = forceRefresh || (System.currentTimeMillis() - lastCheckTime) > CHECK_INTERVAL
                if (!shouldFetch && cachedRelease != null) {
                    return@runCatching cachedRelease!! to isNewer(cachedRelease!!.version)
                }

                val release = fetchLatest()
                cachedRelease = release
                lastCheckTime = System.currentTimeMillis()
                release to isNewer(release.version)
            }
        }

    private fun fetchLatest(): ReleaseInfo {
        val url = URL("https://api.github.com/repos/$repo/releases/latest")
        val connection = (url.openConnection() as HttpURLConnection).apply {
            setRequestProperty("Accept", "application/vnd.github+json")
            connectTimeout = 10000
            readTimeout = 10000
        }

        val json = JSONObject(connection.inputStream.bufferedReader().readText())

        if (json.has("message") && !json.has("tag_name")) {
            throw Exception(json.getString("message"))
        }

        val assets = json.optJSONArray("assets") ?: JSONArray()
        var downloadUrl = ""

        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            val name = asset.getString("name")
            if (name.endsWith(".apk")) {
                if (assetName == null || name == assetName) {
                    downloadUrl = asset.getString("browser_download_url")
                    break
                }
            }
        }

        return ReleaseInfo(
            version = json.getString("tag_name"),
            changelog = json.optString("body", ""),
            downloadUrl = downloadUrl,
            releaseDate = json.optString("published_at", "")
        )
    }

    private fun isNewer(latest: String): Boolean {
        val c = currentVersion.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        val l = latest.removePrefix("v").split(".").map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(c.size, l.size)) {
            val cv = c.getOrNull(i) ?: 0
            val lv = l.getOrNull(i) ?: 0
            if (lv > cv) return true
            if (lv < cv) return false
        }
        return false
    }
}
