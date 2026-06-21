package com.example.tgproxyparser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton

class ProxyListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var toolbar: MaterialToolbar
    private lateinit var fabCopyTop10: ExtendedFloatingActionButton
    private var proxiesList: List<ProxyWithPing> = emptyList()
    private var sourceName: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_list)

        toolbar = findViewById(R.id.toolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        sourceName = intent.getStringExtra("source_name") ?: "Прокси"
        supportActionBar?.title = sourceName

        // Исправлено: получение списка с поддержкой новых версий Android
        proxiesList = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            intent.getSerializableExtra("proxies_list", ArrayList::class.java) as? List<ProxyWithPing> ?: emptyList()
        } else {
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("proxies_list") as? ArrayList<ProxyWithPing> ?: emptyList()
        }

        recyclerView = findViewById(R.id.recyclerView)
        recyclerView.layoutManager = LinearLayoutManager(this)

        val adapter = ProxyAdapter(this, proxiesList)
        recyclerView.adapter = adapter

        fabCopyTop10 = findViewById(R.id.fabCopyTop10)
        fabCopyTop10.setOnClickListener {
            copyTop10Proxies()
        }

        setupToolbarMenu()

        // Исправлено: использование OnBackPressedCallback вместо onBackPressed()
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })
    }

    private fun setupToolbarMenu() {
        toolbar.inflateMenu(R.menu.proxy_list_menu)
        toolbar.setOnMenuItemClickListener { menuItem ->
            when (menuItem.itemId) {
                R.id.action_about -> {
                    showAboutDialog()
                    true
                }
                R.id.action_copy_all -> {
                    copyAllProxies()
                    true
                }
                else -> false
            }
        }
    }

    private fun copyTop10Proxies() {
        val top10 = proxiesList.take(10)
        if (top10.isNotEmpty()) {
            val formattedProxies = formatProxiesWithNumbers(top10)
            val messageWithLinks = "$formattedProxies\n\n📱 Скачать приложение:\nAndroid: https://github.com/ComradeBingo/Proxy-Telegram-Android\nWindows: https://github.com/ComradeBingo/Proxy-telegram-windows"
            copyToClipboard(messageWithLinks)
            Toast.makeText(this, "Скопировано ${top10.size} прокси с номерами и ссылками", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Нет прокси для копирования", Toast.LENGTH_SHORT).show()
        }
    }

    private fun copyAllProxies() {
        if (proxiesList.isNotEmpty()) {
            val formattedProxies = formatProxiesWithNumbers(proxiesList)
            val messageWithLinks = "$formattedProxies\n\n📱 Скачать приложение:\nAndroid: https://github.com/ComradeBingo/Proxy-Telegram-Android\nWindows: https://github.com/ComradeBingo/Proxy-telegram-windows"
            copyToClipboard(messageWithLinks)
            Toast.makeText(this, "Скопировано ${proxiesList.size} прокси с номерами и ссылками", Toast.LENGTH_LONG).show()
        } else {
            Toast.makeText(this, "Нет прокси для копирования", Toast.LENGTH_SHORT).show()
        }
    }

    private fun formatProxiesWithNumbers(proxies: List<ProxyWithPing>): String {
        return proxies.mapIndexed { index, proxy ->
            "${index + 1}. ${proxy.url}"
        }.joinToString("\n")
    }

    private fun copyToClipboard(text: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Proxies", text)
        clipboard.setPrimaryClip(clip)
    }

    private fun showAboutDialog() {
        val message = """
            Proxy for Telegram v${BuildConfig.VERSION_NAME}
            
            Приложение для парсинга и проверки прокси для Telegram
            
            📱 GitHub репозитории:
            
            Android версия:
            https://github.com/ComradeBingo/Proxy-Telegram-Android
            
            Windows версия:
            https://github.com/ComradeBingo/Proxy-telegram-windows
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("О приложении")
            .setMessage(message)
            .setPositiveButton("Открыть Android") { _, _ ->
                openUrl("https://github.com/ComradeBingo/Proxy-Telegram-Android")
            }
            .setNeutralButton("Открыть Windows") { _, _ ->
                openUrl("https://github.com/ComradeBingo/Proxy-telegram-windows")
            }
            .setNegativeButton("Закрыть", null)
            .show()
    }

    private fun openUrl(url: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Не удалось открыть ссылку", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }
}