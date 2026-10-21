package com.anuki.launcher

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode

/** Extra results in the drawer search: a calculator and the phone's contacts (opt-in). */
object SearchExtras {

    // ---------------------------------------------------------------- calculator
    /**
     * The answer when [q] is arithmetic ("12*3", "1,500 × 3", "(2+3)/4", "50%"), else null.
     * Numbers, + - * / × ÷ x % ^ and brackets only, and at least one operator.
     */
    fun calculate(q: String): String? {
        val s = q.replace(" ", "").replace(",", "").replace('×', '*').replace('÷', '/').replace('x', '*').replace('X', '*')
        if (s.isEmpty() || s.length > 60) return null
        if (!s.all { it.isDigit() || it in "+-*/%^()." }) return null
        if (!s.trimStart('-', '+').any { it in "+-*/%^" }) return null // "-5" alone is not a sum
        if (!s.any { it.isDigit() }) return null
        return try {
            val p = Parser(s)
            val v = p.expr()
            if (!p.done()) null else format(v)
        } catch (_: Exception) { null }
    }

    private fun format(v: BigDecimal): String {
        // whole numbers stay exact; fractions are cut to 12 significant digits (1/3 = 0.333333333333)
        val exact = v.stripTrailingZeros()
        val r = if (exact.scale() <= 0 && exact.precision() <= 30) exact else v.round(MathContext(12, RoundingMode.HALF_UP)).stripTrailingZeros()
        val plain = if (r.scale() < 0) r.setScale(0) else r
        val txt = plain.toPlainString()
        // thousands separators for the whole part
        val neg = txt.startsWith("-")
        val body = txt.removePrefix("-")
        val whole = body.substringBefore('.')
        val frac = body.substringAfter('.', "")
        val grouped = whole.reversed().chunked(3).joinToString(",").reversed()
        return (if (neg) "-" else "") + grouped + if (frac.isNotEmpty()) ".$frac" else ""
    }

    /** expr := term (('+'|'-') term)* ; term := factor (('*'|'/') factor)* ; factor := unary ('^' factor)? ; unary := '-' unary | atom '%'? */
    private class Parser(private val s: String) {
        private var i = 0
        private val mc = MathContext(20, RoundingMode.HALF_UP)
        fun done() = i == s.length
        private fun peek() = if (i < s.length) s[i] else '\u0000'

        fun expr(): BigDecimal {
            var v = term()
            while (peek() == '+' || peek() == '-') {
                val op = s[i++]
                v = if (op == '+') v.add(term(), mc) else v.subtract(term(), mc)
            }
            return v
        }

        private fun term(): BigDecimal {
            var v = factor()
            while (peek() == '*' || peek() == '/') {
                val op = s[i++]
                val r = factor()
                v = if (op == '*') v.multiply(r, mc) else {
                    if (r.signum() == 0) throw ArithmeticException("divide by zero")
                    v.divide(r, mc)
                }
            }
            return v
        }

        private fun factor(): BigDecimal {
            val base = unary()
            if (peek() == '^') {
                i++
                val e = factor()
                val n = e.toInt()
                if (BigDecimal(n).compareTo(e) != 0 || n !in -50..50) throw ArithmeticException("power")
                return if (n >= 0) base.pow(n, mc) else BigDecimal.ONE.divide(base.pow(-n, mc), mc)
            }
            return base
        }

        private fun unary(): BigDecimal {
            if (peek() == '-') { i++; return unary().negate() }
            if (peek() == '+') { i++; return unary() }
            var v = atom()
            while (peek() == '%') { i++; v = v.divide(BigDecimal(100), mc) }
            return v
        }

        private fun atom(): BigDecimal {
            if (peek() == '(') {
                i++
                val v = expr()
                if (peek() != ')') throw IllegalArgumentException("bracket")
                i++
                return v
            }
            val start = i
            while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
            if (start == i) throw IllegalArgumentException("number")
            return BigDecimal(s.substring(start, i))
        }
    }

    // ---------------------------------------------------------------- unit conversion
    /** One unit (not kotlin.Unit): [toBase] multiplies into the dimension's base unit (m, kg, ㎡, L, km/h); temperature is special. */
    private class Measure(val dim: String, val show: String, val toBase: Double, vararg val names: String)

    private val UNITS = listOf(
        Measure("len", "mm", 0.001, "mm", "밀리미터"),
        Measure("len", "cm", 0.01, "cm", "센티미터", "센티"),
        Measure("len", "m", 1.0, "m", "미터"),
        Measure("len", "km", 1000.0, "km", "킬로미터", "키로"),
        Measure("len", "in", 0.0254, "in", "inch", "inches", "인치"),
        Measure("len", "ft", 0.3048, "ft", "feet", "foot", "피트"),
        Measure("len", "yd", 0.9144, "yd", "yard", "yards", "야드"),
        Measure("len", "mi", 1609.344, "mi", "mile", "miles", "마일"),
        Measure("mass", "g", 0.001, "g", "그램"),
        Measure("mass", "kg", 1.0, "kg", "킬로그램", "킬로"),
        Measure("mass", "lb", 0.45359237, "lb", "lbs", "pound", "pounds", "파운드"),
        Measure("mass", "oz", 0.028349523125, "oz", "ounce", "온스"),
        Measure("mass", "근", 0.6, "근"),
        Measure("area", "㎡", 1.0, "m2", "㎡", "m²", "제곱미터"),
        Measure("area", "평", 400.0 / 121.0, "평"),
        Measure("area", "ft²", 0.09290304, "ft2", "ft²", "sqft"),
        Measure("vol", "mL", 0.001, "ml", "mL", "밀리리터"),
        Measure("vol", "L", 1.0, "l", "L", "리터"),
        Measure("vol", "gal", 3.785411784, "gal", "gallon", "갤런"),
        Measure("speed", "km/h", 1.0, "km/h", "kmh", "kph"),
        Measure("speed", "mph", 1.609344, "mph"),
        Measure("temp", "°C", 1.0, "°c", "℃", "c", "섭씨"),
        Measure("temp", "°F", 1.0, "°f", "℉", "f", "화씨"),
    )

    /** What each unit turns into first (the usual question), then the rest of its kind. */
    private val PREFER = mapOf(
        "mm" to listOf("in", "cm"), "cm" to listOf("in", "m"), "m" to listOf("ft", "cm"), "km" to listOf("mi", "m"),
        "in" to listOf("cm", "mm"), "ft" to listOf("m", "cm"), "yd" to listOf("m"), "mi" to listOf("km", "m"),
        "g" to listOf("oz", "kg"), "kg" to listOf("lb", "g"), "lb" to listOf("kg", "g"), "oz" to listOf("g"), "근" to listOf("g", "kg"),
        "㎡" to listOf("평", "ft²"), "평" to listOf("㎡"), "ft²" to listOf("㎡", "평"),
        "mL" to listOf("L"), "L" to listOf("gal", "mL"), "gal" to listOf("L"),
        "km/h" to listOf("mph"), "mph" to listOf("km/h"), "°C" to listOf("°F"), "°F" to listOf("°C"),
    )

    private fun unitNamed(s: String): Measure? {
        val t = s.trim()
        if (t.isEmpty()) return null
        // c / f / l alone only in their exact case-insensitive single-letter form; the rest ignore case
        return UNITS.firstOrNull { u -> u.names.any { it.equals(t, ignoreCase = true) } }
    }

    private val QTY = Regex("""^\s*(-?[0-9][0-9,]*(?:\.[0-9]+)?)\s*([^\s0-9][^\s]*?)\s*(?:(?:in|to|→|->|=|을|를|은|는|으로|로)\s*([^\s]+))?\s*$""", RegexOption.IGNORE_CASE)

    /**
     * "10km" → "10 km = 6.214 mi · 10,000 m", "25평" → "25평 = 82.64 ㎡", "30°C to °F" → "30 °C = 86 °F"; null when [q] is
     * not a number with a known unit. Only the whole query counts, so app names like "4K 비디오" don't match.
     */
    fun convert(q: String): String? {
        val m = QTY.matchEntire(q) ?: return null
        val n = m.groupValues[1].replace(",", "").toDoubleOrNull() ?: return null
        var unitText = m.groupValues[2]
        var target = m.groupValues[3]
        // "10kmmi" can't happen; but "30°c→°f" may glue the arrow to the unit
        for (sep in listOf("→", "->", "=")) if (unitText.contains(sep)) {
            target = unitText.substringAfter(sep).ifEmpty { target }; unitText = unitText.substringBefore(sep)
        }
        val from = unitNamed(unitText) ?: return null
        val kinds = UNITS.filter { it.dim == from.dim && it !== from }
        val want = unitNamed(target)?.takeIf { it.dim == from.dim && it !== from }
        val order = (listOfNotNull(want) + (PREFER[from.show] ?: emptyList()).mapNotNull { s -> kinds.firstOrNull { it.show == s } } + kinds)
            .distinct().take(if (want != null) 1 else 2)
        if (order.isEmpty()) return null
        val parts = order.map { to -> fmtNum(convertValue(n, from, to)) + sp(to.show) + to.show }
        return fmtNum(n) + sp(from.show) + from.show + " = " + parts.joinToString(" · ")
    }

    private fun sp(unit: String) = if (unit == "평" || unit == "근") "" else " "

    private fun convertValue(n: Double, from: Measure, to: Measure): Double {
        if (from.dim == "temp") return if (from.show == "°C") n * 9 / 5 + 32 else (n - 32) * 5 / 9
        return n * from.toBase / to.toBase
    }

    /** Whole numbers from 1,000 up, otherwise 4 significant digits (at most 3 decimals); thousands separators. */
    private fun fmtNum(v: Double): String {
        val bd = if (kotlin.math.abs(v) >= 1000) BigDecimal(v).setScale(0, RoundingMode.HALF_UP)
        else BigDecimal(v).round(MathContext(4, RoundingMode.HALF_UP)).let { if (it.scale() > 3) it.setScale(3, RoundingMode.HALF_UP) else it }
        return format(bd.stripTrailingZeros())
    }

    // ---------------------------------------------------------------- contacts
    class Contact(val name: String, val uri: Uri)

    @Volatile private var contacts: List<Contact> = emptyList()
    @Volatile private var loadedAt = 0L

    fun hasPermission(ctx: Context) = ctx.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    /** Reads the contact names again (in the background) when the list is older than a minute. */
    fun refreshContacts(ctx: Context, done: () -> Unit) {
        if (!LauncherApp.instance.prefs.searchContacts || !hasPermission(ctx)) { contacts = emptyList(); return }
        if (System.currentTimeMillis() - loadedAt < 60_000) return
        loadedAt = System.currentTimeMillis()
        Thread {
            val out = ArrayList<Contact>()
            try {
                ctx.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY),
                    null, null, ContactsContract.Contacts.DISPLAY_NAME_PRIMARY,
                )?.use { c ->
                    while (c.moveToNext() && out.size < 5000) {
                        val name = c.getString(2) ?: continue
                        out += Contact(name, ContactsContract.Contacts.getLookupUri(c.getLong(0), c.getString(1)))
                    }
                }
            } catch (e: Exception) { LauncherApp.dlog("contacts read failed: $e") }
            contacts = out
            LauncherApp.dlog("contacts for search: ${out.size}")
            android.os.Handler(android.os.Looper.getMainLooper()).post(done)
        }.start()
    }

    /** Up to [max] contacts whose name matches [q] (초성 works too). */
    fun findContacts(q: String, max: Int = 3): List<Contact> {
        if (q.isBlank()) return emptyList()
        return contacts.asSequence().filter { Hangul.matchesText(it.name, q) }.take(max).toList()
    }

    fun forgetContacts() { contacts = emptyList(); loadedAt = 0L }
}
