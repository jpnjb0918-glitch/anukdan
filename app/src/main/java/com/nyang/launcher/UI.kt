package com.nyang.launcher

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
class AppDrawerView(private val act: HomeActivity) : FrameLayout(act) {
    private val column = LinearLayout(act)
    val search = EditText(act)
    private val tabsRow = LinearLayout(act)
    private val tabsScroll = HorizontalScrollView(act)
    private val scroll = ScrollView(act)
    private val grid = IconGrid(act)
    private var tabIndex = 0
    private var downX = 0f
    private var downY = 0f
    private var dragging = false
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
                if (enter) { filtered().firstOrNull()?.let { act.launch(it, this) }; true } else false
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
        grid.setPadding(act.dp(8f), act.dp(8f), act.dp(8f), act.dp(24f))
        scroll.addView(grid, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        column.addView(scroll, LinearLayout.LayoutParams(LayoutParams.MATCH_PARENT, 0, 1f))
        addView(column, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT))
    }

    fun setInsets(top: Int, bottom: Int) {
        column.setPadding(0, top, 0, 0)
        scroll.setPadding(0, 0, 0, bottom)
    }

    fun applyTheme() {
        setBackgroundColor(Palette.panel(act))
        search.setTextColor(Palette.text(act))
        search.setHintTextColor((Palette.text(act) and 0x00FFFFFF) or 0x88000000.toInt())
        search.background = Palette.rounded(Palette.subtle(act), act.dp(24f).toFloat())
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
                setTextColor(if (selected) Color.WHITE else Palette.text(act))
                background = Palette.rounded(if (selected) Palette.TEAL else Palette.subtle(act), act.dp(16f).toFloat())
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

    fun refresh() {
        if (visibility != VISIBLE) return
        val list = filtered()
        grid.cols = act.prefs.drawerCols
        val labels = act.prefs.drawerLabels
        grid.rowHeight = act.iconSize + if (labels) act.dp(36f) else act.dp(18f)
        // reuse existing views where possible
        while (grid.childCount > list.size) grid.removeViewAt(grid.childCount - 1)
        for ((i, app) in list.withIndex()) {
            if (i < grid.childCount) {
                val v = grid.getChildAt(i) as IconView
                act.bindAppIcon(v, app, null, DragInfo.SRC_DRAWER, labels, onPanel = true)
            } else {
                grid.addView(act.makeAppIcon(app, null, DragInfo.SRC_DRAWER, labels, onPanel = true))
            }
        }
        grid.requestLayout()
    }

    val isOpen: Boolean get() = visibility == VISIBLE

    fun open(focusSearch: Boolean = false) {
        if (isOpen) return
        applyTheme()
        visibility = VISIBLE
        refresh()
        scroll.scrollTo(0, 0)
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

    // swipe down from the top of the list closes the drawer
    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.rawX; downY = ev.rawY; dragging = false }
            MotionEvent.ACTION_MOVE -> {
                val dy = ev.rawY - downY
                if (!dragging && scroll.scrollY == 0 && dy > slop * 2 && dy > abs(ev.rawX - downX) * 1.5f) {
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
