package com.anuki.launcher

import android.content.Context
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.abs

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

object Palette {
    fun isDark(ctx: Context): Boolean = when (LauncherApp.instance.prefs.theme) {
        "dark" -> true
        "light" -> false
        else -> (ctx.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
    }

    fun panel(ctx: Context) = if (isDark(ctx)) 0xF5122021.toInt() else 0xF5FFFFFF.toInt()
    fun card(ctx: Context) = if (isDark(ctx)) 0xFF1C2D2E.toInt() else 0xFFFFFFFF.toInt()
    fun text(ctx: Context) = if (isDark(ctx)) 0xFFEAF6F6.toInt() else 0xFF1A2B2C.toInt()
    fun subtle(ctx: Context) = if (isDark(ctx)) 0xFF243A3B.toInt() else 0xFFE6F4F4.toInt()
    const val TEAL = 0xFF2E9497.toInt()
    const val TEAL_DARK = 0xFF1E6467.toInt()
    const val CYAN = 0xFF4BD5D6.toInt()
    const val PINK = 0xFFEC7474.toInt()

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = 0): Drawable =
        GradientDrawable().apply {
            setColor(color); cornerRadius = radius
            if (stroke > 0) setStroke(stroke, strokeColor)
        }
}

class MenuEntry(val label: String, val icon: Drawable? = null, val action: () -> Unit)

/** Small rounded menu card shown inside the DragLayer (not a separate window, so drags keep working). */
class PopupCard(ctx: Context, entries: List<MenuEntry>, onPick: () -> Unit) : LinearLayout(ctx) {
    init {
        orientation = VERTICAL
        background = Palette.rounded(Palette.card(ctx), ctx.dp(18f).toFloat())
        elevation = ctx.dp(12f).toFloat()
        setPadding(0, ctx.dp(6f), 0, ctx.dp(6f))
        minimumWidth = ctx.dp(200f)
        for (e in entries) {
            val row = LinearLayout(ctx).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(ctx.dp(16f), ctx.dp(11f), ctx.dp(20f), ctx.dp(11f))
                isClickable = true
                background = ctx.getDrawable(android.R.drawable.list_selector_background)
                setOnClickListener { onPick(); e.action() }
            }
            if (e.icon != null) {
                row.addView(ImageView(ctx).apply { setImageDrawable(e.icon) }, LayoutParams(ctx.dp(22f), ctx.dp(22f)).apply {
                    marginEnd = ctx.dp(12f)
                })
            }
            row.addView(TextView(ctx).apply {
                text = e.label; textSize = 15f; setTextColor(Palette.text(ctx)); maxLines = 1
            })
            addView(row, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
    }
}

/** Top bar shown while dragging: remove / app info / uninstall. */
class DropBar(ctx: Context) : LinearLayout(ctx) {
    private val views = LinkedHashMap<Int, TextView>()
    private var hot: Int? = null

    init {
        orientation = HORIZONTAL
        gravity = Gravity.CENTER
        visibility = GONE
    }

    fun setup(kinds: List<Int>) {
        removeAllViews(); views.clear()
        for (k in kinds) {
            val tv = TextView(context).apply {
                text = labelOf(k)
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(context.dp(14f), context.dp(10f), context.dp(14f), context.dp(10f))
                background = Palette.rounded(0x66000000, context.dp(22f).toFloat())
            }
            addView(tv, LayoutParams(0, LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(context.dp(6f), context.dp(6f), context.dp(6f), context.dp(6f))
            })
            views[k] = tv
        }
        hot = null
    }

    fun kindAt(rawX: Float, rawY: Float): Int? {
        if (visibility != VISIBLE) return null
        val loc = IntArray(2)
        for ((k, v) in views) {
            v.getLocationOnScreen(loc)
            if (rawX >= loc[0] && rawX < loc[0] + v.width && rawY >= loc[1] - context.dp(10f) && rawY < loc[1] + v.height + context.dp(10f)) return k
        }
        return null
    }

    fun setHot(k: Int?) {
        if (k == hot) return
        hot = k
        for ((kind, v) in views) {
            val color = when {
                kind != k -> 0x66000000
                kind == UNINSTALL || kind == REMOVE -> 0xE6EC7474.toInt()
                else -> 0xE62E9497.toInt()
            }
            v.background = Palette.rounded(color, context.dp(22f).toFloat())
        }
    }

    companion object {
        const val REMOVE = 1
        const val INFO = 2
        const val UNINSTALL = 3
        fun labelOf(k: Int) = when (k) {
            REMOVE -> "✕  제거"
            INFO -> "ⓘ  앱 정보"
            else -> "🗑  설치 삭제"
        }
    }
}

/** Folder contents shown as a card over a dim scrim. */
class FolderView(private val act: HomeActivity) : FrameLayout(act) {
    var folder: HomeItem? = null
        private set
    private val card = LinearLayout(act)
    private val title = EditText(act)
    private val grid = IconGrid(act)

    init {
        visibility = GONE
        setBackgroundColor(0x66000000)
        isClickable = true
        setOnClickListener { act.closeFolder() }
        card.orientation = LinearLayout.VERTICAL
        card.isClickable = true
        card.setPadding(act.dp(14f), act.dp(14f), act.dp(14f), act.dp(18f))
        card.elevation = act.dp(16f).toFloat()
        title.apply {
            textSize = 18f
            gravity = Gravity.CENTER
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_DONE
            inputType = InputType.TYPE_CLASS_TEXT
            background = null
            setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_DONE) { saveTitle(); clearFocus(); hideKeyboard(); true } else false
            }
        }
        card.addView(title, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        card.addView(grid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(card, LayoutParams(act.dp(320f), LayoutParams.WRAP_CONTENT, Gravity.CENTER))
    }

    fun open(f: HomeItem) {
        folder = f
        card.background = Palette.rounded(Palette.card(act), act.dp(26f).toFloat())
        title.setTextColor(Palette.text(act))
        title.setText(f.title)
        refresh()
        visibility = VISIBLE
        alpha = 0f
        card.scaleX = 0.9f; card.scaleY = 0.9f
        animate().alpha(1f).setDuration(150).start()
        card.animate().scaleX(1f).scaleY(1f).setDuration(180).start()
    }

    fun refresh() {
        val f = folder ?: return
        val items = act.model.folderContents(f.id)
        grid.removeAllViews()
        grid.cols = when {
            items.size <= 4 -> 2.coerceAtMost(maxOf(1, items.size))
            items.size <= 9 -> 3
            else -> 4
        }
        val size = act.iconSize
        grid.rowHeight = size + act.dp(34f)
        for (it in items) {
            val app = act.apps.find(it.key) ?: continue
            val v = act.makeAppIcon(app, it, DragInfo.SRC_FOLDER, labels = true, onPanel = true)
            grid.addView(v)
        }
        val cw = act.dp(78f)
        (card.layoutParams as LayoutParams).width = grid.cols * cw + act.dp(28f)
        card.requestLayout()
    }

    private fun saveTitle() {
        val f = folder ?: return
        val t = title.text.toString().trim().ifEmpty { "폴더" }
        if (t != f.title) {
            f.title = t
            act.model.save()
        }
    }

    private fun hideKeyboard() {
        act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
    }

    fun close() {
        if (visibility != VISIBLE) return
        saveTitle(); hideKeyboard(); title.clearFocus()
        folder = null
        animate().alpha(0f).setDuration(120).withEndAction { visibility = GONE }.start()
    }
}

/** Full-screen app drawer: search (with 초성), tabs, alphabetical grid. Swipe down at the top to close. */
/** Horizontal pages for the "가로 페이지" drawer: swipe left/right, snaps to a page. */
class DrawerPager(ctx: Context) : ViewGroup(ctx) {
    var onPageChanged: ((Int) -> Unit)? = null
    var currentPage = 0
        private set
    private val scroller = android.widget.Scroller(ctx)
    private var velocity: android.view.VelocityTracker? = null
    private val cfg = ViewConfiguration.get(ctx)
    private var downX = 0f
    private var downY = 0f
    private var lastX = 0f
    private var horizontal = false

    val pageCount: Int get() = childCount

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY))
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (scroller.isFinished) scrollTo(currentPage * w, 0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) { scrollTo(scroller.currX, 0); postInvalidateOnAnimation() }
    }

    fun snapTo(p: Int, animate: Boolean = true) {
        val target = p.coerceIn(0, maxOf(0, pageCount - 1))
        if (!scroller.isFinished) scroller.abortAnimation()
        val dx = target * width - scrollX
        if (animate && width > 0) scroller.startScroll(scrollX, 0, dx, 0, 260) else scrollTo(target * width, 0)
        if (target != currentPage) { currentPage = target; onPageChanged?.invoke(target) }
        invalidate()
    }

    private fun track(ev: MotionEvent) {
        (velocity ?: android.view.VelocityTracker.obtain().also { velocity = it }).addMovement(ev)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.x; downY = ev.y; lastX = ev.x; horizontal = false
                velocity?.clear(); track(ev)
                if (!scroller.isFinished) { scroller.abortAnimation(); horizontal = true; return true }
            }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                val dx = ev.x - downX; val dy = ev.y - downY
                if (abs(dx) > cfg.scaledTouchSlop && abs(dx) > abs(dy)) { horizontal = true; lastX = ev.x; return true }
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; lastX = ev.x; if (!scroller.isFinished) scroller.abortAnimation() }
            MotionEvent.ACTION_MOVE -> {
                val dx = lastX - ev.x
                lastX = ev.x
                val nx = (scrollX + dx).coerceIn(-width * 0.2f, (pageCount - 1) * width + width * 0.2f)
                scrollTo(nx.toInt(), 0)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val v = velocity
                v?.computeCurrentVelocity(1000)
                val vx = v?.xVelocity ?: 0f
                val fling = cfg.scaledMinimumFlingVelocity * 4
                val target = when {
                    ev.actionMasked == MotionEvent.ACTION_UP && vx > fling -> currentPage - 1
                    ev.actionMasked == MotionEvent.ACTION_UP && vx < -fling -> currentPage + 1
                    else -> Math.round(scrollX.toFloat() / maxOf(1, width))
                }
                snapTo(target)
                velocity?.recycle(); velocity = null
            }
        }
        return true
    }
}

/** ㄱ…ㅎ A…Z # strip on the right of the "가나다" drawer: touch or slide to jump to a section. */
class SectionIndexBar(ctx: Context) : View(ctx) {
    var keys: List<String> = emptyList()
        set(v) { field = v; invalidate() }
    var onPick: ((String) -> Unit)? = null
    var color = Color.DKGRAY
        set(v) { field = v; paint.color = v; invalidate() }
    private val paint = android.text.TextPaint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = android.graphics.Paint.Align.CENTER
        isFakeBoldText = true
    }
    private var last: String? = null

    private fun step() = if (keys.isEmpty()) 0f else minOf(height.toFloat() / keys.size, resources.displayMetrics.density * 22f)

    override fun onDraw(canvas: android.graphics.Canvas) {
        if (keys.isEmpty()) return
        val st = step()
        paint.textSize = minOf(st * 0.72f, android.util.TypedValue.applyDimension(android.util.TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics))
        val top = (height - st * keys.size) / 2f
        for ((i, k) in keys.withIndex()) {
            val cy = top + st * i + st / 2f
            canvas.drawText(k, width / 2f, cy - (paint.ascent() + paint.descent()) / 2f, paint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (keys.isEmpty()) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val st = step()
                val top = (height - st * keys.size) / 2f
                val i = ((event.y - top) / st).toInt().coerceIn(0, keys.size - 1)
                val k = keys[i]
                if (k != last) { last = k; onPick?.invoke(k); performHapticFeedback(android.view.HapticFeedbackConstants.CLOCK_TICK) }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> last = null
        }
        return true
    }
}

/**
 * App drawer. Four layouts (설정 → 앱 서랍 → 서랍 형식):
 *  grid     세로 격자: one long grid, scroll up/down
 *  pages    가로 페이지: grid pages, swipe left/right, dots underneath
 *  list     목록: one app per row, icon + name
 *  sections 가나다 묶음: grid under ㄱ/ㄴ/…/A/B/# headings, with a jump strip on the right
 * plus order (이름순 / 최근 설치순 / 많이 연 순) and background (테마 색 / 반투명 / 배경화면 보이게).
 */
class AppDrawerView(private val act: HomeActivity) : FrameLayout(act) {
    private val column = LinearLayout(act)
    val search = EditText(act)
    private val tabsRow = LinearLayout(act)
    private val tabsScroll = HorizontalScrollView(act)
    private val body = FrameLayout(act)
    private val scroll = ScrollView(act)
    private val stack = LinearLayout(act)
    private val grid = IconGrid(act)
    private val pager = DrawerPager(act)
    private val dots = PageIndicator(act)
    private val indexBar = SectionIndexBar(act)
    private val headers = HashMap<String, View>()
    private var tabIndex = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
    private var bottomInset = 0
    private val slop = ViewConfiguration.get(act).scaledTouchSlop

    init {
        visibility = GONE
        isClickable = true
        column.orientation = LinearLayout.VERTICAL
        search.apply {
            hint = "앱 검색 (초성도 돼요: ㅋㅋㅇ)"
            textSize = 16f
            setSingleLine(true)
            imeOptions = EditorInfo.IME_ACTION_GO
            setPadding(act.dp(18f), act.dp(12f), act.dp(18f), act.dp(12f))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
                override fun afterTextChanged(s: Editable?) = refresh()
            })
            setOnEditorActionListener { _, id, ev ->
                val enter = id == EditorInfo.IME_ACTION_GO || (ev != null && ev.keyCode == KeyEvent.KEYCODE_ENTER)
                if (enter) { ordered().firstOrNull()?.let { act.launch(it, this) }; true } else false
            }
        }
        column.addView(search, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT).apply {
            setMargins(act.dp(16f), act.dp(12f), act.dp(16f), act.dp(6f))
        })
        tabsScroll.isHorizontalScrollBarEnabled = false
        tabsRow.orientation = LinearLayout.HORIZONTAL
        tabsRow.setPadding(act.dp(12f), 0, act.dp(12f), 0)
        tabsScroll.addView(tabsRow)
        column.addView(tabsScroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))

        scroll.isVerticalScrollBarEnabled = false
        scroll.clipToPadding = false
        stack.orientation = LinearLayout.VERTICAL
        stack.setPadding(act.dp(8f), act.dp(8f), act.dp(8f), act.dp(24f))
        scroll.addView(stack, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        body.addView(scroll, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(pager, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
        body.addView(dots, LayoutParams(LayoutParams.MATCH_PARENT, act.dp(22f), Gravity.BOTTOM))
        body.addView(indexBar, LayoutParams(act.dp(26f), LayoutParams.MATCH_PARENT, Gravity.END))
        pager.onPageChanged = { dots.current = it }
        indexBar.onPick = { k -> headers[k]?.let { scroll.smoothScrollTo(0, it.top) } }
        column.addView(body, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setInsets(top: Int, bottom: Int) {
        column.setPadding(0, top, 0, 0)
        bottomInset = bottom
        scroll.setPadding(0, 0, 0, bottom)
        (dots.layoutParams as LayoutParams).bottomMargin = bottom
        pager.setPadding(0, 0, 0, bottom + act.dp(22f))
        indexBar.setPadding(0, 0, 0, bottom)
    }

    /** Labels and controls are white over the wallpaper background, theme coloured otherwise. */
    private val onPanel: Boolean get() = act.prefs.drawerBg != "wallpaper"
    private fun textColor() = if (onPanel) Palette.text(act) else Color.WHITE
    private fun subtleColor() = if (onPanel) Palette.subtle(act) else 0x33FFFFFF

    fun applyTheme() {
        setBackgroundColor(
            when (act.prefs.drawerBg) {
                "glass" -> (Palette.panel(act) and 0x00FFFFFF) or 0xB8000000.toInt()
                "wallpaper" -> 0x59000000
                else -> Palette.panel(act)
            }
        )
        search.setTextColor(textColor())
        search.setHintTextColor((textColor() and 0x00FFFFFF) or 0x88000000.toInt())
        search.background = Palette.rounded(subtleColor(), act.dp(24f).toFloat())
        if (onPanel) dots.setColors(Palette.TEAL, (Palette.text(act) and 0x00FFFFFF) or 0x40000000)
        else dots.setColors(Color.WHITE, 0x77FFFFFF)
        indexBar.color = if (onPanel) Palette.TEAL else Color.WHITE
        buildTabs()
    }

    private fun buildTabs() {
        tabsRow.removeAllViews()
        val tabs = act.prefs.tabs()
        if (tabIndex > tabs.size) tabIndex = 0
        val names = listOf("전체") + tabs.map { it.name } + listOf("＋")
        for ((i, n) in names.withIndex()) {
            val isAdd = i == names.size - 1
            val tv = TextView(act).apply {
                text = n
                textSize = 14f
                setPadding(act.dp(14f), act.dp(7f), act.dp(14f), act.dp(7f))
                val selected = !isAdd && i == tabIndex
                setTextColor(if (selected) Color.WHITE else textColor())
                background = Palette.rounded(if (selected) Palette.TEAL else subtleColor(), act.dp(16f).toFloat())
                setOnClickListener {
                    if (isAdd) Dialogs.editTabs(act) { applyTheme(); refresh() }
                    else { tabIndex = i; buildTabs(); refresh() }
                }
                if (!isAdd && i > 0) setOnLongClickListener { Dialogs.editTabs(act) { applyTheme(); refresh() }; true }
            }
            tabsRow.addView(tv, LinearLayout.LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT).apply {
                setMargins(act.dp(4f), act.dp(4f), act.dp(4f), act.dp(4f))
            })
        }
    }

    fun filtered(): List<AppEntry> {
        val hidden = act.prefs.hidden
        val q = search.text.toString()
        val tabs = act.prefs.tabs()
        val tabKeys = if (tabIndex in 1..tabs.size) tabs[tabIndex - 1].keys else null
        return act.apps.all.filter {
            (q.isNotBlank() || it.key !in hidden) && (tabKeys == null || it.key in tabKeys) && Hangul.matches(it, q)
        }
    }

    /** [filtered] in the chosen order; apps.all is already sorted by name (Korean collation). */
    fun ordered(): List<AppEntry> {
        val list = filtered()
        if (act.prefs.drawerStyle == "sections") return list
        return when (act.prefs.drawerSort) {
            "recent" -> list.sortedByDescending { it.installTime }
            "usage" -> list.sortedByDescending { act.prefs.launchCount(it.key) } // stable: ties stay in name order
            else -> list
        }
    }

    private fun rowHeight() = act.iconSize + if (act.prefs.drawerLabels) act.dp(36f) else act.dp(18f)

    private fun bind(v: IconView, app: AppEntry, row: Boolean) {
        act.bindAppIcon(v, app, null, DragInfo.SRC_DRAWER, act.prefs.drawerLabels || row, onPanel = onPanel)
        v.row = row
        if (row) {
            v.iconSize = (act.iconSize * 0.78f).toInt()
            v.showLabel = true
        }
    }

    private fun newIcon(app: AppEntry, row: Boolean) = IconView(act).also { bind(it, app, row) }

    private fun newGrid(cols: Int, rowH: Int) = IconGrid(act).apply { this.cols = cols; rowHeight = rowH }

    fun refresh() {
        if (visibility != VISIBLE) return
        val list = ordered()
        val searching = search.text.isNotBlank()
        var style = act.prefs.drawerStyle
        if (searching && style == "sections") style = "grid"
        val paged = style == "pages"
        scroll.visibility = if (paged) GONE else VISIBLE
        pager.visibility = if (paged) VISIBLE else GONE
        dots.visibility = if (paged) VISIBLE else GONE
        indexBar.visibility = if (style == "sections") VISIBLE else GONE
        headers.clear()
        when (style) {
            "pages" -> fillPages(list)
            "sections" -> fillSections(list)
            "list" -> fillGrid(list, cols = 1, rowH = (act.iconSize * 0.78f).toInt() + act.dp(14f), row = true)
            else -> fillGrid(list, cols = act.prefs.drawerCols, rowH = rowHeight(), row = false)
        }
    }

    /** One grid in the scroll view, reusing icon views where possible (search updates on every key). */
    private fun fillGrid(list: List<AppEntry>, cols: Int, rowH: Int, row: Boolean) {
        if (stack.childCount != 1 || stack.getChildAt(0) !== grid) {
            stack.removeAllViews()
            (grid.parent as? ViewGroup)?.removeView(grid)
            stack.addView(grid, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        stack.setPadding(if (row) 0 else act.dp(8f), act.dp(8f), if (row) 0 else act.dp(8f), act.dp(24f))
        grid.cols = cols
        grid.rowHeight = rowH
        while (grid.childCount > list.size) grid.removeViewAt(grid.childCount - 1)
        for ((i, app) in list.withIndex()) {
            if (i < grid.childCount) bind(grid.getChildAt(i) as IconView, app, row)
            else grid.addView(newIcon(app, row))
        }
        grid.requestLayout()
    }

    private fun fillSections(list: List<AppEntry>) {
        stack.removeAllViews()
        (grid.parent as? ViewGroup)?.removeView(grid)
        stack.setPadding(act.dp(8f), act.dp(4f), act.dp(30f), act.dp(24f))
        val groups = list.groupBy { Hangul.section(it.label) }
        val keys = Hangul.SECTIONS.filter { it in groups }
        for (k in keys) {
            val h = TextView(act).apply {
                text = k
                textSize = 15f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setTextColor(if (onPanel) Palette.TEAL else Color.WHITE)
                setPadding(act.dp(12f), act.dp(12f), act.dp(12f), act.dp(2f))
                contentDescription = "구역 $k"
            }
            headers[k] = h
            stack.addView(h, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
            val g = newGrid(act.prefs.drawerCols, rowHeight())
            for (app in groups.getValue(k)) g.addView(newIcon(app, false))
            stack.addView(g, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        }
        indexBar.keys = keys
    }

    private fun fillPages(list: List<AppEntry>) {
        val avail = pager.height - pager.paddingBottom
        if (avail <= 0) { post { refresh() }; return }
        val cols = act.prefs.drawerCols
        val rowH = rowHeight()
        val rows = maxOf(1, (avail - act.dp(8f)) / rowH)
        val per = cols * rows
        val keep = pager.currentPage
        pager.removeAllViews()
        for (start in list.indices step per) {
            val g = newGrid(cols, rowH).apply { setPadding(act.dp(8f), act.dp(8f), act.dp(8f), 0) }
            for (app in list.subList(start, minOf(list.size, start + per))) g.addView(newIcon(app, false))
            pager.addView(g)
        }
        if (pager.childCount == 0) pager.addView(newGrid(cols, rowH))
        dots.count = pager.childCount
        pager.snapTo(if (search.text.isNotBlank()) 0 else keep.coerceAtMost(pager.childCount - 1), false)
        dots.current = pager.currentPage
        LauncherApp.dlog("drawer pages: ${pager.childCount} x $per")
    }

    val isOpen: Boolean get() = visibility == VISIBLE

    fun open(focusSearch: Boolean = false) {
        if (isOpen) return
        applyTheme()
        visibility = VISIBLE
        refresh()
        scroll.scrollTo(0, 0)
        if (pager.pageCount > 0) pager.snapTo(0, false)
        translationY = height.toFloat().takeIf { it > 0 } ?: act.resources.displayMetrics.heightPixels.toFloat()
        animate().translationY(0f).setDuration(220).withEndAction {
            if (focusSearch) {
                search.requestFocus()
                act.getSystemService(InputMethodManager::class.java).showSoftInput(search, 0)
            }
        }.start()
    }

    fun close(animate: Boolean) {
        if (!isOpen) return
        act.getSystemService(InputMethodManager::class.java).hideSoftInputFromWindow(windowToken, 0)
        search.clearFocus()
        val end = Runnable {
            visibility = GONE
            translationY = 0f
            if (search.text.isNotEmpty()) search.setText("")
        }
        if (animate) animate().translationY(height.toFloat()).setDuration(200).withEndAction(end).start() else end.run()
    }

    // swipe down from the top of the list (or anywhere on the paged drawer) closes the drawer
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                val atTop = pager.visibility == VISIBLE || scroll.scrollY == 0
                if (!dragging && atTop && dy > slop * 2 && dy > abs(ev.rawX - downX) * 1.5f) {
                    dragging = true
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return super.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> translationY = (event.rawY - downY).coerceAtLeast(0f)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                if (translationY > height * 0.22f) close(true)
                else animate().translationY(0f).setDuration(150).start()
            }
        }
        return true
    }
}

fun ViewGroup.MarginLayoutParams.setAll(m: Int) = setMargins(m, m, m, m)
