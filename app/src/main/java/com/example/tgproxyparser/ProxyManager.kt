package com.example.tgproxyparser

import android.content.ContentResolver
import android.net.Uri
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.net.Socket
import java.text.SimpleDateFormat
import java.util.*
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request

object ProxyManager {
    private val client = OkHttpClient()
    private const val MAX_PROXIES = 10000
    private val SOURCES = listOf(
        "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_ru.txt" to "tg://proxy?",
        "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_eu.txt" to "tg://proxy?",
        "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/refs/heads/main/proxies-tested.txt" to "https://t.me/proxy?",
        "https://raw.githubusercontent.com/SoliSpirit/mtproto/master/all_proxies.txt" to "https://t.me/proxy?",
        "https://raw.githubusercontent.com/MustafaBaqer/VestraNet-Nodes/refs/heads/main/protocols/mtproto.txt" to "tg://proxy?"
    )

    suspend fun fetchAllSources(onProgress: (sourceIndex: Int, total: Int, count: Int) -> Unit): List<String> {
        return withContext(Dispatchers.IO) {
            val allProxies = mutableListOf<String>()
            val totalSources = SOURCES.size

            SOURCES.forEachIndexed { index, (url, prefix) ->
                try {
                    val proxies = fetchProxies(url, prefix)
                    allProxies.addAll(proxies)
                    onProgress(index + 1, totalSources, proxies.size)
                } catch (_: Exception) {
                    onProgress(index + 1, totalSources, 0)
                }
            }

            allProxies
        }
    }

    suspend fun fetchProxies(url: String, urlPrefix: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder().url(url).build()
                val response = client.newCall(request).execute()

                if (!response.isSuccessful) {
                    android.util.Log.e("ProxyManager", "Response not successful: ${response.code}")
                    return@withContext emptyList()
                }

                val body = response.body?.string() ?: ""
                android.util.Log.d("ProxyManager", "Body length: ${body.length}")
                android.util.Log.d("ProxyManager", "First 200 chars: ${body.take(200)}")

                // ПРЯМОЙ ПАРСИНГ - берем ВСЕ строки, которые содержат server= и port=
                val lines = body.lines()
                android.util.Log.d("ProxyManager", "Total lines: ${lines.size}")

                val result = mutableListOf<String>()
                for (line in lines) {
                    val trimmed = line.trim()
                    // Если строка содержит server= и port= - это прокси
                    if (trimmed.contains("server=") && trimmed.contains("port=")) {
                        // Если строка начинается с https://t.me/proxy?, конвертируем
                        var proxy = trimmed
                        if (proxy.startsWith("https://t.me/proxy?")) {
                            proxy = "tg://proxy?" + proxy.substring("https://t.me/proxy?".length)
                        } else if (!proxy.startsWith("tg://")) {
                            // Если не начинается с tg://, добавляем префикс
                            proxy = "tg://proxy?$proxy"
                        }
                        result.add(proxy)
                    }
                }

                android.util.Log.d("ProxyManager", "Found ${result.size} proxies")
                if (result.isNotEmpty()) {
                    android.util.Log.d("ProxyManager", "First proxy: ${result.first()}")
                }

                result

            } catch (e: Exception) {
                android.util.Log.e("ProxyManager", "Error: ${e.message}", e)
                emptyList()
            }
        }
    }

    fun deduplicateProxies(proxies: List<String>): List<String> {
        return proxies.distinctBy { normalizeProxyKey(it) }
    }

    fun normalizeProxyKey(url: String): String {
        try {
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
                        val rawSecret = param.substringAfter("=")
                        val cleanSecret = rawSecret.split("&", "#", "@").first()
                        if (cleanSecret.isNotEmpty()) {
                            secret = cleanSecret
                        }
                    }
                }
            }

            return if (server.isNotEmpty() && port.isNotEmpty() && secret.isNotEmpty()) {
                "$server:$port:$secret"
            } else if (server.isNotEmpty() && port.isNotEmpty()) {
                "$server:$port"
            } else {
                url
            }
        } catch (_: Exception) {
            return url
        }
    }

    suspend fun saveProxiesToFile(proxies: List<String>): File? {
        return withContext(Dispatchers.IO) {
            try {
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
                val fileName = "proxies_$timestamp.txt"

                val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
                if (!downloadsDir.exists()) {
                    downloadsDir.mkdirs()
                }

                val file = File(downloadsDir, fileName)
                FileOutputStream(file).use { outputStream ->
                    proxies.forEach { proxy ->
                        outputStream.write((proxy + "\n").toByteArray())
                    }
                }

                file
            } catch (_: Exception) {
                null
            }
        }
    }

    suspend fun loadProxiesFromFile(contentResolver: ContentResolver, uri: Uri): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val proxies = mutableListOf<String>()
                contentResolver.openInputStream(uri)?.use { inputStream ->
                    inputStream.bufferedReader().use { reader ->
                        var line: String?
                        var count = 0
                        while (reader.readLine().also { line = it } != null && count < MAX_PROXIES) {
                            line?.let {
                                val trimmedLine = it.trim()
                                if (trimmedLine.isNotEmpty() && !trimmedLine.startsWith("#")) {
                                    proxies.add(trimmedLine)
                                    count++
                                }
                            }
                        }
                    }
                }
                proxies
            } catch (_: Exception) {
                emptyList()
            }
        }
    }

    suspend fun checkProxiesPingParallel(
        proxies: List<String>,
        batchSize: Int = 50,
        onProgress: (processed: Int, total: Int, working: Int) -> Unit
    ): List<ProxyWithPing> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ProxyWithPing>()
            val total = proxies.size
            var processed = 0
            var working = 0

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
                val filtered = batchResults.filterNotNull()
                results.addAll(filtered)
                working += filtered.size
                processed += batch.size

                withContext(Dispatchers.Main) {
                    onProgress(processed, total, working)
                }
            }

            results.sortedBy { it.pingMs }
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
            } catch (_: Exception) {
                -1
            } finally {
                try {
                    socket?.close()
                } catch (_: Exception) { }
            }
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
        } catch (_: Exception) {
            null
        }
    }
}