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
import android.provider.AlarmClock
import android.provider.MediaStore
import android.provider.Settings
import android.util.LruCache

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

    fun invalidate() = cache.evictAll()

    fun onAppsChanged(apps: List<AppEntry>) {
        rebuildAutoMap(apps)
        cache.evictAll()
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

    private fun rebuildAutoMap(apps: List<AppEntry>) {
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
        autoMap = map
    }

    /** Active icon source: "builtin" (cat icons), "none" (original icons) or an icon pack's package name. */
    fun mode(): String {
        val p = LauncherApp.instance.prefs
        return if (p.iconPack == "builtin" && !p.catIcons) "none" else p.iconPack
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
        val bmp = try { render(app, size) } catch (_: Exception) { Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) }
        cache.put(k, bmp)
        return bmp
    }

    private fun render(app: AppEntry, size: Int): Bitmap {
        val prefs = LauncherApp.instance.prefs
        val density = ctx.resources.displayMetrics.densityDpi
        // 1) what the user picked for this app
        val ov = prefs.iconOverrides()[app.key]
        when {
            ov == null -> {}
            ov == ORIGINAL -> return originalIcon(app, size)
            ov.startsWith(FILE) -> CustomIcons.load(ctx, ov.removePrefix(FILE))?.let { return scaled(it, size) }
            ov.startsWith(PACK) -> {
                val rest = ov.removePrefix(PACK)
                IconPacks.get(ctx, rest.substringBefore(':'))?.drawable(rest.substringAfter(':'), density)
                    ?.let { return drawableToBitmap(it, size) }
            }
            else -> asset(ov)?.let { return scaled(it, size) }
        }
        // 2) the launcher's own entry keeps the icon chosen in settings
        if (app.packageName == ctx.packageName) return originalIcon(app, size)
        // 3) the active icon source
        return when (val m = mode()) {
            "none" -> originalIcon(app, size)
            "builtin" -> {
                val cat = autoMap[app.packageName]?.let { asset(it) }
                when {
                    cat != null -> scaled(cat, size)
                    prefs.catPlate -> {
                        val d = try { app.info.getBadgedIcon(density) } catch (_: Exception) { null }
                        if (d != null) catPlate(d, size) else originalIcon(app, size)
                    }
                    else -> originalIcon(app, size)
                }
            }
            else -> {
                val pack = IconPacks.get(ctx, m)
                val d = pack?.iconFor(app.component, density)
                if (d != null) drawableToBitmap(d, size) else {
                    val orig = originalIcon(app, size)
                    pack?.compose(orig, size, app.key.hashCode(), density) ?: orig
                }
            }
        }
    }

    fun originalIcon(app: AppEntry, size: Int): Bitmap {
        val d = try { app.info.getBadgedIcon(ctx.resources.displayMetrics.densityDpi) } catch (_: Exception) { null }
        return if (d == null) Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888) else drawableToBitmap(d, size)
    }

    fun folderIcon(color: String, contents: List<AppEntry>, size: Int): Bitmap {
        val base = asset(folderAsset(color)) ?: asset("folder")
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        if (base != null) c.drawBitmap(base, null, Rect(0, 0, size, size), paint)
        // up to three mini icons on the folder's front panel
        val mini = (size * 0.21f)
        val cy = size * 0.655f
        val xs = floatArrayOf(0.28f, 0.5f, 0.72f)
        for ((i, app) in contents.take(3).withIndex()) {
            val icon = appIcon(app, mini.toInt().coerceAtLeast(8))
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

    /** Original icon drawn on a white cat-head plate with cyan ears and a black outline. */
    fun catPlate(d: Drawable, size: Int): Bitmap {
        val b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = size / 100f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = INK; style = Paint.Style.STROKE; strokeWidth = 4.5f * s; strokeJoin = Paint.Join.ROUND
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = CYAN }
        val earL = Path().apply {
            moveTo(10 * s, 54 * s); lineTo(14 * s, 11 * s); quadTo(15 * s, 4 * s, 21 * s, 7 * s); lineTo(44 * s, 24 * s); close()
        }
        val earR = Path().apply {
            moveTo(90 * s, 54 * s); lineTo(86 * s, 11 * s); quadTo(85 * s, 4 * s, 79 * s, 7 * s); lineTo(56 * s, 24 * s); close()
        }
        val inL = Path().apply { moveTo(17 * s, 14 * s); lineTo(15 * s, 33 * s); lineTo(33 * s, 23 * s); close() }
        val inR = Path().apply { moveTo(83 * s, 14 * s); lineTo(85 * s, 33 * s); lineTo(67 * s, 23 * s); close() }
        for (p in arrayOf(earL, earR)) { c.drawPath(p, white); c.drawPath(p, ink) }
        c.drawPath(inL, cyan); c.drawPath(inR, cyan)
        c.drawCircle(50 * s, 57 * s, 39 * s, white)
        c.drawCircle(50 * s, 57 * s, 39 * s, ink)
        val inset = 25 * s
        d.setBounds((50 * s - inset).toInt(), (57 * s - inset).toInt(), (50 * s + inset).toInt(), (57 * s + inset).toInt())
        d.draw(c)
        return b
    }

    /** Warm the cache off the main thread so the drawer opens smoothly. */
    fun preload(apps: List<AppEntry>, size: Int) {
        Thread {
            for (a in apps) try { appIcon(a, size) } catch (_: Exception) { }
        }.start()
    }

    companion object {
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
