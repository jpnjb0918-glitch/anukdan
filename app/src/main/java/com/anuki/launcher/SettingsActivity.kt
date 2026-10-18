package com.anuki.launcher

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class SettingsActivity : Activity() {
    private val prefs: Prefs get() = LauncherApp.instance.prefs
    private val model: LayoutModel get() = LauncherApp.instance.model
    private lateinit var list: LinearLayout
    private var scrollY = 0
    private var scroll: ScrollView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "아늑이 런처 설정"
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun build() {
        scrollY = scroll?.scrollY ?: 0
        val sv = ScrollView(this)
        list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(0, 0, 0, dp(32f)) }
        sv.addView(list)
        setContentView(sv)
        scroll = sv

        header()

        section("기본")
        action("기본 홈 앱으로 설정", if (Util.isDefaultHome(this)) "지금 아늑이 런처가 기본 홈 앱이에요" else "아직 기본 홈 앱이 아니에요 — 눌러서 설정") {
            Util.requestDefaultHome(this, REQ_DEFAULT)
        }

        section("홈 화면")
        seek("격자 열 수", 3, 7, prefs.homeCols, { "${it}칸" }) { prefs.homeCols = it }
        seek("격자 행 수", 3, 8, prefs.homeRows, { "${it}칸" }) { prefs.homeRows = it }
        seek("독 아이콘 수", 3, 7, prefs.dockCount, { "${it}개" }) { prefs.dockCount = it }
        switch("독 배경", null, prefs.dockBackground) { prefs.dockBackground = it }
        switch("아이콘 이름 표시", null, prefs.homeLabels) { prefs.homeLabels = it }

        section("앱 서랍")
        seek("열 수", 3, 7, prefs.drawerCols, { "${it}칸" }) { prefs.drawerCols = it }
        switch("아이콘 이름 표시", null, prefs.drawerLabels) { prefs.drawerLabels = it }
        action("탭 관리", "${prefs.tabs().size}개 · 탭마다 앱을 골라 묶어요") { Dialogs.editTabs(this) { build() } }
        action("숨긴 앱", "${prefs.hidden.size}개 · 검색하면 숨긴 앱도 나와요") { Dialogs.hiddenApps(this) { build() } }

        section("모양")
        seek("아이콘 크기", 70, 130, prefs.iconScale, { "$it%" }) { prefs.iconScale = it }
        choice("테마 (앱 서랍·폴더)", listOf("auto" to "시스템 따라가기", "light" to "밝게", "dark" to "어둡게"), prefs.theme) { prefs.theme = it }
        action("아이콘 팩", Dialogs.iconPackLabel(this) + " · 설치한 다른 아이콘 팩도 고를 수 있어요") {
            Dialogs.iconPackChooser(this) { build() }
        }
        switch("모든 앱을 고양이 아이콘으로", "손으로 그린 고양이 아이콘(54종)이 없는 앱도 고양이 틀 아이콘으로 바꿔요 (15,322종, 없으면 원래 아이콘을 틀에 넣어요)", prefs.catPlate) { prefs.catPlate = it }
        note("앱 아이콘을 길게 눌러 '아이콘 바꾸기'로 앱마다 고양이 아이콘·사진·다른 아이콘 팩의 아이콘을 고를 수 있어요.")
        action("아늑이 런처 앱 아이콘", AppIcon.label(this)) {
            val opts = AppIcon.OPTIONS
            AlertDialog.Builder(this).setTitle("아늑이 런처 앱 아이콘")
                .setSingleChoiceItems(opts.map { it.second }.toTypedArray(), opts.indexOfFirst { it.first == AppIcon.current(this) }.coerceAtLeast(0)) { d, which ->
                    d.dismiss()
                    AppIcon.set(this, opts[which].first)
                    Toast.makeText(this, "앱 아이콘을 바꿨어요. 홈 화면에 반영되기까지 잠깐 걸릴 수 있어요.", Toast.LENGTH_LONG).show()
                    build()
                }.setNegativeButton("취소", null).show()
        }
        action("배경화면", "고양이 배경 3종 또는 내 사진") { Dialogs.wallpaper(this) }

        section("제스처")
        gesture("위로 쓸기", prefs.gestureUp, prefs.gestureUpApp) { a, k -> prefs.gestureUpApp = k; prefs.gestureUp = a }
        gesture("아래로 쓸기", prefs.gestureDown, prefs.gestureDownApp) { a, k -> prefs.gestureDownApp = k; prefs.gestureDown = a }
        gesture("두 번 탭", prefs.gestureDouble, prefs.gestureDoubleApp) { a, k -> prefs.gestureDoubleApp = k; prefs.gestureDouble = a }
        note("'알림창 내리기'는 기기에 따라 동작하지 않을 수 있어요. '화면 끄기'는 처음에 기기 관리자 권한을 한 번 허용해야 하고, 이렇게 끈 뒤에는 지문·얼굴 대신 PIN을 입력해야 할 수 있어요.")

        section("알림 배지")
        if (Badges.isSupported(this)) {
            choice("배지 모양", listOf("off" to "끄기", "dot" to "점", "count" to "숫자"), prefs.badgeStyle) { prefs.badgeStyle = it }
            action("알림 접근 권한", if (Badges.isAccessGranted(this)) "허용됨" else "허용 필요 — 눌러서 '아늑이 런처'를 켜 주세요") {
                try { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) } catch (_: Exception) { }
            }
        } else {
            note("이 설치 파일(기본판)에는 알림 배지가 없어요. 알림을 읽는 기능이 있는 앱은 Google Play 프로텍트가 설치를 막기 때문이에요. 배지가 필요하면 배지판(anuki-launcher-badge.apk)을 설치하세요.")
        }

        section("백업")
        action("백업 파일 만들기", "홈 화면 배치와 설정을 파일로 저장해요") {
            val name = "anuki-backup-" + SimpleDateFormat("yyyyMMdd-HHmm", Locale.KOREA).format(Date()) + ".json"
            val i = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType("application/json").putExtra(Intent.EXTRA_TITLE, name)
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_BACKUP)
        }
        action("백업에서 복원", "위젯은 다시 추가해야 해요") {
            val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            @Suppress("DEPRECATION") startActivityForResult(i, REQ_RESTORE)
        }
        action("홈 화면 초기화", "배치를 처음 상태로 되돌려요") {
            AlertDialog.Builder(this).setMessage("홈 화면 배치를 모두 지우고 처음 상태로 돌릴까요? (설정은 그대로예요)")
                .setPositiveButton("초기화") { _, _ -> resetLayout() }
                .setNegativeButton("취소", null).show()
        }

        section("정보")
        note("아늑이 런처 1.0 · 노바런처의 기능 구성을 참고해 만든 개인용 런처예요. AI 기능, 광고, 분석 SDK, 결제는 넣지 않았어요.")
        action("아이콘 그림 라이선스", "고양이 틀 안의 그림 15,322종은 Arcticons (CC BY-SA 4.0) 기반") { Dialogs.lineIconsLicense(this) }

        sv.post { sv.scrollTo(0, scrollY) }
    }

    // ------------------------------------------------------------ rows

    private fun header() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(20f), dp(20f), dp(8f))
        }
        val bmp = try { assets.open("icons/web-browser.png").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        row.addView(ImageView(this).apply { setImageBitmap(bmp) }, LinearLayout.LayoutParams(dp(56f), dp(56f)))
        row.addView(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14f), 0, 0, 0)
            addView(TextView(context).apply { text = "아늑이 런처"; textSize = 22f; setTypeface(typeface, Typeface.BOLD) })
            addView(TextView(context).apply { text = "고양이 아이콘 런처"; textSize = 13f; alpha = 0.7f })
        })
        list.addView(row)
    }

    private fun section(t: String) {
        list.addView(TextView(this).apply {
            text = t
            textSize = 13f
            setTextColor(Palette.TEAL)
            setTypeface(typeface, Typeface.BOLD)
            setPadding(dp(20f), dp(22f), dp(20f), dp(6f))
        })
    }

    private fun rowBase(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(20f), dp(12f), dp(20f), dp(12f))
        background = getDrawable(android.R.drawable.list_selector_background)
    }

    private fun titleView(t: String) = TextView(this).apply { text = t; textSize = 16f; setTextColor(0xFF1A2B2C.toInt()) }
    private fun subView(t: String) = TextView(this).apply { text = t; textSize = 13f; setTextColor(0xFF5B6E6F.toInt()) }

    private fun action(title: String, sub: String?, onClick: () -> Unit) {
        val r = rowBase()
        r.addView(titleView(title))
        if (sub != null) r.addView(subView(sub))
        r.isClickable = true
        r.setOnClickListener { onClick() }
        list.addView(r)
    }

    private fun note(t: String) {
        list.addView(TextView(this).apply {
            text = t; textSize = 12.5f; setTextColor(0xFF6E8182.toInt())
            setPadding(dp(20f), dp(4f), dp(20f), dp(8f))
        })
    }

    private fun switch(title: String, sub: String?, value: Boolean, onChange: (Boolean) -> Unit) {
        val r = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(20f), dp(10f), dp(16f), dp(10f))
        }
        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(titleView(title))
        if (sub != null) texts.addView(subView(sub))
        r.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val sw = Switch(this).apply { isChecked = value; setOnCheckedChangeListener { _, b -> onChange(b) } }
        r.addView(sw)
        r.setOnClickListener { sw.toggle() }
        list.addView(r)
    }

    private fun seek(title: String, min: Int, max: Int, value: Int, fmt: (Int) -> String, onChange: (Int) -> Unit) {
        val r = rowBase()
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        head.addView(titleView(title), LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        val valueView = TextView(this).apply { text = fmt(value); textSize = 15f; setTextColor(Palette.TEAL) }
        head.addView(valueView)
        r.addView(head)
        val sb = SeekBar(this).apply {
            this.min = min
            this.max = max
            progress = value
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, p: Int, fromUser: Boolean) { valueView.text = fmt(p) }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) { onChange(progress) }
            })
        }
        r.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            topMargin = dp(6f)
        })
        list.addView(r)
    }

    private fun choice(title: String, options: List<Pair<String, String>>, current: String, onPick: (String) -> Unit) {
        val label = options.firstOrNull { it.first == current }?.second ?: options[0].second
        action(title, label) {
            val idx = options.indexOfFirst { it.first == current }.coerceAtLeast(0)
            AlertDialog.Builder(this).setTitle(title)
                .setSingleChoiceItems(options.map { it.second }.toTypedArray(), idx) { d, which ->
                    d.dismiss(); onPick(options[which].first); build()
                }.show()
        }
    }

    private fun gesture(title: String, action: String, appKey: String, save: (String, String) -> Unit) {
        action(title, Dialogs.actionLabel(action, appKey)) {
            Dialogs.chooseAction(this, title, action) { a, k -> save(a, k); build() }
        }
    }

    // ------------------------------------------------------------ backup

    private fun resetLayout() {
        val host = AnukiWidgetHost(this)
        for (it in model.items) if (it.type == HomeItem.TYPE_WIDGET) try { host.deleteAppWidgetId(it.widgetId) } catch (_: Exception) { }
        model.items.clear()
        model.pageCount = 1
        model.save()
        prefs.firstRunDone = false
        prefs.bump()
        Toast.makeText(this, "홈 화면을 초기화했어요", Toast.LENGTH_SHORT).show()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            REQ_BACKUP -> {
                val ok = try {
                    val o = JSONObject().put("app", "anuki-launcher").put("version", 1)
                        .put("prefs", prefs.exportJson()).put("layout", model.toJson())
                        .put("customIcons", CustomIcons.exportJson(this))
                    contentResolver.openOutputStream(uri)?.use { it.write(o.toString(2).toByteArray()) }
                    true
                } catch (_: Exception) { false }
                Toast.makeText(this, if (ok) "백업 파일을 만들었어요" else "백업에 실패했어요", Toast.LENGTH_SHORT).show()
            }
            REQ_RESTORE -> {
                val ok = try {
                    val text = contentResolver.openInputStream(uri)?.use { it.readBytes().toString(Charsets.UTF_8) } ?: ""
                    val o = JSONObject(text)
                    require(o.optString("app") in setOf("anuki-launcher", "nyang-launcher"))
                    val host = AnukiWidgetHost(this)
                    for (it in model.items) if (it.type == HomeItem.TYPE_WIDGET) try { host.deleteAppWidgetId(it.widgetId) } catch (_: Exception) { }
                    o.optJSONObject("customIcons")?.let { CustomIcons.importJson(this, it) }
                    prefs.importJson(o.getJSONObject("prefs"))
                    model.fromJson(o.getJSONObject("layout"), skipWidgets = true)
                    model.save()
                    prefs.firstRunDone = true
                    prefs.bump()
                    true
                } catch (_: Exception) { false }
                Toast.makeText(this, if (ok) "복원했어요" else "아늑이 런처 백업 파일이 아니거나 읽을 수 없어요", Toast.LENGTH_SHORT).show()
                build()
            }
        }
    }

    companion object {
        const val REQ_DEFAULT = 1
        const val REQ_BACKUP = 2
        const val REQ_RESTORE = 3
    }
}
