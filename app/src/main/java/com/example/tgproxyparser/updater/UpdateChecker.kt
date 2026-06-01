package com.example.tgproxyparser.updater

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.IOException

class UpdateChecker(
    private val context: Context,
    private val client: OkHttpClient
) {

    /**
     * Проверяет наличие новой версии
     * @param currentVersionName Текущая версия (например "1.3" или "v1.3")
     * @return GitHubRelease если есть новая версия, иначе null
     */
    suspend fun checkForUpdate(currentVersionName: String): GitHubRelease? {
        return withContext(Dispatchers.IO) {
            try {
                val latestRelease = fetchLatestRelease()
                val latestVersionName = latestRelease.tagName

                if (isNewerVersion(currentVersionName, latestVersionName)) {
                    latestRelease
                } else {
                    null
                }
            } catch (e: Exception) {
                e.printStackTrace()
                null
            }
        }
    }

    /**
     * Сравнивает две версии
     * Примеры: "1.3" vs "1.4" → true
     *          "1.4.1" vs "1.4.1" → false
     *          "1.9" vs "1.10" → true
     *          "1.4.2" vs "2.0" → true
     */
    private fun isNewerVersion(currentVersion: String, latestVersion: String): Boolean {
        val cleanCurrent = currentVersion.removePrefix("v").split(".")
        val cleanLatest = latestVersion.removePrefix("v").split(".")

        val maxLength = maxOf(cleanCurrent.size, cleanLatest.size)

        for (i in 0 until maxLength) {
            val currentPart = cleanCurrent.getOrNull(i)?.toIntOrNull() ?: 0
            val latestPart = cleanLatest.getOrNull(i)?.toIntOrNull() ?: 0

            if (latestPart != currentPart) {
                return latestPart > currentPart
            }
        }

        return false // версии равны
    }

    private suspend fun fetchLatestRelease(): GitHubRelease {
        val url = "https://api.github.com/repos/ComradeBingo/Proxy-Telegram-Android/releases/latest"
        val request = Request.Builder()
            .url(url)
            .header("Accept", "application/vnd.github.v3+json")
            .header("User-Agent", "TGProxy-Android")
            .build()

        val response = client.newCall(request).execute()

        if (!response.isSuccessful) {
            throw IOException("GitHub API error: ${response.code}")
        }

        val json = JSONObject(response.body?.string() ?: throw IOException("Empty response"))

        // Получаем ссылку на страницу релиза
        val htmlUrl = json.getString("html_url")

        // Получаем ссылку на APK (оставим на всякий случай)
        val assets = json.getJSONArray("assets")
        var apkUrl = ""
        for (i in 0 until assets.length()) {
            val asset = assets.getJSONObject(i)
            if (asset.getString("name").endsWith(".apk")) {
                apkUrl = asset.getString("browser_download_url")
                break
            }
        }

        return GitHubRelease(
            tagName = json.getString("tag_name"),
            changelog = json.optString("body", ""),  // optString чтобы не было ошибки если нет описания
            apkUrl = apkUrl,
            htmlUrl = htmlUrl
        )
    }

    // Метод для открытия страницы релиза
    fun openReleasePage(releaseUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(releaseUrl))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    // Старый метод оставим для совместимости (если нужно)
    fun openDownloadPage(downloadUrl: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(downloadUrl))
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}