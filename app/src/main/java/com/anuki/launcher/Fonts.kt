package com.anuki.launcher

import android.content.Context
import android.graphics.Typeface
import android.widget.TextView

/**
 * Launcher font (Prefs.font): "" = the phone's own font, otherwise one of the free Korean fonts in assets/fonts
 * (all SIL Open Font License, see assets/fonts/NOTICE.txt). Used for app names, folders, the drawer, menus and the 아늑단 widgets.
 */
object Fonts {
    class Font(val key: String, val label: String, val file: String?, val note: String)

    val ALL = listOf(
        Font("", "기본 (휴대폰 글꼴)", null, "휴대폰 설정의 글꼴을 따라가요"),
        Font("jua", "주아", "jua.ttf", "둥글고 통통한 글씨 · 우아한형제들"),
        Font("gaegu", "개구", "gaegu.ttf", "장난스러운 손글씨 · 직지소프트"),
        Font("gamjaflower", "감자꽃", "gamjaflower.ttf", "보들보들한 손글씨 · 윤디자인"),
        Font("himelody", "하이멜로디", "himelody.ttf", "동글동글한 손글씨 · 윤디자인"),
        Font("nanumpen", "나눔손글씨 펜", "nanumpen.ttf", "가는 펜 손글씨 · 네이버"),
        Font("gowundodum", "고운돋움", "gowundodum.ttf", "부드럽고 읽기 쉬운 글씨 · 류양희"),
    )

    private val cache = HashMap<String, Typeface?>()

    fun label(key: String) = ALL.firstOrNull { it.key == key }?.label ?: ALL[0].label

    /** The typeface for [key], or null for the phone's own font (or a missing file). */
    fun typeface(ctx: Context, key: String): Typeface? {
        if (key.isEmpty()) return null
        synchronized(cache) {
            if (cache.containsKey(key)) return cache[key]
            val f = ALL.firstOrNull { it.key == key }?.file
            val tf = try { f?.let { Typeface.createFromAsset(ctx.assets, "fonts/$it") } } catch (e: Exception) {
                LauncherApp.dlog("font $key failed: $e"); null
            }
            cache[key] = tf
            return tf
        }
    }

    /** The chosen launcher font, or null for the phone's own font. */
    fun current(): Typeface? = LauncherApp.instance.let { typeface(it, it.prefs.font) }

    /** Chosen font (or the phone's default), bold when [bold] and the phone's font is used. */
    fun regular(bold: Boolean = false): Typeface = current() ?: if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT

    fun apply(tv: TextView, bold: Boolean = false) {
        val tf = current()
        tv.typeface = if (tf == null) (if (bold) Typeface.DEFAULT_BOLD else Typeface.DEFAULT) else tf
    }
}
