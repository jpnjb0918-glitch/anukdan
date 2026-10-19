package com.anuki.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.provider.Settings
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.lang.ref.WeakReference
import java.net.HttpURLConnection
import java.net.URL

/**
 * New-version check against this project's GitHub releases.
 * Release tags are v1.0.<build number> and the app's versionCode is that same build number.
 *
 * Flow: 업데이트하시겠습니까? → 예: 기본판 / 배지판 → the phone's download manager fetches the APK.
 *  - 기본판: when the download finishes the system installer opens by itself (needs "install apps" allowed once;
 *    if the launcher isn't on screen at that moment it opens the next time the launcher is shown).
 *  - 배지판: no automatic install (Play Protect blocks it when opened on the phone); install steps are shown instead.
 *  - 아니요 on the automatic question turns the automatic question off (설정 → 업데이트 확인 still works).
 */
object Updater {
    private const val REPO = "https://api.github.com/repos/jpnjb0918-glitch/anukdan"
    private const val RELEASES = "$REPO/releases?per_page=30"
    private const val STANDARD = "anuki-launcher.apk"
    private const val BADGE = "anuki-launcher-badge.apk"
    private const val DAY = 20L * 60 * 60 * 1000

    /** Newest release, plus [changes]: the "바뀐 점" lines of every release newer than the installed one, newest first. */
    class Release(
        val tag: String, val number: Long, val standardUrl: String?, val badgeUrl: String?, val date: String,
        val changes: List<String>,
    )

    /** "- …" lines under "## 바뀐 점" in a release's notes. */
    private fun changeLines(body: String): List<String> {
        val out = ArrayList<String>()
        var inside = false
        for (raw in body.lines()) {
            val l = raw.trim()
            if (l.startsWith("## ")) { inside = l.contains("바뀐 점"); continue }
            if (!inside) continue
            if (l.startsWith("- ")) out += l.removePrefix("- ").trim() else if (l.isNotEmpty() && out.isNotEmpty()) break
        }
        return out
    }

    fun currentNumber(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { 0L }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun isDebug(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private fun get(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "anuki-launcher")
        try {
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    /** First line of every commit between two release tags, newest first (works for releases made before notes existed). */
    private fun compareChanges(fromTag: String, toTag: String): List<String> = try {
        val o = JSONObject(get("$REPO/compare/$fromTag...$toTag"))
        val commits = o.getJSONArray("commits")
        (0 until commits.length()).map { commits.getJSONObject(it).getJSONObject("commit").optString("message").lineSequence().first().trim() }
            .filter { it.isNotEmpty() && !it.startsWith("Merge ") }
            .reversed()
    } catch (e: Exception) {
        LauncherApp.dlog("update compare failed: $e")
        emptyList()
    }

    /** Latest release from GitHub with what changed since [current], or null (no network, rate limit, …). Runs on the calling thread. */
    fun fetch(current: Long): Release? = try {
        val arr = org.json.JSONArray(get(RELEASES))
        val list = (0 until arr.length()).map { arr.getJSONObject(it) }
            .filter { !it.optBoolean("prerelease") && !it.optBoolean("draft") && it.optString("tag_name").startsWith("v") }
            .sortedByDescending { it.optString("tag_name").substringAfterLast('.').toLongOrNull() ?: 0L }
        val o = list.first()
        val tag = o.getString("tag_name")
        val number = tag.substringAfterLast('.').toLongOrNull() ?: 0L
        val changes = LinkedHashSet<String>()
        if (number > current) {
            // the commit list between the installed version's tag and the newest one; release notes as a fallback
            changes += compareChanges("v1.0.$current", tag)
            if (changes.isEmpty()) for (rel in list) {
                val n = rel.optString("tag_name").substringAfterLast('.').toLongOrNull() ?: 0L
                if (n <= current) break
                changes += changeLines(rel.optString("body"))
            }
        }
        var std: String? = null
        var badge: String? = null
        val assets = o.optJSONArray("assets")
        if (assets != null) for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            when (a.optString("name")) {
                STANDARD -> std = a.optString("browser_download_url")
                BADGE -> badge = a.optString("browser_download_url")
            }
        }
        Release(tag, number, std, badge, o.optString("published_at").take(10), changes.toList())
    } catch (e: Exception) {
        LauncherApp.dlog("update check failed: $e")
        null
    }

    /** The launcher screen in front, for opening the installer when a download finishes. */
    private var front: WeakReference<Activity>? = null

    fun onResumed(a: Activity) {
        front = WeakReference(a)
        val pending = LauncherApp.instance.prefs.sp.getLong("update_install_id", -1L)
        if (pending >= 0) install(a, pending)
    }

    fun onPaused(a: Activity) { if (front?.get() === a) front = null }

    /** Listens for finished downloads for the whole app process (registered in LauncherApp). */
    fun register(app: Context) {
        val r = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                val id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1L)
                onDownloadFinished(context, id)
            }
        }
        val f = IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE)
        // sent by the download manager (another app), so it has to be an exported receiver
        if (Build.VERSION.SDK_INT >= 33) app.registerReceiver(r, f, Context.RECEIVER_EXPORTED) else app.registerReceiver(r, f)
    }

    private fun onDownloadFinished(ctx: Context, id: Long) {
        val sp = LauncherApp.instance.prefs.sp
        if (id < 0 || id != sp.getLong("update_download_id", -2L)) return
        val ok = try {
            ctx.getSystemService(DownloadManager::class.java).query(DownloadManager.Query().setFilterById(id))?.use { c ->
                c.moveToFirst() && c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
            } ?: false
        } catch (_: Exception) { false }
        val edition = sp.getString("update_download_edition", "") ?: ""
        LauncherApp.dlog("update download $id finished ok=$ok edition=$edition")
        sp.edit().remove("update_download_id").apply()
        if (!ok) {
            Toast.makeText(ctx, "업데이트 파일을 받지 못했어요", Toast.LENGTH_LONG).show()
            return
        }
        if (edition != "standard") return
        sp.edit().putLong("update_install_id", id).apply()
        front?.get()?.let { install(it, id) }
    }

    /** Opens the system installer for the downloaded standard APK (asks once to allow installs from this app). */
    private fun install(a: Activity, id: Long) {
        val sp = LauncherApp.instance.prefs.sp
        if (Build.VERSION.SDK_INT >= 26 && !a.packageManager.canRequestPackageInstalls()) {
            if (sp.getBoolean("update_asked_unknown", false)) return // already showed the explanation once for this file
            sp.edit().putBoolean("update_asked_unknown", true).apply()
            AlertDialog.Builder(a).setTitle("설치를 허용해 주세요")
                .setMessage("새 버전을 바로 설치하려면 한 번만 허용이 필요해요.\n\n다음 화면에서 '이 출처 허용'(또는 '출처를 알 수 없는 앱 설치 허용')을 켜고 " +
                    "뒤로 돌아오면 설치 화면이 떠요.")
                .setPositiveButton("설정 열기") { _, _ ->
                    try {
                        a.startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:" + a.packageName)))
                    } catch (_: Exception) { }
                }
                .setNegativeButton("나중에") { _, _ -> sp.edit().remove("update_install_id").remove("update_asked_unknown").apply() }
                .show()
            return
        }
        sp.edit().remove("update_install_id").remove("update_asked_unknown").apply()
        try {
            val uri = a.getSystemService(DownloadManager::class.java).getUriForDownloadedFile(id) ?: return
            LauncherApp.dlog("update install $uri")
            a.startActivity(
                Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        } catch (e: Exception) {
            LauncherApp.dlog("update install failed: $e")
            Toast.makeText(a, "설치 화면을 열지 못했어요. 다운로드 완료 알림을 눌러 설치해 주세요.", Toast.LENGTH_LONG).show()
        }
    }

    /** Automatic check when the home screen shows: at most about once a day, never in test (debug) builds. */
    fun maybeCheck(a: Activity) {
        val prefs = LauncherApp.instance.prefs
        if (isDebug(a) || !prefs.updateCheck) return
        val now = System.currentTimeMillis()
        if (now - prefs.sp.getLong("update_last_check", 0L) < DAY) return
        prefs.sp.edit().putLong("update_last_check", now).apply()
        Thread {
            val r = fetch(currentNumber(a)) ?: return@Thread
            if (r.number > currentNumber(a)) a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) ask(a, r, auto = true) }
        }.start()
    }

    /** 설정 → 업데이트 확인. */
    fun checkNow(a: Activity) {
        Toast.makeText(a, "새 버전을 확인하는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = fetch(currentNumber(a))
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                val cur = currentNumber(a)
                LauncherApp.dlog("update: current $cur, latest ${r?.number}")
                when {
                    r == null -> Toast.makeText(a, "확인하지 못했어요. 인터넷 연결을 확인해 주세요.", Toast.LENGTH_LONG).show()
                    r.number > cur -> ask(a, r, auto = false)
                    else -> Toast.makeText(a, "최신 버전이에요 (${r.tag})", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun ask(a: Activity, r: Release, auto: Boolean) {
        AlertDialog.Builder(a).setTitle("새 버전이 있어요")
            .setMessage("지금 버전: v${currentName(a)}\n새 버전: ${r.tag} (${r.date})\n\n" + summary(r) + "업데이트하시겠습니까?")
            .setPositiveButton("예") { _, _ -> chooseEdition(a, r) }
            .setNegativeButton("아니요") { _, _ ->
                // "아니요" turns the automatic question off; checking by hand stays available
                LauncherApp.instance.prefs.updateCheck = false
                Toast.makeText(
                    a, "새 버전 알림을 껐어요. 설정 → 업데이트 확인에서 직접 확인하고, '켤 때 새 버전 알림'으로 다시 켤 수 있어요.",
                    Toast.LENGTH_LONG,
                ).show()
                LauncherApp.dlog("update: declined (auto=$auto), automatic check off")
            }
            .show()
    }

    /** Short "바뀐 점" list for the question (up to 6 lines, each kept to one line). */
    private fun summary(r: Release): String {
        if (r.changes.isEmpty()) return ""
        val shown = r.changes.take(6).joinToString("\n") { "• " + if (it.length > 46) it.take(45) + "…" else it }
        val more = if (r.changes.size > 6) "\n그 밖에 ${r.changes.size - 6}가지" else ""
        return "바뀐 점\n$shown$more\n\n"
    }

    private fun chooseEdition(a: Activity, r: Release) {
        val usingBadge = Badges.isSupported(a)
        val items = arrayOf(
            "기본판 — 알림 배지 없음, 휴대폰에서 바로 설치" + if (!usingBadge) " (지금 쓰는 판)" else "",
            "배지판 — 알림 배지 있음, 설치 방법 안내" + if (usingBadge) " (지금 쓰는 판)" else "",
        )
        AlertDialog.Builder(a).setTitle("어떤 판으로 받을까요?")
            .setItems(items) { _, which ->
                if (which == 0) {
                    download(a, r, r.standardUrl, "anuki-launcher-${r.tag}.apk", "기본판", "standard")
                    AlertDialog.Builder(a).setTitle("기본판을 받는 중이에요")
                        .setMessage("다운로드가 끝나면 설치 화면이 자동으로 떠요. '업데이트'(또는 '설치')를 누르면 돼요.\n\n" +
                            "· 처음 한 번은 '이 출처 허용'을 켜 달라는 화면이 나와요.\n" +
                            "· 그때 다른 앱을 쓰고 있으면 홈 화면으로 돌아올 때 떠요.\n" +
                            "· 지금 쓰는 앱 위에 덮어 설치돼서 홈 화면 배치와 설정은 그대로예요.")
                        .setPositiveButton("확인", null)
                        .setNeutralButton("다운로드 목록 열기") { _, _ -> openDownloads(a) }
                        .show()
                } else {
                    download(a, r, r.badgeUrl, "anuki-launcher-badge-${r.tag}.apk", "배지판", "badge")
                    badgeHowTo(a, "anuki-launcher-badge-${r.tag}.apk")
                }
            }
            .setNegativeButton("취소", null)
            .show()
    }

    /** Why the badge build gets blocked and two ways to install it anyway. */
    fun badgeHowTo(a: Activity, file: String) {
        val msg = "배지판은 다른 앱의 알림 개수를 세는 기능(알림 접근)이 있어서, 휴대폰에서 바로 열면 Google Play 프로텍트가 " +
            "\"기기 보호를 위해 앱 차단됨\"으로 막을 수 있어요.\n\n" +
            "방법 1 — Play 프로텍트 잠깐 끄기\n" +
            "① Play 스토어 → 오른쪽 위 프로필 → Play 프로텍트 → 톱니바퀴\n" +
            "② 'Play 프로텍트로 앱 검사' 끄기\n" +
            "③ 다운로드 완료 알림(또는 내 파일 → 다운로드의 $file)을 눌러 설치\n" +
            "④ 설치가 끝나면 꼭 다시 켜기\n" +
            "· 통화 중에는 끌 수 없어요.\n\n" +
            "방법 2 — PC에서 USB로 설치\n" +
            "① 휴대폰 설정 → 휴대폰 정보 → 소프트웨어 정보 → 빌드번호 7번 눌러 개발자 옵션 켜기\n" +
            "② 개발자 옵션 → USB 디버깅 켜기 → PC와 USB 연결\n" +
            "③ PC에서: adb install -r $file\n\n" +
            "두 방법 모두 Google 공식 안내에 '된다'고 적힌 방법은 아니라서, 안 되면 기본판을 쓰세요(배지만 빠지고 나머지는 같아요)."
        AlertDialog.Builder(a).setTitle("배지판 설치 방법")
            .setMessage(msg)
            .setPositiveButton("확인", null)
            .setNeutralButton("Play 프로텍트 설정 열기") { _, _ -> openPlayProtect(a) }
            .show()
    }

    private fun download(a: Activity, r: Release, url: String?, file: String, edition: String, kind: String) {
        if (url.isNullOrEmpty()) {
            Toast.makeText(a, "이 버전에는 $edition 파일이 없어요", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle("아늑이 런처 ${r.tag} ($edition)")
                .setDescription(file)
                .setMimeType("application/vnd.android.package-archive")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            // public Downloads needs no storage permission from Android 10; before that use the app's own folder
            if (Build.VERSION.SDK_INT >= 29) req.setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, file)
            else req.setDestinationInExternalFilesDir(a, Environment.DIRECTORY_DOWNLOADS, file)
            val id = a.getSystemService(DownloadManager::class.java).enqueue(req)
            LauncherApp.instance.prefs.sp.edit().putLong("update_download_id", id).putString("update_download_edition", kind)
                .remove("update_install_id").remove("update_asked_unknown").apply()
            LauncherApp.dlog("update download $edition id=$id $url")
        } catch (e: Exception) {
            LauncherApp.dlog("update download failed: $e")
            // fall back to the browser
            try { a.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { }
        }
    }

    private fun openDownloads(a: Activity) {
        try { a.startActivity(Intent(DownloadManager.ACTION_VIEW_DOWNLOADS)) } catch (_: Exception) { }
    }

    private fun openPlayProtect(a: Activity) {
        val tries = listOf(
            Intent("com.google.android.gms.settings.VERIFY_APPS_SETTINGS"),
            Intent(Intent.ACTION_VIEW, Uri.parse("market://")).setPackage("com.android.vending"),
        )
        for (i in tries) {
            try { a.startActivity(i); return } catch (_: Exception) { }
        }
        Toast.makeText(a, "Play 스토어 앱에서 직접 열어 주세요", Toast.LENGTH_SHORT).show()
    }
}
