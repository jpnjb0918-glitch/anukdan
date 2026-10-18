package com.anuki.launcher

import android.annotation.SuppressLint
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PaintFlagsDrawFilter
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.drawable.Drawable
import android.util.Xml
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.util.Calendar

class IconPackInfo(val packageName: String, val label: String)

/**
 * Third-party icon pack in the common ADW/Nova format:
 * appfilter.xml (res/xml, res/raw or assets) maps ComponentInfo{pkg/cls} -> drawable name,
 * optional iconback/iconmask/iconupon/scale build icons for apps the pack doesn't cover,
 * drawable.xml lists every icon for manual picking.
 */
class ExternalIconPack(ctx: Context, val packageName: String) {
    private val res: Resources = ctx.packageManager.getResourcesForApplication(packageName)
    private val map = HashMap<ComponentName, String>()
    private val calendars = HashMap<ComponentName, String>()
    private val backs = ArrayList<String>()
    private var mask: String? = null
    private var upon: String? = null
    private var scale = 1f
    private val ids = HashMap<String, Int>()

    init {
        val t0 = System.currentTimeMillis()
        parseAppFilter()
        LauncherApp.dlog("icon pack $packageName: items=${map.size} calendars=${calendars.size} backs=${backs.size} " +
            "mask=$mask upon=$upon scale=$scale (${System.currentTimeMillis() - t0}ms)")
    }

    val hasFallback: Boolean get() = backs.isNotEmpty() || mask != null || upon != null

    @SuppressLint("DiscouragedApi")
    private fun openXml(name: String): XmlPullParser? {
        try {
            val xmlId = res.getIdentifier(name, "xml", packageName)
            if (xmlId != 0) return res.getXml(xmlId)
            val rawId = res.getIdentifier(name, "raw", packageName)
            val stream = if (rawId != 0) res.openRawResource(rawId) else res.assets.open("$name.xml")
            val p = XmlPullParserFactory.newInstance().newPullParser()
            p.setInput(stream, Xml.Encoding.UTF_8.toString())
            return p
        } catch (_: Exception) {
            return null
        }
    }

    private fun attr(p: XmlPullParser, name: String): String? = p.getAttributeValue(null, name)

    private fun parseComponent(s: String?): ComponentName? {
        if (s == null || !s.startsWith("ComponentInfo{") || !s.endsWith("}")) return null
        // some packs have stray spaces inside the braces
        return ComponentName.unflattenFromString(s.substring(14, s.length - 1).trim())
    }

    private fun parseAppFilter() {
        val p = openXml("appfilter") ?: return
        try {
            while (p.next() != XmlPullParser.END_DOCUMENT) {
                if (p.eventType != XmlPullParser.START_TAG) continue
                when (p.name) {
                    "item" -> {
                        val c = parseComponent(attr(p, "component"))
                        val d = attr(p, "drawable")
                        if (c != null && !d.isNullOrEmpty() && c !in map) map[c] = d
                    }
                    "calendar" -> {
                        val c = parseComponent(attr(p, "component"))
                        val prefix = attr(p, "prefix")
                        if (c != null && !prefix.isNullOrEmpty()) calendars[c] = prefix
                    }
                    "iconback" -> for (i in 0 until p.attributeCount) {
                        if (p.getAttributeName(i).startsWith("img")) backs += p.getAttributeValue(i)
                    }
                    "iconmask" -> mask = if (p.attributeCount > 0) p.getAttributeValue(0) else null
                    "iconupon" -> upon = if (p.attributeCount > 0) p.getAttributeValue(0) else null
                    "scale" -> {
                        val f = attr(p, "factor") ?: if (p.attributeCount > 0) p.getAttributeValue(0) else null
                        f?.toFloatOrNull()?.let { scale = it }
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    @SuppressLint("DiscouragedApi")
    fun drawableId(name: String): Int = synchronized(ids) {
        ids.getOrPut(name) { res.getIdentifier(name, "drawable", packageName) }
    }

    fun drawable(name: String, density: Int): Drawable? {
        val id = drawableId(name)
        if (id == 0) return null
        return try { res.getDrawableForDensity(id, density, null) } catch (_: Exception) { null }
    }

    /** Icon the pack defines for this activity (calendar icons follow today's date). */
    fun iconFor(c: ComponentName, density: Int): Drawable? {
        calendars[c]?.let { prefix ->
            val name = prefix + Calendar.getInstance().get(Calendar.DAY_OF_MONTH)
            drawable(name, density)?.let {
                LauncherApp.dlog("calendar icon ${c.flattenToShortString()} -> $name")
                return it
            }
        }
        val name = map[c] ?: return null
        return drawable(name, density)
    }

    /** True when the pack has a date icon set for this activity. */
    fun hasCalendar(c: ComponentName) = c in calendars

    private var names: List<String>? = null

    /**
     * Every icon name in the pack: drawable.xml if present, otherwise everything appfilter points at.
     * Names are not checked against the pack's resources here (that took ~10 s for a 15,000-icon pack);
     * the picker checks the one the user taps.
     */
    fun allIconNames(): List<String> = synchronized(this) {
        names ?: loadIconNames().also { names = it }
    }

    private fun loadIconNames(): List<String> {
        val t0 = System.currentTimeMillis()
        val out = LinkedHashSet<String>()
        openXml("drawable")?.let { p ->
            try {
                while (p.next() != XmlPullParser.END_DOCUMENT) {
                    if (p.eventType == XmlPullParser.START_TAG && p.name == "item") {
                        attr(p, "drawable")?.let { if (it.isNotEmpty()) out += it }
                    }
                }
            } catch (_: Exception) {
            }
        }
        if (out.isEmpty()) out += map.values
        val list = out.toList()
        LauncherApp.dlog("icon pack $packageName: ${list.size} icons listed (${System.currentTimeMillis() - t0}ms)")
        return list
    }

    /** Pack-styled icon for an app the pack doesn't cover (classic iconback / iconmask / iconupon recipe). */
    fun compose(original: Bitmap, size: Int, seed: Int, density: Int): Bitmap? {
        if (!hasFallback) return null
        val out = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawFilter = PaintFlagsDrawFilter(0, Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        val s = (size * scale).toInt().coerceIn(1, size)
        val off = (size - s) / 2f
        c.drawBitmap(Bitmap.createScaledBitmap(original, s, s, true), off, off, null)
        mask?.let { m ->
            drawable(m, density)?.let { d ->
                val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
                c.drawBitmap(toBitmap(d, size), 0f, 0f, p)
            }
        }
        if (backs.isNotEmpty()) {
            val name = backs[Math.floorMod(seed, backs.size)]
            drawable(name, density)?.let { d ->
                val p = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OVER) }
                c.drawBitmap(toBitmap(d, size), 0f, 0f, p)
            }
        }
        upon?.let { u -> drawable(u, density)?.let { d -> c.drawBitmap(toBitmap(d, size), 0f, 0f, null) } }
        return out
    }

    companion object {
        fun toBitmap(d: Drawable, size: Int): Bitmap {
            val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            d.setBounds(0, 0, size, size)
            d.draw(Canvas(b))
            return b
        }
    }
}

object IconPacks {
    /** Intent actions icon packs declare so launchers can find them. */
    private val ACTIONS = listOf(
        "org.adw.launcher.THEMES",
        "com.novalauncher.THEME",
        "org.adw.ActivityStarter.THEMES",
        "com.gau.go.launcherex.theme",
        "com.anddoes.launcher.THEME",
    )
    private val loaded = HashMap<String, ExternalIconPack?>()

    fun installed(ctx: Context): List<IconPackInfo> {
        val pm = ctx.packageManager
        val pkgs = LinkedHashSet<String>()
        val intents = ACTIONS.map { Intent(it) } + Intent(Intent.ACTION_MAIN).addCategory("com.anddoes.launcher.THEME")
        for (i in intents) {
            try {
                for (ri in pm.queryIntentActivities(i, 0)) ri.activityInfo?.packageName?.let { pkgs += it }
            } catch (_: Exception) {
            }
        }
        pkgs.remove(ctx.packageName)
        return pkgs.mapNotNull { p ->
            try {
                IconPackInfo(p, pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString())
            } catch (_: PackageManager.NameNotFoundException) { null }
        }.sortedBy { it.label }
    }

    fun get(ctx: Context, pkg: String): ExternalIconPack? = synchronized(loaded) {
        loaded.getOrPut(pkg) {
            try { ExternalIconPack(ctx, pkg) } catch (_: Exception) { null }
        }
    }

    /** Drops the parsed pack so an updated or removed pack is read again. */
    fun forget(pkg: String) = synchronized(loaded) { loaded.remove(pkg) }

    fun isLoaded(pkg: String) = synchronized(loaded) { loaded.containsKey(pkg) }
}
