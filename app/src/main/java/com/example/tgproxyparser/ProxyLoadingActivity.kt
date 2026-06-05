package com.example.tgproxyparser

import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Socket

class ProxyLoadingActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnCancel: MaterialButton

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private var sourceUrl: String = ""
    private var sourceName: String = ""
    private var urlPrefix: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_loading)

        sourceUrl = intent.getStringExtra("source_url") ?: ""
        sourceName = intent.getStringExtra("source_name") ?: "Источник"
        urlPrefix = intent.getStringExtra("url_prefix") ?: "tg://proxy?"

        initViews()
        startLoading()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvLoadingStatus)
        tvCount = findViewById(R.id.tvProxyCount)
        progressBar = findViewById(R.id.loadingProgressBar)
        btnCancel = findViewById(R.id.btnCancel)

        btnCancel.setOnClickListener {
            finish()
        }
    }

    private fun startLoading() {
        scope.launch {
            updateStatus("Загрузка списка прокси...", 0, 0)

            val result = fetchProxies(sourceUrl, urlPrefix)

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    val rawProxies = result.getOrNull() ?: emptyList()
                    if (rawProxies.isNotEmpty()) {
                        // Удаляем дубликаты по нормализованному ключу
                        val uniqueProxies = rawProxies.distinctBy { normalizeProxyKey(it) }

                        updateStatus("Проверяю доступность...", 0, uniqueProxies.size)

                        val proxiesWithPing = withContext(Dispatchers.IO) {
                            checkProxiesPingParallel(uniqueProxies, 50)
                        }

                        val sortedProxies = proxiesWithPing.sortedBy { it.pingMs }

                        if (sortedProxies.isNotEmpty()) {
                            val intent = Intent(this@ProxyLoadingActivity, ProxyListActivity::class.java)
                            intent.putExtra("proxies_list", ArrayList(sortedProxies))
                            intent.putExtra("source_name", sourceName)
                            startActivity(intent)
                            finish()
                        } else {
                            showError("Нет доступных прокси")
                        }
                    } else {
                        showError("Прокси не найдены")
                    }
                } else {
                    val error = result.exceptionOrNull()?.message ?: "Неизвестная ошибка"
                    showError("Ошибка: $error")
                }
            }
        }
    }


    // Функция возвращает ключ для сравнения (server+port+очищенный secret) дабы не лезли дубли
    private fun normalizeProxyKey(url: String): String {
        try {
            // Извлекаем параметры из URL
            val paramsPart = when {
                url.startsWith("tg://proxy?") -> url.substring("tg://proxy?".length)
                url.startsWith("tg://socks?") -> url.substring("tg://socks?".length)
                url.startsWith("https://t.me/proxy?") -> url.substring("https://t.me/proxy?".length)
                url.startsWith("https://t.me/socks?") -> url.substring("https://t.me/socks?".length)
                else -> return url
            }

            val params = paramsPart.split("&")
            var server = ""
            var port = ""
            var secret = ""

            for (param in params) {
                when {
                    param.startsWith("server=") -> server = param.substringAfter("=")
                    param.startsWith("port=") -> port = param.substringAfter("=")
                    param.startsWith("secret=") -> {
                        // Берем значение secret
                        var rawSecret = param.substringAfter("=")
                        // Обрезаем по первому вхождению & или #
                        val cleanSecret = rawSecret.split("&", "#","@").first()
                        if (cleanSecret.isNotEmpty()) {
                            secret = cleanSecret
                        }
                    }
                }
            }

            // Ключ = server:port:secret
            return if (server.isNotEmpty() && port.isNotEmpty() && secret.isNotEmpty()) {
                "$server:$port:$secret"
            } else if (server.isNotEmpty() && port.isNotEmpty()) {
                "$server:$port"
            } else {
                url
            }
        } catch (e: Exception) {
            return url
        }
    }

    private suspend fun checkProxiesPingParallel(proxies: List<String>, batchSize: Int = 50): List<ProxyWithPing> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ProxyWithPing>()
            val total = proxies.size
            var processed = 0

            val batches = proxies.chunked(batchSize)

            for (batch in batches) {
                val deferredResults = batch.map { proxyUrl ->
                    async {
                        val proxyInfo = parseProxyUrl(proxyUrl)
                        if (proxyInfo != null) {
                            val pingMs = measurePing(proxyInfo.server, proxyInfo.port.toIntOrNull() ?: 443)
                            if (pingMs > 0 && pingMs < 5000) {
                                ProxyWithPing(proxyUrl, pingMs)
                            } else null
                        } else null
                    }
                }

                val batchResults = deferredResults.awaitAll()
                results.addAll(batchResults.filterNotNull())

                processed += batch.size
                withContext(Dispatchers.Main) {
                    updateStatus("Проверяю доступность...", processed, total, results.size)
                }
            }

            results
        }
    }

    private suspend fun measurePing(server: String, port: Int): Int {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                val startTime = System.currentTimeMillis()
                socket = Socket()
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

    private fun updateStatus(message: String, current: Int = 0, total: Int = 0, working: Int = 0) {
        runOnUiThread {
            tvStatus.text = message
            if (total > 0) {
                val percent = (current * 100) / total
                progressBar.progress = percent
                if (working > 0) {
                    tvCount.text = "Проверено: $current / $total | Работает: $working"
                } else {
                    tvCount.text = "Проверено: $current / $total"
                }
            } else {
                progressBar.progress = 0
                tvCount.text = ""
            }
        }
    }

    private fun showError(message: String) {
        runOnUiThread {
            tvStatus.text = message
            tvCount.text = "Нажмите 'Назад' для возврата"
            progressBar.visibility = android.view.View.GONE
            Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}