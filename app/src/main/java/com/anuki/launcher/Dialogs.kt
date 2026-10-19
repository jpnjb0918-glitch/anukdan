package com.anuki.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.WallpaperManager
import android.content.Intent
import android.graphics.BitmapFactory
import android.os.Build
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

    /** The 아늑단 메모 widget's text: several lines, empty is allowed (shows the hint again). */
    fun memo(a: Activity, initial: String, onSave: (String) -> Unit) {
        val et = EditText(a).apply {
            setText(initial)
            setSelection(initial.length)
            minLines = 4
            maxLines = 10
            gravity = Gravity.TOP or Gravity.START
            hint = "메모를 써요"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE or
                android.text.InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
            contentDescription = "메모 내용"
            Fonts.apply(this)
        }
        val wrap = LinearLayout(a).apply {
            setPadding(a.dp(22f), a.dp(8f), a.dp(22f), 0)
            addView(et, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        AlertDialog.Builder(a).setTitle("아늑단 메모").setView(wrap)
            .setPositiveButton("저장") { _, _ -> onSave(et.text.toString().trimEnd()) }
            .setNeutralButton("모두 지우기") { _, _ -> onSave("") }
            .setNegativeButton("취소", null).show()
    }

    // ------------------------------------------------------------ launcher font
    /** Each font shown in itself; picks the key ("" = the phone's font). */
    fun fontPicker(a: Activity, current: String, onPick: (String) -> Unit) {
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(0, a.dp(6f), 0, a.dp(6f)) }
        var dialog: AlertDialog? = null
        for (f in Fonts.ALL) {
            val tf = Fonts.typeface(a, f.key)
            val row = LinearLayout(a).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(a.dp(22f), a.dp(10f), a.dp(22f), a.dp(10f))
                isClickable = true
                background = a.getDrawable(android.R.drawable.list_selector_background)
                contentDescription = "글꼴: ${f.label}"
                setOnClickListener { dialog?.dismiss(); onPick(f.key) }
            }
            row.addView(TextView(a).apply {
                text = (if (f.key == current) "✓ " else "") + f.label
                textSize = 20f
                typeface = tf ?: android.graphics.Typeface.DEFAULT_BOLD
                setTextColor(if (f.key == current) Palette.TEAL else 0xFF1A2B2C.toInt())
            })
            row.addView(TextView(a).apply {
                text = "아늑이 런처 · 아늑단 가나다 ABC 123"
                textSize = 16f
                typeface = tf ?: android.graphics.Typeface.DEFAULT
                setTextColor(0xFF1A2B2C.toInt())
            })
            row.addView(TextView(a).apply { text = f.note; textSize = 12f; alpha = 0.6f })
            list.addView(row)
        }
        list.addView(TextView(a).apply {
            text = "앱 이름, 폴더, 앱 서랍, 메뉴, 아늑단 위젯의 글자가 바뀌어요. 글꼴은 모두 무료 글꼴(SIL Open Font License)이에요."
            textSize = 12.5f; alpha = 0.7f
            setPadding(a.dp(22f), a.dp(8f), a.dp(22f), a.dp(4f))
        })
        dialog = AlertDialog.Builder(a).setTitle("글꼴")
            .setView(android.widget.ScrollView(a).apply { addView(list) })
            .setNegativeButton("취소", null).show()
    }

    fun fontLicense(a: Activity) {
        val notice = try { a.assets.open("fonts/NOTICE.txt").bufferedReader().readText() } catch (_: Exception) { "" }
        val ofl = try { a.assets.open("fonts/OFL-jua.txt").bufferedReader().readText() } catch (_: Exception) { "" }
        val text = notice +
            "\n\n— SIL Open Font License 1.1 (주아의 예, 글꼴마다 저작권 줄만 달라요) —\n\n" + ofl
        val tv = TextView(a).apply {
            this.text = text; textSize = 12.5f
            setPadding(a.dp(22f), a.dp(8f), a.dp(22f), a.dp(8f))
            setTextIsSelectable(true)
        }
        AlertDialog.Builder(a).setTitle("글꼴 라이선스")
            .setView(android.widget.ScrollView(a).apply { addView(tv) })
            .setPositiveButton("확인", null).show()
    }

    // ------------------------------------------------------------ per-app icon
    private const val OPT_AUTO = "@auto"
    private const val OPT_PHOTO = "@photo"
    private const val OPT_PACK = "@pack"
    private const val OPT_LINE = "@lines"
    private const val OPT_COLOR = "@color"

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
        options += OPT_COLOR
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
                    OPT_LINE -> "더 많은 아늑단"
                    OPT_COLOR -> "색 바꾸기"
                    OPT_PACK -> "아이콘 팩"
                    else -> ""
                }
                when (o) {
                    OPT_AUTO -> iv.setImageResource(android.R.drawable.ic_menu_revert)
                    IconProvider.ORIGINAL -> iv.setImageBitmap(icons.originalIcon(app, size))
                    OPT_PHOTO -> iv.setImageResource(android.R.drawable.ic_menu_gallery)
                    OPT_PACK -> iv.setImageResource(android.R.drawable.ic_menu_view)
                    OPT_LINE -> iv.setImageBitmap(LineIcons.nameFor(a, app.component)?.let { LineIcons.render(a, it, size, app.packageName, icons.colorFor(app)) }
                        ?: icons.catAsset("folder", icons.colorFor(app)))
                    OPT_COLOR -> iv.setImageDrawable(paletteDrawable())
                    else -> iv.setImageBitmap(icons.catAsset(o, icons.colorFor(app)))
                }
                col.contentDescription = if (label.isNotEmpty()) "아이콘 선택: $label" else "아늑단 아이콘: $o"
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
                OPT_COLOR -> iconColor(a, "${app.label} 아이콘 색", prefs.appIconColor(app.key), sampleApp = app, allowFollow = true) { c ->
                    prefs.setAppIconColor(app.key, c)
                    icons.invalidate()
                    onDone()
                }
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
            val col = LauncherApp.instance.icons.colorFor(app)
            searchGrid(a, "아늑단 아이콘 (${names.size}개)", all, { iv, name -> iv.setImageBitmap(LineIcons.render(a, name, size, name, col)) }) { name ->
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

    /** A small colour wheel for the "색 바꾸기" cell. */
    private fun paletteDrawable() = android.graphics.drawable.GradientDrawable().apply {
        shape = android.graphics.drawable.GradientDrawable.OVAL
        gradientType = android.graphics.drawable.GradientDrawable.SWEEP_GRADIENT
        colors = intArrayOf(0xFFEC7474.toInt(), 0xFFE4B01C.toInt(), 0xFF58C46A.toInt(), 0xFF4BD5D6.toInt(), 0xFF5AA9E6.toInt(), 0xFF9B7BD4.toInt(), 0xFFEC7474.toInt())
        setStroke(3, 0xFF111111.toInt())
    }

    /**
     * Icon colour picker: preset swatches, "직접 고르기" (hue / strength / brightness sliders with a preview),
     * and for a single app "전체 색 따르기" (null). [onPick] gets the colour (0 = 기본).
     */
    fun iconColor(a: Activity, title: String, current: Int?, sampleApp: AppEntry? = null, allowFollow: Boolean = false, onPick: (Int?) -> Unit) {
        val icons = LauncherApp.instance.icons
        val options = ArrayList<Pair<Int?, String>>()
        if (allowFollow) options += (null to "전체 색 따르기")
        options += IconColors.PRESETS.map { (c, n) -> (c as Int?) to n }
        // the whole set can follow the wallpaper (not one app on its own)
        if (!allowFollow && Build.VERSION.SDK_INT >= 27) options += (IconColors.WALLPAPER as Int?) to "배경화면 색 따르기"
        val grid = GridView(a).apply {
            numColumns = 4
            verticalSpacing = a.dp(12f)
            setPadding(a.dp(12f), a.dp(12f), a.dp(12f), a.dp(4f))
        }
        var dialog: AlertDialog? = null
        grid.adapter = object : BaseAdapter() {
            override fun getCount() = options.size
            override fun getItem(position: Int) = options[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val (c, name) = options[position]
                val col = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL }
                val iv = ImageView(a)
                val shown = IconColors.resolve(c ?: LauncherApp.instance.prefs.iconColor)
                iv.setImageBitmap(icons.catAsset("folder", shown))
                if (c == current) iv.background = Palette.rounded(0x332E9497, a.dp(12f).toFloat(), a.dp(2f), Palette.TEAL)
                col.addView(iv, LinearLayout.LayoutParams(a.dp(52f), a.dp(52f)))
                col.addView(TextView(a).apply { text = name; textSize = 11f; gravity = Gravity.CENTER; maxLines = 2 })
                col.contentDescription = "아이콘 색: $name"
                return col
            }
        }
        grid.setOnItemClickListener { _, _, position, _ ->
            dialog?.dismiss()
            onPick(options[position].first)
        }
        dialog = AlertDialog.Builder(a).setTitle(title).setView(grid)
            .setNeutralButton("직접 고르기") { _, _ ->
                customColor(a, title, current?.let { IconColors.resolve(it) }?.takeIf { it != 0 } ?: 0xFF4BD5D6.toInt(), sampleApp, onPick)
            }
            .setNegativeButton("취소", null).show()
    }

    private fun customColor(a: Activity, title: String, start: Int, sampleApp: AppEntry?, onPick: (Int?) -> Unit) {
        val icons = LauncherApp.instance.icons
        val hsv = FloatArray(3)
        IconColors.toHsv(start, hsv)
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20f), a.dp(12f), a.dp(20f), 0) }
        val previews = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        val p1 = ImageView(a); val p2 = ImageView(a); val p3 = ImageView(a)
        for (v in listOf(p1, p2, p3)) previews.addView(v, LinearLayout.LayoutParams(a.dp(64f), a.dp(64f)).apply { setMargins(a.dp(8f), 0, a.dp(8f), 0) })
        box.addView(previews)
        val swatch = View(a)
        box.addView(swatch, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, a.dp(10f)).apply { topMargin = a.dp(10f) })
        fun color() = IconColors.fromHsv(0xFF, hsv[0], hsv[1], hsv[2])
        fun update() {
            val c = color()
            swatch.background = Palette.rounded(c, a.dp(5f).toFloat())
            p1.setImageBitmap(icons.catAsset(sampleApp?.let { icons.catIconName(it) } ?: "web-browser", c))
            p2.setImageBitmap(LineIcons.render(a, "google_maps", a.dp(64f), "google_maps", c) ?: icons.catAsset("camera", c))
            p3.setImageBitmap(icons.catAsset("folder", c))
        }
        fun slider(label: String, max: Int, value: Int, onChange: (Int) -> Unit) {
            box.addView(TextView(a).apply { text = label; textSize = 13f; setPadding(0, a.dp(12f), 0, 0) })
            box.addView(android.widget.SeekBar(a).apply {
                this.max = max
                progress = value
                contentDescription = label
                setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: android.widget.SeekBar?, p: Int, fromUser: Boolean) { onChange(p); update() }
                    override fun onStartTrackingTouch(seekBar: android.widget.SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: android.widget.SeekBar?) {}
                })
            })
        }
        slider("색상", 359, hsv[0].toInt()) { hsv[0] = it.toFloat() }
        slider("진하기", 100, (hsv[1] * 100).toInt()) { hsv[1] = it / 100f }
        slider("밝기 (너무 어두우면 선 그림이 안 보여요)", 70, ((hsv[2] * 100).toInt() - 30).coerceIn(0, 70)) { hsv[2] = (it + 30) / 100f }
        update()
        AlertDialog.Builder(a).setTitle("$title — 직접 고르기").setView(android.widget.ScrollView(a).apply { addView(box) })
            .setPositiveButton("확인") { _, _ -> onPick(color()) }
            .setNegativeButton("취소", null).show()
    }

    // ------------------------------------------------------------ app name size
    /**
     * App name size in pixels with a live preview (real apps at the home screen's cell width):
     * slider, −/+ one pixel at a time, or type the number. Picks 0 for 기본 (12sp, follows the phone's font size).
     */
    fun labelSize(a: Activity, current: Int, onPick: (Int) -> Unit) {
        val app = LauncherApp.instance
        val prefs = app.prefs
        val def = IconView.defaultLabelPx(a)
        var px = if (current > 0) current else def
        var touched = false
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL; setPadding(a.dp(20f), a.dp(12f), a.dp(20f), 0) }

        val dm = a.resources.displayMetrics
        val iconSize = (a.dp(54f) * prefs.iconScale / 100f).toInt()
        val cellW = dm.widthPixels / prefs.homeCols.coerceAtLeast(1)
        val avail = minOf((dm.widthPixels * 0.85f).toInt(), a.dp(560f)) - a.dp(56f)
        val n = (avail / maxOf(1, cellW)).coerceIn(1, 4)
        val preview = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            background = Palette.rounded(Palette.TEAL, a.dp(12f).toFloat())
            setPadding(0, a.dp(10f), 0, a.dp(10f))
        }
        val views = app.apps.all.take(n).map { e ->
            IconView(a).apply {
                this.iconSize = iconSize
                icon = app.icons.appIcon(e, iconSize)
                label = e.label
                labelPx = px
                isClickable = false
                isLongClickable = false
            }
        }
        for (v in views) preview.addView(v, LinearLayout.LayoutParams(cellW, ViewGroup.LayoutParams.WRAP_CONTENT))
        box.addView(preview, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val number = EditText(a).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
            setText(px.toString())
            textSize = 22f
            setTextColor(Palette.TEAL)
            gravity = Gravity.CENTER
            minEms = 2
            contentDescription = "글자 크기 숫자"
        }
        val tag = TextView(a).apply { textSize = 13f; alpha = 0.7f; setPadding(a.dp(8f), 0, 0, 0) }
        val head = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER; setPadding(0, a.dp(12f), 0, 0) }
        head.addView(number)
        head.addView(TextView(a).apply { text = "px"; textSize = 16f })
        head.addView(tag)
        box.addView(head)

        val seek = android.widget.SeekBar(a).apply {
            min = IconView.MIN_LABEL_PX
            max = IconView.MAX_LABEL_PX
            progress = px
            contentDescription = "글자 크기"
        }
        var syncing = false
        fun set(v: Int, from: String) {
            px = v.coerceIn(IconView.MIN_LABEL_PX, IconView.MAX_LABEL_PX)
            syncing = true
            if (from != "seek") seek.progress = px
            if (from != "text") { number.setText(px.toString()); number.setSelection(number.text.length) }
            syncing = false
            tag.text = if (px == def) "(기본과 같은 크기)" else ""
            for (iv in views) iv.labelPx = px
        }
        fun stepButton(t: String, desc: String, d: Int) =
            android.widget.Button(a, null, android.R.attr.borderlessButtonStyle).apply {
                text = t
                textSize = 22f
                contentDescription = desc
                minWidth = 0; minimumWidth = a.dp(48f)
                setOnClickListener { touched = true; set(px + d, "button") }
            }
        val controls = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        controls.addView(stepButton("−", "글자 1픽셀 작게", -1))
        controls.addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        controls.addView(stepButton("+", "글자 1픽셀 크게", 1))
        box.addView(controls)
        seek.setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                if (!syncing) { touched = true; set(p, "seek") }
            }
            override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
            override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
        })
        number.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(t: CharSequence?, st: Int, c: Int, af: Int) {}
            override fun onTextChanged(t: CharSequence?, st: Int, b: Int, c: Int) {}
            override fun afterTextChanged(e: android.text.Editable?) {
                if (syncing) return
                val v = e?.toString()?.toIntOrNull() ?: return
                if (v in IconView.MIN_LABEL_PX..IconView.MAX_LABEL_PX) { touched = true; set(v, "text") }
            }
        })
        box.addView(TextView(a).apply {
            text = "${IconView.MIN_LABEL_PX}~${IconView.MAX_LABEL_PX}px, 1픽셀씩 정할 수 있어요. 숫자를 눌러 직접 써도 돼요.\n" +
                "기본은 휴대폰의 글꼴 크기 설정을 따라가요(이 휴대폰에서 지금 ${def}px). 숫자로 정하면 휴대폰 글꼴 크기와 상관없이 그 크기로 보여요.\n" +
                "홈 화면·폴더·앱 서랍의 앱 이름에 모두 적용돼요."
            textSize = 12.5f
            alpha = 0.75f
            setPadding(0, a.dp(8f), 0, a.dp(8f))
        })
        set(px, "init")

        AlertDialog.Builder(a).setTitle("앱 이름 글자 크기")
            .setView(android.widget.ScrollView(a).apply { addView(box) })
            .setPositiveButton("확인") { _, _ -> onPick(if (touched) px else current) }
            .setNeutralButton("기본으로") { _, _ -> onPick(0) }
            .setNegativeButton("취소", null).show()
    }

    fun labelSizeLabel(a: Activity): String {
        val px = LauncherApp.instance.prefs.labelPx
        return if (px == 0) "기본 (지금 ${IconView.defaultLabelPx(a)}px · 휴대폰 글꼴 크기를 따라가요)"
        else "${px}px · 홈 화면·폴더·앱 서랍의 앱 이름"
    }

    /** Frame for a photo icon. */
    fun photoStyle(a: Activity, onPick: (String) -> Unit) {
        AlertDialog.Builder(a).setTitle("사진 아이콘 모양")
            .setItems(arrayOf("아늑단 얼굴 틀에 넣기", "둥근 사각형 그대로")) { _, which ->
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

    const val CAT_LABEL = "아늑단 아이콘 (내장)"

    /** Credits for the generated cat icons' drawings (CC BY-SA 4.0 asks for the author, licence, source and changes). */
    fun lineIconsLicense(a: Activity) {
        val text = "아늑단 아이콘 중 손으로 그린 54종을 뺀 나머지는 아늑단 틀 안에 Arcticons 아이콘 그림을 바꿔 넣은 거예요.\n\n" +
            "원작: Arcticons — Donnnno와 기여자들\nhttps://github.com/Arcticons-Team/Arcticons\n\n" +
            "라이선스: CC BY-SA 4.0\nhttps://creativecommons.org/licenses/by-sa/4.0/\n\n" +
            "바꾼 점: SVG를 경로 데이터로 변환, 선 굵기·색 변경, 닫힌 모양을 흰색·하늘색으로 채움, 아늑단 틀 안에 배치.\n" +
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
        "wallpapers/cat_pattern.jpg" to "아늑단 패턴",
        "wallpapers/sticker_collage.jpg" to "스티커 모음",
        "wallpapers/cat_peek.jpg" to "빼꼼 아늑단",
    )

    fun wallpaper(a: Activity) {
        val box = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        val row = LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(a.dp(12f), a.dp(12f), a.dp(12f), a.dp(4f))
        }
        box.addView(row)
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
        // moving backgrounds: drawn by the launcher behind its home screen (see MovingWallpaper)
        val prefs = LauncherApp.instance.prefs
        box.addView(TextView(a).apply {
            text = "움직이는 배경 (지금: ${MovingWallpaper.label(a)})"
            textSize = 15f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(a.dp(20f), a.dp(14f), a.dp(20f), a.dp(2f))
        })
        box.addView(TextView(a).apply {
            text = if (AnukiWallpaperService.isActive(a)) "지금 휴대폰 배경화면(홈·잠금 화면)으로 쓰는 중이에요."
            else "기본은 아늑이 런처 홈 화면에서만 움직여요. 잠금 화면에도 쓰려면 아래 '휴대폰 배경화면으로 설정'을 누르세요."
            textSize = 12f
            alpha = 0.7f
            setPadding(a.dp(20f), 0, a.dp(20f), a.dp(4f))
        })
        fun option(label: String, enabled: Boolean = true, onClick: () -> Unit) = box.addView(TextView(a).apply {
            text = label
            textSize = 16f
            isEnabled = enabled
            alpha = if (enabled) 1f else 0.4f
            setPadding(a.dp(20f), a.dp(12f), a.dp(20f), a.dp(12f))
            setOnClickListener { dialog?.dismiss(); onClick() }
        })
        option("아늑단 패턴 흐르기") {
            MovingWallpaper.set(MovingWallpaper.PATTERN, a)
            Toast.makeText(a, "움직이는 배경을 켰어요. 홈 화면에서 보여요.", Toast.LENGTH_SHORT).show()
        }
        option("내 동영상·GIF 고르기") { MovingWallpaper.pick(a) }
        option("휴대폰 배경화면으로 설정 (잠금 화면 포함)") {
            if (prefs.movingBg.isEmpty()) MovingWallpaper.set(MovingWallpaper.PATTERN, a)
            Toast.makeText(a, "다음 화면에서 '배경화면 설정'을 누르고, 고를 수 있으면 '홈 화면 및 잠금 화면'을 고르세요", Toast.LENGTH_LONG).show()
            if (!AnukiWallpaperService.openSystemPreview(a)) Toast.makeText(a, "이 휴대폰에서는 배경화면 설정 화면을 열 수 없어요", Toast.LENGTH_SHORT).show()
        }
        option("움직이는 배경 끄기", enabled = prefs.movingBg.isNotEmpty()) {
            MovingWallpaper.set("", a)
            val msg = if (AnukiWallpaperService.isActive(a)) "움직임을 껐어요. 휴대폰 배경화면은 멈춘 아늑단 패턴으로 남아요." else "움직이는 배경을 껐어요"
            Toast.makeText(a, msg, Toast.LENGTH_LONG).show()
        }
        dialog = AlertDialog.Builder(a).setTitle("배경화면").setView(android.widget.ScrollView(a).apply { addView(box) })
            .setNeutralButton("다른 사진 고르기") { _, _ ->
                // a still picture only shows once the moving background is off
                if (prefs.movingBg.isNotEmpty()) MovingWallpaper.set("", a)
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
                    main.post {
                        // the still picture only shows once the moving background is off
                        if (ok && LauncherApp.instance.prefs.movingBg.isNotEmpty()) MovingWallpaper.set("", a)
                        Toast.makeText(a, if (ok) "배경화면을 바꿨어요" else "배경화면을 바꾸지 못했어요", Toast.LENGTH_SHORT).show()
                    }
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
