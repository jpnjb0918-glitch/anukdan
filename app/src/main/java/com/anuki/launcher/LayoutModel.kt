package com.anuki.launcher

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

class HomeItem(
    var id: Long,
    var type: Int,
    var container: Long,
    var screen: Int = 0,
    var x: Int = 0,
    var y: Int = 0,
    var spanX: Int = 1,
    var spanY: Int = 1,
    var key: String = "",
    var title: String = "",
    var widgetId: Int = -1,
    var provider: String = "",
    var color: String = "teal",
    /** 위로 쓸기: app key opened when this home/dock app icon is swiped up ("" = the home screen's own swipe-up). */
    var swipeUp: String = "",
    /** 커버 폴더: a tap opens the folder's first app, a swipe up opens the folder. */
    var cover: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("id", id).put("type", type).put("container", container)
        .put("screen", screen).put("x", x).put("y", y).put("spanX", spanX).put("spanY", spanY)
        .put("key", key).put("title", title).put("widgetId", widgetId).put("provider", provider).put("color", color)
        .put("swipeUp", swipeUp).put("cover", cover)

    /** A system widget or a built-in 아늑단 widget: desktop only, never merged into a folder. */
    val isWidget get() = type == TYPE_WIDGET || type == TYPE_CAT_WIDGET

    companion object {
        const val TYPE_APP = 0
        const val TYPE_FOLDER = 1
        const val TYPE_WIDGET = 2
        const val TYPE_CAT_WIDGET = 3 // built-in 아늑단 widget (CatWidgets): key = kind
        const val DESKTOP = -100L
        const val DOCK = -101L

        fun fromJson(o: JSONObject) = HomeItem(
            o.getLong("id"), o.getInt("type"), o.getLong("container"),
            o.optInt("screen"), o.optInt("x"), o.optInt("y"), o.optInt("spanX", 1), o.optInt("spanY", 1),
            o.optString("key"), o.optString("title"), o.optInt("widgetId", -1), o.optString("provider"),
            o.optString("color", "teal"), o.optString("swipeUp"), o.optBoolean("cover"),
        )
    }
}

/** Home screen layout: pages, dock, folders and widgets, saved as JSON in the app's private files. */
class LayoutModel(private val ctx: Context) {
    val items = ArrayList<HomeItem>()
    var pageCount = 1
    private var nextId = 1L
    private val file get() = File(ctx.filesDir, "layout.json")

    fun load() {
        if (!file.exists()) return
        try { fromJson(JSONObject(file.readText())) } catch (_: Exception) { }
    }

    fun save() {
        try {
            val tmp = File(ctx.filesDir, "layout.json.tmp")
            tmp.writeText(toJson().toString())
            tmp.renameTo(file)
        } catch (_: Exception) { }
    }

    fun toJson(): JSONObject = JSONObject().put("version", 1).put("pages", pageCount)
        .put("items", JSONArray().apply { for (it in items) put(it.toJson()) })

    fun fromJson(o: JSONObject, skipWidgets: Boolean = false) {
        items.clear()
        pageCount = maxOf(1, o.optInt("pages", 1))
        val arr = o.optJSONArray("items") ?: JSONArray()
        for (i in 0 until arr.length()) {
            val item = HomeItem.fromJson(arr.getJSONObject(i))
            if (skipWidgets && item.type == HomeItem.TYPE_WIDGET) continue
            items += item
        }
        nextId = (items.maxOfOrNull { it.id } ?: 0L) + 1
    }

    fun newId(): Long = nextId++

    fun byId(id: Long): HomeItem? = items.firstOrNull { it.id == id }

    fun folderContents(folderId: Long): List<HomeItem> = items.filter { it.container == folderId }.sortedBy { it.x }

    fun itemAt(container: Long, screen: Int, x: Int, y: Int, exclude: HomeItem? = null): HomeItem? = items.firstOrNull {
        it !== exclude && it.container == container && (container != HomeItem.DESKTOP || it.screen == screen) &&
            x >= it.x && x < it.x + it.spanX && y >= it.y && y < it.y + it.spanY
    }

    fun occupied(container: Long, screen: Int, cols: Int, rows: Int, exclude: HomeItem? = null): Array<BooleanArray> {
        val g = Array(cols) { BooleanArray(rows) }
        for (it in items) {
            if (it === exclude || it.container != container) continue
            if (container == HomeItem.DESKTOP && it.screen != screen) continue
            for (dx in 0 until it.spanX) for (dy in 0 until it.spanY) {
                val cx = it.x + dx; val cy = it.y + dy
                if (cx in 0 until cols && cy in 0 until rows) g[cx][cy] = true
            }
        }
        return g
    }

    fun fits(g: Array<BooleanArray>, x: Int, y: Int, sx: Int, sy: Int): Boolean {
        val cols = g.size; val rows = if (cols > 0) g[0].size else 0
        if (x < 0 || y < 0 || x + sx > cols || y + sy > rows) return false
        for (dx in 0 until sx) for (dy in 0 until sy) if (g[x + dx][y + dy]) return false
        return true
    }

    fun findVacant(
        container: Long, screen: Int, cols: Int, rows: Int, sx: Int, sy: Int,
        nearX: Int = 0, nearY: Int = 0, exclude: HomeItem? = null,
    ): IntArray? {
        val g = occupied(container, screen, cols, rows, exclude)
        var best: IntArray? = null
        var bestD = Int.MAX_VALUE
        for (y in 0..rows - sy) for (x in 0..cols - sx) {
            if (!fits(g, x, y, sx, sy)) continue
            val d = abs(x - nearX) * 10 + abs(y - nearY) * 11
            if (d < bestD) { bestD = d; best = intArrayOf(x, y) }
        }
        return best
    }

    /** First free spot scanning from [startPage]; adds a page if every page is full. Returns (screen, x, y). */
    fun findSpotAnywhere(cols: Int, rows: Int, sx: Int, sy: Int, startPage: Int = 0): IntArray {
        for (p in startPage until pageCount) {
            findVacant(HomeItem.DESKTOP, p, cols, rows, sx, sy)?.let { return intArrayOf(p, it[0], it[1]) }
        }
        for (p in 0 until startPage) {
            findVacant(HomeItem.DESKTOP, p, cols, rows, sx, sy)?.let { return intArrayOf(p, it[0], it[1]) }
        }
        pageCount += 1
        return intArrayOf(pageCount - 1, 0, 0)
    }

    fun removeItem(item: HomeItem) {
        items.remove(item)
        if (item.type == HomeItem.TYPE_FOLDER) items.removeAll { it.container == item.id }
        if (item.container >= 0) cleanupFolder(item.container)
    }

    /** Folder with 0 items disappears, folder with 1 item becomes that item. */
    fun cleanupFolder(folderId: Long) {
        val folder = byId(folderId) ?: return
        if (folder.type != HomeItem.TYPE_FOLDER) return
        val c = folderContents(folderId)
        when (c.size) {
            0 -> items.remove(folder)
            1 -> {
                val only = c[0]
                only.container = folder.container; only.screen = folder.screen
                only.x = folder.x; only.y = folder.y; only.spanX = 1; only.spanY = 1
                items.remove(folder)
            }
            else -> c.forEachIndexed { i, it -> it.x = i }
        }
    }

    /** Keep every item inside the current grid after the user shrinks it. */
    fun normalize(cols: Int, rows: Int, dockCount: Int) {
        val maxScreen = items.filter { it.container == HomeItem.DESKTOP }.maxOfOrNull { it.screen } ?: 0
        pageCount = maxOf(pageCount, maxScreen + 1, 1)
        val desktop = items.filter { it.container == HomeItem.DESKTOP }.sortedWith(compareBy({ it.screen }, { it.y }, { it.x }))
        for (it in desktop) {
            it.spanX = it.spanX.coerceIn(1, cols); it.spanY = it.spanY.coerceIn(1, rows)
            val g = occupied(HomeItem.DESKTOP, it.screen, cols, rows, it)
            if (!fits(g, it.x, it.y, it.spanX, it.spanY)) {
                val v = findVacant(HomeItem.DESKTOP, it.screen, cols, rows, it.spanX, it.spanY, it.x, it.y, it)
                if (v != null) { it.x = v[0]; it.y = v[1] } else {
                    val s = findSpotAnywhere(cols, rows, it.spanX, it.spanY, it.screen + 1)
                    it.screen = s[0]; it.x = s[1]; it.y = s[2]
                }
            }
        }
        val dock = items.filter { it.container == HomeItem.DOCK }.sortedBy { it.x }
        for (it in dock) {
            if (it.x >= dockCount || dock.any { o -> o !== it && o.x == it.x }) {
                val g = occupied(HomeItem.DOCK, 0, dockCount, 1, it)
                val free = (0 until dockCount).firstOrNull { x -> !g[x][0] }
                if (free != null) { it.x = free; it.y = 0 } else {
                    val s = findSpotAnywhere(cols, rows, 1, 1)
                    it.container = HomeItem.DESKTOP; it.screen = s[0]; it.x = s[1]; it.y = s[2]
                }
            }
        }
    }

    fun removeTrailingEmptyPages(keep: Int = 0) {
        while (pageCount > 1 && pageCount - 1 > keep && items.none { it.container == HomeItem.DESKTOP && it.screen == pageCount - 1 }) {
            pageCount -= 1
        }
    }

    fun deletePage(page: Int) {
        items.filter { it.container == HomeItem.DESKTOP && it.screen == page }.forEach { removeItem(it) }
        for (it in items) if (it.container == HomeItem.DESKTOP && it.screen > page) it.screen -= 1
        pageCount = maxOf(1, pageCount - 1)
    }
}
