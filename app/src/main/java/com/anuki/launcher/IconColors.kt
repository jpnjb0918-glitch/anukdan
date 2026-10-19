package com.anuki.launcher

import android.graphics.Bitmap
import android.util.LruCache
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Icon colour: the 아늑단 icons are drawn in a cyan/teal family (#4BD5D6 and its lighter/darker shades).
 * Recolouring moves every pixel of that family to the chosen colour, keeping how light/dark each one is,
 * and leaves white, black, grey and the other accent colours (pink, gold, …) alone.
 *
 * 0 = 기본: no change (and the generated icons keep their mixed tile colours).
 */
object IconColors {
    const val DEFAULT = 0

    val PRESETS: List<Pair<Int, String>> = listOf(
        DEFAULT to "기본 (청록)",
        0xFFEC7474.toInt() to "핑크",
        0xFFF39C4A.toInt() to "주황",
        0xFFE4B01C.toInt() to "골드",
        0xFF58C46A.toInt() to "초록",
        0xFF5AA9E6.toInt() to "하늘",
        0xFF3B5F82.toInt() to "네이비",
        0xFF9B7BD4.toInt() to "보라",
        0xFF9A9A9A.toInt() to "회색",
    )

    fun name(color: Int): String = PRESETS.firstOrNull { it.first == color }?.second
        ?: String.format("직접 고른 색 #%06X", color and 0xFFFFFF)

    // the reference colour of the icon set: #4BD5D6
    private const val BASE_S = 0.648f
    private const val BASE_V = 0.839f

    /** Hue range (degrees) and minimum saturation that count as the cyan family. */
    private fun isFamily(h: Float, s: Float) = h in 160f..205f && s >= 0.12f

    /** One colour of the family mapped to [target]; other colours are returned unchanged. */
    fun map(argb: Int, target: Int): Int {
        if (target == DEFAULT) return argb
        val t = FloatArray(3); toHsv(target, t)
        return mapWith(argb, t)
    }

    private fun mapWith(argb: Int, t: FloatArray): Int {
        val a = argb ushr 24
        if (a == 0) return argb
        val p = FloatArray(3)
        toHsv(argb, p)
        if (!isFamily(p[0], p[1])) return argb
        val s = (p[1] * t[1] / BASE_S).coerceIn(0f, 1f)
        val v = (p[2] * t[2] / BASE_V).coerceIn(0f, 1f)
        return fromHsv(a, t[0], s, v)
    }

    private val cache = object : LruCache<String, Bitmap>(8 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** [src] with its cyan family moved to [target] (cached under [key]); the same bitmap when [target] is 기본. */
    fun recolor(src: Bitmap, target: Int, key: String): Bitmap {
        if (target == DEFAULT) return src
        val k = "$key#$target"
        cache.get(k)?.let { return it }
        val w = src.width; val h = src.height
        val px = IntArray(w * h)
        src.getPixels(px, 0, w, 0, 0, w, h)
        val t = FloatArray(3); toHsv(target, t)
        val p = FloatArray(3)
        for (i in px.indices) {
            val c = px[i]
            val a = c ushr 24
            if (a == 0) continue
            toHsv(c, p)
            if (!isFamily(p[0], p[1])) continue
            val s = (p[1] * t[1] / BASE_S).coerceIn(0f, 1f)
            val v = (p[2] * t[2] / BASE_V).coerceIn(0f, 1f)
            px[i] = fromHsv(a, t[0], s, v)
        }
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.setPixels(px, 0, w, 0, 0, w, h)
        cache.put(k, out)
        return out
    }

    fun clear() = cache.evictAll()

    // plain-Kotlin HSV (Color.colorToHSV goes through JNI per pixel and is much slower for whole bitmaps)
    fun toHsv(c: Int, out: FloatArray) {
        val r = ((c shr 16) and 0xFF) / 255f
        val g = ((c shr 8) and 0xFF) / 255f
        val b = (c and 0xFF) / 255f
        val mx = max(r, max(g, b)); val mn = min(r, min(g, b))
        val d = mx - mn
        var h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        if (h < 0f) h += 360f
        out[0] = h
        out[1] = if (mx == 0f) 0f else d / mx
        out[2] = mx
    }

    fun fromHsv(a: Int, h: Float, s: Float, v: Float): Int {
        val c = v * s
        val hh = (h % 360f) / 60f
        val x = c * (1f - abs(hh % 2f - 1f))
        val (r1, g1, b1) = when (hh.toInt()) {
            0 -> Triple(c, x, 0f)
            1 -> Triple(x, c, 0f)
            2 -> Triple(0f, c, x)
            3 -> Triple(0f, x, c)
            4 -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        val m = v - c
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (a shl 24) or (ch(r1) shl 16) or (ch(g1) shl 8) or ch(b1)
    }
}
