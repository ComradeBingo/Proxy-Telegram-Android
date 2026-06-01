package com.example.tgproxyparser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView

class ProxyAdapter(
    private val context: Context,
    private val proxies: List<ProxyWithPing>
) : RecyclerView.Adapter<ProxyAdapter.ProxyViewHolder>() {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ProxyViewHolder {
        val view = LayoutInflater.from(context).inflate(R.layout.item_proxy, parent, false)
        return ProxyViewHolder(view)
    }

    override fun onBindViewHolder(holder: ProxyViewHolder, position: Int) {
        val proxy = proxies[position]
        holder.bind(proxy)
    }

    override fun getItemCount(): Int = proxies.size

    inner class ProxyViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val cardView: MaterialCardView = itemView.findViewById(R.id.cardProxy)
        private val tvHost: TextView = itemView.findViewById(R.id.tvHost)
        private val tvPing: TextView = itemView.findViewById(R.id.tvPing)
        private val btnCopy: MaterialButton = itemView.findViewById(R.id.btnCopy)
        private val btnConnect: MaterialButton = itemView.findViewById(R.id.btnConnect)

        fun bind(proxy: ProxyWithPing) {
            val proxyInfo = parseProxyUrl(proxy.url)

            if (proxyInfo != null) {
                tvHost.text = "${proxyInfo.server}:${proxyInfo.port}"
                tvPing.text = "${proxy.pingMs} ms"

                // НЕ удаляем иконки - они заданы в XML

                // Цвет пинга
                when {
                    proxy.pingMs < 100 -> tvPing.setTextColor(ContextCompat.getColor(context, android.R.color.holo_green_dark))
                    proxy.pingMs < 300 -> tvPing.setTextColor(ContextCompat.getColor(context, android.R.color.holo_orange_dark))
                    else -> tvPing.setTextColor(ContextCompat.getColor(context, android.R.color.holo_red_dark))
                }

                btnCopy.setOnClickListener {
                    copyToClipboard(proxy.url)
                }

                btnConnect.setOnClickListener {
                    connectToTelegram(proxy.url)
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
            } catch (e: Exception) {
                null
            }
        }

        private fun copyToClipboard(text: String) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("Proxy", text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(context, "Прокси скопирован", Toast.LENGTH_SHORT).show()
        }

        private fun connectToTelegram(proxyUrl: String) {
            try {
                val intent = Intent(Intent.ACTION_VIEW, Uri.parse(proxyUrl))
                context.startActivity(intent)
            } catch (e: Exception) {
                Toast.makeText(context, "Telegram не установлен", Toast.LENGTH_LONG).show()
            }
        }
    }
}