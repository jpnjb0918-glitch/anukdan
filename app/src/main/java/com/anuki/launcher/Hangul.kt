package com.anuki.launcher

/** Korean initial-consonant (초성) search: "ㅋㅋㅇ" finds "카카오톡". */
object Hangul {
    private const val CHO = "ㄱㄲㄴㄷㄸㄹㅁㅂㅃㅅㅆㅇㅈㅉㅊㅋㅌㅍㅎ"

    fun chosung(s: String): String = buildString {
        for (ch in s) {
            if (ch in '가'..'힣') append(CHO[(ch - '가') / 588]) else if (!ch.isWhitespace()) append(ch.lowercaseChar())
        }
    }

    private fun isChosungQuery(q: String) = q.isNotEmpty() && q.all { it in CHO || it == ' ' }

    /** Section order for the "가나다" drawer: ㄱ…ㅎ, then A…Z, then # for everything else. */
    val SECTIONS: List<String> = "ㄱㄴㄷㄹㅁㅂㅅㅇㅈㅊㅋㅌㅍㅎ".map { it.toString() } + ('A'..'Z').map { it.toString() } + "#"

    /** First-letter section of a label: a Hangul syllable gives its initial (ㄲ→ㄱ …), a Latin letter its capital. */
    fun section(label: String): String {
        val c = label.trim().firstOrNull() ?: return "#"
        val cho = when (c) {
            in '가'..'힣' -> CHO[(c - '가') / 588]
            in 'ㄱ'..'ㅎ' -> c
            else -> null
        }
        if (cho != null) {
            return when (cho) { 'ㄲ' -> "ㄱ"; 'ㄸ' -> "ㄷ"; 'ㅃ' -> "ㅂ"; 'ㅆ' -> "ㅅ"; 'ㅉ' -> "ㅈ"; else -> cho.toString() }
                .takeIf { it in SECTIONS } ?: "#"
        }
        val u = c.uppercaseChar()
        return if (u in 'A'..'Z') u.toString() else "#"
    }

    fun matches(app: AppEntry, q: String): Boolean {
        val query = q.trim().lowercase()
        if (query.isEmpty()) return true
        if (app.label.lowercase().contains(query)) return true
        if (isChosungQuery(query) && app.chosung.contains(query.replace(" ", ""))) return true
        return app.packageName.lowercase().contains(query)
    }
}
