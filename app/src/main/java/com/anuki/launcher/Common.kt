package com.anuki.launcher

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/** Small helpers several screens shared as copies; one version of each lives here. */

/** The main (UI) thread, for posting results back from background work. */
val mainHandler = Handler(Looper.getMainLooper())

/**
 * Runs [work] on a background thread, then hands its result to [then] on the main thread.
 * With [alive], [then] is skipped when that screen has closed in the meantime.
 */
fun <T> inBackground(work: () -> T, alive: Activity? = null, then: (T) -> Unit) {
    Thread {
        val r = work()
        mainHandler.post { if (alive == null || (!alive.isFinishing && !alive.isDestroyed)) then(r) }
    }.start()
}

/** dp → whole pixels (sizes, paddings). */
fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()

/** dp → pixels as a float (drawing). */
fun Context.dpf(v: Float): Float = v * resources.displayMetrics.density

fun Context.toast(text: CharSequence, long: Boolean = false) =
    Toast.makeText(this, text, if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()

/** Opens a web page (or any URI) in whatever app handles it; false when nothing can. */
fun Context.openUrl(url: String): Boolean = try {
    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    true
} catch (_: Exception) { false }

/** Copies [text] and says so ("… 을(를) 복사했어요"). */
fun Context.copyText(label: String, text: String) {
    getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText(label, text))
    toast("$text 을(를) 복사했어요")
}

/**
 * Listens to broadcasts that come from the system or another app (time, battery, download manager):
 * Android 13+ wants such receivers marked exported. [receiver] may be null to just read a sticky broadcast.
 */
fun Context.registerSystemReceiver(receiver: BroadcastReceiver?, filter: IntentFilter): Intent? =
    if (Build.VERSION.SDK_INT >= 33) registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED) else registerReceiver(receiver, filter)

/** The direct child under (x, y), in this view's own coordinates (last drawn wins). */
fun ViewGroup.childUnder(x: Float, y: Float): View? {
    for (i in childCount - 1 downTo 0) {
        val c = getChildAt(i)
        if (x >= c.left && x < c.right && y >= c.top && y < c.bottom) return c
    }
    return null
}

object Net {
    /** GET [url] as text (10 s timeouts); throws on a non-200 answer. Call off the main thread. */
    fun getText(url: String, headers: Map<String, String> = emptyMap()): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 10_000
        c.setRequestProperty("User-Agent", "anuki-launcher")
        for ((k, v) in headers) c.setRequestProperty(k, v)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally { c.disconnect() }
    }
}
