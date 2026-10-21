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
        Updater.onResumed(this)
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
        seek("격자 열 수", 3, MAX_COLS, prefs.homeCols, { "${it}칸" }) { prefs.homeCols = it }
        seek("격자 행 수", 3, MAX_ROWS, prefs.homeRows, { "${it}칸" }) { prefs.homeRows = it }
        note("칸이 많아 아이콘이 들어가지 않으면 아이콘을 칸에 맞게 줄여요.")
        seek("독 아이콘 수", 3, 7, prefs.dockCount, { "${it}개" }) { prefs.dockCount = it }
        switch("독 배경", null, prefs.dockBackground) { prefs.dockBackground = it }
        switch("아이콘 이름 표시", null, prefs.homeLabels) { prefs.homeLabels = it }
        switch("홈 화면 배치 잠금", "아이콘·위젯을 옮기거나 지우거나 새로 놓지 못하게 막아요", prefs.lockLayout) { prefs.lockLayout = it }
        switch("새 앱을 홈 화면에 추가", "새로 설치한 앱의 아이콘을 홈 화면 빈자리에 놓아요", prefs.autoAddApps) { prefs.autoAddApps = it }
        switch("무한 스크롤", "마지막 페이지에서 넘기면 첫 페이지로 돌아가요", prefs.infiniteScroll) { prefs.infiniteScroll = it }
        switch("상태 표시줄 숨기기", "홈 화면에서 시계·배터리 줄을 숨겨요 (위 끝에서 쓸어내리면 잠깐 보여요)", prefs.hideStatusBar) { prefs.hideStatusBar = it }

        section("앱 서랍")
        choice("서랍 형식", listOf(
            "grid" to "세로 격자 (위아래로 넘기기)",
            "pages" to "가로 페이지 (좌우로 넘기기)",
            "list" to "목록 (아이콘 + 이름 한 줄씩)",
            "sections" to "가나다 묶음 (첫 글자별, 오른쪽 막대로 이동)",
            "categories" to "종류별 묶음 (게임·소셜·음악·사진 등 자동 분류)",
        ), prefs.drawerStyle) { prefs.drawerStyle = it }
        choice("정렬", listOf("name" to "이름순", "recent" to "최근 설치순", "usage" to "많이 연 순"), prefs.drawerSort) { prefs.drawerSort = it }
        choice("서랍 배경", listOf("solid" to "테마 색", "glass" to "반투명", "wallpaper" to "배경화면 보이게"), prefs.drawerBg) { prefs.drawerBg = it }
        seek("열 수", 3, 7, prefs.drawerCols, { "${it}칸" }) { prefs.drawerCols = it }
        switch("아이콘 이름 표시", "목록 형식에서는 항상 이름이 보여요", prefs.drawerLabels) { prefs.drawerLabels = it }
        note("'가나다 묶음'은 항상 이름순이에요. '많이 연 순'은 아늑이 런처에서 앱을 연 횟수로 정해져요(이번 버전부터 세기 시작해요).")
        action("탭 관리", "${prefs.tabs().size}개 · 탭마다 앱을 골라 묶어요") { Dialogs.editTabs(this) { build() } }
        action("숨긴 앱", "${prefs.hidden.size}개 · 검색하면 숨긴 앱도 나와요" + if (prefs.lockHidden) " (지문·PIN 확인 뒤)" else "") {
            HiddenLock.require(this) { Dialogs.hiddenApps(this) { build() } }
        }
        switch("숨긴 앱 잠금", "숨긴 앱 목록과 검색에 나오는 숨긴 앱을 휴대폰의 지문·얼굴·PIN으로 확인한 뒤에 보여줘요", prefs.lockHidden) { on ->
            if (on && !HiddenLock.deviceSecure(this)) {
                Toast.makeText(this, "먼저 휴대폰 설정에서 화면 잠금(PIN·패턴·비밀번호)을 설정해 주세요", Toast.LENGTH_LONG).show()
                build()
            } else if (on) {
                HiddenLock.relock()
                prefs.lockHidden = true
                LauncherApp.dlog("hidden apps lock on")
                build()
            } else {
                HiddenLock.require(this) { prefs.lockHidden = false; LauncherApp.dlog("hidden apps lock off"); build() }
                build()
            }
        }
        switch("연락처도 검색", "앱 서랍 검색창에서 연락처 이름도 찾아요 (처음 켤 때 연락처 읽기 권한을 물어요)", prefs.searchContacts) { on ->
            if (on && !SearchExtras.hasPermission(this)) {
                @Suppress("DEPRECATION") requestPermissions(arrayOf(android.Manifest.permission.READ_CONTACTS), REQ_CONTACTS)
            } else {
                prefs.searchContacts = on
                SearchExtras.forgetContacts()
            }
        }
        choice("인터넷 검색 엔진", WebSearch.ENGINES, prefs.searchEngine) { prefs.searchEngine = it }
        note("앱 서랍 검색창에 '12*3' 같은 계산식을 쓰면 답이 나오고, '10km'·'25평'·'30°C'처럼 단위를 붙이면 단위 변환이 나와요(눌러서 복사). 맨 아래 '웹에서 검색'으로 인터넷 검색도 할 수 있어요. 홈 화면에는 '아늑단 인터넷 검색' 위젯도 놓을 수 있어요.")

        section("모양")
        seek("아이콘 크기", 70, 130, prefs.iconScale, { "$it%" }) { prefs.iconScale = it }
        action("글꼴", Fonts.label(prefs.font) + " · 앱 이름·폴더·앱 서랍·아늑단 위젯") {
            Dialogs.fontPicker(this, prefs.font) { k ->
                prefs.font = k
                LauncherApp.dlog("font -> ${k.ifEmpty { "기본" }}")
                build()
            }
        }
        action("앱 이름 글자 크기", Dialogs.labelSizeLabel(this)) {
            Dialogs.labelSize(this, prefs.labelPx) { v ->
                prefs.labelPx = v
                LauncherApp.dlog("label size -> ${if (v == 0) "기본 (${IconView.defaultLabelPx(this)}px)" else "${v}px"}")
                build()
            }
        }
        choice("테마 (앱 서랍·폴더)", listOf("auto" to "시스템 따라가기", "light" to "밝게", "dark" to "어둡게"), prefs.theme) { prefs.theme = it }
        action("아이콘 팩", Dialogs.iconPackLabel(this) + " · 설치한 다른 아이콘 팩도 고를 수 있어요") {
            Dialogs.iconPackChooser(this) { build() }
        }
        action("아이콘 색", IconColors.name(prefs.iconColor) + " · 앱 하나만 바꾸려면 아이콘을 길게 눌러 '아이콘 바꾸기' → '색 바꾸기'") {
            Dialogs.iconColor(this, "아이콘 색", prefs.iconColor) { c ->
                prefs.iconColor = c ?: IconColors.DEFAULT
                LauncherApp.instance.icons.invalidate()
                build()
            }
        }
        switch("모든 앱을 아늑단 아이콘으로", "손으로 그린 아늑단 아이콘(54종)이 없는 앱도 아늑단 틀 아이콘으로 바꿔요 (15,322종, 없으면 원래 아이콘을 틀에 넣어요)", prefs.catPlate) { prefs.catPlate = it }
        note("앱 아이콘을 길게 눌러 '아이콘 바꾸기'로 앱마다 아늑단 아이콘·사진·다른 아이콘 팩의 아이콘을 고를 수 있어요.")
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
        action("배경화면", "아늑단 배경 3종 또는 내 사진 · 움직이는 배경: ${MovingWallpaper.label(this)}") { Dialogs.wallpaper(this) }

        section("제스처")
        gesture("위로 쓸기", prefs.gestureUp, prefs.gestureUpApp) { a, k -> prefs.gestureUpApp = k; prefs.gestureUp = a }
        gesture("아래로 쓸기", prefs.gestureDown, prefs.gestureDownApp) { a, k -> prefs.gestureDownApp = k; prefs.gestureDown = a }
        gesture("두 번 탭", prefs.gestureDouble, prefs.gestureDoubleApp) { a, k -> prefs.gestureDoubleApp = k; prefs.gestureDouble = a }
        gesture("두 손가락 오므리기", prefs.gesturePinchIn, prefs.gesturePinchInApp) { a, k -> prefs.gesturePinchInApp = k; prefs.gesturePinchIn = a }
        gesture("두 손가락 벌리기", prefs.gesturePinchOut, prefs.gesturePinchOutApp) { a, k -> prefs.gesturePinchOutApp = k; prefs.gesturePinchOut = a }
        note("아이콘 하나만 따로: 홈 화면 아이콘을 길게 눌러 '위로 쓸면'에서 그 아이콘을 위로 쓸 때 열 앱을 고를 수 있어요. 폴더는 '커버 폴더로 쓰기'를 켜면 탭하면 첫 앱, 위로 쓸면 폴더가 열려요.")
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
        note("아늑이 런처 v${Updater.currentName(this)} · 개인용 런처예요. AI 기능, 광고, 분석 SDK, 결제는 넣지 않았어요.")
        action("업데이트 확인", "지금 v${Updater.currentName(this)} · 새 버전이 있으면 기본판·배지판 중 골라 받아요") { Updater.checkNow(this) }
        switch("켤 때 새 버전 알림", "하루에 한 번 확인해요 · 업데이트 질문에서 '아니요'를 누르면 꺼져요", prefs.updateCheck) { prefs.updateCheck = it }
        action("아이콘 그림 라이선스", "아늑단 틀 안의 그림 15,322종은 Arcticons (CC BY-SA 4.0) 기반") { Dialogs.lineIconsLicense(this) }
        action("날씨 정보", "아늑단 날씨 위젯: Open-Meteo.com (CC BY 4.0) · 지역을 고를 때만 인터넷·위치를 써요") {
            try { startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://open-meteo.com/"))) } catch (_: Exception) { }
        }
        action("글꼴 라이선스", "주아·개구·감자꽃·하이멜로디·나눔손글씨 펜·고운돋움 (SIL Open Font License)") { Dialogs.fontLicense(this) }

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
            addView(TextView(context).apply { text = "아늑단 아이콘 런처"; textSize = 13f; alpha = 0.7f })
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
    override fun onPause() {
        super.onPause()
        Updater.onPaused(this)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_CONTACTS) {
            val ok = grantResults.isNotEmpty() && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED
            prefs.searchContacts = ok
            SearchExtras.forgetContacts()
            LauncherApp.dlog("contacts search ${if (ok) "on" else "refused"}")
            if (!ok) Toast.makeText(this, "연락처 권한이 없어서 연락처 검색을 켜지 않았어요", Toast.LENGTH_SHORT).show()
            build()
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == HiddenLock.REQ_CONFIRM) { HiddenLock.onResult(resultCode == RESULT_OK); return }
        val uri = data?.data
        if (resultCode != RESULT_OK || uri == null) return
        when (requestCode) {
            MovingWallpaper.REQ_PICK -> if (resultCode == RESULT_OK) data?.data?.let { MovingWallpaper.onPicked(this, it) }
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
        const val REQ_CONTACTS = 4
        const val MAX_COLS = 10
        const val MAX_ROWS = 12
    }
}
