package com.anuki.launcher

import android.app.Activity
import android.app.AlertDialog
import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.widget.Toast
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * New-version check against this project's GitHub releases.
 * Release tags are v1.0.<build number> and the app's versionCode is that same build number.
 * Downloads go through the phone's download manager; installing is done by tapping the finished download
 * (the app itself never installs anything, so it doesn't need the "install apps" permission).
 */
object Updater {
    private const val LATEST = "https://api.github.com/repos/jpnjb0918-glitch/anukdan/releases/latest"
    private const val STANDARD = "anuki-launcher.apk"
    private const val BADGE = "anuki-launcher-badge.apk"
    private const val DAY = 20L * 60 * 60 * 1000

    class Release(val tag: String, val number: Long, val standardUrl: String?, val badgeUrl: String?, val date: String)

    fun currentNumber(ctx: Context): Long = try {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
    } catch (_: Exception) { 0L }

    fun currentName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: "?"
    } catch (_: Exception) { "?" }

    private fun isDebug(ctx: Context) = (ctx.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /** Latest release from GitHub, or null (no network, rate limit, …). Runs on the calling thread. */
    fun fetch(): Release? = try {
        val c = URL(LATEST).openConnection() as HttpURLConnection
        c.connectTimeout = 10000
        c.readTimeout = 10000
        c.setRequestProperty("Accept", "application/vnd.github+json")
        c.setRequestProperty("User-Agent", "anuki-launcher")
        val body = c.inputStream.bufferedReader().use { it.readText() }
        c.disconnect()
        val o = JSONObject(body)
        val tag = o.getString("tag_name")
        val number = tag.substringAfterLast('.').toLongOrNull() ?: 0L
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
        Release(tag, number, std, badge, o.optString("published_at").take(10))
    } catch (e: Exception) {
        LauncherApp.dlog("update check failed: $e")
        null
    }

    /** Automatic check when the home screen shows: at most about once a day, never in test (debug) builds. */
    fun maybeCheck(a: Activity) {
        val prefs = LauncherApp.instance.prefs
        if (isDebug(a) || !prefs.updateCheck) return
        val now = System.currentTimeMillis()
        if (now - prefs.sp.getLong("update_last_check", 0L) < DAY) return
        prefs.sp.edit().putLong("update_last_check", now).apply()
        Thread {
            val r = fetch() ?: return@Thread
            if (r.number > currentNumber(a)) a.runOnUiThread { if (!a.isFinishing && !a.isDestroyed) ask(a, r) }
        }.start()
    }

    /** 설정 → 업데이트 확인. */
    fun checkNow(a: Activity) {
        Toast.makeText(a, "새 버전을 확인하는 중…", Toast.LENGTH_SHORT).show()
        Thread {
            val r = fetch()
            a.runOnUiThread {
                if (a.isFinishing || a.isDestroyed) return@runOnUiThread
                val cur = currentNumber(a)
                LauncherApp.dlog("update: current $cur, latest ${r?.number}")
                when {
                    r == null -> Toast.makeText(a, "확인하지 못했어요. 인터넷 연결을 확인해 주세요.", Toast.LENGTH_LONG).show()
                    r.number > cur -> ask(a, r)
                    else -> Toast.makeText(a, "최신 버전이에요 (${r.tag})", Toast.LENGTH_LONG).show()
                }
            }
        }.start()
    }

    private fun ask(a: Activity, r: Release) {
        AlertDialog.Builder(a).setTitle("새 버전이 있어요")
            .setMessage("지금 버전: v${currentName(a)}\n새 버전: ${r.tag} (${r.date})\n\n업데이트하시겠습니까?")
            .setPositiveButton("예") { _, _ -> chooseEdition(a, r) }
            .setNegativeButton("나중에", null)
            .show()
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
                    download(a, r, r.standardUrl, "anuki-launcher-${r.tag}.apk", "기본판")
                    AlertDialog.Builder(a).setTitle("기본판을 받는 중이에요")
                        .setMessage("알림창에 다운로드가 보여요. 끝나면 '다운로드 완료' 알림을 누르거나, 내 파일 → 다운로드에서 " +
                            "anuki-launcher-${r.tag}.apk 를 눌러 설치하세요.\n\n지금 쓰는 앱 위에 덮어 설치돼서 홈 화면 배치와 설정은 그대로예요.")
                        .setPositiveButton("확인", null)
                        .setNeutralButton("다운로드 목록 열기") { _, _ -> openDownloads(a) }
                        .show()
                } else {
                    download(a, r, r.badgeUrl, "anuki-launcher-badge-${r.tag}.apk", "배지판")
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

    private fun download(a: Activity, r: Release, url: String?, file: String, edition: String) {
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
