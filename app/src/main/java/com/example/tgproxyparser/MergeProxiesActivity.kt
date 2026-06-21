package com.example.tgproxyparser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.util.Log
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import java.io.File
import kotlinx.coroutines.*

class MergeProxiesActivity : AppCompatActivity() {

    private lateinit var tvStatus: TextView
    private lateinit var tvCount: TextView
    private lateinit var progressBar: LinearProgressIndicator
    private lateinit var btnCancel: MaterialButton

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val TAG = "MergeProxiesActivity"
    private var savedFilePath: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_merge_proxies)

        initViews()
        startMerging()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tvMergeStatus)
        tvCount = findViewById(R.id.tvMergeCount)
        progressBar = findViewById(R.id.mergeProgressBar)
        btnCancel = findViewById(R.id.btnCancelMerge)

        btnCancel.setOnClickListener {
            scope.cancel()
            finish()
        }
    }

    private fun startMerging() {
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

                Log.d(TAG, "Загружено прокси: ${allProxies.size}")

                if (allProxies.isEmpty()) {
                    withContext(Dispatchers.Main) {
                        showError("Не удалось загрузить прокси из источников")
                    }
                    return@launch
                }

                updateStatus("Удаление дубликатов...", 0, allProxies.size)

                val uniqueProxies = ProxyManager.deduplicateProxies(allProxies)

                Log.d(TAG, "Уникальных прокси: ${uniqueProxies.size}")

                updateStatus("Сохранение файла...", 0, uniqueProxies.size)

                val file = ProxyManager.saveProxiesToFile(uniqueProxies)

                withContext(Dispatchers.Main) {
                    if (file != null) {
                        savedFilePath = file.absolutePath
                        Log.d(TAG, "Файл сохранен: ${file.absolutePath}")
                        showSuccessDialog(uniqueProxies.size, file.absolutePath)
                    } else {
                        showError("Не удалось сохранить файл")
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Ошибка при скачивании прокси", e)
                withContext(Dispatchers.Main) {
                    showError("Ошибка: ${e.message}")
                }
            }
        }
    }

    private fun updateStatus(message: String, current: Int = 0, total: Int = 0, count: Int = 0) {
        runOnUiThread {
            tvStatus.text = message
            if (total > 0) {
                val percent = (current * 100) / total
                progressBar.progress = percent
                if (count > 0) {
                    tvCount.text = "Всего: $count | Уникальных: ${if (current == total) count else "..."}"
                } else {
                    tvCount.text = "Загружено: $current / $total"
                }
            } else {
                progressBar.progress = 0
                tvCount.text = ""
            }
        }
    }

    private fun showSuccessDialog(count: Int, filePath: String) {
        val fileName = filePath.substringAfterLast("/")

        MaterialAlertDialogBuilder(this)
            .setTitle("✅ Готово!")
            .setMessage("""
            Сохранено $count уникальных прокси
            
            Файл: $fileName
            Папка: Downloads
        """.trimIndent())
            .setPositiveButton("Закрыть") { _, _ ->
                finish()
            }
            .show()
    }


    private fun showFileLocationDialog() {
        val downloadsPath = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS).absolutePath

        MaterialAlertDialogBuilder(this)
            .setTitle("📁 Файл сохранен")
            .setMessage("""
                Файл сохранен в папке:
                $downloadsPath
                
                📄 Имя файла: ${savedFilePath.substringAfterLast("/")}
                
                Используйте любой файловый менеджер для доступа к файлу.
            """.trimIndent())
            .setPositiveButton("📋 Скопировать путь") { _, _ ->
                copyToClipboard(downloadsPath)
            }
            .setNegativeButton("Закрыть") { _, _ ->
                finish()
            }
            .show()
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val clip = android.content.ClipData.newPlainText("Path", text)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, "Путь скопирован в буфер обмена", Toast.LENGTH_SHORT).show()
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