package com.anuki.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache
import java.util.Calendar
import java.util.concurrent.Executors

/**
 * Cat icon pack.
 * Order: user override > default app for a role (browser, camera...) > well-known package list > original icon
 * (optionally placed on a white cat-head plate so every icon matches the theme).
 */
class IconProvider(private val ctx: Context) {
    val catNames: List<String> = (ctx.assets.list("icons") ?: emptyArray())
        .filter { it.endsWith(".png") }.map { it.removeSuffix(".png") }.sorted()

    private val assetCache = HashMap<String, Bitmap?>()
    private val cache = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap) = value.byteCount
    }

    /** package name -> cat icon name */
    @Volatile private var autoMap: Map<String, String> = emptyMap()

    fun asset(name: String): Bitmap? = synchronized(assetCache) {
        assetCache.getOrPut(name) {
            try { ctx.assets.open("icons/$name.png").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
    }

    /** Icon colour for [app]: its own colour, else the global one (0 = 기본). */
    fun colorFor(app: AppEntry): Int {
        val p = LauncherApp.instance.prefs
        return p.appIconColor(app.key) ?: p.iconColor
    }

    /** A hand-made 아늑단 icon in [color]. */
    fun catAsset(name: String, color: Int): Bitmap? = asset(name)?.let { IconColors.recolor(it, color, "asset:$name") }

    fun invalidate() {
        cacheDay = today()
        generation++
        cache.evictAll()
    }

    // ---- icons drawn off the main thread
    /** Bumped whenever the cache is reset, so work started before a reset can't put old pictures back. */
    @Volatile private var generation = 0
    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newFixedThreadPool(2) { r ->
        Thread(r, "anuki-icons").apply { priority = Thread.NORM_PRIORITY - 1; isDaemon = true }
    }
    private val pending = HashMap<String, MutableList<(Bitmap) -> Unit>>()

    /** Icon size on the home screen, dock and drawer in px (54dp × 아이콘 크기). */
    fun homeSize(): Int {
        val d = ctx.resources.displayMetrics.density
        return ((54f * d + 0.5f).toInt() * LauncherApp.instance.prefs.iconScale / 100f).toInt()
    }

    /** The cached icon, or null if it hasn't been drawn yet. */
    fun cached(app: AppEntry, size: Int): Bitmap? = cache.get(app.key + "@" + size)

    /**
     * The icon right away when it's ready; otherwise null, and it is drawn on a background thread and handed to
     * [done] on the main thread (requests for the same icon share one drawing).
     */
    fun appIconAsync(app: AppEntry, size: Int, done: (Bitmap) -> Unit): Bitmap? {
        val k = app.key + "@" + size
        cache.get(k)?.let { return it }
        val gen = generation
        val pk = "$k#$gen"
        synchronized(pending) {
            val waiting = pending[pk]
            if (waiting != null) { waiting += done; return null }
            pending[pk] = mutableListOf(done)
        }
        worker.execute {
            val bmp = if (gen == generation) try { appIcon(app, size) } catch (_: Throwable) { null } else null
            val callbacks = synchronized(pending) { pending.remove(pk) } ?: return@execute
            if (bmp != null && gen == generation) main.post { if (gen == generation) for (cb in callbacks) cb(bmp) }
        }
        return null
    }

    private var cacheDay = today()
    private fun today(): Int = Calendar.getInstance().let { it.get(Calendar.YEAR) * 1000 + it.get(Calendar.DAY_OF_YEAR) }

    /** Date icons from icon packs go stale at midnight; drops the cache when the day changed. Returns true if it did. */
    fun checkDate(): Boolean {
        if (today() == cacheDay) return false
        invalidate()
        return true
    }

    /**
     * The app list changed. Only icons that can look different are dropped: apps in [changed] (installed, updated,
     * removed) and apps whose hand-made icon mapping moved (e.g. a new default browser). Everything when [changed] is
     * null or the active icon pack itself changed.
     */
    fun onAppsChanged(apps: List<AppEntry>, changed: Set<String>? = null, newMap: Map<String, String>? = null) {
        val before = autoMap
        autoMap = newMap ?: computeAutoMap(apps)
        if (changed == null || mode() in changed) {
            invalidate()
            return
        }
        val after = autoMap
        val drop = HashSet(changed)
        for (p in before.keys + after.keys) if (before[p] != after[p]) drop += p
        if (drop.isEmpty()) return
        var n = 0
        for (k in cache.snapshot().keys) {
            if (k.substringBefore('/') in drop) { cache.remove(k); n++ }
        }
        if (LauncherApp.debug) LauncherApp.dlog("icon cache: dropped $n for ${drop.size} changed package(s)")
    }

    /** Package that the auto-mapping gave [icon] to, e.g. "web-browser" -> com.android.chrome. */
    fun packageForIcon(icon: String): String? = autoMap.entries.firstOrNull { it.value == icon }?.key

    private fun defaultPackage(intent: Intent): String? {
        val ri = try {
            ctx.packageManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
        } catch (_: Exception) { null } ?: return null
        val pkg = ri.activityInfo?.packageName ?: return null
        return if (pkg == "android" || pkg.contains("resolver", ignoreCase = true)) null else pkg
    }

    /** Which installed app gets which hand-made icon (asks the system for default apps: call off the main thread). */
    fun computeAutoMap(apps: List<AppEntry>): Map<String, String> {
        val installed = apps.map { it.packageName }.toHashSet()
        val map = LinkedHashMap<String, String>()
        val claimed = HashSet<String>()
        fun claim(pkg: String?, icon: String) {
            if (pkg != null && pkg in installed && pkg !in map && icon !in claimed) {
                map[pkg] = icon
                claimed += icon
            }
        }
        fun sel(category: String) = Intent.makeMainSelectorActivity(Intent.ACTION_MAIN, category)

        claim(defaultPackage(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)), "web-browser")
        claim(defaultPackage(Intent(Intent.ACTION_DIAL)), "phone")
        claim(defaultPackage(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA)), "camera")
        claim(defaultPackage(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:"))), "chat")
        claim(defaultPackage(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:"))), "mail")
        claim(defaultPackage(sel(Intent.CATEGORY_APP_GALLERY)), "photos")
        claim(defaultPackage(sel(Intent.CATEGORY_APP_MUSIC)), "music")
        claim(defaultPackage(sel(Intent.CATEGORY_APP_CALENDAR)), "calendar")
        claim(defaultPackage(sel(Intent.CATEGORY_APP_CALCULATOR)), "calculator")
        claim(defaultPackage(sel(Intent.CATEGORY_APP_MARKET)), "store")
        if (Build.VERSION.SDK_INT >= 29) claim(defaultPackage(sel(Intent.CATEGORY_APP_FILES)), "file-manager")
        claim(defaultPackage(Intent(Settings.ACTION_SETTINGS)), "settings")
        claim(defaultPackage(Intent(AlarmClock.ACTION_SHOW_ALARMS)), "clock")
        for ((pkg, icon) in KNOWN) claim(pkg, icon)
        return map
    }

    /** Active icon source: "builtin" (cat icons), "none" (original icons) or an icon pack's package name. */
    fun mode(): String {
        val p = LauncherApp.instance.prefs
        // "lines" (a separate line icon set in 1.0.13) is now part of the cat icons
        val pack = if (p.iconPack == LINES) "builtin" else p.iconPack
        return if (pack == "builtin" && !p.catIcons) "none" else pack
    }

    /** Built-in cat icon name that [app] currently shows, or null. */
    fun catIconName(app: AppEntry): String? {
        val ov = LauncherApp.instance.prefs.iconOverrides()[app.key]
        if (ov != null) return if (ov.startsWith("@")) null else ov
        if (mode() != "builtin" || app.packageName == ctx.packageName) return null
        return autoMap[app.packageName]
    }

    fun appIcon(app: AppEntry, size: Int): Bitmap {
        val k = app.key + "@" + size
        cache.get(k)?.let { return it }
        val gen = generation
        val bmp = try { render(app, size) } catch (_: Exception) { Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) }
        if (gen == generation) cache.put(k, bmp)
        return bmp
    }

    private fun render(app: AppEntry, size: Int): Bitmap {
        val prefs = LauncherApp.instance.prefs
        val density = ctx.resources.displayMetrics.densityDpi
        val col = colorFor(app)
        // 1) what the user picked for this app
        val ov = prefs.iconOverrides()[app.key]
        when {
            ov == null -> {}
            ov == ORIGINAL -> return originalIcon(app, size)
            ov.startsWith(FILE) -> CustomIcons.load(ctx, ov.removePrefix(FILE))?.let { return scaled(it, size) }
            ov.startsWith(LINE) -> LineIcons.render(ctx, ov.removePrefix(LINE), size, app.packageName, col)?.let { return it }
            ov.startsWith(PACK) -> {
                val rest = ov.removePrefix(PACK)
                IconPacks.get(ctx, rest.substringBefore(':'))?.drawable(rest.substringAfter(':'), density)
                    ?.let { return drawableToBitmap(it, size) }
            }
            else -> catAsset(ov, col)?.let { return scaled(it, size) }
        }
        // 2) the launcher's own entry keeps the icon chosen in settings
        if (app.packageName == ctx.packageName) return originalIcon(app, size)
        // 3) the active icon source
        return when (val m = mode()) {
            "none" -> originalIcon(app, size)
            "builtin" -> {
                // hand-made cat icon > generated cat icon (Arcticons drawing on the cat frame) > own icon in the cat frame
                val cat = autoMap[app.packageName]?.let { catAsset(it, col) }
                when {
                    cat != null -> {
                        if (LauncherApp.debug) LauncherApp.dlog("cat icon ${app.component.flattenToShortString()} -> hand ${autoMap[app.packageName]}")
                        scaled(cat, size)
                    }
                    !prefs.catPlate -> originalIcon(app, size)
                    else -> {
                        val name = LineIcons.nameFor(ctx, app.component)
                        val made = name?.let { LineIcons.render(ctx, it, size, app.packageName, col) }
                        if (LauncherApp.debug) LauncherApp.dlog("cat icon ${app.component.flattenToShortString()} -> " +
                            if (made != null) "drawing $name (${LineIcons.tileFor(app.packageName)})" else "own icon in frame")
                        made ?: run {
                            val d = try { app.info.getBadgedIcon(density) } catch (_: Exception) { null }
                            if (d != null) LineIcons.renderOriginal(ctx, d, size, app.packageName, col) else originalIcon(app, size)
                        }
                    }
                }
            }
            else -> {
                val pack = IconPacks.get(ctx, m)
                val d = pack?.iconFor(app.component, density)
                if (d != null) {
                    if (LauncherApp.debug) LauncherApp.dlog("pack icon ${app.component.flattenToShortString()} -> pack")
                    drawableToBitmap(d, size)
                } else {
                    val orig = originalIcon(app, size)
                    val composed = pack?.compose(orig, size, app.key.hashCode(), density)
                    if (LauncherApp.debug) LauncherApp.dlog("pack icon ${app.component.flattenToShortString()} -> " + if (composed != null) "composed" else "original")
                    composed ?: orig
                }
            }
        }
    }

    fun originalIcon(app: AppEntry, size: Int): Bitmap {
        val d = try { app.info.getBadgedIcon(ctx.resources.displayMetrics.densityDpi) } catch (_: Exception) { null }
        return if (d == null) Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) else drawableToBitmap(d, size)
    }

    fun folderIcon(color: String, contents: List<AppEntry>, size: Int): Bitmap {
        // the default (teal) folder follows the icon colour; pink / gold / navy folders keep theirs
        val base = (if (color == "teal") catAsset("folder", LauncherApp.instance.prefs.iconColor) else asset(folderAsset(color))) ?: asset("folder")
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (base != null) c.drawBitmap(base, null, Rect(0, 0, size, size), paint)
        // up to three mini icons on the folder's front panel
        val mini = (size * 0.21f)
        val cy = size * 0.655f
        val xs = floatArrayOf(0.28f, 0.5f, 0.72f)
        for ((i, app) in contents.take(3).withIndex()) {
            // the full-size icon is usually drawn already: shrink that instead of drawing a new small one
            val icon = cache.get(app.key + "@" + homeSize()) ?: appIcon(app, mini.toInt().coerceAtLeast(8))
            val cx = size * xs[i]
            c.drawBitmap(icon, null, RectF(cx - mini / 2, cy - mini / 2, cx + mini / 2, cy + mini / 2), paint)
        }
        return b
    }

    private fun scaled(src: Bitmap, size: Int): Bitmap =
        if (src.width == size) src else Bitmap.createScaledBitmap(src, size, size, true)

    private fun drawableToBitmap(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        d.setBounds(0, 0, size, size)
        d.draw(c)
        return b
    }

    /** Warm the cache off the main thread so the drawer opens smoothly (queued after what's on screen). */
    fun preload(apps: List<AppEntry>, size: Int) {
        for (a in apps) appIconAsync(a, size) { }
    }

    companion object {
        /** Stored value of the old separate line icon set; treated as "builtin". */
        const val LINES = "lines"
        const val LINE = "@line:"
        const val ORIGINAL = "@original"
        const val FILE = "@file:"
        const val PACK = "@pack:"
        val INK = 0xFF111111.toInt()
        val CYAN = 0xFF4BD5D6.toInt()
        val TEAL = 0xFF2E9497.toInt()

        val FOLDER_COLORS = listOf("teal" to "청록", "pink" to "핑크", "gold" to "골드", "navy" to "네이비")
        fun folderAsset(color: String) = when (color) {
            "pink" -> "folder-pink"
            "gold" -> "folder-gold"
            "navy" -> "folder-navy"
            else -> "folder"
        }

        /** Well-known apps. Only used when no default-app role already took that cat icon. */
        val KNOWN = listOf(
            "com.android.chrome" to "web-browser",
            "com.sec.android.app.sbrowser" to "web-browser",
            "com.naver.whale" to "web-browser",
            "org.mozilla.firefox" to "web-browser",
            "com.microsoft.emmx" to "web-browser",
            "com.kakao.talk" to "chat",
            "com.google.android.apps.messaging" to "chat",
            "com.samsung.android.messaging" to "chat",
            "org.telegram.messenger" to "chat",
            "com.google.android.gm" to "mail",
            "com.samsung.android.email.provider" to "mail",
            "com.nhn.android.mail" to "mail",
            "com.google.android.youtube" to "video",
            "com.google.android.apps.youtube.music" to "music",
            "com.iloen.melon" to "music",
            "com.spotify.music" to "music",
            "com.google.android.apps.photos" to "photos",
            "com.sec.android.gallery3d" to "photos",
            "com.sec.android.app.camera" to "camera",
            "com.google.android.GoogleCamera" to "camera",
            "com.samsung.android.calendar" to "calendar",
            "com.google.android.calendar" to "calendar",
            "com.sec.android.app.clockpackage" to "clock",
            "com.google.android.deskclock" to "clock",
            "com.sec.android.app.popupcalculator" to "calculator",
            "com.google.android.calculator" to "calculator",
            "com.sec.android.app.myfiles" to "file-manager",
            "com.google.android.apps.nbu.files" to "file-manager",
            "com.google.android.documentsui" to "file-manager",
            "com.android.vending" to "store",
            "com.sec.android.app.samsungapps" to "store",
            "com.android.settings" to "settings",
            "com.samsung.android.app.notes" to "notes",
            "com.google.android.keep" to "notes",
            "com.google.android.dialer" to "phone",
            "com.samsung.android.dialer" to "phone",
            "com.termux" to "terminal",
            "com.github.android" to "code-editor",
            "com.google.android.apps.docs" to "cloud",
            "com.microsoft.skydrive" to "cloud",
            "com.adobe.reader" to "pdf",
            "com.google.android.apps.authenticator2" to "key",
        )
    }
}
