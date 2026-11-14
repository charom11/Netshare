package com.netshare.wifidirect.util

import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import androidx.core.content.FileProvider
import com.netshare.wifidirect.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.BufferedReader
import java.io.File
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

    private var activeDownloadId: Long = -1L

    suspend fun checkForUpdate(
        owner: String = repositoryOwner,
        repo: String = repositoryName,
        token: String? = null
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
                if (!token.isNullOrBlank()) {
                    setRequestProperty("Authorization", "Bearer ${token.trim()}")
                }
            }

            val code = conn.responseCode
            if (code == 404) {
                return@withContext Result.failure(
                    Exception("Repository is private or no releases published yet. If private, make it Public on GitHub or provide a token.")
                )
            }
            if (code != 200) {
                return@withContext Result.failure(
                    Exception("GitHub returned HTTP $code (${conn.responseMessage})")
                )
            }

            val responseBody = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            val json = JSONObject(responseBody)
            val tagName = json.optString("tag_name", "").removePrefix("v")
            val releaseName = json.optString("name", "v$tagName")
            val body = json.optString("body", "No release notes provided.")

            // Look for .apk asset
            var apkDownloadUrl = ""
            val assets = json.optJSONArray("assets")
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val name = asset.optString("name", "")
                    if (name.endsWith(".apk", ignoreCase = true)) {
                        apkDownloadUrl = asset.optString("browser_download_url", "")
                        break
                    }
                }
            }

            // Fallback to direct repository APK if asset was not attached to release
            if (apkDownloadUrl.isEmpty()) {
                apkDownloadUrl = "https://github.com/$owner/$repo/raw/main/NetShareDirect.apk"
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

    /**
     * Downloads the APK using Android DownloadManager and auto-installs it when complete.
     */
    fun downloadAndInstall(context: Context, downloadUrl: String, versionTag: String) {
        try {
            val fileName = "NetShareDirect-v$versionTag.apk"

            // Clean up old downloads
            val downloadDir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
            downloadDir?.listFiles()?.forEach { file ->
                if (file.name.endsWith(".apk")) {
                    file.delete()
                }
            }

            val dm = context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager
            val uri = Uri.parse(downloadUrl)

            val request = DownloadManager.Request(uri).apply {
                setTitle("NetShare Direct Update v$versionTag")
                setDescription("Downloading update...")
                setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                setDestinationInExternalFilesDir(context, Environment.DIRECTORY_DOWNLOADS, fileName)
                setMimeType("application/vnd.android.package-archive")
                // Allow download over any network
                setAllowedNetworkTypes(
                    DownloadManager.Request.NETWORK_WIFI or DownloadManager.Request.NETWORK_MOBILE
                )
            }

            activeDownloadId = dm.enqueue(request)
            Toast.makeText(context, "Downloading update v$versionTag...", Toast.LENGTH_SHORT).show()

            // Register receiver to auto-install when download completes
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                    if (id != activeDownloadId) return

                    try {
                        ctx.unregisterReceiver(this)
                    } catch (_: Exception) {}

                    val downloadedFile = File(downloadDir, fileName)
                    if (downloadedFile.exists()) {
                        installApk(ctx, downloadedFile)
                    } else {
                        Toast.makeText(ctx, "Download failed. Try again.", Toast.LENGTH_LONG).show()
                    }
                }
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE),
                    Context.RECEIVER_EXPORTED
                )
            } else {
                @Suppress("UnspecifiedRegisterReceiverFlag")
                context.registerReceiver(
                    receiver,
                    IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
                )
            }
        } catch (e: Exception) {
            Toast.makeText(context, "Download error: ${e.message}", Toast.LENGTH_LONG).show()
            // Fallback: open in browser
            openDownload(context, downloadUrl)
        }
    }

    /**
     * Launches the Android package installer for the given APK file.
     */
    private fun installApk(context: Context, apkFile: File) {
        try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )

            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(installIntent)
        } catch (e: Exception) {
            // Fallback for older devices or if FileProvider fails
            try {
                val installIntent = Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(
                        Uri.fromFile(apkFile),
                        "application/vnd.android.package-archive"
                    )
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(installIntent)
            } catch (e2: Exception) {
                Toast.makeText(context, "Install failed: ${e2.message}", Toast.LENGTH_LONG).show()
            }
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
