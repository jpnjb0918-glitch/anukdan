package com.anuki.launcher

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.os.BatteryManager
import android.os.Build
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import java.util.Calendar
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 아늑단 widgets drawn by the launcher itself (they live only inside 아늑이 런처): 시계·날짜, 배터리, 검색창, 메모.
 *
 * They use the icon pack's look: a tile with a thick dark outline and a shine line, the cat peeking over the tile's
 * top edge and its paws gripping the edge (assets/catwidget, made by tools/catfmt/make_widget_parts.py), and they
 * follow 아이콘 색 and the launcher font.
 *
 * Stored as HomeItem.TYPE_CAT_WIDGET: key = kind, provider = clock style ("analog" or ""), title = memo text.
 */
object CatWidgets {
    const val CLOCK = "clock"
    const val BATTERY = "battery"
    const val SEARCH = "search"
    const val MEMO = "memo"
    const val ANALOG = "analog"

    class Kind(val key: String, val label: String, val desc: String, val spanX: Int, val spanY: Int)

    val KINDS = listOf(
        Kind(CLOCK, "아늑단 시계", "시간과 날짜 · 길게 눌러 아날로그 시계로 바꿀 수 있어요", 4, 2),
        Kind(BATTERY, "아늑단 배터리", "남은 배터리와 충전 상태", 2, 2),
        Kind(SEARCH, "아늑단 검색창", "누르면 앱을 검색해요", 4, 1),
        Kind(MEMO, "아늑단 메모", "눌러서 메모를 써요", 2, 2),
    )

    fun kind(key: String) = KINDS.firstOrNull { it.key == key }

    fun create(ctx: Context, item: HomeItem): CatWidgetView = when (item.key) {
        BATTERY -> BatteryWidget(ctx, item)
        SEARCH -> SearchWidget(ctx, item)
        MEMO -> MemoWidget(ctx, item)
        else -> ClockWidget(ctx, item)
    }

    /** Picture of a widget at [w] x [h] px, for the widget list. */
    fun preview(ctx: Context, key: String, w: Int, h: Int): Bitmap? = try {
        val k = kind(key)
        val item = HomeItem(
            -1, HomeItem.TYPE_CAT_WIDGET, HomeItem.DESKTOP, spanX = k?.spanX ?: 1, spanY = k?.spanY ?: 1, key = key,
            title = if (key == MEMO) "우유 사기\n6시 요가" else "",
        )
        val v = create(ctx, item)
        v.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY))
        v.layout(0, 0, w, h)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        b
    } catch (e: Exception) {
        LauncherApp.dlog("widget preview $key failed: $e"); null
    }
}

/** The cat's head (drawn behind the tile) and paws (in front of it); all layers share one crop. */
object CatParts {
    // icon units (512 per icon), printed by tools/catfmt/make_widget_parts.py
    const val CROP_W = 286f
    const val CROP_H = 154.5f
    const val EDGE = 121.5f   // the tile's top edge, from the top of the crop
    const val HEAD_CX = 143f  // the cat's centre, from the left of the crop
    const val BELOW = CROP_H - EDGE // how far the paws reach below the edge

    private val raw = HashMap<String, Bitmap?>()
    private val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
    private val dst = RectF()

    private fun load(ctx: Context, name: String): Bitmap? = synchronized(raw) {
        raw.getOrPut(name) {
            try { ctx.assets.open("catwidget/$name.png").use { BitmapFactory.decodeStream(it) } } catch (_: Exception) { null }
        }
    }

    private fun place(cx: Float, edgeY: Float, u: Float) =
        dst.set(cx - HEAD_CX * u, edgeY - EDGE * u, cx + (CROP_W - HEAD_CX) * u, edgeY + BELOW * u)

    fun drawHead(c: Canvas, ctx: Context, expr: String, color: Int, cx: Float, edgeY: Float, u: Float) {
        val b = load(ctx, "head_$expr") ?: load(ctx, "head_happy") ?: return
        place(cx, edgeY, u)
        c.drawBitmap(IconColors.recolor(b, color, "whead:$expr"), null, dst, paint)
    }

    fun drawPaws(c: Canvas, ctx: Context, cx: Float, edgeY: Float, u: Float) {
        val b = load(ctx, "paws") ?: return
        place(cx, edgeY, u)
        c.drawBitmap(b, null, dst, paint)
    }
}

/** One 아늑단 widget: cat head, tile (fill, inside, shine, outline), content, paws. */
abstract class CatWidgetView(ctx: Context, val item: HomeItem) : View(ctx) {
    var onTap: (() -> Unit)? = null
    var onLongPress: ((CatWidgetView) -> Unit)? = null
    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set

    protected val density = resources.displayMetrics.density
    /** px per icon unit (the icon drawings are 512 units wide). */
    protected var u = 1f
    protected val panel = RectF()
    protected var radius = 0f
    protected var color = IconColors.DEFAULT
    protected open val paperTile = false
    protected open fun expr() = "happy"
    protected open fun headCx() = panel.centerX()

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; color = INK; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND
    }
    private val shinePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x66FFFFFF; strokeCap = Paint.Cap.ROUND }
    private val clipPath = Path()

    protected val textFill = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textAlign = Paint.Align.CENTER }
    protected val textLine = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = INK; style = Paint.Style.STROKE; strokeJoin = Paint.Join.ROUND; textAlign = Paint.Align.CENTER
    }

    init {
        isClickable = true
        isLongClickable = true
        isHapticFeedbackEnabled = true
        setOnClickListener { onTap?.invoke() }
        setOnLongClickListener { onLongPress?.invoke(this); onLongPress != null }
        contentDescription = CatWidgets.kind(item.key)?.label
    }

    fun dp(v: Float) = v * density
    protected fun sp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, resources.displayMetrics)

    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX
        lastRawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> animate().scaleX(1f).scaleY(1f).setDuration(120).start()
        }
        return super.onTouchEvent(event)
    }

    /** The cat about 2/5 of the tile's width, between 1.4x and 2.2x its size on an app icon (follows 아이콘 크기). */
    protected fun unitFor(tileW: Float): Float {
        val s = LauncherApp.instance.prefs.iconScale / 100f
        return (tileW * 0.42f / CatParts.CROP_W).coerceIn(dp(0.16f) * s, dp(0.26f) * s)
    }

    /** Where the tile goes for this size (default: the whole cell under the cat). */
    protected open fun layoutTile(w: Int, h: Int) {
        val pad = dp(4f)
        u = unitFor(w - 2 * pad)
        panel.set(pad, pad + CatParts.EDGE * u, w - pad, h - pad)
        radius = min(64f * 1.3f * u, panel.height() / 2)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutTile(w, h)
        clipPath.reset()
        clipPath.addRoundRect(panel, radius, radius, Path.Direction.CW)
    }

    override fun onDraw(c: Canvas) {
        if (panel.width() <= 0f || panel.height() <= 0f) return
        color = LauncherApp.instance.prefs.iconColor
        val cx = headCx()
        CatParts.drawHead(c, context, expr(), color, cx, panel.top, u)
        fillPaint.shader = tileShader()
        c.drawRoundRect(panel, radius, radius, fillPaint)
        c.save()
        c.clipPath(clipPath)
        drawInside(c)
        c.restore()
        shinePaint.strokeWidth = 10f * u
        val sy = panel.top + 28f * u
        if (panel.width() > 2 * radius) c.drawLine(panel.left + radius, sy, panel.right - radius, sy, shinePaint)
        linePaint.strokeWidth = 16f * u
        c.drawRoundRect(panel, radius, radius, linePaint)
        drawContent(c)
        CatParts.drawPaws(c, context, cx, panel.top, u)
    }

    /** A colour of the icons' cyan family, moved to the chosen 아이콘 색. */
    protected fun cyan(c: Int) = IconColors.map(c, color)

    private fun tileShader(): Shader = if (paperTile) {
        LinearGradient(0f, panel.top, 0f, panel.bottom, Color.WHITE, PAPER_2, Shader.TileMode.CLAMP)
    } else {
        LinearGradient(
            panel.left, panel.top, panel.right, panel.bottom,
            intArrayOf(cyan(0xFF7FE6E4.toInt()), cyan(0xFF4BD5D6.toInt()), cyan(0xFF2E9497.toInt())),
            floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP,
        )
    }

    /** Drawn inside the tile (clipped), under the outline. */
    protected open fun drawInside(c: Canvas) {}

    /** Drawn over the tile, under the paws. */
    protected abstract fun drawContent(c: Canvas)

    /** The part of the tile clear of the outline and the paws. */
    protected fun contentArea(out: RectF, side: Float = dp(12f)): RectF {
        out.set(panel.left + side, panel.top + CatParts.BELOW * u + dp(6f), panel.right - side, panel.bottom - dp(10f))
        return out
    }

    /** White (or [fill]) text with the icons' dark outline, centred on [x]. */
    protected fun outlined(c: Canvas, s: String, x: Float, y: Float, size: Float, fill: Int = Color.WHITE) {
        val tf = Fonts.regular(bold = true)
        textFill.typeface = tf; textLine.typeface = tf
        textFill.textSize = size; textLine.textSize = size
        textLine.strokeWidth = min(size * 0.16f, max(dp(2f), 14f * u))
        textFill.color = fill
        c.drawText(s, x, y, textLine)
        c.drawText(s, x, y, textFill)
    }

    // ---- live updates while on screen
    private var receiver: BroadcastReceiver? = null
    protected open fun filter(): IntentFilter? = null
    protected open fun onBroadcast(i: Intent) = invalidate()

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        val f = filter() ?: return
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) { if (intent != null) onBroadcast(intent) }
        }
        receiver = r
        // system broadcasts only (time tick, battery)
        val sticky = try {
            if (Build.VERSION.SDK_INT >= 33) context.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else context.registerReceiver(r, f)
        } catch (e: Exception) { LauncherApp.dlog("widget receiver failed: $e"); null }
        sticky?.let { onBroadcast(it) }
        invalidate()
    }

    override fun onDetachedFromWindow() {
        receiver?.let { try { context.unregisterReceiver(it) } catch (_: Exception) { } }
        receiver = null
        super.onDetachedFromWindow()
    }

    companion object {
        const val INK = 0xFF111111.toInt()
        const val TEXT = 0xFF1A2B2C.toInt()
        const val MUTED = 0xFF5B6E6F.toInt()
        const val PAPER_2 = 0xFFE9F4F4.toInt()
        val WEEK = arrayOf("일요일", "월요일", "화요일", "수요일", "목요일", "금요일", "토요일")
    }
}

/** 시계·날짜: big time with 오전/오후 and a date label, or a round clock face (item.provider = "analog"). */
class ClockWidget(ctx: Context, item: HomeItem) : CatWidgetView(ctx, item) {
    private val analog get() = item.provider == CatWidgets.ANALOG
    private val area = RectF()
    private val box = RectF()
    private val bounds = Rect()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textAlign = Paint.Align.CENTER }

    override fun filter() = IntentFilter().apply {
        addAction(Intent.ACTION_TIME_TICK)
        addAction(Intent.ACTION_TIME_CHANGED)
        addAction(Intent.ACTION_TIMEZONE_CHANGED)
    }

    override fun drawContent(c: Canvas) {
        val now = Calendar.getInstance()
        contentArea(area)
        if (area.width() <= 0f || area.height() <= 0f) return
        if (analog) drawAnalog(c, now) else drawDigital(c, now)
    }

    private fun drawDigital(c: Canvas, now: Calendar) {
        val h24 = DateFormat.is24HourFormat(context)
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val time = String.format("%d:%02d", if (h24) hour else (hour + 11) % 12 + 1, minute)
        val ampm = if (h24) null else if (hour < 12) "오전" else "오후"
        val date = "${now.get(Calendar.MONTH) + 1}월 ${now.get(Calendar.DAY_OF_MONTH)}일 ${WEEK[now.get(Calendar.DAY_OF_WEEK) - 1]}"
        contentDescription = "아늑단 시계: ${ampm?.let { "$it " } ?: ""}$time, $date"

        val pillH = (area.height() * 0.22f).coerceIn(dp(20f), dp(34f))
        val gap = dp(8f)
        box.set(area.left, area.top, area.right, area.bottom - pillH - gap)

        // the time: digits as tall as fits, then narrowed to the width
        textFill.typeface = Fonts.regular(true)
        textFill.textSize = 100f
        textFill.getTextBounds("0123456789", 0, 10, bounds)
        var size = 100f * box.height() * 0.8f / max(1, bounds.height())
        val ampmGap = dp(6f)
        fun widthAt(s: Float): Float {
            textFill.textSize = s
            var w = textFill.measureText(time)
            if (ampm != null) { textFill.textSize = s * 0.3f; w += textFill.measureText(ampm) + ampmGap }
            return w
        }
        val maxW = box.width() * 0.94f
        val w0 = widthAt(size)
        if (w0 > maxW) size *= maxW / w0
        textFill.textSize = size
        textFill.getTextBounds("0123456789", 0, 10, bounds)
        val tw = textFill.measureText(time)
        textFill.textSize = size * 0.3f
        val aw = if (ampm != null) textFill.measureText(ampm) else 0f
        val total = tw + if (ampm != null) aw + ampmGap else 0f
        val left = area.centerX() - total / 2
        val baseline = box.centerY() + bounds.height() / 2f
        if (ampm != null) outlined(c, ampm, left + aw / 2, baseline, size * 0.3f)
        outlined(c, time, left + total - tw / 2, baseline, size)

        // the date on a paper label
        small.typeface = Fonts.regular(true)
        small.textSize = pillH * 0.52f
        val maxPill = area.width()
        val text = TextUtils.ellipsize(date, small, maxPill - pillH, TextUtils.TruncateAt.END).toString()
        val pw = min(maxPill, small.measureText(text) + pillH * 1.1f)
        box.set(area.centerX() - pw / 2, area.bottom - pillH, area.centerX() + pw / 2, area.bottom)
        label(c, box)
        c.drawText(text, box.centerX(), box.centerY() - (small.ascent() + small.descent()) / 2, small)
    }

    /** Paper label with the icons' outline. */
    private fun label(c: Canvas, r: RectF) {
        p.shader = LinearGradient(0f, r.top, 0f, r.bottom, Color.WHITE, PAPER_2, Shader.TileMode.CLAMP)
        p.style = Paint.Style.FILL
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.color = INK
        p.strokeWidth = 12f * u
        c.drawRoundRect(r, r.height() / 2, r.height() / 2, p)
    }

    private fun drawAnalog(c: Canvas, now: Calendar) {
        val hour = now.get(Calendar.HOUR_OF_DAY)
        val minute = now.get(Calendar.MINUTE)
        val day = now.get(Calendar.DAY_OF_MONTH)
        val week = WEEK[now.get(Calendar.DAY_OF_WEEK) - 1]
        contentDescription = "아늑단 시계: 아날로그 ${hour}시 ${minute}분, ${day}일 $week"
        // a wide widget has the date beside the clock face; a square one shows the day in a window at 3 o'clock
        val wide = area.width() >= area.height() * 1.55f
        val r = min(area.width(), area.height()) / 2f - dp(2f)
        if (r <= 0f) return
        val blockW = r * 1.5f
        val gap = r * 0.35f
        val groupLeft = area.centerX() - (2 * r + gap + blockW) / 2
        val cx = if (wide) groupLeft + r else area.centerX()
        val cy = area.centerY()
        if (wide) {
            val bx = groupLeft + 2 * r + gap + blockW / 2
            outlined(c, "${now.get(Calendar.MONTH) + 1}월", bx, cy - r * 0.55f, r * 0.26f)
            outlined(c, "$day", bx, cy + r * 0.28f, r * 0.78f)
            small.typeface = Fonts.regular(true)
            small.textSize = r * 0.24f
            val pw = min(blockW + gap * 0.8f, small.measureText(week) + r * 0.36f * 1.1f)
            box.set(bx - pw / 2, cy + r * 0.48f, bx + pw / 2, cy + r * 0.48f + r * 0.36f)
            label(c, box)
            c.drawText(week, box.centerX(), box.centerY() - (small.ascent() + small.descent()) / 2, small)
        }
        // dial
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(0f, cy - r, 0f, cy + r, Color.WHITE, PAPER_2, Shader.TileMode.CLAMP)
        c.drawCircle(cx, cy, r, p)
        p.shader = null
        p.style = Paint.Style.STROKE
        p.color = INK
        p.strokeWidth = min(16f * u, r * 0.07f)
        c.drawCircle(cx, cy, r, p)
        // hour marks
        for (i in 0 until 12) {
            val major = i % 3 == 0
            val a = Math.toRadians(i * 30.0)
            val r1 = r * if (major) 0.74f else 0.8f
            val r2 = r * 0.87f
            p.strokeWidth = r * if (major) 0.055f else 0.032f
            c.drawLine(cx + r1 * sin(a).toFloat(), cy - r1 * cos(a).toFloat(), cx + r2 * sin(a).toFloat(), cy - r2 * cos(a).toFloat(), p)
        }
        if (!wide) {
            // day of the month in a small window at 3 o'clock, like a watch
            small.typeface = Fonts.regular(true)
            small.textSize = r * 0.17f
            val ww = max(r * 0.3f, small.measureText("$day") + r * 0.12f)
            box.set(cx + r * 0.5f - ww / 2, cy - r * 0.13f, cx + r * 0.5f + ww / 2, cy + r * 0.13f)
            p.style = Paint.Style.FILL
            p.color = Color.WHITE
            c.drawRoundRect(box, r * 0.05f, r * 0.05f, p)
            p.style = Paint.Style.STROKE
            p.color = INK
            p.strokeWidth = r * 0.025f
            c.drawRoundRect(box, r * 0.05f, r * 0.05f, p)
            c.drawText("$day", box.centerX(), box.centerY() - (small.ascent() + small.descent()) / 2, small)
        }
        // hands
        val ha = Math.toRadians(((hour % 12) + minute / 60.0) * 30.0)
        val ma = Math.toRadians(minute * 6.0)
        p.strokeWidth = r * 0.09f
        c.drawLine(cx, cy, cx + r * 0.48f * sin(ha).toFloat(), cy - r * 0.48f * cos(ha).toFloat(), p)
        p.strokeWidth = r * 0.06f
        c.drawLine(cx, cy, cx + r * 0.72f * sin(ma).toFloat(), cy - r * 0.72f * cos(ma).toFloat(), p)
        // centre
        p.style = Paint.Style.FILL
        p.color = 0xFFEC7474.toInt()
        c.drawCircle(cx, cy, r * 0.075f, p)
        p.style = Paint.Style.STROKE
        p.color = INK
        p.strokeWidth = r * 0.03f
        c.drawCircle(cx, cy, r * 0.075f, p)
    }
}

/** 배터리: the tile fills up like a cup with the battery level; pink when low, a bolt while charging. */
class BatteryWidget(ctx: Context, item: HomeItem) : CatWidgetView(ctx, item) {
    override val paperTile = true
    private var level = -1
    private var charging = false
    private var full = false
    private val area = RectF()
    private val wave = Path()
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeJoin = Paint.Join.ROUND }
    private val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT; textAlign = Paint.Align.CENTER }
    private val bolt = Path()

    init {
        // current state right away (also for the picture in the widget list)
        try {
            val f = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            val i = if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(null, f, Context.RECEIVER_EXPORTED) else ctx.registerReceiver(null, f)
            i?.let { read(it) }
        } catch (_: Exception) { }
    }

    private val low get() = level in 0..15 && !charging
    override fun expr() = if (charging || low) "open" else "happy"
    override fun filter() = IntentFilter(Intent.ACTION_BATTERY_CHANGED)
    override fun onBroadcast(i: Intent) { read(i); invalidate() }

    private fun read(i: Intent) {
        val l = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val s = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        level = if (l >= 0 && s > 0) (l * 100 / s).coerceIn(0, 100) else -1
        val st = i.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        // only while plugged in: some phones keep reporting "charging" for a moment after the cable comes out
        full = plugged && (st == BatteryManager.BATTERY_STATUS_FULL || level >= 100)
        charging = plugged && (st == BatteryManager.BATTERY_STATUS_CHARGING || full)
        contentDescription = "아늑단 배터리: ${if (level >= 0) "$level%" else "알 수 없음"}${if (charging) ", 충전 중" else ""}"
    }

    override fun drawInside(c: Canvas) {
        if (level < 0) return
        val top = panel.bottom - panel.height() * level / 100f
        val amp = max(dp(2f), 9f * u)
        val n = 4
        val step = panel.width() / n
        wave.reset()
        wave.moveTo(panel.left, top)
        for (i in 0 until n) {
            val x0 = panel.left + i * step
            wave.quadTo(x0 + step / 2, top + if (i % 2 == 0) amp else -amp, x0 + step, top)
        }
        wave.lineTo(panel.right, panel.bottom)
        wave.lineTo(panel.left, panel.bottom)
        wave.close()
        val colors = if (low) intArrayOf(0xFFF7B0B0.toInt(), 0xFFEF8A8A.toInt(), 0xFFDC6262.toInt())
        else intArrayOf(cyan(0xFF7FE6E4.toInt()), cyan(0xFF4BD5D6.toInt()), cyan(0xFF2E9497.toInt()))
        p.style = Paint.Style.FILL
        p.shader = LinearGradient(panel.left, top, panel.right, panel.bottom, colors, floatArrayOf(0f, 0.5f, 1f), Shader.TileMode.CLAMP)
        c.drawPath(wave, p)
        p.shader = null
    }

    override fun drawContent(c: Canvas) {
        contentArea(area)
        if (area.width() <= 0f || area.height() <= 0f) return
        val text = if (level >= 0) "$level%" else "?"
        textFill.typeface = Fonts.regular(true)
        var size = min(area.height() * 0.42f, area.width() * 0.5f)
        textFill.textSize = size
        val w = textFill.measureText(text)
        if (w > area.width() * 0.9f) size *= area.width() * 0.9f / w
        val status = when {
            full -> "가득 찼어요"
            charging -> "충전 중"
            low -> "충전이 필요해요"
            else -> "남은 배터리"
        }
        small.typeface = Fonts.regular(true)
        small.textSize = max(sp(11f), size * 0.26f).coerceAtMost(area.height() * 0.16f)
        val gap = dp(4f)
        val blockH = size * 0.75f + gap + small.textSize
        val baseline = area.centerY() - blockH / 2 + size * 0.75f
        outlined(c, text, area.centerX(), baseline, size)
        val sw = small.measureText(status)
        val sy = baseline + gap + small.textSize
        val boltW = if (charging) small.textSize * 0.7f else 0f
        c.drawText(status, area.centerX() + boltW / 2, sy, small)
        if (charging) drawBolt(c, area.centerX() - sw / 2 - boltW * 0.4f, sy - small.textSize * 0.38f, small.textSize * 0.62f)
    }

    /** Gold lightning bolt with the icons' outline, centred on (x, y), [s] tall from centre to tip. */
    private fun drawBolt(c: Canvas, x: Float, y: Float, s: Float) {
        bolt.reset()
        bolt.moveTo(x + 0.15f * s, y - s)
        bolt.lineTo(x - 0.55f * s, y + 0.12f * s)
        bolt.lineTo(x - 0.02f * s, y + 0.12f * s)
        bolt.lineTo(x - 0.18f * s, y + s)
        bolt.lineTo(x + 0.55f * s, y - 0.18f * s)
        bolt.lineTo(x + 0.02f * s, y - 0.18f * s)
        bolt.close()
        p.style = Paint.Style.FILL
        p.color = 0xFFE4B01C.toInt()
        c.drawPath(bolt, p)
        p.style = Paint.Style.STROKE
        p.color = INK
        p.strokeWidth = max(dp(1f), s * 0.16f)
        c.drawPath(bolt, p)
    }
}

/** 검색창: a paper bar with the cat peeking over its left end; tapping opens the app search. */
class SearchWidget(ctx: Context, item: HomeItem) : CatWidgetView(ctx, item) {
    override val paperTile = true
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = INK; strokeCap = Paint.Cap.ROUND }
    private val hint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = MUTED }

    override fun layoutTile(w: Int, h: Int) {
        val pad = dp(4f)
        u = unitFor(w - 2 * pad)
        var avail = h - 2 * pad - CatParts.EDGE * u
        if (avail < dp(30f)) { // a short cell: smaller cat so the bar still fits
            u = max(dp(0.08f), (h - 2 * pad - dp(30f)) / CatParts.EDGE)
            avail = h - 2 * pad - CatParts.EDGE * u
        }
        val ph = avail.coerceAtMost(dp(58f))
        val top = pad + (h - 2 * pad - (CatParts.EDGE * u + ph)) / 2 + CatParts.EDGE * u
        panel.set(pad, top, w - pad, top + ph)
        radius = ph / 2
    }

    override fun headCx() = panel.left + radius * 0.5f + CatParts.HEAD_CX * u

    override fun drawContent(c: Canvas) {
        val ph = panel.height()
        val gr = ph * 0.17f
        val gx = panel.left + radius * 0.75f + gr
        val gy = panel.centerY() - gr * 0.15f
        p.strokeWidth = max(dp(1.5f), 13f * u)
        c.drawCircle(gx, gy, gr, p)
        p.strokeWidth = max(dp(2f), 16f * u)
        c.drawLine(gx + gr * 0.72f, gy + gr * 0.72f, gx + gr * 1.45f, gy + gr * 1.45f, p)
        hint.typeface = Fonts.regular(false)
        hint.textSize = ph * 0.36f
        val tx = gx + gr * 2.1f
        val text = TextUtils.ellipsize("앱 검색", hint, panel.right - radius * 0.6f - tx, TextUtils.TruncateAt.END).toString()
        c.drawText(text, tx, panel.centerY() - (hint.ascent() + hint.descent()) / 2, hint)
    }
}

/** 메모: lined paper with the note (item.title); tapping opens the editor. */
class MemoWidget(ctx: Context, item: HomeItem) : CatWidgetView(ctx, item) {
    override val paperTile = true
    override fun expr() = "open"
    private val area = RectF()
    private val tp = TextPaint(Paint.ANTI_ALIAS_FLAG).apply { color = TEXT }
    private val rule = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }

    init {
        contentDescription = "아늑단 메모: " + item.title.ifEmpty { "비어 있음" }
    }

    override fun drawContent(c: Canvas) {
        contentArea(area, dp(14f))
        if (area.width() <= 0f || area.height() <= 0f) return
        tp.typeface = Fonts.regular(false)
        tp.textSize = sp(15f) * LauncherApp.instance.prefs.iconScale / 100f
        val fm = tp.fontMetrics
        val lineH = fm.descent - fm.ascent // StaticLayout's line height without extra padding
        val lines = max(1, (area.height() / lineH).toInt())
        val empty = item.title.isBlank()
        tp.color = if (empty) MUTED else TEXT
        val text = if (empty) "눌러서 메모를 써요" else item.title
        val layout = StaticLayout.Builder.obtain(text, 0, text.length, tp, area.width().toInt())
            .setMaxLines(lines).setEllipsize(TextUtils.TruncateAt.END).setIncludePad(false).build()
        // ruled lines under every line of text, and on to the bottom
        rule.color = cyan(0xFFBFE9E8.toInt())
        rule.strokeWidth = max(dp(1f), 4f * u)
        for (i in 0 until lines) {
            val y = area.top + i * lineH - fm.ascent + fm.descent * 0.7f
            c.drawLine(area.left - dp(2f), y, area.right + dp(2f), y, rule)
        }
        c.save()
        c.translate(area.left, area.top)
        layout.draw(c)
        c.restore()
    }
}
