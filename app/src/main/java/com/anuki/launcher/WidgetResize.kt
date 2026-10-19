package com.anuki.launcher

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.util.TypedValue
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Resize mode for a home-screen widget (system or 아늑단), like the Samsung home screen: a frame with a handle on
 * each side over the widget. Dragging a handle changes the size one cell at a time (the widget follows live;
 * cells taken by other items stop it). Tapping the size label opens the number dialog; tapping outside the frame,
 * Back or Home finishes and saves.
 */
class WidgetResizeFrame(
    private val act: HomeActivity,
    private val item: HomeItem,
    private val widget: View,
    private val page: CellLayout,
) : View(act) {
    /** Called once when resize mode ends; true when the size or position changed. */
    var onDone: ((Boolean) -> Unit)? = null

    private val startX = item.x
    private val startY = item.y
    private val startSX = item.spanX
    private val startSY = item.spanY
    private var cx = item.x
    private var cy = item.y
    private var sx = item.spanX
    private var sy = item.spanY
    private var edge = NONE
    private var finished = false

    private val d = resources.displayMetrics.density
    private val frame = RectF()
    private val snapped = RectF()
    private val labelRect = RectF()
    private val originLoc = IntArray(2)
    private val pageLoc = IntArray(2)
    private var ox = 0f
    private var oy = 0f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x2A4BD5D6 }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFFFFFFFF.toInt(); strokeWidth = 3f * d }
    private val lineShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x55000000; strokeWidth = 5f * d }
    private val knob = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFFFFFF.toInt() }
    private val knobLine = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = Palette.TEAL; strokeWidth = 3f * d }
    private val pill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xE6FFFFFF.toInt() }
    private val text = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF1A2B2C.toInt(); textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14f, resources.displayMetrics)
    }

    init {
        isClickable = true
        isFocusable = true
        updateDescription()
    }

    private fun updateDescription() {
        contentDescription = "위젯 크기 조절: 가로 ${sx}칸 × 세로 ${sy}칸"
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        getLocationOnScreen(originLoc)
        page.getLocationOnScreen(pageLoc)
        ox = (pageLoc[0] - originLoc[0]).toFloat()
        oy = (pageLoc[1] - originLoc[1]).toFloat()
        if (edge == NONE) snapFrame()
    }

    private fun cellLeft(x: Int) = ox + page.paddingLeft + x * page.cellW
    private fun cellTop(y: Int) = oy + page.paddingTop + y * page.cellH

    private fun snapFrame() {
        snapped.set(cellLeft(cx), cellTop(cy), cellLeft(cx + sx), cellTop(cy + sy))
        frame.set(snapped)
    }

    private fun handles(): Array<FloatArray> = arrayOf(
        floatArrayOf(frame.left, frame.centerY()), floatArrayOf(frame.centerX(), frame.top),
        floatArrayOf(frame.right, frame.centerY()), floatArrayOf(frame.centerX(), frame.bottom),
    )

    override fun onDraw(c: Canvas) {
        val r = 14f * d
        c.drawRoundRect(frame, r, r, fill)
        c.drawRoundRect(frame, r, r, lineShadow)
        c.drawRoundRect(frame, r, r, line)
        for (h in handles()) {
            c.drawCircle(h[0], h[1], 11f * d, knob)
            c.drawCircle(h[0], h[1], 11f * d, knobLine)
        }
        // size label above the frame (below when there is no room); tap it for the number dialog
        val s = "가로 ${sx}칸 × 세로 ${sy}칸 · 숫자로"
        val w = text.measureText(s) + 28f * d
        val h = 32f * d
        var top = frame.top - h - 16f * d
        if (top < 8f * d) top = frame.bottom + 16f * d
        if (top + h > height - 8f * d) top = frame.centerY() - h / 2
        val left = (frame.centerX() - w / 2).coerceIn(8f * d, width - w - 8f * d)
        labelRect.set(left, top, left + w, top + h)
        c.drawRoundRect(labelRect, h / 2, h / 2, pill)
        c.drawText(s, labelRect.centerX(), labelRect.centerY() - (text.ascent() + text.descent()) / 2, text)
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edge = NONE
                val hs = handles()
                var best = 30f * d
                for (i in hs.indices) {
                    val dist = hypot(e.x - hs[i][0], e.y - hs[i][1])
                    if (dist < best) { best = dist; edge = i }
                }
                if (edge == NONE) {
                    when {
                        labelRect.contains(e.x, e.y) -> numberDialog()
                        frame.contains(e.x, e.y) -> {}
                        else -> finish()
                    }
                }
            }
            MotionEvent.ACTION_MOVE -> if (edge != NONE) drag(e.x, e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (edge != NONE) {
                edge = NONE
                snapFrame()
                invalidate()
            }
        }
        return true
    }

    /** Moves the dragged side with the finger and takes the nearest cell edge whenever the new size fits. */
    private fun drag(x: Float, y: Float) {
        val cw = page.cellW.toFloat()
        val ch = page.cellH.toFloat()
        val cols = page.cols
        val rows = page.rows
        var nx = cx; var ny = cy; var nsx = sx; var nsy = sy
        when (edge) {
            LEFT -> {
                frame.left = x.coerceIn(cellLeft(0), frame.right - cw * 0.6f)
                nx = ((frame.left - cellLeft(0)) / cw).roundToInt().coerceIn(0, cx + sx - 1)
                nsx = cx + sx - nx
            }
            RIGHT -> {
                frame.right = x.coerceIn(frame.left + cw * 0.6f, cellLeft(cols))
                nsx = ((frame.right - cellLeft(cx)) / cw).roundToInt().coerceIn(1, cols - cx)
            }
            TOP -> {
                frame.top = y.coerceIn(cellTop(0), frame.bottom - ch * 0.6f)
                ny = ((frame.top - cellTop(0)) / ch).roundToInt().coerceIn(0, cy + sy - 1)
                nsy = cy + sy - ny
            }
            BOTTOM -> {
                frame.bottom = y.coerceIn(frame.top + ch * 0.6f, cellTop(rows))
                nsy = ((frame.bottom - cellTop(cy)) / ch).roundToInt().coerceIn(1, rows - cy)
            }
        }
        if (nx != cx || ny != cy || nsx != sx || nsy != sy) {
            val g = act.model.occupied(HomeItem.DESKTOP, item.screen, cols, rows, item)
            if (act.model.fits(g, nx, ny, nsx, nsy)) apply(nx, ny, nsx, nsy)
        }
        invalidate()
    }

    private fun apply(nx: Int, ny: Int, nsx: Int, nsy: Int) {
        cx = nx; cy = ny; sx = nsx; sy = nsy
        (widget.layoutParams as? CellLayout.LP)?.let { it.cx = nx; it.cy = ny; it.sx = nsx; it.sy = nsy }
        widget.requestLayout()
        page.requestLayout()
        performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        updateDescription()
        LauncherApp.dlog("widget resize -> ${nsx}x$nsy at ($nx,$ny)")
    }

    private fun numberDialog() {
        item.x = cx; item.y = cy; item.spanX = sx; item.spanY = sy
        Dialogs.widgetResize(act, item) {
            apply(item.x, item.y, item.spanX, item.spanY)
            snapFrame()
            invalidate()
        }
    }

    /** Saves the new size (if any) and leaves resize mode. */
    fun finish() {
        if (finished) return
        finished = true
        item.x = cx; item.y = cy; item.spanX = sx; item.spanY = sy
        val changed = cx != startX || cy != startY || sx != startSX || sy != startSY
        onDone?.invoke(changed)
    }

    companion object {
        private const val NONE = -1
        private const val LEFT = 0
        private const val TOP = 1
        private const val RIGHT = 2
        private const val BOTTOM = 3
    }
}
