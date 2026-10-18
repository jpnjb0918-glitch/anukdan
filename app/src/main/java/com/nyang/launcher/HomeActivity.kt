package com.nyang.launcher

import android.annotation.SuppressLint
import android.app.Activity
import android.app.ActivityOptions
import android.app.AlertDialog
import android.app.admin.DevicePolicyManager
import android.app.role.RoleManager
import android.appwidget.AppWidgetManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.LauncherApps
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.UserHandle
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.Toast
import kotlin.math.abs
import kotlin.math.ceil

class HomeActivity : Activity(), PagedView.Listener {
    private val app: LauncherApp get() = application as LauncherApp
    val prefs: Prefs get() = app.prefs
    val model: LayoutModel get() = app.model
    val apps: AppRepository get() = app.apps
    val icons: IconProvider get() = app.icons

    lateinit var dragLayer: DragLayer
        private set
    lateinit var workspace: PagedView
        private set
    lateinit var dock: CellLayout
        private set
    lateinit var dropBar: DropBar
        private set
    private lateinit var column: LinearLayout
    private lateinit var indicator: PageIndicator
    private lateinit var dockFrame: SwipeFrame
    private lateinit var drawer: AppDrawerView
    private lateinit var folderView: FolderView
    private lateinit var drag: DragController
    private lateinit var widgetHost: NyangWidgetHost
    private lateinit var widgetManager: AppWidgetManager

    var iconSize = 0
        private set
    private var boundVersion = -1
    private var insetTop = 0
    private var insetBottom = 0
    private var popup: View? = null
    private var pendingWidgetId = -1

    private val appsListener: () -> Unit = { onAppsChanged() }
    private val badgeListener: () -> Unit = { updateBadges() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setupWindow()
        widgetManager = AppWidgetManager.getInstance(this)
        widgetHost = NyangWidgetHost(this)
        iconSize = (dp(54f) * prefs.iconScale / 100f).toInt()
        buildViews()
        setContentView(dragLayer)
        apps.addListener(appsListener)
        Badges.listeners += badgeListener
        if (apps.loaded) onAppsChanged() // otherwise the listener fires when loading finishes
    }

    override fun onDestroy() {
        apps.removeListener(appsListener)
        Badges.listeners -= badgeListener
        super.onDestroy()
    }

    override fun onStart() {
        super.onStart()
        try { widgetHost.startListening() } catch (_: Exception) { }
    }

    override fun onStop() {
        super.onStop()
        try { widgetHost.stopListening() } catch (_: Exception) { }
    }

    override fun onResume() {
        super.onResume()
        if (apps.loaded && prefs.version != boundVersion) {
            icons.invalidate()
            bindAll()
        }
    }

    // ------------------------------------------------------------ window & views

    private fun setupWindow() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 30) {
            window.setDecorFitsSystemWindows(false)
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
        }
        if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = false
    }

    private fun buildViews() {
        dragLayer = DragLayer(this)
        drag = DragController(this)
        dragLayer.controller = drag

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        workspace = PagedView(this).apply { listener = this@HomeActivity }
        column.addView(workspace, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        indicator = PageIndicator(this)
        column.addView(indicator, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(18f)))
        dock = CellLayout(this).apply { container = HomeItem.DOCK; rows = 1; cols = prefs.dockCount }
        dockFrame = SwipeFrame(this) { runAction(prefs.gestureUp, prefs.gestureUpApp) }
        dockFrame.addView(dock, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        column.addView(dockFrame, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, iconSize + dp(28f)).apply {
            setMargins(dp(12f), dp(4f), dp(12f), dp(10f))
        })
        dragLayer.addView(column, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))

        drawer = AppDrawerView(this)
        dragLayer.addView(drawer, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        folderView = FolderView(this)
        dragLayer.addView(folderView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        dropBar = DropBar(this)
        dragLayer.addView(dropBar, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP).apply {
            setMargins(dp(8f), dp(28f), dp(8f), 0)
        })

        dragLayer.setOnApplyWindowInsetsListener { _, ins ->
            @Suppress("DEPRECATION") val top = ins.systemWindowInsetTop
            @Suppress("DEPRECATION") val bottom = ins.systemWindowInsetBottom
            insetTop = top; insetBottom = bottom
            column.setPadding(0, top, 0, bottom)
            drawer.setInsets(top, bottom)
            (dropBar.layoutParams as FrameLayout.LayoutParams).topMargin = top + dp(4f)
            dropBar.requestLayout()
            ins
        }
        dragLayer.requestApplyInsets()
    }

    fun dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

    private fun toast(t: String) = Toast.makeText(this, t, Toast.LENGTH_SHORT).show()

    // ------------------------------------------------------------ binding

    private fun onAppsChanged() {
        // remove icons of uninstalled apps
        if (apps.removedPackages.isNotEmpty()) {
            val gone = model.items.filter {
                it.type == HomeItem.TYPE_APP && apps.find(it.key) == null &&
                    it.key.substringBefore('/') in apps.removedPackages
            }
            for (g in gone) model.removeItem(g)
            if (gone.isNotEmpty()) model.save()
            apps.removedPackages.clear()
        }
        if (!prefs.firstRunDone && model.items.isEmpty()) askDefaultHomeOnce()
        icons.preload(apps.all, iconSize)
        bindAll()
    }

    private fun populateDefaults() {
        var x = 0
        for (icon in listOf("phone", "chat", "web-browser", "camera")) {
            val a = icons.packageForIcon(icon)?.let { apps.firstOfPackage(it) } ?: continue
            model.items += HomeItem(model.newId(), HomeItem.TYPE_APP, HomeItem.DOCK, 0, x++, 0, key = a.key, title = a.label)
        }
        var col = 0
        val row = prefs.homeRows - 1
        for (icon in listOf("photos", "store", "settings", "clock", "calendar")) {
            if (col >= prefs.homeCols) break
            val a = icons.packageForIcon(icon)?.let { apps.firstOfPackage(it) } ?: continue
            model.items += HomeItem(model.newId(), HomeItem.TYPE_APP, HomeItem.DESKTOP, 0, col++, row, key = a.key, title = a.label)
        }
        prefs.firstRunDone = true
        model.save()
    }

    private fun askDefaultHomeOnce() {
        if (Util.isDefaultHome(this)) return
        AlertDialog.Builder(this)
            .setTitle("냥런처를 기본 홈 앱으로 쓸까요?")
            .setMessage("홈 버튼을 눌렀을 때 냥런처가 열리게 돼요. 나중에 설정에서도 바꿀 수 있어요.")
            .setPositiveButton("설정하기") { _, _ -> Util.requestDefaultHome(this, REQ_DEFAULT_HOME) }
            .setNegativeButton("나중에", null)
            .show()
    }

    fun rebindAll() = bindAll()

    private fun newPage(i: Int) = CellLayout(this).apply {
        cols = prefs.homeCols; rows = prefs.homeRows
        container = HomeItem.DESKTOP; screen = i
        setPadding(dp(6f), dp(6f), dp(6f), dp(2f))
    }

    private fun bindAll() {
        if (!apps.loaded) return
        if (!prefs.firstRunDone && model.items.isEmpty()) populateDefaults()
        boundVersion = prefs.version
        iconSize = (dp(54f) * prefs.iconScale / 100f).toInt()
        model.normalize(prefs.homeCols, prefs.homeRows, prefs.dockCount)

        val cur = workspace.currentPage
        workspace.removeAllViews()
        for (i in 0 until model.pageCount) workspace.addView(newPage(i))

        dock.removeAllViews()
        dock.cols = prefs.dockCount
        dock.rows = 1
        dockFrame.background = if (prefs.dockBackground) Palette.rounded(0x38FFFFFF, dp(28f).toFloat()) else null
        (dockFrame.layoutParams as LinearLayout.LayoutParams).height = iconSize + dp(28f)
        dockFrame.requestLayout()

        val homeLabels = prefs.homeLabels
        for (item in model.items) {
            if (item.container >= 0) continue // lives inside a folder
            val onDesktop = item.container == HomeItem.DESKTOP
            val v: View = when (item.type) {
                HomeItem.TYPE_APP -> apps.find(item.key)?.let { makeAppIcon(it, item, sourceOf(item), onDesktop && homeLabels, false) }
                HomeItem.TYPE_FOLDER -> makeFolderIcon(item, onDesktop && homeLabels)
                HomeItem.TYPE_WIDGET -> if (onDesktop) makeWidgetView(item) else null
                else -> null
            } ?: continue
            if (onDesktop) {
                if (item.screen < workspace.pageCount) {
                    workspace.page(item.screen).addView(v, CellLayout.LP(item.x, item.y, item.spanX, item.spanY))
                }
            } else if (item.container == HomeItem.DOCK) {
                dock.addView(v, CellLayout.LP(item.x, 0, 1, 1))
            }
        }
        workspace.snapToPage(cur.coerceAtMost(model.pageCount - 1), false)
        indicator.count = model.pageCount
        indicator.current = workspace.currentPage
        if (drawer.isOpen) { drawer.applyTheme(); drawer.refresh() }
        folderView.folder?.let { f -> if (model.byId(f.id) != null) folderView.refresh() else folderView.close() }
    }

    fun workspaceAllPages(f: (CellLayout) -> Unit) {
        for (i in 0 until workspace.pageCount) f(workspace.page(i))
    }

    fun addEmptyPage() {
        model.pageCount += 1
        workspace.addView(newPage(model.pageCount - 1))
        indicator.count = model.pageCount
    }

    private fun sourceOf(item: HomeItem) = when {
        item.container == HomeItem.DOCK -> DragInfo.SRC_DOCK
        item.container >= 0 -> DragInfo.SRC_FOLDER
        else -> DragInfo.SRC_WORKSPACE
    }

    fun makeAppIcon(app: AppEntry, item: HomeItem?, source: Int, labels: Boolean, onPanel: Boolean): IconView {
        val v = IconView(this)
        bindAppIcon(v, app, item, source, labels, onPanel)
        return v
    }

    fun bindAppIcon(v: IconView, app: AppEntry, item: HomeItem?, source: Int, labels: Boolean, onPanel: Boolean) {
        v.app = app
        v.item = item
        v.tag = item
        v.iconSize = iconSize
        v.icon = icons.appIcon(app, iconSize)
        v.label = app.label
        v.showLabel = labels
        v.labelColor = if (onPanel) Palette.text(this) else Color.WHITE
        v.labelShadow = !onPanel
        v.badgeStyle = prefs.badgeStyle
        v.badge = Badges.countFor(app.packageName, app.serial)
        v.setOnClickListener { launch(app, v) }
        v.setOnLongClickListener { onItemLongPress(v, item, app, source, v.lastRawX, v.lastRawY); true }
        v.requestLayout()
    }

    private fun makeFolderIcon(item: HomeItem, labels: Boolean): IconView {
        val contents = model.folderContents(item.id).mapNotNull { apps.find(it.key) }
        val v = IconView(this)
        v.item = item
        v.tag = item
        v.iconSize = iconSize
        v.icon = icons.folderIcon(item.color, contents, iconSize)
        v.label = item.title
        v.showLabel = labels
        v.badgeStyle = prefs.badgeStyle
        v.badge = contents.sumOf { Badges.countFor(it.packageName, it.serial) }
        v.setOnClickListener { openFolder(item) }
        v.setOnLongClickListener { onItemLongPress(v, item, null, sourceOf(item), v.lastRawX, v.lastRawY); true }
        return v
    }

    private fun makeWidgetView(item: HomeItem): View? {
        val info = try { widgetManager.getAppWidgetInfo(item.widgetId) } catch (_: Exception) { null } ?: return null
        val hv = widgetHost.createView(this, item.widgetId, info) as? NyangWidgetView ?: return null
        hv.item = item
        hv.tag = item
        hv.onLongPress = { w -> onItemLongPress(w, item, null, DragInfo.SRC_WORKSPACE, w.lastRawX, w.lastRawY) }
        hv.addOnLayoutChangeListener { v, l, t, r, b, ol, ot, orr, ob ->
            if (r - l != orr - ol || b - t != ob - ot) {
                val d = resources.displayMetrics.density
                val w = ((r - l) / d).toInt(); val h = ((b - t) / d).toInt()
                @Suppress("DEPRECATION")
                try { (v as NyangWidgetView).updateAppWidgetSize(null, w, h, w, h) } catch (_: Exception) { }
            }
        }
        return hv
    }

    private fun updateBadges() {
        val style = prefs.badgeStyle
        fun walk(g: ViewGroup) {
            for (i in 0 until g.childCount) {
                val c = g.getChildAt(i)
                if (c is IconView) {
                    c.badgeStyle = style
                    val a = c.app
                    val item = c.item
                    c.badge = when {
                        a != null -> Badges.countFor(a.packageName, a.serial)
                        item != null && item.type == HomeItem.TYPE_FOLDER -> model.folderContents(item.id)
                            .mapNotNull { apps.find(it.key) }.sumOf { Badges.countFor(it.packageName, it.serial) }
                        else -> 0
                    }
                    c.invalidate()
                } else if (c is ViewGroup && c !is NyangWidgetView) walk(c)
            }
        }
        walk(dragLayer)
    }

    // ------------------------------------------------------------ actions

    fun launch(app: AppEntry, v: View) {
        try {
            val loc = IntArray(2)
            v.getLocationOnScreen(loc)
            val bounds = Rect(loc[0], loc[1], loc[0] + v.width, loc[1] + v.height)
            val opts = ActivityOptions.makeScaleUpAnimation(v, 0, 0, v.width, v.height).toBundle()
            apps.launcherApps.startMainActivity(app.component, app.user, bounds, opts)
        } catch (_: Exception) {
            toast("앱을 열 수 없어요")
        }
    }

    private fun appInfo(app: AppEntry) {
        try { apps.launcherApps.startAppDetailsActivity(app.component, app.user, null, null) } catch (_: Exception) { }
    }

    private fun uninstall(app: AppEntry) {
        try {
            startActivity(Intent(Intent.ACTION_DELETE, Uri.fromParts("package", app.packageName, null)))
        } catch (_: Exception) { toast("설치 삭제 화면을 열 수 없어요") }
    }

    private fun addToHome(app: AppEntry) {
        val s = model.findSpotAnywhere(prefs.homeCols, prefs.homeRows, 1, 1, workspace.currentPage)
        model.items += HomeItem(model.newId(), HomeItem.TYPE_APP, HomeItem.DESKTOP, s[0], s[1], s[2], key = app.key, title = app.label)
        model.save()
        bindAll()
        toast("홈 화면에 추가했어요")
    }

    private fun removeItem(item: HomeItem) {
        removeItemNoSave(item)
        model.save()
        bindAll()
    }

    private fun removeItemNoSave(item: HomeItem) {
        if (item.type == HomeItem.TYPE_WIDGET) try { widgetHost.deleteAppWidgetId(item.widgetId) } catch (_: Exception) { }
        model.removeItem(item)
    }

    fun openDrawer(focusSearch: Boolean) {
        closePopup(); closeFolder()
        drawer.open(focusSearch)
    }

    fun closeDrawer(animate: Boolean) = drawer.close(animate)

    private fun openFolder(item: HomeItem) {
        closePopup()
        folderView.open(item)
    }

    fun closeFolder() {
        val was = folderView.folder
        folderView.close()
        if (was != null) {
            // title may have changed
            workspaceAllPages { page ->
                for (i in 0 until page.childCount) {
                    val v = page.getChildAt(i)
                    if (v is IconView && v.item === was) v.label = was.title
                }
            }
        }
    }

    private fun runAction(action: String, appKey: String) {
        when (action) {
            "drawer" -> openDrawer(false)
            "search" -> openDrawer(true)
            "notifications" -> expandNotifications()
            "screen_off" -> lockScreen()
            "widgets" -> pickWidget()
            "settings" -> startActivity(Intent(this, SettingsActivity::class.java))
            "app" -> apps.find(appKey)?.let { launch(it, workspace) } ?: toast("설정된 앱을 찾을 수 없어요")
        }
    }

    @SuppressLint("WrongConstant", "PrivateApi")
    private fun expandNotifications() {
        try {
            val sbm = getSystemService("statusbar")
            Class.forName("android.app.StatusBarManager").getMethod("expandNotificationsPanel").invoke(sbm)
        } catch (_: Throwable) {
            toast("이 기기에서는 알림창 열기를 지원하지 않아요")
        }
    }

    private fun lockScreen() {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(this, LockAdminReceiver::class.java)
        if (dpm.isAdminActive(admin)) {
            dpm.lockNow()
        } else {
            val i = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN)
                .putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, admin)
                .putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "제스처로 화면을 끄려면 '화면 잠금' 권한이 필요해요.")
            try { startActivity(i) } catch (_: Exception) { toast("권한 화면을 열 수 없어요") }
        }
    }

    // ------------------------------------------------------------ PagedView.Listener

    override fun onPageChanged(page: Int) { indicator.current = page }
    override fun onSwipeUp() = runAction(prefs.gestureUp, prefs.gestureUpApp)
    override fun onSwipeDown() = runAction(prefs.gestureDown, prefs.gestureDownApp)
    override fun onDoubleTap() = runAction(prefs.gestureDouble, prefs.gestureDoubleApp)

    override fun onEmptyLongPress(rawX: Float, rawY: Float) {
        val entries = ArrayList<MenuEntry>()
        entries += MenuEntry("위젯 추가") { pickWidget() }
        entries += MenuEntry("배경화면") { Dialogs.wallpaper(this) }
        val cur = workspace.currentPage
        if (model.pageCount > 1 && model.items.none { it.container == HomeItem.DESKTOP && it.screen == cur }) {
            entries += MenuEntry("이 페이지 삭제") { model.deletePage(cur); model.save(); bindAll() }
        }
        entries += MenuEntry("런처 설정") { startActivity(Intent(this, SettingsActivity::class.java)) }
        dragLayer.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS)
        showPopupAt(Rect(rawX.toInt(), rawY.toInt(), rawX.toInt(), rawY.toInt()), entries)
    }

    // ------------------------------------------------------------ long press / popup / drag

    private fun onItemLongPress(view: View, item: HomeItem?, app: AppEntry?, source: Int, rawX: Float, rawY: Float) {
        Log.d(TAG, "longPress source=$source item=${item?.id} app=${app?.label} active=${drag.isActive}")
        if (drag.isActive) return
        val entryApp = app ?: item?.takeIf { it.type == HomeItem.TYPE_APP }?.let { apps.find(it.key) }
        drag.startPending(DragInfo(source, item, entryApp, view), rawX, rawY)
        showPopup(view, menuFor(item, entryApp, source))
    }

    private fun menuFor(item: HomeItem?, app: AppEntry?, source: Int): List<MenuEntry> {
        val list = ArrayList<MenuEntry>()
        when {
            item?.type == HomeItem.TYPE_WIDGET -> {
                list += MenuEntry("크기 조절") { Dialogs.widgetResize(this, item) { model.save(); bindAll() } }
                list += MenuEntry("위젯 삭제") { removeItem(item) }
            }
            item?.type == HomeItem.TYPE_FOLDER -> {
                list += MenuEntry("이름 바꾸기") {
                    Dialogs.askText(this, "폴더 이름", item.title) { item.title = it; model.save(); bindAll() }
                }
                list += MenuEntry("폴더 색") { Dialogs.folderColor(this, item) { model.save(); bindAll() } }
                list += MenuEntry("폴더 삭제") {
                    AlertDialog.Builder(this).setMessage("'${item.title}' 폴더와 안의 바로가기를 홈에서 지울까요? (앱은 지워지지 않아요)")
                        .setPositiveButton("삭제") { _, _ -> removeItem(item) }
                        .setNegativeButton("취소", null).show()
                }
            }
            app != null -> {
                list += shortcuts(app)
                list += MenuEntry("앱 정보") { appInfo(app) }
                list += MenuEntry("아이콘 바꾸기") { Dialogs.iconPicker(this, app) { bindAll() } }
                if (source == DragInfo.SRC_DRAWER) {
                    list += MenuEntry("홈 화면에 추가") { addToHome(app) }
                    list += MenuEntry("앱 서랍에서 숨기기") { prefs.hidden = prefs.hidden + app.key; drawer.refresh() }
                } else if (item != null) {
                    list += MenuEntry("홈에서 제거") { removeItem(item) }
                }
                if (!app.isSystem) list += MenuEntry("설치 삭제") { uninstall(app) }
            }
        }
        return list
    }

    private fun shortcuts(app: AppEntry): List<MenuEntry> {
        val la = apps.launcherApps
        return try {
            if (!la.hasShortcutHostPermission()) return emptyList()
            val q = LauncherApps.ShortcutQuery()
                .setPackage(app.packageName)
                .setActivity(app.component)
                .setQueryFlags(LauncherApps.ShortcutQuery.FLAG_MATCH_DYNAMIC or LauncherApps.ShortcutQuery.FLAG_MATCH_MANIFEST)
            val found = la.getShortcuts(q, app.user) ?: emptyList()
            found.filter { it.isEnabled }.sortedBy { it.rank }.take(4).map { s ->
                val d = try { la.getShortcutIconDrawable(s, resources.displayMetrics.densityDpi) } catch (_: Exception) { null }
                MenuEntry(s.shortLabel?.toString() ?: s.id, d) {
                    try { la.startShortcut(s.`package`, s.id, null, null, s.userHandle) } catch (_: Exception) { toast("바로가기를 열 수 없어요") }
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun showPopup(anchor: View, entries: List<MenuEntry>) {
        val loc = IntArray(2)
        anchor.getLocationOnScreen(loc)
        showPopupAt(Rect(loc[0], loc[1], loc[0] + anchor.width, loc[1] + anchor.height), entries)
    }

    private fun showPopupAt(rect: Rect, entries: List<MenuEntry>) {
        closePopup()
        if (entries.isEmpty()) return
        val holder = FrameLayout(this)
        val scrim = View(this)
        scrim.setOnTouchListener { _, e -> if (e.actionMasked == MotionEvent.ACTION_DOWN) closePopup(); true }
        holder.addView(scrim, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val card = PopupCard(this, entries) { closePopup() }
        card.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val rootLoc = IntArray(2)
        dragLayer.getLocationOnScreen(rootLoc)
        val cw = card.measuredWidth
        val ch = card.measuredHeight
        val maxX = maxOf(dp(8f), dragLayer.width - cw - dp(8f))
        val x = (rect.centerX() - cw / 2 - rootLoc[0]).coerceIn(dp(8f), maxX)
        var y = rect.top - ch - dp(8f) - rootLoc[1]
        if (y < insetTop + dp(8f)) y = rect.bottom + dp(8f) - rootLoc[1]
        y = y.coerceIn(insetTop + dp(8f), maxOf(insetTop + dp(8f), dragLayer.height - ch - insetBottom - dp(8f)))
        holder.addView(card, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = x; topMargin = y
        })
        dragLayer.addView(holder, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        popup = holder
        Log.d(TAG, "showPopup entries=${entries.size} at $x,$y size=${cw}x$ch")
        card.alpha = 0f; card.scaleX = 0.92f; card.scaleY = 0.92f
        card.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(140).start()
    }

    fun closePopup() {
        if (popup != null) Log.d(TAG, "closePopup", Exception("closed from"))
        popup?.let { dragLayer.removeView(it) }
        popup = null
    }

    fun showDropBar(info: DragInfo) {
        val a = info.app
        val kinds = ArrayList<Int>()
        if (info.source != DragInfo.SRC_DRAWER) kinds += DropBar.REMOVE
        if (a != null) {
            kinds += DropBar.INFO
            if (!a.isSystem) kinds += DropBar.UNINSTALL
        }
        dropBar.setup(kinds)
        dropBar.visibility = View.VISIBLE
        dropBar.alpha = 0f
        dropBar.animate().alpha(1f).setDuration(120).start()
    }

    fun hideDropBar() {
        dropBar.visibility = View.GONE
    }

    fun onDropBar(kind: Int, info: DragInfo) {
        when (kind) {
            DropBar.REMOVE -> info.item?.let { removeItemNoSave(it) }
            DropBar.INFO -> info.app?.let { appInfo(it) }
            DropBar.UNINSTALL -> info.app?.let { uninstall(it) }
        }
    }

    // ------------------------------------------------------------ widgets

    private fun pickWidget() {
        closePopup()
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(this, WidgetPickerActivity::class.java), REQ_PICK_WIDGET)
    }

    private fun bindWidget(provider: ComponentName, user: UserHandle) {
        val id = widgetHost.allocateAppWidgetId()
        pendingWidgetId = id
        val ok = try { widgetManager.bindAppWidgetIdIfAllowed(id, user, provider, null) } catch (_: Exception) { false }
        if (ok) {
            configureOrPlace(id)
        } else {
            val i = Intent(AppWidgetManager.ACTION_APPWIDGET_BIND)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER, provider)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_PROVIDER_PROFILE, user)
            @Suppress("DEPRECATION")
            startActivityForResult(i, REQ_BIND_WIDGET)
        }
    }

    private fun configureOrPlace(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id)
        if (info?.configure != null) {
            try {
                widgetHost.startAppWidgetConfigureActivityForResult(this, id, 0, REQ_CONFIG_WIDGET, null)
                return
            } catch (_: Exception) { }
        }
        placeWidget(id)
    }

    private fun placeWidget(id: Int) {
        val info = widgetManager.getAppWidgetInfo(id) ?: run { widgetHost.deleteAppWidgetId(id); return }
        val cols = prefs.homeCols
        val rows = prefs.homeRows
        val cur = workspace.currentPage.coerceIn(0, maxOf(0, workspace.pageCount - 1))
        val cw = if (workspace.pageCount > 0 && workspace.page(cur).width > 0) workspace.page(cur).cellW else maxOf(1, workspace.width / cols)
        val ch = if (workspace.pageCount > 0 && workspace.page(cur).height > 0) workspace.page(cur).cellH else maxOf(1, workspace.height / rows)
        val sx = ceil(info.minWidth.toDouble() / cw).toInt().coerceIn(1, cols)
        val sy = ceil(info.minHeight.toDouble() / ch).toInt().coerceIn(1, rows)
        val v = model.findVacant(HomeItem.DESKTOP, cur, cols, rows, sx, sy)
        val spot = if (v != null) intArrayOf(cur, v[0], v[1]) else model.findSpotAnywhere(cols, rows, sx, sy, cur)
        model.items += HomeItem(
            model.newId(), HomeItem.TYPE_WIDGET, HomeItem.DESKTOP, spot[0], spot[1], spot[2], sx, sy,
            widgetId = id, provider = info.provider.flattenToString(),
        )
        model.save()
        bindAll()
        workspace.snapToPage(spot[0])
        pendingWidgetId = -1
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            REQ_PICK_WIDGET -> if (resultCode == RESULT_OK && data != null) {
                @Suppress("DEPRECATION") val provider = data.getParcelableExtra<ComponentName>("provider")
                @Suppress("DEPRECATION") val user = data.getParcelableExtra<UserHandle>("user")
                if (provider != null && user != null) bindWidget(provider, user)
            }
            REQ_BIND_WIDGET -> if (resultCode == RESULT_OK && pendingWidgetId != -1) configureOrPlace(pendingWidgetId)
            else if (pendingWidgetId != -1) { widgetHost.deleteAppWidgetId(pendingWidgetId); pendingWidgetId = -1 }
            REQ_CONFIG_WIDGET -> if (resultCode == RESULT_OK && pendingWidgetId != -1) placeWidget(pendingWidgetId)
            else if (pendingWidgetId != -1) { widgetHost.deleteAppWidgetId(pendingWidgetId); pendingWidgetId = -1 }
        }
    }

    // ------------------------------------------------------------ system keys

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        when {
            popup != null -> closePopup()
            folderView.visibility == View.VISIBLE -> closeFolder()
            drawer.isOpen -> closeDrawer(true)
            else -> workspace.snapToPage(0)
        }
    }

    override fun onNewIntent(intent: Intent?) {
        super.onNewIntent(intent)
        val wasFocused = hasWindowFocus()
        val hadOverlay = popup != null || folderView.visibility == View.VISIBLE || drawer.isOpen
        closePopup()
        closeFolder()
        closeDrawer(wasFocused)
        if (wasFocused && !hadOverlay) workspace.snapToPage(0)
    }

    companion object {
        const val TAG = "Nyang"
        const val REQ_PICK_WIDGET = 11
        const val REQ_BIND_WIDGET = 12
        const val REQ_CONFIG_WIDGET = 13
        const val REQ_DEFAULT_HOME = 14
    }
}

/** Dock wrapper: swiping up on the dock opens the drawer too. */
class SwipeFrame(ctx: Context, private val onSwipeUp: () -> Unit) : FrameLayout(ctx) {
    private var downX = 0f
    private var downY = 0f
    private var swiping = false
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = downY - ev.rawY
                if (dy > slop * 2 && dy > abs(ev.rawX - downX)) { swiping = true; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = downY - event.rawY
                if (dy > slop * 2 && dy > abs(event.rawX - downX)) swiping = true
            }
            MotionEvent.ACTION_UP -> { if (swiping) onSwipeUp(); swiping = false }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return true
    }
}

object Util {
    fun isDefaultHome(ctx: Context): Boolean {
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val ri = ctx.packageManager.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)
        return ri?.activityInfo?.packageName == ctx.packageName
    }

    fun requestDefaultHome(a: Activity, requestCode: Int) {
        if (Build.VERSION.SDK_INT >= 29) {
            val rm = a.getSystemService(RoleManager::class.java)
            if (rm != null && rm.isRoleAvailable(RoleManager.ROLE_HOME) && !rm.isRoleHeld(RoleManager.ROLE_HOME)) {
                try {
                    @Suppress("DEPRECATION")
                    a.startActivityForResult(rm.createRequestRoleIntent(RoleManager.ROLE_HOME), requestCode)
                    return
                } catch (_: Exception) { }
            }
        }
        try {
            a.startActivity(Intent(Settings.ACTION_HOME_SETTINGS))
        } catch (_: Exception) {
            a.startActivity(Intent(Settings.ACTION_SETTINGS))
        }
    }
}
