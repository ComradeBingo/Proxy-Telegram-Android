package com.example.tgproxyparser

import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.isVisible
import com.example.tgproxyparser.updater.UpdateChecker
import com.example.tgproxyparser.updater.GitHubRelease
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var btnHelp: Button
    private lateinit var btnSurfboard: Button
    private lateinit var tvStatus: TextView

    // Элементы для раскрывающегося блока
    private lateinit var headerKort0881: LinearLayout
    private lateinit var contentKort0881: LinearLayout
    private lateinit var tvArrow: TextView
    private lateinit var btnRussiaCard: LinearLayout
    private lateinit var btnEuropeCard: LinearLayout

    private var isKort0881Expanded = false

    // Диалог загрузки
    private var loadingDialog: AlertDialog? = null
    private var progressDialogView: View? = null
    private var tvDialogMessage: TextView? = null
    private var progressBarDialog: ProgressBar? = null

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private lateinit var updateChecker: UpdateChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Инициализация UI элементов
        btnHelp = findViewById(R.id.btn_help)
        btnSurfboard = findViewById(R.id.btn_surfboard)

        // Элементы Kort0881
        headerKort0881 = findViewById(R.id.header_kort0881)
        contentKort0881 = findViewById(R.id.content_kort0881)
        tvArrow = findViewById(R.id.tv_kort0881_arrow)
        btnRussiaCard = findViewById(R.id.btn_russia_card)
        btnEuropeCard = findViewById(R.id.btn_europe_card)

        // Инициализация проверки обновлений
        updateChecker = UpdateChecker(this, client)

        // Обработчик раскрытия блока Kort0881
        headerKort0881.setOnClickListener {
            toggleKort0881()
        }

        // Обработчики кнопок регионов
        btnRussiaCard.setOnClickListener {
            parseProxies(
                "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_ru.txt",
                "Россия (Kort0881)",
                "tg://proxy?"
            )
        }

        btnEuropeCard.setOnClickListener {
            parseProxies(
                "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_eu.txt",
                "Европа (Kort0881)",
                "tg://proxy?"
            )
        }

        btnSurfboard.setOnClickListener {
            parseProxies(
                "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/refs/heads/main/proxies-tested.txt",
                "SurfboardV2ray",
                "https://t.me/proxy?"
            )
        }

        btnHelp.setOnClickListener {
            showHelpDialog()
            checkForUpdates()
        }

        // Проверка обновлений при запуске
        checkForUpdates()
    }

    private fun toggleKort0881() {
        isKort0881Expanded = !isKort0881Expanded
        contentKort0881.visibility = if (isKort0881Expanded) View.VISIBLE else View.GONE
        tvArrow.text = if (isKort0881Expanded) "▼" else "▶"
    }

    private fun showLoadingDialog(proxyCount: Int) {
        // Создаём кастомный диалог
        progressDialogView = layoutInflater.inflate(R.layout.dialog_progress, null)
        tvDialogMessage = progressDialogView?.findViewById(R.id.tv_progress_message)
        progressBarDialog = progressDialogView?.findViewById(R.id.progressBarDialog)

        loadingDialog = AlertDialog.Builder(this)
            .setView(progressDialogView)
            .setCancelable(false)
            .create()

        loadingDialog?.show()
        updateDialogMessage("Загрузка списка прокси...")
    }

    private fun updateDialogMessage(message: String) {
        tvDialogMessage?.text = message
    }

    private fun updateDialogProgress(current: Int, total: Int) {
        tvDialogMessage?.text = "Проверяю пинг... $current/$total"
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
        progressDialogView = null
        tvDialogMessage = null
        progressBarDialog = null
    }

    private fun showHelpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_help, null)

        val btnClose = dialogView.findViewById<Button>(R.id.btn_close)
        val btnGitHub = dialogView.findViewById<Button>(R.id.btn_github)


        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setIcon(android.R.drawable.ic_dialog_info)
            .create()

        // Показ версии из BuildConfig
        val tvVersion = dialogView.findViewById<TextView>(R.id.tv_version)
        tvVersion.text = "Версия: ${BuildConfig.VERSION_NAME}"

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnGitHub.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/ComradeBingo"))
            startActivity(intent)
        }


        dialog.show()
    }

    private fun parseProxies(url: String, region: String, urlPrefix: String) {
        scope.launch {
            withContext(Dispatchers.Main) {
                // Блокируем кнопки
                btnHelp.isEnabled = false
                btnSurfboard.isEnabled = false
                btnRussiaCard.isEnabled = false
                btnEuropeCard.isEnabled = false

                // Показываем диалог
                showLoadingDialog(0)
            }

            val result = fetchProxies(url, urlPrefix)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val proxies = result.getOrNull() ?: emptyList()
                    if (proxies.isNotEmpty()) {
                        updateDialogMessage("Найдено ${proxies.size} прокси, проверяем пинг...")

                        val proxiesWithPing = withContext(Dispatchers.IO) {
                            checkProxiesPing(proxies)
                        }

                        hideLoadingDialog()

                        val sortedProxies = proxiesWithPing.sortedBy { it.pingMs }

                        if (sortedProxies.isNotEmpty()) {
                            // Открываем новое окно со списком прокси
                            val intent = Intent(this@MainActivity, ProxyListActivity::class.java).apply {
                                putExtra("proxies_list", ArrayList(sortedProxies.map {
                                    ProxyListActivity.ProxyWithPing(it.url, it.pingMs)
                                }))
                                putExtra("source_name", region)
                            }
                            startActivity(intent)
                        } else {
                            Toast.makeText(this@MainActivity, "Нет доступных прокси", Toast.LENGTH_SHORT).show()
                        }
                    } else {
                        hideLoadingDialog()
                        Toast.makeText(this@MainActivity, "Прокси не найдены", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    hideLoadingDialog()
                    Toast.makeText(this@MainActivity, "Ошибка: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }

                // Разблокируем кнопки
                btnHelp.isEnabled = true
                btnSurfboard.isEnabled = true
                btnRussiaCard.isEnabled = true
                btnEuropeCard.isEnabled = true
            }
        }
    }

    private suspend fun checkProxiesPing(proxies: List<String>): List<ProxyWithPing> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ProxyWithPing>()
            val total = proxies.size
            val batchSize = 10  // Проверяем по 10 прокси одновременно

            val chunks = proxies.chunked(batchSize)
            var processed = 0

            for (chunk in chunks) {
                val jobs = chunk.map { proxyUrl ->
                    async {
                        val proxyInfo = parseProxyUrl(proxyUrl)
                        val pingMs = if (proxyInfo != null) {
                            measurePing(proxyInfo.server, proxyInfo.port.toIntOrNull() ?: 443)
                        } else {
                            -1
                        }
                        ProxyWithPing(proxyUrl, pingMs)
                    }
                }

                val chunkResults = jobs.awaitAll()
                results.addAll(chunkResults)
                processed += chunk.size

                // Обновляем прогресс в диалоге
                withContext(Dispatchers.Main) {
                    updateDialogProgress(processed, total)
                }
            }

            results.filter { it.pingMs > 0 }
        }
    }

    private suspend fun measurePing(server: String, port: Int): Int {
        return withContext(Dispatchers.IO) {
            var socket: java.net.Socket? = null
            try {
                val startTime = System.currentTimeMillis()
                socket = java.net.Socket()
                socket.connect(java.net.InetSocketAddress(server, port), 3000)
                val endTime = System.currentTimeMillis()
                (endTime - startTime).toInt()
            } catch (e: Exception) {
                -1
            } finally {
                try {
                    socket?.close()
                } catch (e: Exception) { }
            }
        }
    }

    private suspend fun fetchProxies(url: String, urlPrefix: String): Result<List<String>> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    return@withContext Result.failure(IOException("HTTP ${response.code}"))
                }

                val body = response.body?.string() ?: ""
                val proxies = body.lines()
                    .filter { it.isNotBlank() }
                    .map { it.trim() }
                    .filter { it.startsWith(urlPrefix) }
                    .map { convertToTgFormat(it, urlPrefix) }
                    .distinct()

                Result.success(proxies)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    private fun convertToTgFormat(url: String, originalPrefix: String): String {
        return when {
            originalPrefix == "https://t.me/proxy?" -> {
                "tg://proxy?" + url.substring("https://t.me/proxy?".length)
            }
            originalPrefix == "https://t.me/socks?" -> {
                "tg://socks?" + url.substring("https://t.me/socks?".length)
            }
            else -> url
        }
    }

    private fun parseProxyUrl(url: String): ProxyInfo? {
        return try {
            val cleanUrl = when {
                url.startsWith("tg://proxy?") -> url.substring("tg://proxy?".length)
                url.startsWith("tg://socks?") -> url.substring("tg://socks?".length)
                else -> return null
            }

            val params = cleanUrl.split("&")
            var server = ""
            var port = ""

            for (param in params) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    when (parts[0]) {
                        "server" -> server = parts[1]
                        "port" -> port = parts[1]
                    }
                }
            }

            if (server.isNotEmpty() && port.isNotEmpty()) {
                ProxyInfo(server, port)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun checkForUpdates() {
        scope.launch {
            try {
                val versionName = packageManager.getPackageInfo(packageName, 0).versionName
                val release = updateChecker.checkForUpdate(versionName ?: "1.0")
                release?.let {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(it)
                    }
                }
            } catch (e: Exception) {
                // Игнорируем ошибки
            }
        }
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        val versionName = release.tagName.removePrefix("v")

        AlertDialog.Builder(this)
            .setTitle("Доступно обновление v$versionName")
            .setMessage(release.changelog.ifEmpty { "Доступна новая версия приложения" })
            .setPositiveButton("Скачать") { _, _ ->
                updateChecker.openDownloadPage(release.apkUrl)
            }
            .setNegativeButton("Позже", null)
            .setIcon(android.R.drawable.ic_dialog_info)
            .show()
    }

    data class ProxyInfo(
        val server: String,
        val port: String
    )

    data class ProxyWithPing(
        val url: String,
        val pingMs: Int
    )

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
        hideLoadingDialog()
    }
}