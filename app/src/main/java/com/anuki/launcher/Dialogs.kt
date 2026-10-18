package com.anuki.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.GridView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast

object Dialogs {
    private val main = Handler(Looper.getMainLooper())

    // ------------------------------------------------------------ gestures
    val ACTIONS = listOf(
        "none" to "없음",
        "drawer" to "앱 서랍 열기",
        "search" to "앱 검색",
        "notifications" to "알림창 내리기",
        "screen_off" to "화면 끄기",
        "widgets" to "위젯 추가",
        "settings" to "런처 설정",
        "app" to "앱 실행…",
    )

    fun chooseAction(a: Activity, title: String, current: String, onPick: (action: String, appKey: String) -> Unit) {
        val idx = ACTIONS.indexOfFirst { it.first == current }.coerceAtLeast(0)
        AlertDialog.Builder(a).setTitle(title)
            .setSingleChoiceItems(ACTIONS.map { it.second }.toTypedArray(), idx) { d, which ->
                d.dismiss()
                val action = ACTIONS[which].first
                if (action == "app") pickApp(a, "실행할 앱") { onPick(action, it.key) } else onPick(action, "")
            }
            .setNegativeButton("취소", null).show()
    }

    fun actionLabel(action: String, appKey: String): String {
        if (action == "app") {
            val app = LauncherApp.instance.apps.find(appKey)
            return "앱 실행: " + (app?.label ?: "?")
        }
        return ACTIONS.firstOrNull { it.first == action }?.second ?: "없음"
    }

    // ------------------------------------------------------------ app pickers
    fun pickApp(a: Activity, title: String, onPick: (AppEntry) -> Unit) {
        val apps = LauncherApp.instance.apps.all
        AlertDialog.Builder(a).setTitle(title)
            .setItems(apps.map { it.label }.toTypedArray()) { _, which -> onPick(apps[which]) }
            .setNegativeButton("취소", null).show()
    }

    fun pickApps(a: Activity, title: String, selected: Set<String>, onDone: (Set<String>) -> Unit) {
        val apps = LauncherApp.instance.apps.all
        val checked = BooleanArray(apps.size) { apps[it].key in selected }
        AlertDialog.Builder(a).setTitle(title)
            .setMultiChoiceItems(apps.map { it.label }.toTypedArray(), checked) { _, which, isChecked -> checked[which] = isChecked }
            .setPositiveButton("확인") { _, _ -> onDone(apps.indices.filter { checked[it] }.map { apps[it].key }.toSet()) }
            .setNegativeButton("취소", null).show()
    }

    fun hiddenApps(a: Activity, onDone: () -> Unit = {}) {
        val prefs = LauncherApp.instance.prefs
        pickApps(a, "앱 서랍에서 숨길 앱", prefs.hidden) { prefs.hidden = it; onDone() }
    }

    // ------------------------------------------------------------ drawer tabs
    fun editTabs(a: Activity, onChanged: () -> Unit) {
        val prefs = LauncherApp.instance.prefs
        val tabs = prefs.tabs()
        val names = tabs.map { it.name } + listOf("＋ 새 탭 만들기")
        AlertDialog.Builder(a).setTitle("앱 서랍 탭")
            .setItems(names.toTypedArray()) { _, which ->
                if (which == tabs.size) {
                    askText(a, "새 탭 이름", "") { name ->
                        pickApps(a, "'$name' 탭에 넣을 앱", emptySet()) { keys ->
                            tabs += Prefs.Tab(name, keys.toMutableSet()); prefs.saveTabs(tabs); onChanged()
                        }
                    }
                } else {
                    val t = tabs[which]
                    AlertDialog.Builder(a).setTitle(t.name)
                        .setItems(arrayOf("앱 고르기", "이름 바꾸기", "탭 삭제")) { _, op ->
                            when (op) {
                                0 -> pickApps(a, "'${t.name}' 탭의 앱", t.keys) { keys ->
                                    t.keys.clear(); t.keys.addAll(keys); prefs.saveTabs(tabs); onChanged()
                                }
                                1 -> askText(a, "탭 이름", t.name) { t.name = it; prefs.saveTabs(tabs); onChanged() }
                                2 -> { tabs.removeAt(which); prefs.saveTabs(tabs); onChanged() }
                            }
                        }.show()
                }
            }
            .setNegativeButton("닫기", null).show()
    }

    fun askText(a: Activity, title: String, initial: String, onOk: (String) -> Unit) {
        val et = EditText(a).apply { setText(initial); setSingleLine(true); setSelection(initial.length) }
        val wrap = LinearLayout(a).apply {
            setPadding(a.dp(22f), a.dp(8f), a.dp(22f), 0)
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(a).setTitle(title).setView(wrap)
            .setPositiveButton("확인") { _, _ -> et.text.toString().trim().takeIf { it.isNotEmpty() }?.let(onOk) }
            .setNegativeButton("취소", null).show()
    }

    // ------------------------------------------------------------ per-app icon
    fun iconPicker(a: Activity, app: AppEntry, onDone: () -> Unit) {
        val icons = LauncherApp.instance.icons
        val prefs = LauncherApp.instance.prefs
        val options = listOf("@auto", IconProvider.ORIGINAL) + icons.catNames
        val size = a.dp(48f)
        val grid = GridView(a).apply {
            numColumns = 5
            verticalSpacing = a.dp(10f)
            setPadding(a.dp(12f), a.dp(12f), a.dp(12f), a.dp(12f))
        }
        var dialog: AlertDialog? = null
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(position: Int) = options[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val iv = ImageView(a)
                val o = options[position]
                when (o) {
                    "@auto" -> iv.setImageResource(android.R.drawable.ic_menu_revert)
                    IconProvider.ORIGINAL -> iv.setImageBitmap(icons.originalIcon(app, size))
                    else -> iv.setImageBitmap(icons.asset(o))
                }
                col.addView(iv, LinearLayout.LayoutParams(size, size))
                col.addView(TextView(a).apply {
                    text = when (o) { "@auto" -> "자동"; IconProvider.ORIGINAL -> "원래"; else -> "" }
                    textSize = 11f; gravity = Gravity.CENTER
                })
                return col
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            val o = options[position]
            prefs.setIconOverride(app.key, if (o == "@auto") null else o)
            icons.invalidate()
            dialog?.dismiss()
            onDone()
        }
        dialog = AlertDialog.Builder(a).setTitle("${app.label} 아이콘").setView(grid).setNegativeButton("취소", null).show()
    }

    // ------------------------------------------------------------ wallpapers
    val WALLPAPERS = listOf(
        "wallpapers/cat_pattern.jpg" to "고양이 패턴",
        "wallpapers/sticker_collage.jpg" to "스티커 모음",
        "wallpapers/cat_peek.jpg" to "빼꼼 고양이",
    )

    fun wallpaper(a: Activity) {
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(a.dp(12f), a.dp(12f), a.dp(12f), a.dp(4f))
        }
        var dialog: AlertDialog? = null
        for ((path, name) in WALLPAPERS) {
            val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
            val thumb = try {
                a.assets.open(path).use { BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 8 }) }
            } catch (_: Exception) { null }
            col.addView(ImageView(a).apply {
                setImageBitmap(thumb)
                scaleType = ImageView.ScaleType.CENTER_CROP
                background = Palette.rounded(0xFFDDDDDD.toInt(), a.dp(10f).toFloat())
                clipToOutline = true
                setOnClickListener { dialog?.dismiss(); applyWallpaper(a, path) }
            }, LinearLayout.LayoutParams(a.dp(78f), a.dp(150f)))
            col.addView(TextView(a).apply { text = name; textSize = 12f; gravity = Gravity.CENTER; setPadding(0, a.dp(6f), 0, 0) })
            row.addView(col, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        dialog = AlertDialog.Builder(a).setTitle("배경화면").setView(row)
            .setNeutralButton("다른 사진 고르기") { _, _ ->
                try { a.startActivity(Intent.createChooser(Intent(Intent.ACTION_SET_WALLPAPER), "배경화면 고르기")) } catch (_: Exception) { }
            }
            .setNegativeButton("닫기", null).show()
    }

    private fun applyWallpaper(a: Activity, path: String) {
        AlertDialog.Builder(a).setTitle("어디에 적용할까요?")
            .setItems(arrayOf("홈 화면", "홈 화면 + 잠금 화면")) { _, which ->
                val flags = if (which == 0) WallpaperManager.FLAG_SYSTEM else WallpaperManager.FLAG_SYSTEM or WallpaperManager.FLAG_LOCK
                Toast.makeText(a, "배경화면을 바꾸는 중…", Toast.LENGTH_SHORT).show()
                Thread {
                    val ok = try {
                        a.assets.open(path).use { WallpaperManager.getInstance(a).setStream(it, null, true, flags) }
                        true
                    } catch (_: Exception) { false }
                    main.post { Toast.makeText(a, if (ok) "배경화면을 바꿨어요" else "배경화면을 바꾸지 못했어요", Toast.LENGTH_SHORT).show() }
                }.start()
            }.show()
    }

    // ------------------------------------------------------------ folders & widgets
    fun folderColor(a: Activity, folder: HomeItem, onDone: () -> Unit) {
        val colors = IconProvider.FOLDER_COLORS
        AlertDialog.Builder(a).setTitle("폴더 색")
            .setSingleChoiceItems(colors.map { it.second }.toTypedArray(), colors.indexOfFirst { it.first == folder.color }.coerceAtLeast(0)) { d, which ->
                folder.color = colors[which].first
                d.dismiss(); onDone()
            }.show()
    }

    fun widgetResize(a: HomeActivity, item: HomeItem, onDone: () -> Unit) {
        val prefs = a.prefs
        val model = a.model
        var sx = item.spanX
        var sy = item.spanY
        val label = TextView(a).apply { textSize = 18f; gravity = Gravity.CENTER; setPadding(0, a.dp(8f), 0, a.dp(8f)) }
        fun update() { label.text = "가로 ${sx}칸 × 세로 ${sy}칸" }
        update()
        fun btn(t: String, f: () -> Unit) = TextView(a).apply {
            text = t; textSize = 16f; gravity = Gravity.CENTER
            setPadding(a.dp(10f), a.dp(10f), a.dp(10f), a.dp(10f))
            background = Palette.rounded(0xFFE6F4F4.toInt(), a.dp(12f).toFloat())
            setOnClickListener { f(); update() }
        }
        fun fits(nx: Int, ny: Int): Boolean {
            val g = model.occupied(HomeItem.DESKTOP, item.screen, prefs.homeCols, prefs.homeRows, item)
            return model.fits(g, item.x, item.y, nx, ny)
        }
        val rows = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20f), a.dp(8f), a.dp(20f), 0) }
        rows.addView(label)
        fun pair(t: String, minus: () -> Unit, plus: () -> Unit) = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(TextView(a).apply { text = t; textSize = 15f }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(btn("－", minus), LinearLayout.LayoutParams(a.dp(56f), a.dp(44f)).apply { marginEnd = a.dp(8f) })
            addView(btn("＋", plus), LinearLayout.LayoutParams(a.dp(56f), a.dp(44f)))
            setPadding(0, a.dp(6f), 0, a.dp(6f))
        }
        rows.addView(pair("가로", { if (sx > 1) sx-- }, { if (fits(sx + 1, sy)) sx++ else Toast.makeText(a, "자리가 부족해요", Toast.LENGTH_SHORT).show() }))
        rows.addView(pair("세로", { if (sy > 1) sy-- }, { if (fits(sx, sy + 1)) sy++ else Toast.makeText(a, "자리가 부족해요", Toast.LENGTH_SHORT).show() }))
        AlertDialog.Builder(a).setTitle("위젯 크기").setView(rows)
            .setPositiveButton("적용") { _, _ -> item.spanX = sx; item.spanY = sy; onDone() }
            .setNegativeButton("취소", null).show()
    }
}
