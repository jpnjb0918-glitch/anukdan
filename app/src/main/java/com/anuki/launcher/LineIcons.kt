package com.anuki.launcher

import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.util.LruCache
import java.util.Calendar

/**
 * Built-in line icons adapted from Arcticons (https://github.com/Arcticons-Team/Arcticons) by Donnnno and
 * contributors, licensed CC BY-SA 4.0. Converted to compact path data by tools/linecons/convert.py and drawn
 * by the launcher on its cat-head plate. Data: assets/linecons (CC BY-SA 4.0, see NOTICE.txt there).
 */
object LineIcons {
    private const val DIR = "linecons"
    private const val CHUNK = 256
    private const val MAP_SHARDS = 64

    /** Stroke weight relative to Arcticons' 1-unit lines, so the drawing holds up next to the plate outline. */
    private const val WEIGHT = 1.7f

    /** Glyph box inside the 100-unit cat plate (centred on the face circle). */
    private const val BOX = 56f
    private const val FACE_X = 50f
    private const val FACE_Y = 57f

    private val FLAG = Regex("([SDOFE])([^SDOFE]*)")

    class Element(val path: Path, val stroke: Float, val dash: FloatArray?, val dashOffset: Float, val fill: Boolean)

    @Volatile private var names: Array<String>? = null
    private val chunks = LruCache<Int, Array<String>>(12)
    private val maps = LruCache<Int, HashMap<String, String>>(16)
    private val glyphs = LruCache<String, List<Element>>(300)

    fun names(ctx: Context): Array<String> {
        names?.let { return it }
        synchronized(this) {
            names?.let { return it }
            val t0 = System.currentTimeMillis()
            val list = try {
                ctx.assets.open("$DIR/names.txt").bufferedReader().useLines { s -> s.filter { it.isNotEmpty() }.toList() }
            } catch (_: Exception) { emptyList() }
            val arr = list.toTypedArray()
            names = arr
            LauncherApp.dlog("line icons: ${arr.size} names (${System.currentTimeMillis() - t0}ms)")
            return arr
        }
    }

    fun has(ctx: Context, name: String) = names(ctx).binarySearch(name) >= 0

    private fun chunk(ctx: Context, k: Int): Array<String> = synchronized(chunks) {
        chunks.get(k) ?: run {
            val rows = try {
                ctx.assets.open(String.format("%s/icons/%02d.txt", DIR, k)).bufferedReader().useLines { it.toList() }
            } catch (_: Exception) { emptyList() }
            rows.toTypedArray().also { chunks.put(k, it) }
        }
    }

    private fun data(ctx: Context, name: String): String? {
        val i = names(ctx).binarySearch(name)
        if (i < 0) return null
        return chunk(ctx, i / CHUNK).getOrNull(i % CHUNK)
    }

    private fun map(ctx: Context, pkg: String): HashMap<String, String> {
        val k = Math.floorMod(pkg.hashCode(), MAP_SHARDS)
        synchronized(maps) {
            maps.get(k)?.let { return it }
            val m = HashMap<String, String>()
            try {
                ctx.assets.open(String.format("%s/map/%02d.txt", DIR, k)).bufferedReader().useLines { lines ->
                    for (l in lines) {
                        val t = l.indexOf('\t')
                        if (t > 0) m[l.substring(0, t)] = l.substring(t + 1)
                    }
                }
            } catch (_: Exception) { }
            maps.put(k, m)
            return m
        }
    }

    /** Icon name for an app, or null. Date icons resolve to today's picture. */
    fun nameFor(ctx: Context, c: ComponentName): String? {
        val v = map(ctx, c.packageName)[c.packageName + "/" + c.className] ?: return null
        if (!v.startsWith("@")) return v
        val day = v.substring(1) + Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
        return if (has(ctx, day)) day else null
    }

    fun glyph(ctx: Context, name: String): List<Element>? {
        glyphs.get(name)?.let { return it }
        val row = data(ctx, name) ?: return null
        val out = ArrayList<Element>()
        for (el in row.split('|')) {
            val sp = el.indexOf(' ')
            if (sp < 0) continue
            val flags = el.substring(0, sp)
            val path = Path()
            try {
                SvgPathParser.parse(el.substring(sp + 1), object : PathSink {
                    override fun moveTo(x: Float, y: Float) = path.moveTo(x, y)
                    override fun lineTo(x: Float, y: Float) = path.lineTo(x, y)
                    override fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float) = path.cubicTo(x1, y1, x2, y2, x, y)
                    override fun quadTo(x1: Float, y1: Float, x: Float, y: Float) = path.quadTo(x1, y1, x, y)
                    override fun close() = path.close()
                })
            } catch (_: Exception) { continue }
            var stroke = 0f; var dash: FloatArray? = null; var offset = 0f; var fill = false
            FLAG.findAll(flags).forEach { m ->
                val v = m.groupValues[2]
                when (m.groupValues[1]) {
                    "S" -> stroke = v.toFloatOrNull() ?: 1f
                    "D" -> dash = v.split(',').mapNotNull { it.toFloatOrNull() }.toFloatArray().takeIf { it.size >= 2 && it.size % 2 == 0 }
                    "O" -> offset = v.toFloatOrNull() ?: 0f
                    "F" -> fill = true
                    "E" -> path.fillType = Path.FillType.EVEN_ODD
                }
            }
            out += Element(path, stroke, dash, offset, fill)
        }
        glyphs.put(name, out)
        return out
    }

    /** Draws the glyph in the 48-unit box [left, top, left + side, top + side]. */
    fun drawGlyph(c: Canvas, els: List<Element>, left: Float, top: Float, side: Float, color: Int) {
        c.save()
        c.translate(left, top)
        c.scale(side / 48f, side / 48f)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (e in els) {
            if (e.fill) {
                p.style = Paint.Style.FILL; p.pathEffect = null
                c.drawPath(e.path, p)
            }
            if (e.stroke > 0f) {
                p.style = Paint.Style.STROKE
                p.strokeWidth = e.stroke * WEIGHT
                p.pathEffect = e.dash?.let { DashPathEffect(it, e.dashOffset) }
                c.drawPath(e.path, p)
            }
        }
        c.restore()
    }

    /** The launcher's look: the line drawing on the white cat-head plate. */
    fun render(ctx: Context, name: String, size: Int): Bitmap? {
        val els = glyph(ctx, name) ?: return null
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        IconProvider.drawCatPlate(c, size)
        val s = size / 100f
        drawGlyph(c, els, (FACE_X - BOX / 2) * s, (FACE_Y - BOX / 2) * s, BOX * s, IconProvider.INK)
        return b
    }
}
