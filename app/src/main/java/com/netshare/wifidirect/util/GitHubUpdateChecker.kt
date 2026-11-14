package com.netshare.wifidirect.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.netshare.wifidirect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL

data class GitHubReleaseInfo(
    val tagName: String,
    val releaseName: String,
    val releaseNotes: String,
    val downloadUrl: String,
    val hasUpdate: Boolean
)

object GitHubUpdateChecker {

    // Default repository
    var repositoryOwner: String = "charom11"
    var repositoryName: String = "Netshare"

    suspend fun checkForUpdate(
        owner: String = repositoryOwner,
        repo: String = repositoryName
    ): Result<GitHubReleaseInfo> = withContext(Dispatchers.IO) {
        try {
            val endpoint = "https://api.github.com/repos/$owner/$repo/releases/latest"
            val url = URL(endpoint)
            val conn = (url.openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                requestMethod = "GET"
                setRequestProperty("Accept", "application/vnd.github.v3+json")
                setRequestProperty("User-Agent", "NetShareDirect-Android")
            }

            if (conn.responseCode != 200) {
                return@withContext Result.failure(
                    Exception("GitHub returned HTTP ${conn.responseCode} (${conn.responseMessage})")
                )
            }

            val responseBody = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(responseBody)
            val tagName = json.optString("tag_name", "").removePrefix("v")
            val releaseName = json.optString("name", "v$tagName")
            val body = json.optString("body", "No release notes provided.")

            // Look for .apk asset
            var apkDownloadUrl = json.optString("html_url", "")
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.optString("browser_download_url", apkDownloadUrl)
                        break
                    }
                }
            }

            val currentVersion = BuildConfig.VERSION_NAME
            val hasUpdate = isNewerVersion(tagName, currentVersion)

            Result.success(
                GitHubReleaseInfo(
                    tagName = tagName,
                    releaseName = releaseName,
                    releaseNotes = body,
                    downloadUrl = apkDownloadUrl,
                    hasUpdate = hasUpdate
                )
            )
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun openDownload(context: Context, downloadUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl)).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
        }
    }

    private fun isNewerVersion(remote: String, current: String): Boolean {
        if (remote.isBlank() || current.isBlank()) return false
        val remoteParts = remote.split(".").mapNotNull { it.toIntOrNull() }
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val maxLen = maxOf(remoteParts.size, currentParts.size)
        for (i in 0 until maxLen) {
            val r = remoteParts.getOrElse(i) { 0 }
            val c = currentParts.getOrElse(i) { 0 }
            if (r > c) return true
            if (r < c) return false
        }
        return false
    }
}
