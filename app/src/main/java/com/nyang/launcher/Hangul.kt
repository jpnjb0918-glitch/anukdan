package com.nyang.launcher

/** Korean initial-consonant (초성) search: "ㅋㅋㅇ" finds "카카오톡". */
object Hangul {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    fun chosung(s: String): String = buildString {
        for (ch in s) {
            if (ch in '가'..'힣') append(CHO[(ch - '가') / 588]) else if (!ch.isWhitespace()) append(ch.lowercaseChar())
        }
    }

    private fun isChosungQuery(q: String) = q.isNotEmpty() && q.all { it in CHO || it == ' ' }

    fun matches(app: AppEntry, q: String): Boolean {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return true
        if (app.label.lowercase().contains(query)) return true
        if (isChosungQuery(query) && app.chosung.contains(query.replace(" ", ""))) return true
        return app.packageName.lowercase().contains(query)
    }
}
