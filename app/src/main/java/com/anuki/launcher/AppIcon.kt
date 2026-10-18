package com.anuki.launcher

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher's own icon in app lists. Each choice is an <activity-alias> of SettingsActivity with its
 * own icon (see AndroidManifest.xml); exactly one alias is enabled at a time.
 */
object AppIcon {
    val OPTIONS = listOf(
        "IconTeal" to "청록 (기본)",
        "IconPink" to "핑크",
        "IconGold" to "골드",
        "IconNavy" to "네이비",
        "IconGlobe" to "지구본 고양이",
    )
    private const val DEFAULT = "IconTeal"

    private fun component(ctx: Context, alias: String) = ComponentName(ctx.packageName, "com.anuki.launcher.$alias")

    fun current(ctx: Context): String {
        val pm = ctx.packageManager
        for ((alias, _) in OPTIONS) {
            if (pm.getComponentEnabledSetting(component(ctx, alias)) == PackageManager.COMPONENT_ENABLED_STATE_ENABLED) return alias
        }
        return DEFAULT
    }

    fun label(ctx: Context) = OPTIONS.firstOrNull { it.first == current(ctx) }?.second ?: OPTIONS[0].second

    fun set(ctx: Context, alias: String) {
        val pm = ctx.packageManager
        // enable the new one first so the app never disappears from the app list
        pm.setComponentEnabledSetting(component(ctx, alias), PackageManager.COMPONENT_ENABLED_STATE_ENABLED, PackageManager.DONT_KILL_APP)
        for ((other, _) in OPTIONS) {
            if (other == alias) continue
            pm.setComponentEnabledSetting(component(ctx, other), PackageManager.COMPONENT_ENABLED_STATE_DISABLED, PackageManager.DONT_KILL_APP)
        }
        android.util.Log.d("Anuki", "app icon -> $alias")
    }
}
