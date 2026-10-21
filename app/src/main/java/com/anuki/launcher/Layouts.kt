package com.anuki.launcher

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.VelocityTracker
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.Scroller
import kotlin.math.abs
import kotlin.math.roundToInt

private fun View.dpx(v: Float) = v * resources.displayMetrics.density

/** Grid of cells; children carry a [LP] with cell position and span. */
class CellLayout(ctx: Context) : ViewGroup(ctx) {
    var cols = 5
    var rows = 6
    var container = HomeItem.DESKTOP
    var screen = 0

    class LP(var cx: Int, var cy: Int, var sx: Int = 1, var sy: Int = 1) : ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT)

    /** highlight: x, y, spanX, spanY, mode (0 = place, 1 = merge into folder) */
    private var highlight: IntArray? = null
    private val placePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x40FFFFFF }
    private val placeStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xCCFFFFFF.toInt(); style = Paint.Style.STROKE; strokeWidth = dpx(2f)
    }
    private val mergePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x664BD5D6 }
    private val rect = RectF()

    init {
        setWillNotDraw(false)
        clipChildren = false
        clipToPadding = false
    }

    val cellW: Int get() = maxOf(1, (width - paddingLeft - paddingRight) / cols)
    val cellH: Int get() = maxOf(1, (height - paddingTop - paddingBottom) / rows)

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val cw = maxOf(1, (w - paddingLeft - paddingRight) / cols)
        val ch = maxOf(1, (h - paddingTop - paddingBottom) / rows)
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as LP
            v.measure(
                MeasureSpec.makeMeasureSpec(cw * lp.sx, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(ch * lp.sy, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = cellW; val ch = cellH
        for (i in 0 until childCount) {
            val v = getChildAt(i)
            val lp = v.layoutParams as LP
            val x = paddingLeft + lp.cx * cw
            val y = paddingTop + lp.cy * ch
            v.layout(x, y, x + v.measuredWidth, y + v.measuredHeight)
        }
    }

    override fun checkLayoutParams(p: ViewGroup.LayoutParams?) = p is LP
    override fun generateDefaultLayoutParams(): ViewGroup.LayoutParams = LP(0, 0)
    override fun generateLayoutParams(p: ViewGroup.LayoutParams?): ViewGroup.LayoutParams = LP(0, 0)

    /** Cell under a point in this view's coordinates, top-left aligned for the given span. */
    fun cellAt(x: Float, y: Float, sx: Int = 1, sy: Int = 1): IntArray {
        val cx = ((x - paddingLeft) / cellW - (sx - 1) / 2f).toInt().coerceIn(0, maxOf(0, cols - sx))
        val cy = ((y - paddingTop) / cellH - (sy - 1) / 2f).toInt().coerceIn(0, maxOf(0, rows - sy))
        return intArrayOf(cx, cy)
    }

    fun setHighlight(h: IntArray?) {
        if (h == null && highlight == null) return
        highlight = h
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val h = highlight ?: return
        val pad = dpx(4f)
        rect.set(
            paddingLeft + h[0] * cellW + pad, paddingTop + h[1] * cellH + pad,
            paddingLeft + (h[0] + h[2]) * cellW - pad, paddingTop + (h[1] + h[3]) * cellH - pad,
        )
        val r = dpx(18f)
        if (h[4] == 1) {
            canvas.drawCircle(rect.centerX(), rect.centerY(), minOf(rect.width(), rect.height()) / 2f, mergePaint)
        } else {
            canvas.drawRoundRect(rect, r, r, placePaint)
            canvas.drawRoundRect(rect, r, r, placeStroke)
        }
    }
}

/**
 * Pages side by side, dragged and flung sideways: the home pages ([PagedView]) and the "가로 페이지" drawer
 * ([DrawerPager]) share this. A quick flick, or a drag of 40% of the width, turns the page (the flick speed can read
 * 0 when the finger rests a moment before lifting, so the distance counts on its own too). With [wrap] (무한 스크롤)
 * the pull past the last page stops at 45% and 20% is enough to go round to the first.
 */
open class HorizontalPager(ctx: Context) : ViewGroup(ctx) {
    var currentPage = 0
        private set
    var wrap = false
    protected val scroller = Scroller(ctx)
    protected val cfg: ViewConfiguration = ViewConfiguration.get(ctx)
    protected val slop = cfg.scaledTouchSlop
    private var velocity: VelocityTracker? = null
    protected var downX = 0f
    protected var downY = 0f
    private var lastX = 0f
    /** True while the finger drags the pages sideways (a layout pass then leaves the scroll alone). */
    private var dragging = false
    protected open val snapMillis = 280

    val pageCount: Int get() = childCount

    /** The page changed (by a swipe or [snapToPage]). */
    protected open fun onPageSelected(page: Int) {}

    /** A sideways swipe ended: [vx] its speed, from page [from] to [to]. */
    protected open fun onSwiped(vx: Float, from: Int, to: Int) {}

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY),
            )
        }
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val w = r - l
        for (i in 0 until childCount) getChildAt(i).layout(i * w, 0, (i + 1) * w, b - t)
        if (scroller.isFinished && !dragging) scrollTo(currentPage * w, 0)
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            scrollTo(scroller.currX, 0)
            postInvalidateOnAnimation()
        }
    }

    fun snapToPage(p: Int, animate: Boolean = true) {
        val target = p.coerceIn(0, maxOf(0, pageCount - 1))
        if (!scroller.isFinished) scroller.abortAnimation()
        val dx = target * width - scrollX
        if (animate && width > 0) scroller.startScroll(scrollX, 0, dx, 0, snapMillis) else scrollTo(target * width, 0)
        val changed = target != currentPage
        currentPage = target
        if (changed) onPageSelected(target)
        invalidate()
    }

    protected fun track(ev: MotionEvent) {
        (velocity ?: VelocityTracker.obtain().also { velocity = it }).addMovement(ev)
    }

    /** (x, y) speed of the finger in px/s. */
    protected fun speed(): FloatArray {
        val v = velocity ?: return floatArrayOf(0f, 0f)
        v.computeCurrentVelocity(1000)
        return floatArrayOf(v.xVelocity, v.yVelocity)
    }

    protected fun recycleVelocity() { velocity?.recycle(); velocity = null }

    /** A new touch. True when it caught a page still settling: the pages are being dragged again. */
    protected fun touchDown(ev: MotionEvent): Boolean {
        downX = ev.x; downY = ev.y; lastX = ev.x; dragging = false
        velocity?.clear()
        track(ev)
        if (!scroller.isFinished) { scroller.abortAnimation(); dragging = true; return true }
        return false
    }

    /** Past the touch slop sideways (more sideways than up or down): the pages start following the finger. */
    protected fun startsSideways(ev: MotionEvent): Boolean {
        val dx = ev.x - downX
        if (abs(dx) > slop && abs(dx) > abs(ev.y - downY)) { dragging = true; lastX = ev.x; return true }
        return false
    }

    protected fun dragTo(ev: MotionEvent) {
        dragging = true
        val dx = lastX - ev.x
        lastX = ev.x
        val edge = if (wrap && pageCount > 1) 0.45f else 0.2f
        val nx = (scrollX + dx).coerceIn(-width * edge, (pageCount - 1) * width + width * edge)
        scrollTo(nx.roundToInt(), 0)
    }

    /**
     * A touch that ended without dragging the pages: if it caught a page half-way through settling
     * (a tap or an up/down swipe right after a page turn), finish the turn instead of leaving it stuck between pages.
     */
    protected fun settle() {
        dragging = false
        if (scroller.isFinished && width > 0 && scrollX != currentPage * width) {
            if (LauncherApp.debug) LauncherApp.dlog("page settle: scrollX=$scrollX -> page $currentPage")
            snapToPage(currentPage)
        }
    }

    /** The finger lifted (or the touch was cancelled): settle on a page. */
    protected fun release(ev: MotionEvent, cancelled: Boolean) {
        val vx = speed()[0]
        val nearest = (scrollX.toFloat() / maxOf(1, width)).roundToInt()
        var target = if (cancelled) nearest else {
            val fling = cfg.scaledMinimumFlingVelocity * 4
            val dragged = downX - ev.x
            val beyond = (dragged > 0 && currentPage == pageCount - 1) || (dragged < 0 && currentPage == 0)
            val need = width * if (wrap && beyond) 0.2f else 0.4f
            when {
                vx > fling -> currentPage - 1
                vx < -fling -> currentPage + 1
                dragged > need -> currentPage + 1
                dragged < -need -> currentPage - 1
                else -> nearest
            }
        }
        if (wrap && pageCount > 1) {
            if (target >= pageCount) target = 0 else if (target < 0) target = pageCount - 1
        }
        dragging = false
        onSwiped(vx, currentPage, target)
        snapToPage(target)
        recycleVelocity()
    }
}

/**
 * The home pages ([CellLayout]s) with swipe-up/down, double-tap and two-finger pinch detection on top of the
 * sideways paging. A swipe up that starts on an icon goes to [Listener.onIconSwipeUp] first (위로 쓸기 / 커버 폴더).
 */
class PagedView(ctx: Context) : HorizontalPager(ctx) {
    interface Listener {
        fun onPageChanged(page: Int)
        fun onSwipeUp()
        fun onSwipeDown()
        fun onDoubleTap()
        fun onEmptyLongPress(rawX: Float, rawY: Float)
        /** A swipe up that began on [child]; true when the icon handled it. */
        fun onIconSwipeUp(child: View?): Boolean = false
        fun onPinchIn() {}
        fun onPinchOut() {}
    }

    var listener: Listener? = null
    private var mode = 0 // 0 undecided, 1 sideways, 2 up/down, 3 two-finger pinch, 4 pinch done (rest of the touch ignored)
    /** The icon (or widget) under the finger when the touch began. */
    private var downChild: View? = null
    private var pinchStart = 0f
    private var pinchNow = 0f

    private val gestures = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent): Boolean = true
        override fun onDoubleTap(e: MotionEvent): Boolean { listener?.onDoubleTap(); return true }
        override fun onLongPress(e: MotionEvent) { if (mode == 0) listener?.onEmptyLongPress(e.rawX, e.rawY) }
    })

    fun page(i: Int): CellLayout = getChildAt(i) as CellLayout

    override fun onPageSelected(page: Int) { listener?.onPageChanged(page) }

    override fun onSwiped(vx: Float, from: Int, to: Int) {
        if (LauncherApp.debug) LauncherApp.dlog("page swipe: vx=${vx.toInt()} from $from to $to of $pageCount wrap=$wrap")
    }

    private fun childAt(x: Float, y: Float): View? {
        if (currentPage !in 0 until pageCount) return null
        val p = page(currentPage)
        return p.childUnder(x + scrollX - p.left, y - p.top)
    }

    private fun span(ev: MotionEvent): Float =
        if (ev.pointerCount < 2) 0f else kotlin.math.hypot(ev.getX(0) - ev.getX(1), ev.getY(0) - ev.getY(1))

    private fun startPinch(ev: MotionEvent) {
        mode = 3
        pinchStart = span(ev)
        pinchNow = pinchStart
    }

    private fun endPinch() {
        val r = if (pinchStart > 0f) pinchNow / pinchStart else 1f
        if (LauncherApp.debug) LauncherApp.dlog("pinch ratio ${"%.2f".format(r)}")
        if (r < 0.75f) listener?.onPinchIn() else if (r > 1.33f) listener?.onPinchOut()
        pinchStart = 0f
    }

    private fun decide(ev: MotionEvent): Boolean {
        if (startsSideways(ev)) { mode = 1; return true }
        val dx = ev.x - downX
        val dy = ev.y - downY
        if (abs(dy) > slop * 2 && abs(dy) > abs(dx) * 1.2f) { mode = 2; return true }
        return false
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 0
                downChild = childAt(ev.x, ev.y)
                if (touchDown(ev)) { mode = 1; return true }
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == 0 || mode == 2) { startPinch(ev); return true }
            MotionEvent.ACTION_MOVE -> {
                track(ev)
                if (mode == 0 && decide(ev)) return true
            }
        }
        return false
    }

    override fun onTouchEvent(ev: MotionEvent): Boolean {
        gestures.onTouchEvent(ev)
        if (ev.actionMasked != MotionEvent.ACTION_DOWN) track(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                mode = 0
                downChild = childAt(ev.x, ev.y)
                if (touchDown(ev)) mode = 1
            }
            MotionEvent.ACTION_POINTER_DOWN -> if (mode == 0 || mode == 2) startPinch(ev)
            MotionEvent.ACTION_POINTER_UP -> if (mode == 3) { endPinch(); mode = 4 }
            MotionEvent.ACTION_MOVE -> {
                if (mode == 3) { if (ev.pointerCount >= 2) pinchNow = span(ev); return true }
                if (mode == 4) return true
                if (mode == 0) decide(ev)
                if (mode == 1) dragTo(ev)
            }
            MotionEvent.ACTION_UP -> {
                when (mode) {
                    1 -> release(ev, cancelled = false)
                    2 -> {
                        val vy = speed()[1]
                        val dy = ev.y - downY
                        val threshold = dpx(56f)
                        if (dy < -threshold || vy < -1500f) {
                            if (listener?.onIconSwipeUp(downChild) != true) listener?.onSwipeUp()
                        } else if (dy > threshold || vy > 1500f) listener?.onSwipeDown()
                    }
                    3 -> endPinch()
                }
                if (mode != 1) settle()
                mode = 0
                downChild = null
                recycleVelocity()
            }
            MotionEvent.ACTION_CANCEL -> {
                if (mode == 1) release(ev, cancelled = true) else settle()
                mode = 0
                downChild = null
                recycleVelocity()
            }
        }
        return true
    }
}

class PageIndicator(ctx: Context) : View(ctx) {
    var count = 1
        set(v) { field = v; invalidate() }
    var current = 0
        set(v) { field = v; invalidate() }
    private val on = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val off = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x77FFFFFF }

    /** Dot colours (white by default, for the home screen over a wallpaper). */
    fun setColors(current: Int, other: Int) { on.color = current; off.color = other; invalidate() }

    override fun onDraw(canvas: Canvas) {
        if (count <= 1) return
        val r = dpx(3.5f)
        val gap = dpx(12f)
        val total = (count - 1) * gap
        var x = width / 2f - total / 2f
        for (i in 0 until count) {
            canvas.drawCircle(x, height / 2f, if (i == current) r * 1.25f else r, if (i == current) on else off)
            x += gap
        }
    }
}

/** Root view: steals the touch stream while a drag is pending or running. */
class DragLayer(ctx: Context) : FrameLayout(ctx) {
    var controller: DragController? = null

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        val c = controller
        if (c != null && c.isActive) {
            // the event that triggers interception is not re-sent to onTouchEvent, so hand it over here
            c.onTouch(ev)
            if (c.isActive || ev.actionMasked != MotionEvent.ACTION_DOWN) return true
        }
        return super.onInterceptTouchEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val c = controller
        if (c != null && c.isActive) { c.onTouch(event); return true }
        return super.onTouchEvent(event)
    }
}

/** Fixed-column grid used by the app drawer and folders. */
class IconGrid(ctx: Context) : ViewGroup(ctx) {
    var cols = 5
    var rowHeight = 0

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val cw = (w - paddingLeft - paddingRight) / maxOf(1, cols)
        val rows = (childCount + cols - 1) / maxOf(1, cols)
        for (i in 0 until childCount) {
            getChildAt(i).measure(
                MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(rowHeight, MeasureSpec.EXACTLY),
            )
        }
        setMeasuredDimension(w, rows * rowHeight + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val cw = (r - l - paddingLeft - paddingRight) / maxOf(1, cols)
        for (i in 0 until childCount) {
            val x = paddingLeft + (i % cols) * cw
            val y = paddingTop + (i / cols) * rowHeight
            getChildAt(i).layout(x, y, x + cw, y + rowHeight)
        }
    }
}
