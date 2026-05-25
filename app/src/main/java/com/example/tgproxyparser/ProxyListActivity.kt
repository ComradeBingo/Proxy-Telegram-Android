package com.example.tgproxyparser

import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

class ProxyListActivity : AppCompatActivity() {

    private lateinit var recyclerView: RecyclerView
    private lateinit var btnBack: Button
    private lateinit var tvTitle: TextView
    private lateinit var tvWorkingCount: TextView
    private lateinit var adapter: ProxyAdapter

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_proxy_list)

        recyclerView = findViewById(R.id.recyclerView)
        btnBack = findViewById(R.id.btn_back)
        tvTitle = findViewById(R.id.tv_title)
        tvWorkingCount = findViewById(R.id.tv_working_proxies_count)

        // Универсальный способ для всех версий Android (от API 23 до API 36)
        val serializableList: ArrayList<MainActivity.SerializableProxyWithPing>? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // Для Android 13+ (API 33+)
            @Suppress("UNCHECKED_CAST")
            intent.getSerializableExtra("proxies_list", ArrayList::class.java) as? ArrayList<MainActivity.SerializableProxyWithPing>
        } else {
            // Для Android 6-12 (API 23-32)
            @Suppress("DEPRECATION", "UNCHECKED_CAST")
            intent.getSerializableExtra("proxies_list") as? ArrayList<MainActivity.SerializableProxyWithPing>
        }

        val proxiesList = serializableList?.map {
            MainActivity.ProxyWithPing(it.url, it.pingMs)
        } ?: emptyList()

        val sourceName = intent.getStringExtra("source_name") ?: "Список прокси"

        tvTitle.text = sourceName
        tvWorkingCount.text = "✅ Найдено ${proxiesList.size} рабочих прокси"

        adapter = ProxyAdapter(this, proxiesList)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        btnBack.setOnClickListener {
            finish()
        }
    }
}