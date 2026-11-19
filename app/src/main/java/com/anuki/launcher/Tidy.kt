package com.anuki.launcher

import android.app.Activity
import android.app.AlertDialog
import org.json.JSONArray
import org.json.JSONObject

/**
 * 홈 화면 정리 (the idea of Nova Launcher 9's "Tidy my home screen", made here without AI): the home screen's loose apps
 * go into folders by kind — the same kinds as the 종류별 묶음 drawer. A preview comes first and nothing changes until
 * 정리하기; afterwards it can be undone for [UNDO_DAYS] days.
 *
 * Kinds with two or more apps get a folder (or join a folder of that name that is already there); apps of the kind
 * 기타 stay where they are. On the pages that lost apps the remaining one-cell icons move up into the gaps
 * (widgets stay put).
 */
object Tidy {
    const val UNDO_DAYS = 7
    private const val DAY = 24L * 60 * 60 * 1000

    /** Folder names: the 종류별 묶음 kinds, shortened for a label under an icon. */
    private val SHORT = mapOf(
        "소셜·메신저" to "소셜", "음악·오디오" to "음악", "사진·이미지" to "사진", "뉴스·잡지" to "뉴스", "지도·내비" to "지도",
    )

    fun folderName(kind: String) = SHORT[kind] ?: kind

    class Group(val name: String, val apps: List<HomeItem>, val into: HomeItem?)

    class Plan(val groups: List<Group>, val kept: Int) {
        val moved get() = groups.sumOf { it.apps.size }
    }

    fun plan(model: LayoutModel, apps: AppRepository): Plan {
        val loose = model.items.filter { it.container == HomeItem.DESKTOP && it.type == HomeItem.TYPE_APP }
            .sortedWith(compareBy<HomeItem>({ it.screen }, { it.y }, { it.x }))
        val byKind = HashMap<String, MutableList<HomeItem>>()
        var kept = 0
        for (item in loose) {
            val kind = apps.find(item.key)?.let { Categories.of(it) } ?: "기타"
            if (kind == "기타") kept++ else byKind.getOrPut(kind) { ArrayList() } += item
        }
        val folders = model.items.filter { it.container == HomeItem.DESKTOP && it.type == HomeItem.TYPE_FOLDER }
        val groups = ArrayList<Group>()
        for (kind in Categories.ORDER) {
            val list = byKind[kind] ?: continue
            val name = folderName(kind)
            val existing = folders.firstOrNull { it.title == name }
            if (list.size >= 2 || existing != null) groups += Group(name, list, existing) else kept += list.size
        }
        return Plan(groups, kept)
    }

    /** The preview, then (on 정리하기) the change. [onChanged] runs after the layout was saved. */
    fun ask(a: Activity, onChanged: () -> Unit) {
        val app = LauncherApp.instance
        val p = plan(app.model, app.apps)
        LauncherApp.dlog("tidy plan: ${p.groups.size} folders, ${p.moved} apps move, ${p.kept} stay")
        if (p.groups.isEmpty()) {
            AlertDialog.Builder(a).setTitle("홈 화면 정리")
                .setMessage("이미 깔끔해요.\n홈 화면에 같은 종류의 앱이 두 개 이상 모여 있지 않아요.")
                .setPositiveButton("확인", null).show()
            return
        }
        val text = StringBuilder("같은 종류의 앱을 폴더로 묶어요. '정리하기'를 누르기 전에는 아무것도 바뀌지 않아요.\n")
        for (g in p.groups) {
            val names = g.apps.joinToString(", ") { app.apps.find(it.key)?.label ?: it.title }
            text.append("\n📁 ").append(g.name)
                .append(if (g.into != null) " (있는 폴더에 넣기)" else " (새 폴더)")
                .append(" · ").append(g.apps.size).append("개\n    ").append(names)
        }
        text.append("\n\n그대로 두는 앱 ").append(p.kept).append("개 · 빈칸은 앞으로 당겨요 · ")
            .append(UNDO_DAYS).append("일 동안 되돌릴 수 있어요")
        AlertDialog.Builder(a).setTitle("홈 화면 정리")
            .setMessage(text)
            .setPositiveButton("정리하기") { _, _ ->
                apply(app.model, app.prefs, p)
                a.toast("홈 화면을 정리했어요. ${UNDO_DAYS}일 동안 되돌릴 수 있어요")
                onChanged()
            }
            .setNegativeButton("취소", null)
            .show()
    }

    fun apply(model: LayoutModel, prefs: Prefs, plan: Plan) {
        // where everything on the home screen was, for 되돌리기
        val pos = JSONObject()
        for (it in model.items) {
            if (it.container == HomeItem.DESKTOP) pos.put(it.id.toString(), JSONArray().put(it.screen).put(it.x).put(it.y))
        }
        val made = JSONArray()
        val pages = HashSet<Int>()
        for (g in plan.groups) {
            val folder = g.into ?: g.apps.first().let { f ->
                HomeItem(model.newId(), HomeItem.TYPE_FOLDER, HomeItem.DESKTOP, f.screen, f.x, f.y, title = g.name)
                    .also { model.items += it; made.put(it.id) }
            }
            var n = model.folderContents(folder.id).size
            for (item in g.apps) {
                pages += item.screen
                item.container = folder.id
                item.x = n++
                item.y = 0
            }
        }
        for (p in pages) compact(model, p, prefs.homeCols, prefs.homeRows)
        prefs.tidyUndo = JSONObject().put("time", System.currentTimeMillis()).put("pos", pos).put("made", made).toString()
        model.save()
        LauncherApp.dlog("tidy applied: ${plan.groups.joinToString { "${it.name}=${it.apps.size}" }}, pages $pages")
    }

    /** The page's one-cell icons in reading order, moved up into the free cells (widgets and big items stay put). */
    private fun compact(model: LayoutModel, page: Int, cols: Int, rows: Int) {
        val onPage = model.items.filter { it.container == HomeItem.DESKTOP && it.screen == page }
        val small = onPage.filter { !it.isWidget && it.spanX == 1 && it.spanY == 1 }.sortedWith(compareBy({ it.y }, { it.x }))
        val g = Array(cols) { BooleanArray(rows) }
        for (f in onPage) {
            if (f in small) continue
            for (dx in 0 until f.spanX) for (dy in 0 until f.spanY) {
                val x = f.x + dx; val y = f.y + dy
                if (x in 0 until cols && y in 0 until rows) g[x][y] = true
            }
        }
        val free = ArrayList<IntArray>()
        for (y in 0 until rows) for (x in 0 until cols) if (!g[x][y]) free += intArrayOf(x, y)
        if (free.size < small.size) return
        for ((i, item) in small.withIndex()) { item.x = free[i][0]; item.y = free[i][1] }
    }

    /** Whole days 되돌리기 is still offered for, 0 when there is nothing to undo. */
    fun undoDaysLeft(prefs: Prefs): Int {
        val t = try { JSONObject(prefs.tidyUndo).optLong("time") } catch (_: Exception) { 0L }
        if (t <= 0L) return 0
        val left = t + UNDO_DAYS * DAY - System.currentTimeMillis()
        return if (left <= 0) 0 else ((left + DAY - 1) / DAY).toInt()
    }

    /** Puts everything back where it was before the last 정리하기. False when there is nothing (left) to undo. */
    fun undo(model: LayoutModel, prefs: Prefs): Boolean {
        if (undoDaysLeft(prefs) <= 0) { prefs.tidyUndo = ""; return false }
        val o = try { JSONObject(prefs.tidyUndo) } catch (_: Exception) { return false }
        val pos = o.optJSONObject("pos") ?: return false
        val madeArr = o.optJSONArray("made") ?: JSONArray()
        val made = (0 until madeArr.length()).map { madeArr.getLong(it) }.toSet()
        val restored = ArrayList<HomeItem>()
        val emptied = HashSet<Long>()
        for (k in pos.keys()) {
            val item = model.byId(k.toLongOrNull() ?: continue) ?: continue
            val at = pos.getJSONArray(k)
            if (item.container >= 0) emptied += item.container
            item.container = HomeItem.DESKTOP
            item.screen = at.getInt(0); item.x = at.getInt(1); item.y = at.getInt(2)
            restored += item
            model.pageCount = maxOf(model.pageCount, item.screen + 1)
        }
        // folders the tidy-up made go away; an app put into one since then goes back to the home screen
        val homeless = ArrayList<HomeItem>()
        for (id in made) {
            val f = model.byId(id) ?: continue
            for (rest in model.folderContents(id)) { rest.container = HomeItem.DESKTOP; homeless += rest }
            model.items.remove(f)
        }
        // anything placed since then that now shares a cell with a restored icon moves to a free cell
        val cols = prefs.homeCols; val rows = prefs.homeRows
        fun overlaps(a: HomeItem, b: HomeItem) = a.screen == b.screen &&
            a.x < b.x + b.spanX && b.x < a.x + a.spanX && a.y < b.y + b.spanY && b.y < a.y + a.spanY
        val others = model.items.filter { it.container == HomeItem.DESKTOP && it !in restored && it !in homeless }
        for (it in others + homeless) {
            if (it in homeless || restored.any { r -> overlaps(r, it) }) {
                model.items.remove(it)
                val spot = model.findSpotAnywhere(cols, rows, it.spanX, it.spanY)
                it.screen = spot[0]; it.x = spot[1]; it.y = spot[2]
                model.items += it
            }
        }
        for (id in emptied) if (id !in made) model.cleanupFolder(id)
        prefs.tidyUndo = ""
        model.save()
        LauncherApp.dlog("tidy undone: ${restored.size} items back, ${made.size} folders removed")
        return true
    }
}
