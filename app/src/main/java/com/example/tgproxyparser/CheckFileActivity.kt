package com.example.tgproxyparser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.google.android.material.button.MaterialButton
import com.google.android.material.progressindicator.LinearProgressIndicator
import kotlinx.coroutines.*

class CheckFileActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnCancel: MaterialButton

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var fileUri: Uri? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_check_file)

        // Получаем URI с поддержкой новых и старых версий Android
        fileUri = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            intent.getParcelableExtra("file_uri", Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra("file_uri")
        }

        initViews()
        startChecking()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvCheckStatus)
        tvCount = findViewById(R.id.tvCheckCount)
        progressBar = findViewById(R.id.checkProgressBar)
        btnCancel = findViewById(R.id.btnCancelCheck)

        btnCancel.setOnClickListener {
            scope.cancel()
            finish()
        }
    }

    private fun startChecking() {
        val uri = fileUri
        if (uri == null) {
            showError("Файл не выбран")
            return
        }

        scope.launch {
            updateStatus("Чтение файла...", 0, 0)

            val proxies = ProxyManager.loadProxiesFromFile(contentResolver, uri)

            if (proxies.isEmpty()) {
                withContext(Dispatchers.Main) {
                    showError("Файл пуст или содержит неверный формат")
                }
                return@launch
            }

            updateStatus("Проверка прокси...", 0, proxies.size)

            val checkedProxies = ProxyManager.checkProxiesPingParallel(
                proxies,
                batchSize = 50
            ) { processed, total, working ->
                updateStatus(
                    "Проверка прокси...",
                    processed,
                    total,
                    working
                )
            }

            withContext(Dispatchers.Main) {
                if (checkedProxies.isNotEmpty()) {
                    showResults(checkedProxies)
                } else {
                    showError("Нет доступных прокси")
                }
            }
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

    private fun showResults(proxies: List<ProxyWithPing>) {
        val intent = Intent(this, ProxyListActivity::class.java)
        intent.putExtra("proxies_list", ArrayList(proxies))
        intent.putExtra("source_name", "Из файла")
        startActivity(intent)
        finish()
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