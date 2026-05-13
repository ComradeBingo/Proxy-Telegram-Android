package com.example.tgproxyparser

import android.app.AlertDialog
import android.os.Bundle
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import androidx.core.view.isVisible
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

class MainActivity : AppCompatActivity() {

    private lateinit var btnEurope: Button
    private lateinit var btnRussia: Button
    private lateinit var btnHelp: Button
    private lateinit var progressBar: ProgressBar
    private lateinit var recyclerView: RecyclerView
    private lateinit var adapter: ProxyAdapter

    // Диалог загрузки
    private var loadingDialog: AlertDialog? = null

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnEurope = findViewById(R.id.btn_europe)
        btnRussia = findViewById(R.id.btn_russia)
        btnHelp = findViewById(R.id.btn_help)
        progressBar = findViewById(R.id.progressBar)
        recyclerView = findViewById(R.id.recyclerView)

        adapter = ProxyAdapter(this, emptyList())
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnEurope.setOnClickListener {
            parseProxies("https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_eu.txt", "Европа")
        }

        btnRussia.setOnClickListener {
            parseProxies("https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_ru.txt", "Россия")
        }

        btnHelp.setOnClickListener {
            showHelpDialog()
        }
    }

    private fun showLoadingDialog(proxyCount: Int) {
        val dialogView = layoutInflater.inflate(R.layout.dialog_loading, null)
        val progressBarDialog = dialogView.findViewById<ProgressBar>(R.id.progressBarDialog)
        val tvMessage = dialogView.findViewById<TextView>(R.id.tvLoadingMessage)

        tvMessage.text = "Вычисляю доступные прокси...\nПроверяю пинг до $proxyCount серверов"

        loadingDialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setCancelable(false)
            .create()

        loadingDialog?.show()
    }

    private fun hideLoadingDialog() {
        loadingDialog?.dismiss()
        loadingDialog = null
    }

    private fun showHelpDialog() {
        val dialogView = layoutInflater.inflate(R.layout.dialog_help, null)

        val btnClose = dialogView.findViewById<Button>(R.id.btn_close)
        val btnGitHub = dialogView.findViewById<Button>(R.id.btn_github)
        val btnBoosty = dialogView.findViewById<Button>(R.id.btn_boosty)
        val btnYooMoney = dialogView.findViewById<Button>(R.id.btn_yoomoney)

        val dialog = AlertDialog.Builder(this)
            .setView(dialogView)
            .setIcon(android.R.drawable.ic_dialog_info)
            .create()

        btnClose.setOnClickListener {
            dialog.dismiss()
        }

        btnGitHub.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://github.com/ComradeBingo/Parser-telegram-proxies-list"))
            startActivity(intent)
        }

        btnBoosty.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://boosty.to/comradebingo/donate"))
            startActivity(intent)
        }

        btnYooMoney.setOnClickListener {
            val intent = android.content.Intent(android.content.Intent.ACTION_VIEW,
                android.net.Uri.parse("https://yoomoney.ru/to/410011017939948"))
            startActivity(intent)
        }

        dialog.show()
    }

    private fun parseProxies(url: String, region: String) {
        scope.launch {
            // Показываем прогресс загрузки списка
            withContext(Dispatchers.Main) {
                progressBar.isVisible = true
                btnEurope.isEnabled = false
                btnRussia.isEnabled = false
            }

            val result = fetchProxies(url)

            withContext(Dispatchers.Main) {
                progressBar.isVisible = false
                btnEurope.isEnabled = true
                btnRussia.isEnabled = true

                if (result.isSuccess) {
                    val proxies = result.getOrNull() ?: emptyList()
                    if (proxies.isNotEmpty()) {
                        // Показываем кастомный диалог вместо ProgressDialog
                        showLoadingDialog(proxies.size)

                        // Запускаем проверку пинга в фоне
                        val proxiesWithPing = withContext(Dispatchers.IO) {
                            checkProxiesPing(proxies)
                        }

                        hideLoadingDialog()

                        // Сортируем и отображаем
                        val sortedProxies = proxiesWithPing.sortedBy { it.pingMs }
                        val proxyUrls = sortedProxies.map { it.url }

                        adapter.updateData(proxyUrls)
                        Toast.makeText(
                            this@MainActivity,
                            "Загружено ${proxyUrls.size} прокси из $region\nОтсортировано по пингу",
                            Toast.LENGTH_LONG
                        ).show()
                    } else {
                        Toast.makeText(this@MainActivity, "Прокси не найдены", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(this@MainActivity, "Ошибка: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    // Функция для проверки пинга всех прокси
    private suspend fun checkProxiesPing(proxies: List<String>): List<ProxyWithPing> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ProxyWithPing>()

            // Запускаем параллельную проверку всех прокси
            val jobs = proxies.map { proxyUrl ->
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

            // Ждем все результаты
            jobs.forEach { job ->
                results.add(job.await())
            }

            // Фильтруем доступные (пинг > 0)
            results.filter { it.pingMs > 0 }
        }
    }

    // Измерение пинга
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

    private suspend fun fetchProxies(url: String): Result<List<String>> {
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
                    .filter { it.startsWith("tg://proxy?") }

                Result.success(proxies)
            } catch (e: Exception) {
                Result.failure(e)
            }
        }
    }

    // Парсинг прокси URL
    private fun parseProxyUrl(url: String): ProxyInfo? {
        return try {
            if (!url.startsWith("tg://proxy?")) return null

            val params = url.substring("tg://proxy?".length).split("&")
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