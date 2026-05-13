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
    private var proxies: List<String>
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
        val proxyUrl = proxies[position]

        val proxyInfo = parseProxyUrl(proxyUrl)

        if (proxyInfo != null) {
            holder.tvServer.text = "${proxyInfo.server}:${proxyInfo.port}"
        } else {
            holder.tvServer.text = proxyUrl
        }

        // Здесь пинг уже посчитан и отсортирован в MainActivity
        // Просто отображаем информацию - пинг уже не вычисляем заново
        holder.tvPing.text = "✅ Готов к использованию"
        holder.tvPing.setTextColor(Color.parseColor("#4CAF50"))

        holder.btnAdd.setOnClickListener {
            openTelegramWithProxy(proxyUrl)
        }
    }

    override fun getItemCount(): Int = proxies.size

    fun updateData(newProxies: List<String>) {
        proxies = newProxies
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
                "Telegram не установлен. Установите Telegram из Play Market.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

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
}