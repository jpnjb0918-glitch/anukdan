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
        if (rows.isEmpty()) {
            setContentView(TextView(this).apply { text = "추가할 수 있는 위젯이 없어요"; gravity = Gravity.CENTER; textSize = 16f })
            return
        }
        lv.adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convertView: View?, parent: ViewGroup?): View {
                val r = rows[position]
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
            val info = rows[position].info
            setResult(RESULT_OK, Intent().putExtra("provider", info.provider).putExtra("user", info.profile))
            finish()
        }
        setContentView(lv)
    }

    private fun dp(v: Float) = (v * resources.displayMetrics.density + 0.5f).toInt()
}
