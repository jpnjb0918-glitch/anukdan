package com.anuki.launcher

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 아늑단 날씨: current weather and today's high / low from Open-Meteo (free, no key; data CC BY 4.0,
 * "Weather data by Open-Meteo.com"). The place is a city picked by name (Open-Meteo geocoding) or the phone's
 * rough location, taken once when chosen. Stored on the widget item: provider = "lat,lon", title = place name.
 * Readings are kept for 30 minutes (also across restarts) so the home screen doesn't ask the network all the time.
 */
object Weather {
    class Now(val temp: Double, val code: Int, val day: Boolean, val max: Double, val min: Double, val at: Long)
    class Place(val name: String, val sub: String, val lat: Double, val lon: Double)

    const val MAX_AGE = 30 * 60 * 1000L
    const val SOURCE = "날씨 정보: Open-Meteo.com (CC BY 4.0)"
    private val sp by lazy { LauncherApp.instance.getSharedPreferences("anuki_weather", Context.MODE_PRIVATE) }
    private val main = Handler(Looper.getMainLooper())
    private val running = HashSet<String>()
    private val failedAt = HashMap<String, Long>()
    val listeners = ArrayList<() -> Unit>()

    fun loc(lat: Double, lon: Double) = String.format(Locale.US, "%.4f,%.4f", lat, lon)

    private fun parseLoc(s: String): Pair<Double, Double>? {
        val p = s.split(',')
        if (p.size != 2) return null
        val a = p[0].toDoubleOrNull() ?: return null
        val b = p[1].toDoubleOrNull() ?: return null
        return a to b
    }

    fun cached(loc: String): Now? {
        val t = sp.getString("w:$loc", null) ?: return null
        return try {
            val o = JSONObject(t)
            Now(o.getDouble("t"), o.getInt("c"), o.getBoolean("d"), o.getDouble("x"), o.getDouble("n"), o.getLong("at"))
        } catch (_: Exception) { null }
    }

    /** True when the last try for [loc] failed (and no reading is there to show). */
    fun failed(loc: String) = synchronized(running) { failedAt.containsKey(loc) } && cached(loc) == null

    /** Asks Open-Meteo when the kept reading is older than 30 minutes (or [force]); listeners hear about it on the main thread. */
    fun refresh(loc: String, force: Boolean = false) {
        if (loc.isEmpty()) return
        val c = cached(loc)
        val now = System.currentTimeMillis()
        if (!force && c != null && now - c.at < MAX_AGE) return
        // after a failure wait a minute before trying again on its own
        if (!force && synchronized(running) { failedAt[loc] ?: 0L } > now - 60_000) return
        val (lat, lon) = parseLoc(loc) ?: return
        synchronized(running) { if (!running.add(loc)) return }
        Thread {
            try {
                val url = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon" +
                    "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min" +
                    "&timezone=auto&forecast_days=1"
                val o = JSONObject(get(url))
                val cur = o.getJSONObject("current")
                val d = o.getJSONObject("daily")
                val r = Now(
                    cur.getDouble("temperature_2m"), cur.getInt("weather_code"), cur.optInt("is_day", 1) == 1,
                    d.getJSONArray("temperature_2m_max").getDouble(0), d.getJSONArray("temperature_2m_min").getDouble(0),
                    System.currentTimeMillis(),
                )
                sp.edit().putString(
                    "w:$loc",
                    JSONObject().put("t", r.temp).put("c", r.code).put("d", r.day).put("x", r.max).put("n", r.min).put("at", r.at).toString(),
                ).apply()
                synchronized(running) { failedAt.remove(loc) }
                LauncherApp.dlog("weather $loc: ${r.temp}° code ${r.code} ${desc(r.code)} high ${r.max} low ${r.min}")
            } catch (e: Exception) {
                synchronized(running) { failedAt[loc] = System.currentTimeMillis() }
                LauncherApp.dlog("weather $loc failed: $e")
            } finally {
                synchronized(running) { running.remove(loc) }
            }
            main.post { for (l in listeners.toList()) l() }
        }.start()
    }

    /** Cities called [q] (Open-Meteo geocoding, names in Korean where it has them); null when the search failed. */
    fun search(q: String, done: (List<Place>?) -> Unit) {
        Thread {
            val out: List<Place>? = try {
                val url = "https://geocoding-api.open-meteo.com/v1/search?name=" + URLEncoder.encode(q.trim(), "UTF-8") +
                    "&count=8&language=ko&format=json"
                val arr = JSONObject(get(url)).optJSONArray("results")
                (0 until (arr?.length() ?: 0)).map { i ->
                    val o = arr!!.getJSONObject(i)
                    val sub = listOf(o.optString("admin1"), o.optString("country")).filter { it.isNotEmpty() }.joinToString(", ")
                    Place(o.getString("name"), sub, o.getDouble("latitude"), o.getDouble("longitude"))
                }
            } catch (e: Exception) { LauncherApp.dlog("weather search '$q' failed: $e"); null }
            LauncherApp.dlog("weather search '$q': ${out?.size ?: "failed"}")
            main.post { done(out) }
        }.start()
    }

    fun hasLocationPermission(ctx: Context) =
        ctx.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** The phone's rough location once (last known, or a fresh one on Android 11+), named with the phone's geocoder. */
    @Suppress("MissingPermission")
    fun currentPlace(a: Activity, done: (Place?) -> Unit) {
        if (!hasLocationPermission(a)) { done(null); return }
        val lm = a.getSystemService(LocationManager::class.java) ?: run { done(null); return }
        fun named(l: Location) {
            Thread {
                val name = try {
                    @Suppress("DEPRECATION")
                    Geocoder(a, Locale.KOREA).getFromLocation(l.latitude, l.longitude, 1)?.firstOrNull()?.let {
                        it.subLocality ?: it.locality ?: it.subAdminArea ?: it.adminArea
                    }
                } catch (_: Exception) { null }
                val p = Place(name ?: "현재 위치", "현재 위치", l.latitude, l.longitude)
                LauncherApp.dlog("weather current place: ${p.name}")
                main.post { done(p) }
            }.start()
        }
        val last = try {
            lm.getProviders(true).mapNotNull { lm.getLastKnownLocation(it) }.maxByOrNull { it.time }
        } catch (_: Exception) { null }
        if (last != null) { named(last); return }
        if (Build.VERSION.SDK_INT >= 30) {
            val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.FUSED_PROVIDER, LocationManager.GPS_PROVIDER)
                .firstOrNull { try { lm.isProviderEnabled(it) } catch (_: Exception) { false } }
            if (provider != null) {
                try {
                    lm.getCurrentLocation(provider, null, a.mainExecutor) { l -> if (l != null) named(l) else done(null) }
                    return
                } catch (_: Exception) { }
            }
        }
        done(null)
    }

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "AnukiLauncher")
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }

    /** WMO weather codes in plain Korean. */
    fun desc(code: Int): String = when (code) {
        0 -> "맑음"
        1 -> "대체로 맑음"
        2 -> "구름 조금"
        3 -> "흐림"
        45, 48 -> "안개"
        in 51..57 -> "이슬비"
        in 61..67 -> "비"
        in 71..77 -> "눈"
        in 80..82 -> "소나기"
        85, 86 -> "눈 소나기"
        in 95..99 -> "뇌우"
        else -> "날씨"
    }

    fun kind(code: Int): String = when (code) {
        0, 1 -> "clear"
        2 -> "partly"
        45, 48 -> "fog"
        in 51..67, in 80..82 -> "rain"
        in 71..77, 85, 86 -> "snow"
        in 95..99 -> "storm"
        else -> "cloud"
    }
}

/** 아늑단 날씨: weather picture, temperature, sky and place, today's high / low; tapping shows the forecast on the web. */
class WeatherWidget(ctx: Context, item: HomeItem) : CatWidgetView(ctx, item) {
    private val area = RectF()
    private val box = RectF()
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = INK; strokeJoin = Paint.Join.ROUND; strokeCap = Paint.Cap.ROUND }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val path = Path()
    private val sample = item.id < 0 // the picture in the widget list
    private val listener: () -> Unit = { updateDesc(); invalidate() }
    private val tick = object : Runnable {
        override fun run() { Weather.refresh(item.provider); postDelayed(this, Weather.MAX_AGE) }
    }

    private val place get() = if (sample) "서울" else item.title

    private fun now(): Weather.Now? =
        if (sample) Weather.Now(21.0, 0, true, 24.0, 15.0, 0L) else Weather.cached(item.provider)

    override fun expr(): String = when (now()?.let { Weather.kind(it.code) }) { "rain", "snow", "storm" -> "open"; else -> "happy" }

    init { updateDesc() }

    private fun updateDesc() {
        val n = now()
        contentDescription = when {
            item.provider.isEmpty() && !sample -> "아늑단 날씨: 지역을 정해 주세요"
            n == null -> "아늑단 날씨: ${item.title} 받는 중"
            else -> "아늑단 날씨: $place ${n.temp.roundToInt()}° ${Weather.desc(n.code)}"
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (sample) return
        Weather.listeners += listener
        removeCallbacks(tick)
        post(tick)
    }

    override fun onDetachedFromWindow() {
        Weather.listeners -= listener
        removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    override fun drawContent(c: Canvas) {
        contentArea(area)
        if (area.width() <= 0f || area.height() <= 0f) return
        val n = now()
        if (n == null) {
            val t1 = when {
                item.provider.isEmpty() -> "지역을 정해 주세요"
                Weather.failed(item.provider) -> "날씨를 받지 못했어요"
                else -> "날씨를 받는 중…"
            }
            val t2 = if (item.provider.isEmpty()) "눌러서 고르기" else item.title
            val s = min(area.height() * 0.26f, area.width() / max(6, t1.length) * 1.5f)
            outlined(c, t1, area.centerX(), area.centerY() - s * 0.1f, s)
            outlined(c, t2, area.centerX(), area.centerY() + s * 1.1f, s * 0.72f)
            return
        }
        val temp = "${n.temp.roundToInt()}°"
        val line2 = Weather.desc(n.code) + if (place.isNotEmpty()) " · $place" else ""
        val line3 = "최고 ${n.max.roundToInt()}° · 최저 ${n.min.roundToInt()}°"
        val wide = area.width() >= area.height() * 1.45f
        if (wide) {
            val g = min(area.height(), area.width() * 0.38f)
            box.set(area.left, area.centerY() - g / 2, area.left + g, area.centerY() + g / 2)
            drawGlyph(c, box, n)
            val tx = (box.right + area.right) / 2
            val tw = area.right - box.right
            var big = min(area.height() * 0.5f, tw * 0.5f)
            textFill.textSize = big
            val w = textFill.measureText(temp)
            if (w > tw * 0.9f) big *= tw * 0.9f / w
            val small = min(big * 0.36f, fitSize(listOf(line2, line3), tw * 0.95f, big * 0.36f))
            val block = big * 0.78f + small * 2.5f
            val base = area.centerY() - block / 2 + big * 0.78f
            outlined(c, temp, tx, base, big)
            outlined(c, line2, tx, base + small * 1.3f, small)
            outlined(c, line3, tx, base + small * 2.5f, small)
        } else {
            val g = min(area.height() * 0.48f, area.width() * 0.7f)
            box.set(area.centerX() - g / 2, area.top, area.centerX() + g / 2, area.top + g)
            drawGlyph(c, box, n)
            val rest = area.bottom - box.bottom
            val big = min(rest * 0.52f, area.width() * 0.36f)
            val small = min(big * 0.42f, fitSize(listOf(line2), area.width() * 0.95f, big * 0.42f))
            val base = box.bottom + big * 0.9f
            outlined(c, temp, area.centerX(), base, big)
            if (rest > big + small * 1.2f) outlined(c, line2, area.centerX(), base + small * 1.3f, small)
        }
    }

    /** Largest size up to [want] at which every line fits in [w]. */
    private fun fitSize(lines: List<String>, w: Float, want: Float): Float {
        textFill.typeface = Fonts.regular(true)
        textFill.textSize = want
        val widest = lines.maxOf { textFill.measureText(it) }
        return if (widest > w) want * w / widest else want
    }

    // ---------------------------------------------------------------- pictures, in the icons' ink style
    private fun drawGlyph(c: Canvas, b: RectF, n: Weather.Now) {
        ink.strokeWidth = max(dp(1.5f), 12f * u)
        val s = b.width()
        when (Weather.kind(n.code)) {
            "clear" -> if (n.day) sun(c, b.centerX(), b.centerY(), s * 0.26f) else moon(c, b.centerX(), b.centerY(), s * 0.3f)
            "partly" -> {
                if (n.day) sun(c, b.left + s * 0.36f, b.top + s * 0.36f, s * 0.19f) else moon(c, b.left + s * 0.36f, b.top + s * 0.34f, s * 0.2f)
                cloud(c, b.left + s * 0.58f, b.top + s * 0.62f, s * 0.62f)
            }
            "rain" -> { cloud(c, b.centerX(), b.top + s * 0.44f, s * 0.78f); drops(c, b, s) }
            "snow" -> { cloud(c, b.centerX(), b.top + s * 0.44f, s * 0.78f); flakes(c, b, s) }
            "storm" -> { cloud(c, b.centerX(), b.top + s * 0.42f, s * 0.78f); bolt(c, b.centerX(), b.top + s * 0.78f, s * 0.2f) }
            "fog" -> {
                cloud(c, b.centerX(), b.top + s * 0.42f, s * 0.72f)
                for (i in 0..1) {
                    val y = b.top + s * (0.76f + i * 0.13f)
                    c.drawLine(b.left + s * (0.2f + i * 0.08f), y, b.right - s * (0.2f - i * 0.04f), y, ink)
                }
            }
            else -> cloud(c, b.centerX(), b.centerY(), s * 0.86f)
        }
    }

    private fun sun(c: Canvas, x: Float, y: Float, r: Float) {
        for (i in 0 until 8) {
            val a = Math.toRadians(i * 45.0)
            val cs = kotlin.math.cos(a).toFloat(); val sn = kotlin.math.sin(a).toFloat()
            c.drawLine(x + cs * r * 1.35f, y + sn * r * 1.35f, x + cs * r * 1.75f, y + sn * r * 1.75f, ink)
        }
        fill.shader = LinearGradient(x - r, y - r, x + r, y + r, 0xFFF7D774.toInt(), 0xFFE4B01C.toInt(), Shader.TileMode.CLAMP)
        c.drawCircle(x, y, r, fill)
        fill.shader = null
        c.drawCircle(x, y, r, ink)
    }

    private fun moon(c: Canvas, x: Float, y: Float, r: Float) {
        path.reset()
        val outer = Path().apply { addCircle(x, y, r, Path.Direction.CW) }
        val bite = Path().apply { addCircle(x + r * 0.55f, y - r * 0.35f, r * 0.85f, Path.Direction.CW) }
        path.op(outer, bite, Path.Op.DIFFERENCE)
        fill.shader = LinearGradient(x - r, y - r, x + r, y + r, 0xFFF7D774.toInt(), 0xFFE4B01C.toInt(), Shader.TileMode.CLAMP)
        c.drawPath(path, fill)
        fill.shader = null
        c.drawPath(path, ink)
    }

    /** A white cloud [w] wide centred on (x, y). */
    private fun cloud(c: Canvas, x: Float, y: Float, w: Float) {
        val h = w * 0.56f
        path.reset()
        val parts = Path()
        parts.addCircle(x - w * 0.2f, y + h * 0.08f, h * 0.36f, Path.Direction.CW)
        parts.addCircle(x + w * 0.06f, y - h * 0.12f, h * 0.48f, Path.Direction.CW)
        parts.addCircle(x + w * 0.3f, y + h * 0.12f, h * 0.32f, Path.Direction.CW)
        parts.addRoundRect(RectF(x - w * 0.42f, y + h * 0.04f, x + w * 0.44f, y + h * 0.44f), h * 0.2f, h * 0.2f, Path.Direction.CW)
        path.op(parts, Path(), Path.Op.UNION)
        fill.color = Color.WHITE
        c.drawPath(path, fill)
        c.drawPath(path, ink)
    }

    private fun drops(c: Canvas, b: RectF, s: Float) {
        val p = Paint(ink).apply { color = cyan(0xFF2E9497.toInt()); strokeWidth = ink.strokeWidth * 1.1f }
        for (i in 0..2) {
            val x = b.left + s * (0.32f + i * 0.18f)
            c.drawLine(x, b.top + s * 0.74f, x - s * 0.05f, b.top + s * 0.9f, p)
        }
    }

    private fun flakes(c: Canvas, b: RectF, s: Float) {
        fill.color = Color.WHITE
        for (i in 0..2) {
            val x = b.left + s * (0.32f + i * 0.18f)
            val y = b.top + s * (0.8f + (i % 2) * 0.08f)
            c.drawCircle(x, y, s * 0.045f, fill)
            c.drawCircle(x, y, s * 0.045f, ink)
        }
    }

    private fun bolt(c: Canvas, x: Float, y: Float, h: Float) {
        path.reset()
        path.moveTo(x + 0.15f * h, y - h)
        path.lineTo(x - 0.55f * h, y + 0.12f * h)
        path.lineTo(x - 0.02f * h, y + 0.12f * h)
        path.lineTo(x - 0.18f * h, y + h)
        path.lineTo(x + 0.55f * h, y - 0.18f * h)
        path.lineTo(x + 0.02f * h, y - 0.18f * h)
        path.close()
        fill.color = 0xFFE4B01C.toInt()
        c.drawPath(path, fill)
        c.drawPath(path, ink)
    }
}
