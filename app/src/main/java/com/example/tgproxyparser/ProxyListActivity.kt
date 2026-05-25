package com.example.tgproxyparser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup

class ProxyListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnBack: Button
    private lateinit var tvTitle: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_list)

        recyclerView = findViewById(R.id.recyclerView)
        btnBack = findViewById(R.id.btn_back)
        tvTitle = findViewById(R.id.tv_title)

        // Получаем данные из Intent с безопасной обработкой
        val proxiesList = getProxiesListFromIntent()
        val sourceName = intent.getStringExtra("source_name") ?: "Прокси"

        tvTitle.text = sourceName

        // Настройка адаптера
        val adapter = ProxyListAdapter(this, proxiesList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        // Кнопка назад
        btnBack.setOnClickListener {
            finish()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun getProxiesListFromIntent(): List<ProxyWithPing> {
        return if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            // Android 13+ (API 33)
            intent.getSerializableExtra("proxies_list", ArrayList::class.java) as? ArrayList<ProxyWithPing>
        } else {
            // Старые версии Android
            @Suppress("DEPRECATION")
            intent.getSerializableExtra("proxies_list") as? ArrayList<ProxyWithPing>
        }?.toList() ?: emptyList()
    }

    data class ProxyWithPing(
        val url: String,
        val pingMs: Int
    ) : java.io.Serializable
}

class ProxyListAdapter(
    private val context: Context,
    private val proxies: List<ProxyListActivity.ProxyWithPing>
) : RecyclerView.Adapter<ProxyListAdapter.ViewHolder>() {

    class ViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val tvServer: TextView = itemView.findViewById(R.id.tv_server)
        val tvPing: TextView = itemView.findViewById(R.id.tv_ping)
        val btnAdd: Button = itemView.findViewById(R.id.btn_add)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_proxy, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val proxy = proxies[position]
        val proxyUrl = proxy.url
        val pingMs = proxy.pingMs

        val proxyInfo = parseProxyUrl(proxyUrl)

        if (proxyInfo != null) {
            holder.tvServer.text = "${proxyInfo.server}:${proxyInfo.port}"
        } else {
            holder.tvServer.text = proxyUrl.take(50)
        }

        // Отображение пинга с цветом
        holder.tvPing.text = when {
            pingMs < 100 -> "${pingMs}мс"
            pingMs < 300 -> "${pingMs}мс"
            pingMs < 600 -> "${pingMs}мс"
            else -> "⚠️ ${pingMs}мс"
        }

        holder.tvPing.setTextColor(when {
            pingMs < 100 -> Color.parseColor("#4CAF50")
            pingMs < 300 -> Color.parseColor("#FF9800")
            else -> Color.parseColor("#F44336")
        })

        holder.btnAdd.setOnClickListener {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proxyUrl))
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Telegram не установлен", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun getItemCount(): Int = proxies.size

    private fun parseProxyUrl(url: String): MainActivity.ProxyInfo? {
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
                MainActivity.ProxyInfo(server, port)
            } else {
                null
            }
        } catch (e: Exception) {
            null
        }
    }
}