package com.example.tgproxyparser

import java.io.Serializable

data class ProxyWithPing(
    val url: String,
    val pingMs: Int
) : Serializable

data class ProxyInfo(
    val server: String,
    val port: String
)