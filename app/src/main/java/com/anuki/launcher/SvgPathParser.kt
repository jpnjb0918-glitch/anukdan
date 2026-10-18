package com.anuki.launcher

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan

/** Receives absolute drawing commands (android.graphics.Path in the app, a text writer in tests). */
interface PathSink {
    fun moveTo(x: Float, y: Float)
    fun lineTo(x: Float, y: Float)
    fun cubicTo(x1: Float, y1: Float, x2: Float, y2: Float, x: Float, y: Float)
    fun quadTo(x1: Float, y1: Float, x: Float, y: Float)
    fun close()
}

/**
 * Parser for SVG path data (all commands, absolute and relative, arcs turned into cubic curves).
 * Plain Kotlin with no Android classes so it can be tested on a desktop JVM.
 */
object SvgPathParser {

    fun parse(d: String, sink: PathSink) {
        val r = Reader(d)
        var cx = 0f; var cy = 0f      // current point
        var sx = 0f; var sy = 0f      // start of the current subpath
        var qx = 0f; var qy = 0f      // last control point (for S / T)
        var prev = ' '
        while (true) {
            r.skip()
            if (r.end()) break
            val cmd = r.peek()
            if (!cmd.isLetter()) break // malformed: numbers without a command
            r.next()
            val rel = cmd.isLowerCase()
            when (cmd.lowercaseChar()) {
                'z' -> {
                    sink.close(); cx = sx; cy = sy
                    prev = 'z'
                    continue
                }
                'm' -> {
                    var first = true
                    do {
                        var x = r.num(); var y = r.num()
                        if (rel) { x += cx; y += cy }
                        if (first) { sink.moveTo(x, y); sx = x; sy = y; first = false } else sink.lineTo(x, y)
                        cx = x; cy = y
                    } while (r.hasNum())
                    prev = 'l'
                }
                'l' -> {
                    do {
                        var x = r.num(); var y = r.num()
                        if (rel) { x += cx; y += cy }
                        sink.lineTo(x, y); cx = x; cy = y
                    } while (r.hasNum())
                    prev = 'l'
                }
                'h' -> {
                    do {
                        var x = r.num(); if (rel) x += cx
                        sink.lineTo(x, cy); cx = x
                    } while (r.hasNum())
                    prev = 'l'
                }
                'v' -> {
                    do {
                        var y = r.num(); if (rel) y += cy
                        sink.lineTo(cx, y); cy = y
                    } while (r.hasNum())
                    prev = 'l'
                }
                'c' -> {
                    do {
                        var x1 = r.num(); var y1 = r.num(); var x2 = r.num(); var y2 = r.num(); var x = r.num(); var y = r.num()
                        if (rel) { x1 += cx; y1 += cy; x2 += cx; y2 += cy; x += cx; y += cy }
                        sink.cubicTo(x1, y1, x2, y2, x, y)
                        qx = x2; qy = y2; cx = x; cy = y
                        prev = 'c'
                    } while (r.hasNum())
                }
                's' -> {
                    do {
                        val x1 = if (prev == 'c') 2 * cx - qx else cx
                        val y1 = if (prev == 'c') 2 * cy - qy else cy
                        var x2 = r.num(); var y2 = r.num(); var x = r.num(); var y = r.num()
                        if (rel) { x2 += cx; y2 += cy; x += cx; y += cy }
                        sink.cubicTo(x1, y1, x2, y2, x, y)
                        qx = x2; qy = y2; cx = x; cy = y
                        prev = 'c'
                    } while (r.hasNum())
                }
                'q' -> {
                    do {
                        var x1 = r.num(); var y1 = r.num(); var x = r.num(); var y = r.num()
                        if (rel) { x1 += cx; y1 += cy; x += cx; y += cy }
                        sink.quadTo(x1, y1, x, y)
                        qx = x1; qy = y1; cx = x; cy = y
                        prev = 'q'
                    } while (r.hasNum())
                }
                't' -> {
                    do {
                        val x1 = if (prev == 'q') 2 * cx - qx else cx
                        val y1 = if (prev == 'q') 2 * cy - qy else cy
                        var x = r.num(); var y = r.num()
                        if (rel) { x += cx; y += cy }
                        sink.quadTo(x1, y1, x, y)
                        qx = x1; qy = y1; cx = x; cy = y
                        prev = 'q'
                    } while (r.hasNum())
                }
                'a' -> {
                    do {
                        val rx = r.num(); val ry = r.num(); val rot = r.num()
                        val large = r.flag(); val sweep = r.flag()
                        var x = r.num(); var y = r.num()
                        if (rel) { x += cx; y += cy }
                        arc(sink, cx, cy, rx, ry, rot, large, sweep, x, y)
                        cx = x; cy = y
                    } while (r.hasNum())
                    prev = 'a'
                }
                else -> return // unknown command: stop rather than draw garbage
            }
        }
    }

    /** SVG elliptical arc (endpoint form) as up-to-90-degree cubic pieces, per SVG 1.1 appendix F.6. */
    private fun arc(
        sink: PathSink, x0: Float, y0: Float, rxIn: Float, ryIn: Float, angle: Float,
        large: Boolean, sweep: Boolean, x: Float, y: Float,
    ) {
        if (x0 == x && y0 == y) return
        var rx = abs(rxIn.toDouble()); var ry = abs(ryIn.toDouble())
        if (rx == 0.0 || ry == 0.0) { sink.lineTo(x, y); return }
        val phi = Math.toRadians(angle.toDouble())
        val cosP = cos(phi); val sinP = sin(phi)
        val dx2 = (x0 - x) / 2.0; val dy2 = (y0 - y) / 2.0
        val x1p = cosP * dx2 + sinP * dy2
        val y1p = -sinP * dx2 + cosP * dy2
        val lambda = (x1p * x1p) / (rx * rx) + (y1p * y1p) / (ry * ry)
        if (lambda > 1) { val s = sqrt(lambda); rx *= s; ry *= s }
        val num = rx * rx * ry * ry - rx * rx * y1p * y1p - ry * ry * x1p * x1p
        val den = rx * rx * y1p * y1p + ry * ry * x1p * x1p
        var coef = if (den == 0.0) 0.0 else sqrt(max(0.0, num / den))
        if (large == sweep) coef = -coef
        val cxp = coef * (rx * y1p / ry)
        val cyp = coef * (-ry * x1p / rx)
        val ccx = cosP * cxp - sinP * cyp + (x0 + x) / 2.0
        val ccy = sinP * cxp + cosP * cyp + (y0 + y) / 2.0
        val ux = (x1p - cxp) / rx; val uy = (y1p - cyp) / ry
        val vx = (-x1p - cxp) / rx; val vy = (-y1p - cyp) / ry
        val theta1 = atan2(uy, ux)
        var dTheta = atan2(ux * vy - uy * vx, ux * vx + uy * vy)
        if (!sweep && dTheta > 0) dTheta -= 2 * PI else if (sweep && dTheta < 0) dTheta += 2 * PI
        val segs = ceil(abs(dTheta) / (PI / 2) - 1e-9).toInt().coerceAtLeast(1)
        val delta = dTheta / segs
        val t = 4.0 / 3.0 * tan(delta / 4)
        fun mx(a: Double, b: Double) = (ccx + rx * a * cosP - ry * b * sinP).toFloat()
        fun my(a: Double, b: Double) = (ccy + rx * a * sinP + ry * b * cosP).toFloat()
        var th = theta1
        for (k in 0 until segs) {
            val c1 = cos(th); val s1 = sin(th)
            val th2 = th + delta
            val c2 = cos(th2); val s2 = sin(th2)
            val ax = c1 - t * s1; val ay = s1 + t * c1
            val bx = c2 + t * s2; val by = s2 - t * c2
            if (k == segs - 1) sink.cubicTo(mx(ax, ay), my(ax, ay), mx(bx, by), my(bx, by), x, y)
            else sink.cubicTo(mx(ax, ay), my(ax, ay), mx(bx, by), my(bx, by), mx(c2, s2), my(c2, s2))
            th = th2
        }
    }

    private class Reader(private val s: String) {
        private var i = 0
        fun end() = i >= s.length
        fun peek() = s[i]
        fun next() { i++ }
        fun skip() {
            while (i < s.length) {
                val c = s[i]
                if (c == ' ' || c == ',' || c == '\n' || c == '\t' || c == '\r') i++ else break
            }
        }
        fun hasNum(): Boolean {
            skip()
            if (i >= s.length) return false
            val c = s[i]
            return c == '-' || c == '+' || c == '.' || (c in '0'..'9')
        }
        fun flag(): Boolean {
            skip()
            val c = s[i]; i++
            return c == '1'
        }
        fun num(): Float {
            skip()
            val start = i
            if (i < s.length && (s[i] == '-' || s[i] == '+')) i++
            var dot = false
            while (i < s.length) {
                val c = s[i]
                if (c in '0'..'9') i++
                else if (c == '.' && !dot) { dot = true; i++ }
                else if ((c == 'e' || c == 'E') && i + 1 < s.length && (s[i + 1] in '0'..'9' || s[i + 1] == '-' || s[i + 1] == '+')) {
                    i += 2
                    while (i < s.length && s[i] in '0'..'9') i++
                    break
                } else break
            }
            return s.substring(start, i).toFloatOrNull() ?: throw IllegalArgumentException("bad number at $start")
        }
    }
}
