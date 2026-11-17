package com.example.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import com.example.BuildConfig
import com.example.MainActivity
import com.example.device.PermissionManager
import com.example.model.UpdateInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.util.concurrent.TimeUnit

sealed class UpdateCheckOutcome {
    data class UpdateAvailable(val updateInfo: UpdateInfo) : UpdateCheckOutcome()
    data class UpToDate(val currentVersionCode: Int, val currentVersionName: String) : UpdateCheckOutcome()
    data class Error(val message: String) : UpdateCheckOutcome()
}

sealed class ApkInstallLaunchOutcome {
    data object LaunchedInstaller : ApkInstallLaunchOutcome()
    data object UnknownSourcesPermissionNeeded : ApkInstallLaunchOutcome()
    data class Failed(val reason: String) : ApkInstallLaunchOutcome()
}

/**
 * Checks GitHub Releases API for newer APK versions, verifies downloads via SHA-256 checksum,
 * and launches Android's official non-silent system package installation UI.
 */
class AppUpdateManager(
    private val context: Context,
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()
) {

    companion object {
        private const val TAG = "AppUpdateManager"
        const val UPDATE_CHANNEL_ID = "siri_app_updates_channel"
        const val UPDATE_NOTIFICATION_ID = 1042
        const val EXTRA_OPEN_UPDATE_DIALOG = "EXTRA_OPEN_UPDATE_DIALOG"
        private val SHA256_REGEX = Regex("""(?i)\b([a-f0-9]{64})\b""")
        private val VERSION_CODE_REGEX = Regex("""(?i)(?:versionCode|version\s*code|build|\+b?)\s*[:=#]?\s*(\d+)""")
        private val SEMVER_REGEX = Regex("""v?(\d+(?:\.\d+){1,3})""")
    }

    suspend fun checkForLatestRelease(
        repoSlug: String = BuildConfig.GITHUB_UPDATE_REPO,
        currentVersionCode: Int = BuildConfig.VERSION_CODE,
        currentVersionName: String = BuildConfig.VERSION_NAME,
        apiBaseUrl: String = "https://api.github.com"
    ): UpdateCheckOutcome = withContext(Dispatchers.IO) {
        val cleanSlug = repoSlug.trim().trim('/')
        if (cleanSlug.isBlank() || !cleanSlug.contains('/')) {
            return@withContext UpdateCheckOutcome.Error("Invalid GitHub repository configuration.")
        }

        val url = "${apiBaseUrl.trimEnd('/')}/repos/$cleanSlug/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "SiriAI-Android/${currentVersionName}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext UpdateCheckOutcome.Error("Release check failed (HTTP ${response.code}).")
                }
                val bodyStr = response.body?.string().orEmpty()
                if (bodyStr.isBlank()) {
                    return@withContext UpdateCheckOutcome.Error("Empty response from GitHub Releases.")
                }

                val parsed = parseReleaseJson(
                    jsonStr = bodyStr,
                    repoSlug = cleanSlug,
                    currentVersionCode = currentVersionCode,
                    currentVersionName = currentVersionName
                )

                // If checksum wasn't embedded in release notes body, try fetching attached .sha256 asset
                val finalInfo = if (parsed.sha256Checksum.isBlank() && parsed.checksumAssetUrl.isNotBlank()) {
                    val fetchedSha = fetchChecksumFile(parsed.checksumAssetUrl)
                    parsed.copy(sha256Checksum = fetchedSha)
                } else {
                    parsed
                }

                if (finalInfo.isUpdateAvailable) {
                    UpdateCheckOutcome.UpdateAvailable(finalInfo)
                } else {
                    UpdateCheckOutcome.UpToDate(currentVersionCode, currentVersionName)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "GitHub release check failed", e)
            UpdateCheckOutcome.Error(e.localizedMessage ?: "Unable to check for updates.")
        }
    }

    internal fun parseReleaseJson(
        jsonStr: String,
        repoSlug: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): UpdateInfo {
        val obj = JSONObject(jsonStr)
        val tagName = obj.optString("tag_name", "")
        val releaseName = obj.optString("name", "")
        val notes = obj.optString("body", "").ifBlank { "New Siri AI Assistant release available." }
        val htmlUrl = obj.optString("html_url", "https://github.com/$repoSlug/releases")

        val assets = obj.optJSONArray("assets")
        var apkDownloadUrl = ""
        var apkFileName = "app-debug.apk"
        var apkSize = 0L
        var checksumAssetUrl = ""

        if (assets != null) {
            for (i in 0 until assets.length()) {
                val asset = assets.optJSONObject(i) ?: continue
                val name = asset.optString("name", "")
                val downloadUrl = asset.optString("browser_download_url", "")
                if (name.endsWith(".apk", ignoreCase = true) && apkDownloadUrl.isBlank()) {
                    apkDownloadUrl = downloadUrl
                    apkFileName = name
                    apkSize = asset.optLong("size", 0L)
                } else if (name.endsWith(".sha256", ignoreCase = true) && checksumAssetUrl.isBlank()) {
                    checksumAssetUrl = downloadUrl
                }
            }
        }

        val parsedVersionName = extractVersionName(tagName, releaseName, notes, currentVersionName)
        val parsedVersionCode = extractVersionCode(tagName, releaseName, notes, currentVersionCode)
        val bodySha256 = extractSha256FromText(notes)

        val newer = isNewerRelease(
            remoteVersionCode = parsedVersionCode,
            remoteVersionName = parsedVersionName,
            currentVersionCode = currentVersionCode,
            currentVersionName = currentVersionName
        )

        return UpdateInfo(
            latestVersionCode = parsedVersionCode,
            latestVersionName = parsedVersionName,
            releaseNotes = notes,
            downloadUrl = apkDownloadUrl,
            releasePageUrl = htmlUrl,
            sha256Checksum = bodySha256,
            checksumAssetUrl = checksumAssetUrl,
            assetFileName = apkFileName,
            assetSizeBytes = apkSize,
            isUpdateAvailable = newer
        )
    }

    private fun extractVersionName(tagName: String, releaseName: String, notes: String, fallback: String): String {
        for (source in listOf(tagName, releaseName, notes)) {
            val m = SEMVER_REGEX.find(source)
            if (m != null) return m.groupValues[1]
        }
        return fallback
    }

    private fun extractVersionCode(tagName: String, releaseName: String, notes: String, fallback: Int): Int {
        for (source in listOf(notes, tagName, releaseName)) {
            val m = VERSION_CODE_REGEX.find(source)
            val parsed = m?.groupValues?.getOrNull(1)?.toIntOrNull()
            if (parsed != null && parsed > 0) return parsed
        }
        return fallback
    }

    internal fun extractSha256FromText(text: String): String {
        val m = SHA256_REGEX.find(text)
        return m?.groupValues?.getOrNull(1)?.lowercase().orEmpty()
    }

    internal fun isNewerRelease(
        remoteVersionCode: Int,
        remoteVersionName: String,
        currentVersionCode: Int,
        currentVersionName: String
    ): Boolean {
        if (remoteVersionCode > currentVersionCode) return true
        if (remoteVersionCode < currentVersionCode) return false
        return compareSemVer(remoteVersionName, currentVersionName) > 0
    }

    internal fun compareSemVer(a: String, b: String): Int {
        val partsA = a.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val partsB = b.trimStart('v', 'V').split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val maxLen = maxOf(partsA.size, partsB.size)
        for (i in 0 until maxLen) {
            val va = partsA.getOrElse(i) { 0 }
            val vb = partsB.getOrElse(i) { 0 }
            if (va != vb) return va.compareTo(vb)
        }
        return 0
    }

    private fun fetchChecksumFile(url: String): String {
        return try {
            val req = Request.Builder().url(url).build()
            httpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return ""
                extractSha256FromText(resp.body?.string().orEmpty())
            }
        } catch (_: Exception) {
            ""
        }
    }

    suspend fun downloadAndVerifyApk(
        updateInfo: UpdateInfo,
        onProgress: (Float) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (updateInfo.downloadUrl.isBlank()) {
            return@withContext Result.failure(IllegalStateException("No downloadable APK asset attached to this release."))
        }

        val updatesDir = File(context.cacheDir, "updates").apply { mkdirs() }
        val tempFile = File(updatesDir, "${updateInfo.assetFileName}.download")
        val targetFile = File(updatesDir, updateInfo.assetFileName)
        if (tempFile.exists()) tempFile.delete()

        val request = Request.Builder()
            .url(updateInfo.downloadUrl)
            .header("User-Agent", "SiriAI-Android/${BuildConfig.VERSION_NAME}")
            .build()

        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return@withContext Result.failure(IllegalStateException("APK download failed (HTTP ${response.code})."))
                }
                val body = response.body ?: return@withContext Result.failure(IllegalStateException("Empty APK response."))
                val totalBytes = body.contentLength().takeIf { it > 0 } ?: updateInfo.assetSizeBytes

                body.byteStream().use { input ->
                    FileOutputStream(tempFile).use { output ->
                        val buffer = ByteArray(16 * 1024)
                        var copied = 0L
                        while (true) {
                            val read = input.read(buffer)
                            if (read <= 0) break
                            output.write(buffer, 0, read)
                            copied += read
                            if (totalBytes > 0) {
                                onProgress((copied.toFloat() / totalBytes.toFloat()).coerceIn(0f, 1f))
                            }
                        }
                        output.flush()
                    }
                }
            }

            if (!tempFile.exists() || tempFile.length() < 1024L || !hasApkZipMagicHeader(tempFile)) {
                tempFile.delete()
                return@withContext Result.failure(IllegalStateException("Downloaded file is not a valid Android APK."))
            }

            val actualSha = computeFileSha256(tempFile)
            val expectedSha = updateInfo.sha256Checksum.trim().lowercase()
            if (expectedSha.isBlank()) {
                tempFile.delete()
                return@withContext Result.failure(SecurityException("Release SHA-256 checksum missing; installation blocked for safety."))
            }
            if (!actualSha.equals(expectedSha, ignoreCase = true)) {
                tempFile.delete()
                return@withContext Result.failure(SecurityException("SHA-256 checksum verification failed."))
            }

            if (targetFile.exists()) targetFile.delete()
            if (!tempFile.renameTo(targetFile)) {
                tempFile.copyTo(targetFile, overwrite = true)
                tempFile.delete()
            }
            onProgress(1f)
            Result.success(targetFile)
        } catch (e: Exception) {
            tempFile.delete()
            Log.w(TAG, "APK download/verify failed", e)
            Result.failure(e)
        }
    }

    internal fun computeFileSha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { fis ->
            val buf = ByteArray(16 * 1024)
            while (true) {
                val read = fis.read(buf)
                if (read <= 0) break
                digest.update(buf, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun hasApkZipMagicHeader(file: File): Boolean {
        return try {
            FileInputStream(file).use { fis ->
                val header = ByteArray(2)
                fis.read(header) == 2 && header[0] == 'P'.code.toByte() && header[1] == 'K'.code.toByte()
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Launches Android's official system Package Installer sheet via FileProvider URI.
     * Never silently installs packages; requires user approval and OS permission.
     */
    fun launchOfficialPackageInstaller(apkFile: File): ApkInstallLaunchOutcome {
        if (!apkFile.exists() || !apkFile.canRead()) {
            return ApkInstallLaunchOutcome.Failed("Verified APK file is missing.")
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
            !context.packageManager.canRequestPackageInstalls()
        ) {
            val permIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            return try {
                context.startActivity(permIntent)
                ApkInstallLaunchOutcome.UnknownSourcesPermissionNeeded
            } catch (e: Exception) {
                ApkInstallLaunchOutcome.Failed("Enable 'Install unknown apps' in Android Settings to install updates.")
            }
        }

        return try {
            val contentUri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apkFile
            )
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(contentUri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(installIntent)
            ApkInstallLaunchOutcome.LaunchedInstaller
        } catch (e: Exception) {
            Log.w(TAG, "Unable to launch package installer", e)
            ApkInstallLaunchOutcome.Failed(e.localizedMessage ?: "Failed to launch Android package installer.")
        }
    }

    fun showUpdateAvailableNotification(info: UpdateInfo) {
        if (!info.isUpdateAvailable) return
        if (!PermissionManager.isGranted(context, android.Manifest.permission.POST_NOTIFICATIONS)) return

        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                UPDATE_CHANNEL_ID,
                "App Updates",
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Notifies when a new verified Siri AI release is available on GitHub"
            }
            manager.createNotificationChannel(channel)
        }

        val openIntent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_OPEN_UPDATE_DIALOG, true)
        }
        val pi = PendingIntent.getActivity(
            context,
            UPDATE_NOTIFICATION_ID,
            openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notif = NotificationCompat.Builder(context, UPDATE_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentTitle("Siri AI Update v${info.latestVersionName} Available")
            .setContentText("Current: v${BuildConfig.VERSION_NAME} • Tap to review and install")
            .setContentIntent(pi)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()
        manager.notify(UPDATE_NOTIFICATION_ID, notif)
    }
}
