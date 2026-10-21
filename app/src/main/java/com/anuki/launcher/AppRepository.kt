package com.anuki.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.UserHandle
import android.os.UserManager
import java.text.Collator
import java.util.Locale
import java.util.concurrent.Executors

class AppEntry(val info: LauncherActivityInfo, val label: String, val serial: Long) {
    val component: ComponentName get() = info.componentName
    val user: UserHandle get() = info.user
    val packageName: String get() = info.componentName.packageName
    val key: String = keyOf(info.componentName, serial)
    val chosung: String = Hangul.chosung(label)
    val isSystem: Boolean = (info.applicationInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
    val isGame: Boolean = info.applicationInfo.category == ApplicationInfo.CATEGORY_GAME
    /** The app's own category (ApplicationInfo.CATEGORY_*), -1 when it doesn't say; used by the 종류별 drawer. */
    val category: Int = if (isGame || (info.applicationInfo.flags and ApplicationInfo.FLAG_IS_GAME) != 0) ApplicationInfo.CATEGORY_GAME
        else info.applicationInfo.category
    val installTime: Long = try { info.firstInstallTime } catch (_: Exception) { 0L }

    companion object {
        fun keyOf(c: ComponentName, serial: Long) = c.flattenToShortString() + "#" + serial
    }
}

/** Loads launchable activities for every user profile and keeps them up to date. */
class AppRepository(private val ctx: Context) {
    val launcherApps: LauncherApps = ctx.getSystemService(LauncherApps::class.java)
    val userManager: UserManager = ctx.getSystemService(UserManager::class.java)
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = ArrayList<() -> Unit>()

    var all: List<AppEntry> = emptyList()
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()
    var loaded = false
        private set

    /** Packages uninstalled since the home screen last looked; their icons get removed from the layout. */
    val removedPackages = HashSet<String>()

    /** Packages newly installed since the home screen last looked (for 새 앱을 홈 화면에 추가). */
    val addedPackages = HashSet<String>()

    /** Packages changed since the last reload: only their icons are drawn again. */
    private val changed = HashSet<String>()
    private val reloadLater = Runnable { reload() }

    /** Several apps often change in a row (Play Store updates): wait a moment and reload once for all of them. */
    private fun changedSoon(pkgs: Collection<String>) {
        for (p in pkgs) { changed += p; IconPacks.forget(p) }
        mainHandler.removeCallbacks(reloadLater)
        mainHandler.postDelayed(reloadLater, 300)
    }

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
            if (packageName != null) { removedPackages += packageName; changedSoon(listOf(packageName)) }
        }
        override fun onPackageAdded(packageName: String?, user: UserHandle?) {
            packageName?.let { if (user == null || user == android.os.Process.myUserHandle()) addedPackages += it; changedSoon(listOf(it)) }
        }
        override fun onPackageChanged(packageName: String?, user: UserHandle?) { packageName?.let { changedSoon(listOf(it)) } }
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
            changedSoon(packageNames?.toList() ?: emptyList())
        }
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) {
            changedSoon(packageNames?.toList() ?: emptyList())
        }
    }

    init {
        launcherApps.registerCallback(callback, mainHandler)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun reload(done: (() -> Unit)? = null) {
        mainHandler.removeCallbacks(reloadLater)
        // the first load has nothing cached yet; later ones only redraw what changed
        val what: Set<String>? = if (!loaded && changed.isEmpty()) null else HashSet(changed)
        changed.clear()
        io.execute {
            val t0 = System.currentTimeMillis()
            val collator = Collator.getInstance(Locale.KOREAN)
            val list = ArrayList<AppEntry>()
            for (user in userManager.userProfiles) {
                val serial = userManager.getSerialNumberForUser(user)
                val acts = try { launcherApps.getActivityList(null, user) } catch (_: Exception) { emptyList() }
                for (info in acts) {
                    val label = info.label?.toString()?.trim().takeUnless { it.isNullOrEmpty() } ?: info.componentName.packageName
                    list += AppEntry(info, label, serial)
                }
            }
            list.sortWith { a, b -> collator.compare(a.label, b.label) }
            val map = LauncherApp.instance.icons.computeAutoMap(list)
            when (val mode = LauncherApp.instance.icons.mode()) {
                "none" -> {}
                "builtin" -> LineIcons.names(ctx)
                else -> IconPacks.get(ctx, mode)
            }
            mainHandler.post {
                all = list
                byKey = list.associateBy { it.key }
                loaded = true
                LauncherApp.instance.icons.onAppsChanged(list, what, map)
                if (LauncherApp.debug) LauncherApp.dlog("apps loaded: ${list.size} in ${System.currentTimeMillis() - t0}ms, changed ${what?.size ?: "all"}")
                for (l in listeners.toList()) l()
                done?.invoke()
            }
        }
    }

    fun find(key: String): AppEntry? = byKey[key]

    fun firstOfPackage(pkg: String): AppEntry? = all.firstOrNull { it.packageName == pkg && it.serial == mainSerial() }

    fun mainSerial(): Long = userManager.getSerialNumberForUser(android.os.Process.myUserHandle())

    /** True when the profile that owns [serial] is currently unavailable (work profile paused etc.). */
    fun isProfilePaused(serial: Long): Boolean {
        val user = userManager.getUserForSerialNumber(serial) ?: return false
        return try { userManager.isQuietModeEnabled(user) } catch (_: Exception) { false }
    }
}
