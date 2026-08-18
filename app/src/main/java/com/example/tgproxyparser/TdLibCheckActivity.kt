package com.example.tgproxyparser

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi
import java.io.File
import java.net.Socket
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class TdLibCheckActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var tvTitle: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnCancel: MaterialButton
    private lateinit var btnSources: MaterialButton
    private lateinit var btnFile: MaterialButton

    // НОВОЕ: кнопка сохранения и текстовый блок
    private lateinit var btnSaveAll: MaterialButton
    private lateinit var infoCard: com.google.android.material.card.MaterialCardView
    private lateinit var tvInfoText: TextView

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var isCancelled = false

    // TDLib параметры
    private companion object {
        private const val TAG = "TdLibCheckActivity"
        private const val CLIENT_POOL_SIZE = 3
        private const val BATCH_SIZE = 20
        private const val PING_TIMEOUT = 3.0
        private const val SOCKET_TIMEOUT = 1500
    }

    private val lifecycleMutex = Mutex()
    private val poolWriteLock = Any()
    private val nextClientIndex = AtomicInteger(0)

    @Volatile
    private var workRoot: File? = null
    @Volatile
    private var clientPool: List<Client> = emptyList()

    private var selectedFileUri: Uri? = null

    // Регистрируем файловый пикер
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedFileUri = it
            startFileCheck()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tdlib_check)

        initializeTdLib(this)
        initViews()
    }

    private fun initializeTdLib(context: Context) {
        workRoot = File(context.filesDir, "tdlib-pings").apply { mkdirs() }
    }

    private fun initViews() {
        tvTitle = findViewById(R.id.tvTdLibTitle)
        tvStatus = findViewById(R.id.tvTdLibStatus)
        tvCount = findViewById(R.id.tvTdLibCount)
        progressBar = findViewById(R.id.tdLibProgressBar)
        btnCancel = findViewById(R.id.btnCancelTdLib)
        btnSources = findViewById(R.id.btnCheckSources)
        btnFile = findViewById(R.id.btnCheckFile)

        // НОВОЕ: инициализация кнопки сохранения и текстового блока
        btnSaveAll = findViewById(R.id.btnSaveAllProxies)
        infoCard = findViewById(R.id.infoCard)
        tvInfoText = findViewById(R.id.tvInfoText)
        infoCard.visibility = android.view.View.VISIBLE

        btnCancel.setOnClickListener {
            isCancelled = true
            scope.cancel()
            finish()
        }

        btnSources.setOnClickListener {
            startSourcesCheck()
        }

        btnFile.setOnClickListener {
            openFilePicker()
        }

        // НОВОЕ: обработчик кнопки сохранения
        btnSaveAll.setOnClickListener {
            saveAllProxiesToFile()
        }
    }

    // ==================== Проверка из списков ====================

    private fun startSourcesCheck() {
        scope.launch {
            try {
                // Подготовка TDLib
                updateStatus("Подготовка TDLib...", 0, 0)
                prepareSearch()

                if (isCancelled) return@launch

                // Сбор прокси
                updateStatus("Сбор прокси из всех источников...", 0, 0)

                val allProxies = ProxyManager.fetchAllSources { sourceIndex, total, count ->
                    updateStatus(
                        "Загрузка источника $sourceIndex из $total...",
                        sourceIndex,
                        total,
                        count
                    )
                }

                if (isCancelled) return@launch

                if (allProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Не удалось загрузить прокси из источников")
                    }
                    return@launch
                }

                // Дедупликация
                updateStatus("Удаление дубликатов...", 0, allProxies.size)
                val uniqueProxies = ProxyManager.deduplicateProxies(allProxies)

                if (isCancelled) return@launch

                if (uniqueProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("После удаления дубликатов не осталось прокси")
                    }
                    return@launch
                }

                // Фильтр "ee"
                val filteredProxies = uniqueProxies.filter { proxyUrl ->
                    val proxyInfo = parseProxyUrl(proxyUrl)
                    val secret = proxyInfo?.secret ?: ""
                    proxyInfo != null && secret.startsWith("ee", ignoreCase = true)
                }

                if (filteredProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Нет MTProto прокси с секретом 'ee'")
                    }
                    return@launch
                }

                updateStatus(
                    "Найдено ${filteredProxies.size} прокси с секретом 'ee'...",
                    0,
                    filteredProxies.size
                )

                // Предпроверка Socket
                updateStatus("Быстрая предпроверка...", 0, filteredProxies.size)
                val socketAliveProxies = filterWithSocket(filteredProxies) { processed, total ->
                    updateStatus("Предпроверка...", processed, total)
                }

                if (isCancelled) return@launch

                if (socketAliveProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Нет живых прокси после предпроверки")
                    }
                    return@launch
                }

                // TDLib проверка
                updateStatus(
                    "MTProto проверка через TDLib (${socketAliveProxies.size} прокси)...",
                    0,
                    socketAliveProxies.size
                )

                val workingProxies = checkProxiesWithTDLib(socketAliveProxies) { processed, total, working ->
                    updateStatus(
                        "MTProto проверка...",
                        processed,
                        total,
                        working
                    )
                }

                if (isCancelled) return@launch

                // ПОКАЗЫВАЕМ РЕЗУЛЬТАТЫ (СТАРАЯ ЛОГИКА — НЕ ТРОГАЕМ)
                showResults(workingProxies, "Из списков (TDLib)")

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Ошибка: ${e.message}")
                }
            } finally {
                finishSearch()
            }
        }
    }

    // ==================== Проверка из файла ====================

    private fun openFilePicker() {
        filePickerLauncher.launch(arrayOf("text/plain", "text/*"))
    }

    private fun startFileCheck() {
        scope.launch {
            try {
                val uri = selectedFileUri
                if (uri == null) {
                    withContext(Dispatchers.Main) {
                        showError("Файл не выбран")
                    }
                    return@launch
                }

                // Подготовка TDLib
                updateStatus("Подготовка TDLib...", 0, 0)
                prepareSearch()

                if (isCancelled) return@launch

                // Чтение файла
                updateStatus("Чтение файла...", 0, 0)

                val proxiesFromFile = ProxyManager.loadProxiesFromFile(contentResolver, uri)

                if (proxiesFromFile.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Файл пуст или содержит неверный формат")
                    }
                    return@launch
                }

                // Фильтр "ee"
                val filteredProxies = proxiesFromFile.filter { proxyUrl ->
                    val proxyInfo = parseProxyUrl(proxyUrl)
                    val secret = proxyInfo?.secret ?: ""
                    proxyInfo != null && secret.startsWith("ee", ignoreCase = true)
                }

                if (filteredProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Нет MTProto прокси с секретом 'ee'")
                    }
                    return@launch
                }

                updateStatus(
                    "Найдено ${filteredProxies.size} прокси с секретом 'ee'...",
                    0,
                    filteredProxies.size
                )

                // Предпроверка Socket
                updateStatus("Быстрая предпроверка...", 0, filteredProxies.size)
                val socketAliveProxies = filterWithSocket(filteredProxies) { processed, total ->
                    updateStatus("Предпроверка...", processed, total)
                }

                if (isCancelled) return@launch

                if (socketAliveProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Нет живых прокси после предпроверки")
                    }
                    return@launch
                }

                // TDLib проверка
                updateStatus(
                    "MTProto проверка через TDLib (${socketAliveProxies.size} прокси)...",
                    0,
                    socketAliveProxies.size
                )

                val workingProxies = checkProxiesWithTDLib(socketAliveProxies) { processed, total, working ->
                    updateStatus(
                        "MTProto проверка...",
                        processed,
                        total,
                        working
                    )
                }

                if (isCancelled) return@launch

                // ПОКАЗЫВАЕМ РЕЗУЛЬТАТЫ (СТАРАЯ ЛОГИКА — НЕ ТРОГАЕМ)
                showResults(workingProxies, "Из файла (TDLib)")

            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    showError("Ошибка: ${e.message}")
                }
            } finally {
                finishSearch()
            }
        }
    }

    // ==================== НОВЫЙ МЕТОД: СОХРАНЕНИЕ ВСЕХ ПРОКСИ (БЕЗ БЛОКИРОВКИ) ====================

    private fun saveAllProxiesToFile() {
        scope.launch {
            try {
                updateStatus("Загрузка всех источников...", 0, 0)

                val allProxies = ProxyManager.fetchAllSources { sourceIndex, total, count ->
                    updateStatus(
                        "Загрузка источника $sourceIndex из $total...",
                        sourceIndex,
                        total,
                        count
                    )
                }

                if (isCancelled) return@launch

                if (allProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Не удалось загрузить прокси из источников")
                    }
                    return@launch
                }

                updateStatus("Удаление дубликатов...", 0, allProxies.size)

                val uniqueProxies = ProxyManager.deduplicateProxies(allProxies)

                if (uniqueProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("После удаления дубликатов не осталось прокси")
                    }
                    return@launch
                }

                updateStatus("Сохранение файла...", 0, uniqueProxies.size)

                val file = ProxyManager.saveProxiesToFile(uniqueProxies)

                withContext(Dispatchers.Main) {
                    if (file != null) {
                        Toast.makeText(
                            this@TdLibCheckActivity,
                            "✅ Сохранено ${uniqueProxies.size} прокси в файл",
                            Toast.LENGTH_LONG
                        ).show()
                        updateStatus("✅ Готово", 0, 0)
                    } else {
                        showError("Не удалось сохранить файл")
                    }
                }

            } catch (e: Exception) {
                Log.e("SaveProxies", "Ошибка сохранения", e)
                withContext(Dispatchers.Main) {
                    showError("Ошибка: ${e.message}")
                }
            }
        }
    }

    // ==================== TDLib методы (БЕЗ ИЗМЕНЕНИЙ) ====================

    private suspend fun prepareSearch() {
        val root = workRoot ?: error("TDLib не инициализирован")
        lifecycleMutex.withLock {
            val existing = clientPool
            if (existing.size >= CLIENT_POOL_SIZE) return
            if (existing.isEmpty()) clearWorkRoot(root)

            val created = coroutineScope {
                List(CLIENT_POOL_SIZE - existing.size) {
                    async { createTdLibClient(root) }
                }.awaitAll()
            }
            synchronized(poolWriteLock) {
                clientPool = clientPool + created
            }
            Log.i(TAG, "TDLib client pool ready, size=${clientPool.size}")
        }
    }

    private suspend fun finishSearch() {
        clientPool.forEach { client ->
            runCatching { client.send(TdApi.Close()) { } }
        }
        clientPool = emptyList()
    }

    private suspend fun createTdLibClient(root: File): Client {
        val tdReady = CompletableDeferred<Unit>()
        val clientDir = File(root, UUID.randomUUID().toString()).apply { mkdirs() }
        val clientRef = AtomicReference<Client?>(null)

        val client = Client.create(
            { update ->
                val c = clientRef.get()
                if (c != null) {
                    handleAuthorizationUpdate(update, c, tdReady, clientDir)
                }
            },
            { e -> Log.e(TAG, "TDLib update handler exception", e) },
            { e -> Log.e(TAG, "TDLib internal exception", e) }
        )
        clientRef.set(client)

        try {
            tdReady.await()
        } catch (error: Throwable) {
            client.send(TdApi.Close()) { }
            throw error
        }
        return client
    }

    private fun handleAuthorizationUpdate(
        update: TdApi.Object,
        client: Client,
        tdReady: CompletableDeferred<Unit>,
        clientDir: File,
    ) {
        if (update !is TdApi.UpdateAuthorizationState) return

        when (update.authorizationState) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                if (tdReady.isCompleted) return

                val dbPath = File(clientDir, "tdlib-db").absolutePath
                val filesPath = File(clientDir, "tdlib-files").apply { mkdirs() }.absolutePath

                val params = TdApi.SetTdlibParameters().apply {
                    useTestDc = false
                    databaseDirectory = dbPath
                    filesDirectory = filesPath
                    databaseEncryptionKey = byteArrayOf()
                    useFileDatabase = false
                    useChatInfoDatabase = false
                    useMessageDatabase = false
                    useSecretChats = false
                    apiId = 36488326
                    apiHash = "28e0c7f8112c7b6c5c2d31fed35b66ac"
                    systemLanguageCode = "en"
                    deviceModel = "Android"
                    systemVersion = "Android"
                    applicationVersion = "1.9"
                }

                client.send(params) { result ->
                    when (result) {
                        is TdApi.Ok -> tdReady.complete(Unit)
                        is TdApi.Error -> tdReady.completeExceptionally(
                            IllegalStateException("setTdlibParameters failed ${result.code}: ${result.message}")
                        )
                        else -> tdReady.completeExceptionally(
                            IllegalStateException("Unexpected response: ${result.javaClass.simpleName}")
                        )
                    }
                }
            }
            is TdApi.AuthorizationStateClosed -> {
                Log.w(TAG, "TDLib client closed unexpectedly")
                tdReady.completeExceptionally(IllegalStateException("TDLib client closed"))
                synchronized(poolWriteLock) {
                    clientPool = clientPool.filter { it !== client }
                }
            }
            else -> { }
        }
    }

    private fun clearWorkRoot(root: File) {
        runCatching {
            if (root.exists()) root.deleteRecursively()
            root.mkdirs()
        }.onFailure { e ->
            Log.e(TAG, "clearWorkRoot failed", e)
        }
    }

    // ==================== Socket предпроверка (БЕЗ ИЗМЕНЕНИЙ) ====================

    private suspend fun filterWithSocket(
        proxies: List<String>,
        onProgress: (processed: Int, total: Int) -> Unit
    ): List<String> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<String>()
            val total = proxies.size
            var processed = 0

            val batches = proxies.chunked(50)

            for (batch in batches) {
                if (isCancelled) break

                val deferredResults = batch.map { proxyUrl ->
                    async {
                        val proxyInfo = parseProxyUrl(proxyUrl)
                        if (proxyInfo != null) {
                            val server = proxyInfo.server
                            val port = proxyInfo.port.toIntOrNull() ?: 443
                            if (quickSocketCheck(server, port)) {
                                proxyUrl
                            } else null
                        } else null
                    }
                }

                val batchResults = deferredResults.awaitAll()
                results.addAll(batchResults.filterNotNull())
                processed += batch.size

                withContext(Dispatchers.Main) {
                    onProgress(processed, total)
                }
            }

            results
        }
    }

    private suspend fun quickSocketCheck(server: String, port: Int): Boolean {
        return withContext(Dispatchers.IO) {
            var socket: Socket? = null
            try {
                socket = Socket()
                socket.connect(java.net.InetSocketAddress(server, port), SOCKET_TIMEOUT)
                true
            } catch (_: Exception) {
                false
            } finally {
                try { socket?.close() } catch (_: Exception) { }
            }
        }
    }

    // ==================== TDLib проверка (БЕЗ ИЗМЕНЕНИЙ) ====================

    private suspend fun checkProxiesWithTDLib(
        proxies: List<String>,
        batchSize: Int = BATCH_SIZE,
        onProgress: (processed: Int, total: Int, working: Int) -> Unit
    ): List<ProxyWithPing> {
        return withContext(Dispatchers.IO) {
            val results = mutableListOf<ProxyWithPing>()
            val total = proxies.size
            var processed = 0
            var working = 0

            val batches = proxies.chunked(batchSize)

            for (batch in batches) {
                if (isCancelled) break

                val deferredResults = batch.map { proxyUrl ->
                    async {
                        checkProxyWithTDLib(proxyUrl)
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

            results
        }
    }

    private suspend fun checkProxyWithTDLib(proxyUrl: String): ProxyWithPing? {
        return withContext(Dispatchers.IO) {
            try {
                val proxyInfo = parseProxyUrl(proxyUrl) ?: return@withContext null

                // Только MTProto прокси
                if (!proxyUrl.startsWith("tg://proxy?")) {
                    return@withContext null
                }

                // ФИЛЬТР: Проверяем секрет - должен начинаться с "ee"
                val secret = proxyInfo.secret ?: ""
                if (!secret.startsWith("ee", ignoreCase = true)) {
                    return@withContext null
                }

                val server = proxyInfo.server
                val port = proxyInfo.port.toIntOrNull() ?: 443

                val result = testProxyWithPing(
                    server = server,
                    port = port,
                    secret = secret,
                    timeoutSeconds = PING_TIMEOUT
                )

                if (result.isSuccess) {
                    val pingMs = result.getOrThrow()
                    if (pingMs > 0 && pingMs < 10000) {
                        return@withContext ProxyWithPing(proxyUrl, pingMs.toInt())
                    }
                }
                null

            } catch (e: Exception) {
                null
            }
        }
    }

    private suspend fun testProxyWithPing(
        server: String,
        port: Int,
        secret: String = "",
        timeoutSeconds: Double = PING_TIMEOUT,
    ): Result<Long> {
        val pool = clientPool
        if (pool.isEmpty()) {
            return Result.failure(IllegalStateException("TDLib client pool is not prepared"))
        }
        val client = pool[Math.floorMod(nextClientIndex.getAndIncrement(), pool.size)]
        val timeoutMs = (timeoutSeconds * 1_000).toLong()

        val addResponse = try {
            withTimeout(timeoutMs) {
                val result = CompletableDeferred<TdApi.Object>()
                client.send(
                    TdApi.AddProxy(server, port, false, TdApi.ProxyTypeMtproto(secret))
                ) { response ->
                    result.complete(response)
                }
                result.await()
            }
        } catch (error: TimeoutCancellationException) {
            return Result.failure(error)
        }

        val proxyId = when (addResponse) {
            is TdApi.Proxy -> addResponse.id
            is TdApi.Error -> return Result.failure(
                IllegalStateException("addProxy failed ${addResponse.code}: ${addResponse.message}")
            )
            else -> return Result.failure(
                IllegalStateException("Unexpected TDLib response: ${addResponse.javaClass.simpleName}")
            )
        }

        return try {
            val pingResult = withTimeout(timeoutMs) {
                val result = CompletableDeferred<TdApi.Object>()
                client.send(TdApi.PingProxy(proxyId)) { response ->
                    result.complete(response)
                }
                result.await()
            }

            when (pingResult) {
                is TdApi.Seconds -> {
                    val pingMs = (pingResult.seconds * 1_000).toLong().coerceAtLeast(1L)
                    Result.success(pingMs)
                }
                is TdApi.Error -> Result.failure(
                    IllegalStateException("pingProxy failed ${pingResult.code}: ${pingResult.message}")
                )
                else -> Result.failure(
                    IllegalStateException("Unexpected TDLib response: ${pingResult.javaClass.simpleName}")
                )
            }
        } catch (e: TimeoutCancellationException) {
            Result.failure(e)
        } finally {
            client.send(TdApi.RemoveProxy(proxyId)) { }
        }
    }

    // ==================== Вспомогательные методы (БЕЗ ИЗМЕНЕНИЙ) ====================

    private fun parseProxyUrl(url: String): ProxyInfoExt? {
        return try {
            val cleanUrl = when {
                url.startsWith("tg://proxy?") -> url.substring("tg://proxy?".length)
                url.startsWith("tg://socks?") -> url.substring("tg://socks?".length)
                else -> return null
            }

            val params = cleanUrl.split("&")
            var server = ""
            var port = ""
            var secret: String? = null

            for (param in params) {
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) {
                    when (parts[0]) {
                        "server" -> server = parts[1]
                        "port" -> port = parts[1]
                        "secret" -> secret = parts[1]
                    }
                }
            }

            if (server.isNotEmpty() && port.isNotEmpty()) {
                ProxyInfoExt(server, port, secret)
            } else {
                null
            }
        } catch (_: Exception) {
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

    private fun showResults(proxies: List<ProxyWithPing>, sourceName: String) {
        if (proxies.isNotEmpty()) {
            // СОРТИРОВКА ПО ПИНГУ (от меньшего к большему)
            val sortedProxies = proxies.sortedBy { it.pingMs }

            val intent = Intent(this, ProxyListActivity::class.java)
            intent.putExtra("proxies_list", ArrayList(sortedProxies))
            intent.putExtra("source_name", "TDLib: $sourceName")
            startActivity(intent)
            finish()
        } else {
            showError("Нет рабочих MTProto прокси")
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
        scope.launch { finishSearch() }
    }

    data class ProxyInfoExt(
        val server: String,
        val port: String,
        val secret: String?
    )
}