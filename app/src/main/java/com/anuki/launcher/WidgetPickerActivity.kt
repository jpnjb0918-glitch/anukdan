package com.anuki.launcher

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProviderInfo
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.UserManager
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.TextView
import java.text.Collator
import java.util.Locale
import kotlin.math.ceil

/** Lists every widget provider (all profiles); returns the chosen provider + user to HomeActivity. */
class WidgetPickerActivity : Activity() {
    private class Row(val info: AppWidgetProviderInfo, val app: String, val label: String)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = "위젯 추가"
        val awm = AppWidgetManager.getInstance(this)
        val um = getSystemService(UserManager::class.java)
        val pm = packageManager
        val collator = Collator.getInstance(Locale.KOREAN)
        val rows = um.userProfiles.flatMap { u ->
            try { awm.getInstalledProvidersForProfile(u) } catch (_: Exception) { emptyList() }
        }.map { info ->
            val appLabel = try {
                pm.getApplicationLabel(pm.getApplicationInfo(info.provider.packageName, 0)).toString()
            } catch (_: Exception) { info.provider.packageName }
            Row(info, appLabel, info.loadLabel(pm) ?: appLabel)
        }.sortedWith { a, b ->
            val c = collator.compare(a.app, b.app)
            if (c != 0) c else collator.compare(a.label, b.label)
        }

        val prefs = LauncherApp.instance.prefs
        val cellW = resources.displayMetrics.widthPixels / prefs.homeCols.toFloat()
        val cellH = resources.displayMetrics.heightPixels * 0.78f / prefs.homeRows
        val density = resources.displayMetrics.densityDpi

        val lv = ListView(this)
        // the built-in 아늑단 widgets first, then the phone's widgets
        val cats = CatWidgets.KINDS
        val sysHeader = cats.size + 1
        val total = cats.size + 2 + rows.size
        fun header(t: String) = TextView(this).apply {
            text = t; textSize = 13f; setTypeface(typeface, Typeface.BOLD); setTextColor(Palette.TEAL)
            setPadding(dp(16f), dp(16f), dp(16f), dp(6f))
        }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = total
            override fun getItem(position: Int) = position
            override fun getItemId(position: Int) = position.toLong()
            override fun getViewTypeCount() = 3
            override fun getItemViewType(position: Int) = when {
                position == 0 || position == sysHeader -> 0
                position < sysHeader -> 1
                else -> 2
            }
            override fun isEnabled(position: Int) = getItemViewType(position) != 0
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                if (position == 0) return header("아늑단 위젯 · 아늑이 런처 전용")
                if (position == sysHeader) return header(if (rows.isEmpty()) "다른 앱 위젯 · 없음" else "다른 앱 위젯")
                if (position < sysHeader) return catRow(cats[position - 1], cellW, cellH)
                val r = rows[position - sysHeader - 1]
                val holder = (convertView as? LinearLayout) ?: LinearLayout(this@WidgetPickerActivity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(16f), dp(10f), dp(16f), dp(10f))
                    addView(ImageView(context).apply { scaleType = ImageView.ScaleType.FIT_CENTER },
                        LinearLayout.LayoutParams(dp(96f), dp(72f)))
                    addView(LinearLayout(context).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(14f), 0, 0, 0)
                        addView(TextView(context).apply { textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
                        addView(TextView(context).apply { textSize = 13f; alpha = 0.7f })
                    })
                }
                val iv = holder.getChildAt(0) as ImageView
                val texts = holder.getChildAt(1) as LinearLayout
                iv.setImageDrawable(
                    try { r.info.loadPreviewImage(this@WidgetPickerActivity, density) } catch (_: Exception) { null }
                        ?: try { r.info.loadIcon(this@WidgetPickerActivity, density) } catch (_: Exception) { null },
                )
                val sx = ceil(r.info.minWidth / cellW).toInt().coerceIn(1, prefs.homeCols)
                val sy = ceil(r.info.minHeight / cellH).toInt().coerceIn(1, prefs.homeRows)
                (texts.getChildAt(0) as TextView).text = r.label
                (texts.getChildAt(1) as TextView).text = "${r.app} · ${sx}×${sy}칸"
                return holder
            }
        }
        lv.setOnItemClickListener { _, _, position, _ ->
            if (position == 0 || position == sysHeader) return@setOnItemClickListener
            if (position < sysHeader) {
                setResult(RESULT_OK, Intent().putExtra("cat_widget", cats[position - 1].key))
                finish()
                return@setOnItemClickListener
            }
            val info = rows[position - sysHeader - 1].info
            setResult(RESULT_OK, Intent().putExtra("provider", info.provider).putExtra("user", info.profile))
            finish()
        }
        setContentView(lv)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()

    private val previews = HashMap<String, android.graphics.Bitmap?>()

    /** A 아늑단 widget: name, what it does, and a picture of it at its real size. */
    private fun catRow(k: CatWidgets.Kind, cellW: Float, cellH: Float): View {
        val prefs = LauncherApp.instance.prefs
        val sx = k.spanX.coerceAtMost(prefs.homeCols)
        val sy = k.spanY.coerceAtMost(prefs.homeRows)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16f), dp(10f), dp(16f), dp(12f))
            contentDescription = "위젯: ${k.label}"
        }
        box.addView(TextView(this).apply { text = k.label; textSize = 15f; setTypeface(typeface, Typeface.BOLD) })
        box.addView(TextView(this).apply { text = "${k.desc} · ${sx}×${sy}칸"; textSize = 13f; alpha = 0.7f })
        val bmp = previews.getOrPut(k.key) {
            CatWidgets.preview(this, k.key, (cellW * sx).toInt(), (cellH * sy).toInt())
        }
        box.addView(ImageView(this).apply {
            setImageBitmap(bmp)
            scaleType = ImageView.ScaleType.FIT_START
            adjustViewBounds = true
            background = Palette.rounded(0xFF3E8E8E.toInt(), dp(12f).toFloat())
            setPadding(dp(8f), dp(8f), dp(8f), dp(8f))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(if (sy > 1) 150f else 90f)).apply { topMargin = dp(8f) })
        return box
    }
}
