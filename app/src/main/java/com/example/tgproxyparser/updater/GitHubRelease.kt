package com.example.tgproxyparser.updater

data class GitHubRelease(
    val tagName: String,     // Например: "v1.3"
    val changelog: String,   // Описание релиза (патчноут)
    val apkUrl: String,       // Прямая ссылка на скачивание APK
    val htmlUrl: String      // Ссылка на страницу релиза на GitHub
)