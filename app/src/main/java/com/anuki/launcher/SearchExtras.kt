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
