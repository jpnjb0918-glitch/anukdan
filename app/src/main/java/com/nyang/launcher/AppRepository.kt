package com.nyang.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.LauncherActivityInfo
import android.content.pm.LauncherApps
import android.os.Handler
import android.os.Looper
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

    companion object {
        fun keyOf(c: ComponentName, serial: Long) = c.flattenToShortString() + "#" + serial
    }
}

/** Loads launchable activities for every user profile and keeps them up to date. */
class AppRepository(private val ctx: Context) {
    val launcherApps: LauncherApps = ctx.getSystemService(LauncherApps::class.java)
    val userManager: UserManager = ctx.getSystemService(UserManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newSingleThreadExecutor()
    private val listeners = ArrayList<() -> Unit>()

    var all: List<AppEntry> = emptyList()
        private set
    private var byKey: Map<String, AppEntry> = emptyMap()
    var loaded = false
        private set

    /** Packages uninstalled since the home screen last looked; their icons get removed from the layout. */
    val removedPackages = HashSet<String>()

    private val callback = object : LauncherApps.Callback() {
        override fun onPackageRemoved(packageName: String?, user: UserHandle?) {
            if (packageName != null) removedPackages += packageName
            reload()
        }
        override fun onPackageAdded(packageName: String?, user: UserHandle?) = reload()
        override fun onPackageChanged(packageName: String?, user: UserHandle?) = reload()
        override fun onPackagesAvailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
        override fun onPackagesUnavailable(packageNames: Array<out String>?, user: UserHandle?, replacing: Boolean) = reload()
    }

    init {
        launcherApps.registerCallback(callback, main)
    }

    fun addListener(l: () -> Unit) { listeners += l }
    fun removeListener(l: () -> Unit) { listeners -= l }

    fun reload(done: (() -> Unit)? = null) {
        io.execute {
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
            main.post {
                all = list
                byKey = list.associateBy { it.key }
                loaded = true
                LauncherApp.instance.icons.onAppsChanged(list)
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
