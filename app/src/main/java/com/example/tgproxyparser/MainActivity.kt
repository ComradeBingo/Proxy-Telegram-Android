package com.example.tgproxyparser

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.lifecycleScope
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.example.tgproxyparser.updater.UpdateChecker
import com.example.tgproxyparser.updater.GitHubRelease
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.Socket

class MainActivity : AppCompatActivity() {

    private lateinit var btnSurfboard: MaterialButton
    private lateinit var btnRussiaCard: MaterialCardView
    private lateinit var btnEuropeCard: MaterialCardView
    private lateinit var btnSupport: MaterialButton
    private lateinit var btnHelp: MaterialButton
    private lateinit var btnTheme: MaterialButton
    private lateinit var tvStatus: TextView
    private lateinit var tvVersion: TextView

    private val client = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private lateinit var updateChecker: UpdateChecker

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        updateChecker = UpdateChecker(this, client)

        initViews()
        setupClickListeners()
        setupVersion()

        // Проверка обновлений при запуске
        checkForUpdates()
    }

    private fun initViews() {
        btnSurfboard = findViewById(R.id.btn_surfboard)
        btnRussiaCard = findViewById(R.id.btn_russia_card)
        btnEuropeCard = findViewById(R.id.btn_europe_card)
        btnSupport = findViewById(R.id.btn_support)
        btnHelp = findViewById(R.id.btnHelp)
        btnTheme = findViewById(R.id.btnTheme)
        tvStatus = findViewById(R.id.statusText)
        tvVersion = findViewById(R.id.tvVersion)
    }

    private fun setupVersion() {
        try {
            val versionName = packageManager.getPackageInfo(packageName, 0).versionName
            tvVersion.text = "v$versionName"  // было "Версия: $versionName", стало "v$versionName"
        } catch (e: Exception) {
            tvVersion.text = "v1.0"
        }
    }

    private fun setupClickListeners() {
        btnRussiaCard.setOnClickListener {
            startLoadingActivity(
                "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_ru.txt",
                "Россия (Kort0881)",
                "tg://proxy?"
            )
        }

        btnEuropeCard.setOnClickListener {
            startLoadingActivity(
                "https://raw.githubusercontent.com/kort0881/telegram-proxy-collector/main/proxy_eu.txt",
                "Европа (Kort0881)",
                "tg://proxy?"
            )
        }

        btnSurfboard.setOnClickListener {
            startLoadingActivity(
                "https://raw.githubusercontent.com/Surfboardv2ray/TGProto/refs/heads/main/proxies-tested.txt",
                "SurfboardV2ray",
                "https://t.me/proxy?"
            )
        }

        btnSupport.setOnClickListener {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ComradeBingo"))
            startActivity(intent)
        }

        btnHelp.setOnClickListener {
            showHelpDialog()
        }

        btnTheme.setOnClickListener {
            showThemeDialog()
        }
    }

    private fun startLoadingActivity(url: String, name: String, prefix: String) {
        val intent = Intent(this, ProxyLoadingActivity::class.java)
        intent.putExtra("source_url", url)
        intent.putExtra("source_name", name)
        intent.putExtra("url_prefix", prefix)
        startActivity(intent)
    }

    private fun showThemeDialog() {
        val themes = arrayOf("Системная", "Светлая", "Темная")
        val sharedPref = getSharedPreferences("app_settings", MODE_PRIVATE)
        val currentTheme = sharedPref.getInt("theme", 0)

        MaterialAlertDialogBuilder(this)
            .setTitle("Выберите тему")
            .setSingleChoiceItems(themes, currentTheme) { _, which ->
                when (which) {
                    0 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
                    1 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
                    2 -> AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
                }
                sharedPref.edit().putInt("theme", which).apply()

                // Перезапускаем активность для применения темы
                val intent = intent
                finish()
                startActivity(intent)
            }
            .setNegativeButton("Отмена", null)
            .show()
    }

    private fun showHelpDialog() {
        MaterialAlertDialogBuilder(this)
            .setTitle("Справка")
            .setMessage("Приложение для парсинга прокси для Telegram\n\n" +
                    "Как работает:\n" +
                    "• Загружает список прокси из источников\n" +
                    "• Проверяет до 50 прокси одновременно\n" +
                    "• Показывает только работающие прокси\n\n" +
                    "Источники:\n" +
                    "• Kort0881 - прокси с закосом под сервисы России и Европы\n" +
                    "• SurfboardV2ray - большой список\n\n" +
                    "💡 Чем меньше пинг, тем быстрее прокси\n\n")
            .setPositiveButton("GitHub") { _, _ ->
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/ComradeBingo"))
                startActivity(intent)
            }
            .setNeutralButton("Закрыть", null)
            .setIcon(android.R.drawable.ic_dialog_info)
            .show()
    }

    private fun checkForUpdates() {
        lifecycleScope.launch {
            try {
                val versionName = packageManager.getPackageInfo(packageName, 0).versionName
                val release = updateChecker.checkForUpdate(versionName ?: "1.0")
                release?.let {
                    withContext(Dispatchers.Main) {
                        showUpdateDialog(it)
                    }
                }
            } catch (e: Exception) {
                // Игнорируем ошибки проверки обновлений
            }
        }
    }

    private fun showUpdateDialog(release: GitHubRelease) {
        val versionName = release.tagName.removePrefix("v")

        MaterialAlertDialogBuilder(this)
            .setTitle("Доступно обновление v$versionName")
            .setMessage(release.changelog.ifEmpty { "Доступна новая версия приложения" })
            .setPositiveButton("Перейти к релизу") { _, _ ->
                updateChecker.openReleasePage(release.htmlUrl)
            }
            .setNegativeButton("Позже", null)
            .setIcon(android.R.drawable.ic_dialog_info)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}