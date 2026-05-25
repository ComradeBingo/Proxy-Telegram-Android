package com.example.tgproxyparser

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.recyclerview.widget.RecyclerView

class ProxyAdapter(
    private val context: Context,
    private var proxiesWithPing: List<MainActivity.ProxyWithPing>
) : RecyclerView.Adapter<ProxyAdapter.ViewHolder>() {

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
        val proxy = proxiesWithPing[position]
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
            pingMs < 100 -> "⚡ ${pingMs}мс (Отлично)"
            pingMs < 300 -> "📡 ${pingMs}мс (Хорошо)"
            pingMs < 600 -> "🐢 ${pingMs}мс (Средне)"
            else -> "⚠️ ${pingMs}мс (Медленно)"
        }

        holder.tvPing.setTextColor(when {
            pingMs < 100 -> Color.parseColor("#4CAF50")  // Зелёный
            pingMs < 300 -> Color.parseColor("#FF9800")  // Оранжевый
            else -> Color.parseColor("#F44336")          // Красный
        })

        holder.btnAdd.setOnClickListener {
            openTelegramWithProxy(proxyUrl)
        }
    }

    override fun getItemCount(): Int = proxiesWithPing.size

    fun updateData(newProxiesWithPing: List<MainActivity.ProxyWithPing>) {
        proxiesWithPing = newProxiesWithPing
        notifyDataSetChanged()
    }

    private fun openTelegramWithProxy(proxyUrl: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proxyUrl))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(
                context,
                "❌ Telegram не установлен. Установите Telegram из Play Market.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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