package com.anuki.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.text.TextPaint
import android.text.TextUtils
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View

/**
 * One icon + label + notification badge. Used on the home screen, dock, drawer and inside folders.
 * [row] = list style: icon on the left, name to its right (app drawer "목록").
 */
class IconView(ctx: Context) : View(ctx) {
    var item: HomeItem? = null
    var app: AppEntry? = null

    var icon: Bitmap? = null
        set(v) { field = v; invalidate() }
    var label: String = ""
        set(v) { field = v; contentDescription = v; invalidate() }
    var showLabel = true
    var iconSize = dp(52f).toInt()
    var labelColor = Color.WHITE
    var labelShadow = true
    var badge = 0
        set(v) { if (field != v) { field = v; invalidate() } }
    var badgeStyle = "dot"
    var row = false

    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set

    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 12f, resources.displayMetrics)
    }
    private val iconPaint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEC7474.toInt() }
    private val badgeRing = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = dp(1.5f)
    }
    private val badgeText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textAlign = Paint.Align.CENTER; isFakeBoldText = true
    }
    private val dst = RectF()

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX
        lastRawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.92f).scaleY(0.92f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(event)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val wantH = if (row) iconSize + dp(14f).toInt()
        else (iconSize + if (showLabel) labelHeight() + dp(4f) else 0f).toInt() + dp(8f).toInt()
        val h = when (MeasureSpec.getMode(heightMeasureSpec)) {
            MeasureSpec.EXACTLY -> MeasureSpec.getSize(heightMeasureSpec)
            MeasureSpec.AT_MOST -> minOf(wantH, MeasureSpec.getSize(heightMeasureSpec))
            else -> wantH
        }
        setMeasuredDimension(w, h)
    }

    private fun labelHeight() = textPaint.fontSpacing

    override fun onDraw(canvas: Canvas) {
        if (row) {
            val top = (height - iconSize) / 2f
            val left = dp(18f)
            dst.set(left, top, left + iconSize, top + iconSize)
            icon?.let { canvas.drawBitmap(it, null, dst, iconPaint) }
            val tx = dst.right + dp(16f)
            rowText.color = labelColor
            if (labelShadow) rowText.setShadowLayer(dp(2f), 0f, dp(1f), 0xAA000000.toInt()) else rowText.clearShadowLayer()
            val text = TextUtils.ellipsize(label, rowText, width - tx - dp(16f), TextUtils.TruncateAt.END).toString()
            canvas.drawText(text, tx, height / 2f - (rowText.ascent() + rowText.descent()) / 2f, rowText)
            drawBadge(canvas)
            return
        }
        val total = iconSize + if (showLabel && label.isNotEmpty()) labelHeight() + dp(4f) else 0f
        val top = ((height - total) / 2f).coerceAtLeast(0f)
        val left = (width - iconSize) / 2f
        dst.set(left, top, left + iconSize, top + iconSize)
        icon?.let { canvas.drawBitmap(it, null, dst, iconPaint) }

        if (showLabel && label.isNotEmpty()) {
            val text = TextUtils.ellipsize(label, textPaint, width - dp(6f), TextUtils.TruncateAt.END).toString()
            textPaint.color = labelColor
            if (labelShadow) textPaint.setShadowLayer(dp(2f), 0f, dp(1f), 0xAA000000.toInt()) else textPaint.clearShadowLayer()
            canvas.drawText(text, width / 2f, top + iconSize + dp(4f) - textPaint.ascent(), textPaint)
        }
        drawBadge(canvas)
    }

    private val rowText = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.LEFT
        textSize = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 16f, resources.displayMetrics)
    }

    private fun drawBadge(canvas: Canvas) {
        if (badge > 0 && badgeStyle != "off") {
            if (badgeStyle == "count") {
                val r = iconSize * 0.17f
                val cx = dst.right - r * 0.55f
                val cy = dst.top + r * 0.55f
                canvas.drawCircle(cx, cy, r, badgeFill)
                canvas.drawCircle(cx, cy, r, badgeRing)
                badgeText.textSize = r * (if (badge > 9) 1.0f else 1.25f)
                val t = if (badge > 99) "99+" else badge.toString()
                canvas.drawText(t, cx, cy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText)
            } else {
                val r = iconSize * 0.11f
                val cx = dst.right - r
                val cy = dst.top + r
                canvas.drawCircle(cx, cy, r, badgeFill)
                canvas.drawCircle(cx, cy, r, badgeRing)
            }
        }
    }

    fun dp(v: Float) = v * resources.displayMetrics.density
}
