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
    private const val OPT_AUTO = "@auto"
    private const val OPT_PHOTO = "@photo"
    private const val OPT_PACK = "@pack"
    private const val OPT_LINE = "@lines"

    /** Icon grid for one app: auto / original / photo / more cat icons (generated) / another icon pack / the 54 cat icons. */
    fun iconPicker(a: Activity, app: AppEntry, onDone: () -> Unit, onPhoto: (() -> Unit)? = null) {
        val icons = LauncherApp.instance.icons
        val prefs = LauncherApp.instance.prefs
        val packs = IconPacks.installed(a)
        val options = ArrayList<String>()
        options += OPT_AUTO
        options += IconProvider.ORIGINAL
        if (onPhoto != null) options += OPT_PHOTO
        options += OPT_LINE
        if (packs.isNotEmpty()) options += OPT_PACK
        options += icons.catNames
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
                val label = when (o) {
                    OPT_AUTO -> "자동"
                    IconProvider.ORIGINAL -> "원래"
                    OPT_PHOTO -> "사진"
                    OPT_LINE -> "더 많은 고양이"
                    OPT_PACK -> "아이콘 팩"
                    else -> ""
                }
                when (o) {
                    OPT_AUTO -> iv.setImageResource(android.R.drawable.ic_menu_revert)
                    IconProvider.ORIGINAL -> iv.setImageBitmap(icons.originalIcon(app, size))
                    OPT_PHOTO -> iv.setImageResource(android.R.drawable.ic_menu_gallery)
                    OPT_PACK -> iv.setImageResource(android.R.drawable.ic_menu_view)
                    OPT_LINE -> iv.setImageBitmap(LineIcons.nameFor(a, app.component)?.let { LineIcons.render(a, it, size, app.packageName) }
                        ?: icons.asset("folder"))
                    else -> iv.setImageBitmap(icons.asset(o))
                }
                col.contentDescription = if (label.isNotEmpty()) "아이콘 선택: $label" else "고양이 아이콘: $o"
                col.addView(iv, LinearLayout.LayoutParams(size, size))
                col.addView(TextView(a).apply { text = label; textSize = 11f; gravity = Gravity.CENTER })
                return col
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            dialog?.dismiss()
            when (val o = options[position]) {
                OPT_PHOTO -> onPhoto?.invoke()
                OPT_PACK -> packIconPicker(a, app, packs, onDone)
                OPT_LINE -> lineIconGrid(a, app, onDone)
                else -> {
                    prefs.setIconOverride(app.key, if (o == OPT_AUTO) null else o)
                    icons.invalidate()
                    onDone()
                }
            }
        }
        dialog = AlertDialog.Builder(a).setTitle("${app.label} 아이콘").setView(grid).setNegativeButton("취소", null).show()
    }

    /** Pick one icon out of an installed icon pack (with a name filter, packs can hold thousands). */
    fun packIconPicker(a: Activity, app: AppEntry, packs: List<IconPackInfo>, onDone: () -> Unit) {
        if (packs.size > 1) {
            AlertDialog.Builder(a).setTitle("아이콘 팩 고르기")
                .setItems(packs.map { it.label }.toTypedArray()) { _, which -> packIconGrid(a, app, packs[which], onDone) }
                .setNegativeButton("취소", null).show()
        } else if (packs.size == 1) {
            packIconGrid(a, app, packs[0], onDone)
        }
    }

    /** Runs [work] off the main thread behind a small "loading" dialog (big packs take a moment to read). */
    private fun <T> loading(a: Activity, work: () -> T?, then: (T?) -> Unit) {
        val d = AlertDialog.Builder(a).setMessage("아이콘 팩 불러오는 중…").setCancelable(false).show()
        Thread {
            val r = try { work() } catch (_: Exception) { null }
            a.runOnUiThread {
                try { d.dismiss() } catch (_: Exception) { }
                if (!a.isFinishing && !a.isDestroyed) then(r)
            }
        }.start()
    }

    private fun packIconGrid(a: Activity, app: AppEntry, info: IconPackInfo, onDone: () -> Unit) {
        loading(a, {
            IconPacks.get(a, info.packageName)?.let { it to it.allIconNames() }
        }) { r ->
            if (r == null) Toast.makeText(a, "아이콘 팩을 읽을 수 없어요", Toast.LENGTH_SHORT).show()
            else showPackGrid(a, app, info, r.first, r.second, onDone)
        }
    }

    private fun showPackGrid(a: Activity, app: AppEntry, info: IconPackInfo, pack: ExternalIconPack, all: List<String>, onDone: () -> Unit) {
        val density = a.resources.displayMetrics.densityDpi
        searchGrid(a, "${info.label} (${all.size}개)", all, { iv, name -> iv.setImageDrawable(pack.drawable(name, density)) }) { name ->
            if (pack.drawableId(name) == 0) {
                Toast.makeText(a, "이 아이콘은 팩 안에 그림이 없어요", Toast.LENGTH_SHORT).show()
                false
            } else {
                LauncherApp.instance.prefs.setIconOverride(app.key, IconProvider.PACK + info.packageName + ":" + name)
                LauncherApp.instance.icons.invalidate()
                onDone()
                true
            }
        }
    }

    /** The generated cat icons (drawings adapted from Arcticons); the one matching this app comes first. */
    private fun lineIconGrid(a: Activity, app: AppEntry, onDone: () -> Unit) {
        loading(a, { LineIcons.names(a).asList() to LineIcons.nameFor(a, app.component) }) { r ->
            val (names, match) = r ?: return@loading
            val all = if (match != null) listOf(match) + names.filter { it != match } else names
            val size = a.dp(48f)
            searchGrid(a, "고양이 아이콘 (${names.size}개)", all, { iv, name -> iv.setImageBitmap(LineIcons.render(a, name, size)) }) { name ->
                LauncherApp.instance.prefs.setIconOverride(app.key, IconProvider.LINE + name)
                LauncherApp.instance.icons.invalidate()
                onDone()
                true
            }
        }
    }

    /** Grid of named icons with a name filter (thousands of entries); [pick] returns true to close. */
    private fun searchGrid(a: Activity, title: String, all: List<String>, bind: (ImageView, String) -> Unit, pick: (String) -> Boolean) {
        var shown = all
        val size = a.dp(48f)
        val column = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val filter = EditText(a).apply { hint = "아이콘 이름 검색"; setSingleLine(true) }
        column.addView(filter, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            setMargins(a.dp(16f), a.dp(8f), a.dp(16f), 0)
        })
        val grid = GridView(a).apply {
            numColumns = 5
            verticalSpacing = a.dp(10f)
            setPadding(a.dp(12f), a.dp(12f), a.dp(12f), a.dp(12f))
        }
        val adapter = object : BaseAdapter() {
            override fun getCount() = shown.size
            override fun getItem(position: Int) = shown[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val iv = (convertView as? ImageView) ?: ImageView(a).apply {
                    layoutParams = android.widget.AbsListView.LayoutParams(size, size)
                }
                val name = shown[position]
                bind(iv, name)
                iv.contentDescription = name
                return iv
            }
        }
        grid.adapter = adapter
        column.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(420f)))
        filter.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim()?.lowercase()?.replace(' ', '_') ?: ""
                shown = if (q.isEmpty()) all else all.filter { it.lowercase().contains(q) }
                adapter.notifyDataSetChanged()
            }
        })
        var dialog: AlertDialog? = null
        grid.setOnItemClickListener { _, _, position, _ ->
            if (pick(shown[position])) dialog?.dismiss()
        }
        dialog = AlertDialog.Builder(a).setTitle(title).setView(column).setNegativeButton("취소", null).show()
    }

    /** Frame for a photo icon. */
    fun photoStyle(a: Activity, onPick: (String) -> Unit) {
        AlertDialog.Builder(a).setTitle("사진 아이콘 모양")
            .setItems(arrayOf("고양이 얼굴 틀에 넣기", "둥근 사각형 그대로")) { _, which ->
                onPick(if (which == 0) CustomIcons.STYLE_CAT else CustomIcons.STYLE_ROUNDED)
            }
            .setNegativeButton("취소", null).show()
    }

    // ------------------------------------------------------------ icon source for every app
    fun iconPackChooser(a: Activity, onDone: () -> Unit) {
        val prefs = LauncherApp.instance.prefs
        val packs = IconPacks.installed(a)
        val values = listOf("builtin", "none") + packs.map { it.packageName }
        val labels = listOf(CAT_LABEL, "원래 아이콘") + packs.map { it.label }
        val current = LauncherApp.instance.icons.mode()
        AlertDialog.Builder(a).setTitle(if (packs.isEmpty()) "아이콘 팩 (설치된 외부 팩 없음)" else "아이콘 팩")
            .setSingleChoiceItems(labels.toTypedArray(), values.indexOf(current).coerceAtLeast(0)) { d, which ->
                d.dismiss()
                val v = values[which]
                val commit = {
                    prefs.catIcons = true
                    prefs.iconPack = v
                    LauncherApp.instance.icons.invalidate()
                    onDone()
                }
                when {
                    v == "none" || IconPacks.isLoaded(v) -> commit()
                    v == "builtin" -> loading(a, { LineIcons.names(a) }) { commit() }
                    else -> loading(a, { IconPacks.get(a, v) }) { commit() }
                }
            }
            .setNegativeButton("취소", null).show()
    }

    const val CAT_LABEL = "고양이 아이콘 (내장)"

    /** Credits for the generated cat icons' drawings (CC BY-SA 4.0 asks for the author, licence, source and changes). */
    fun lineIconsLicense(a: Activity) {
        val text = "고양이 아이콘 중 손으로 그린 54종을 뺀 나머지는 고양이 틀 안에 Arcticons 아이콘 그림을 바꿔 넣은 거예요.\n\n" +
            "원작: Arcticons — Donnnno와 기여자들\nhttps://github.com/Arcticons-Team/Arcticons\n\n" +
            "라이선스: CC BY-SA 4.0\nhttps://creativecommons.org/licenses/by-sa/4.0/\n\n" +
            "바꾼 점: SVG를 경로 데이터로 변환, 선 굵기·색 변경, 닫힌 모양을 흰색·하늘색으로 채움, 고양이 틀 안에 배치.\n" +
            "바꾼 선 그림 데이터(앱의 assets/linecons)도 CC BY-SA 4.0으로 공개해요."
        AlertDialog.Builder(a).setTitle("아이콘 그림 라이선스").setMessage(text)
            .setPositiveButton("확인", null)
            .setNeutralButton("Arcticons 열기") { _, _ ->
                try { a.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://github.com/Arcticons-Team/Arcticons"))) } catch (_: Exception) { }
            }.show()
    }

    fun iconPackLabel(a: Activity): String {
        return when (val m = LauncherApp.instance.icons.mode()) {
            "builtin" -> CAT_LABEL
            "none" -> "원래 아이콘"
            else -> IconPacks.installed(a).firstOrNull { it.packageName == m }?.label ?: "$m (찾을 수 없음)"
        }
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
