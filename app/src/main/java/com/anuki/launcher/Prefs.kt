package com.anuki.launcher

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** All launcher settings. Every write bumps [version] so the home screen knows to rebind. */
class Prefs(ctx: Context) {
    val sp: SharedPreferences = ctx.getSharedPreferences("anuki", Context.MODE_PRIVATE)
    /** How often each app was opened from this launcher (for the "most used" drawer order). Not a setting, so no bump. */
    private val usage: SharedPreferences = ctx.getSharedPreferences("anuki_usage", Context.MODE_PRIVATE)

    private fun intPref(key: String, def: Int) = object : ReadWriteProperty<Any?, Int> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Int = sp.getInt(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Int) {
            sp.edit().putInt(key, value).apply(); bump()
        }
    }

    private fun boolPref(key: String, def: Boolean) = object : ReadWriteProperty<Any?, Boolean> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): Boolean = sp.getBoolean(key, def)
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: Boolean) {
            sp.edit().putBoolean(key, value).apply(); bump()
        }
    }

    private fun strPref(key: String, def: String) = object : ReadWriteProperty<Any?, String> {
        override fun getValue(thisRef: Any?, property: KProperty<*>): String = sp.getString(key, def) ?: def
        override fun setValue(thisRef: Any?, property: KProperty<*>, value: String) {
            sp.edit().putString(key, value).apply(); bump()
        }
    }

    // home
    var homeCols by intPref("home_cols", 5)
    var homeRows by intPref("home_rows", 6)
    var dockCount by intPref("dock_count", 5)
    var dockBackground by boolPref("dock_bg", true)
    var homeLabels by boolPref("home_labels", true)

    // drawer
    var drawerCols by intPref("drawer_cols", 5)
    var drawerLabels by boolPref("drawer_labels", true)
    var drawerStyle by strPref("drawer_style", "grid")  // grid | pages | list | sections
    var drawerSort by strPref("drawer_sort", "name")    // name | recent | usage
    var drawerBg by strPref("drawer_bg", "solid")       // solid | glass | wallpaper

    fun launchCount(key: String): Int = usage.getInt(key, 0)
    fun countLaunch(key: String) { usage.edit().putInt(key, launchCount(key) + 1).apply() }

    // look
    var iconScale by intPref("icon_scale", 100)
    var theme by strPref("theme", "auto")            // auto | light | dark
    var catIcons by boolPref("cat_icons", true)
    var catPlate by boolPref("cat_plate", true)
    var iconPack by strPref("icon_pack", "builtin")   // builtin | none | icon pack package name

    // badges: off | dot | count
    var badgeStyle by strPref("badges", "dot")

    // gestures: none | drawer | search | notifications | settings | screen_off | widgets | app
    var gestureUp by strPref("g_up", "drawer")
    var gestureDown by strPref("g_down", "notifications")
    var gestureDouble by strPref("g_double", "none")
    var gestureUpApp by strPref("g_up_app", "")
    var gestureDownApp by strPref("g_down_app", "")
    var gestureDoubleApp by strPref("g_double_app", "")

    var firstRunDone: Boolean
        get() = sp.getBoolean("first_run", false)
        set(v) { sp.edit().putBoolean("first_run", v).apply() }

    val version: Int get() = sp.getInt("_version", 0)
    fun bump() { sp.edit().putInt("_version", version + 1).apply() }

    // ------------------------------------------------------------ hidden apps
    var hidden: Set<String>
        get() = HashSet(sp.getStringSet("hidden", emptySet()) ?: emptySet())
        set(v) { sp.edit().putStringSet("hidden", HashSet(v)).apply(); bump() }

    // ------------------------------------------------------------ per-app icon overrides
    private var overridesCache: MutableMap<String, String>? = null

    fun iconOverrides(): Map<String, String> {
        overridesCache?.let { return it }
        val m = HashMap<String, String>()
        try {
            val o = JSONObject(sp.getString("icon_overrides", "{}") ?: "{}")
            for (k in o.keys()) m[k] = o.getString(k)
        } catch (_: Exception) {
        }
        overridesCache = m
        return m
    }

    fun setIconOverride(key: String, value: String?) {
        val m = HashMap(iconOverrides())
        if (value == null) m.remove(key) else m[key] = value
        sp.edit().putString("icon_overrides", JSONObject(m as Map<*, *>).toString()).apply()
        android.util.Log.d("Anuki", "icon override $key -> $value")
        overridesCache = m
        bump()
    }

    // ------------------------------------------------------------ drawer tabs
    class Tab(var name: String, val keys: MutableSet<String>)

    fun tabs(): MutableList<Tab> {
        val out = ArrayList<Tab>()
        try {
            val arr = JSONArray(sp.getString("tabs", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val keys = HashSet<String>()
                val ka = o.optJSONArray("keys") ?: JSONArray()
                for (j in 0 until ka.length()) keys += ka.getString(j)
                out += Tab(o.optString("name", "탭"), keys)
            }
        } catch (_: Exception) {
        }
        return out
    }

    fun saveTabs(tabs: List<Tab>) {
        val arr = JSONArray()
        for (t in tabs) arr.put(JSONObject().put("name", t.name).put("keys", JSONArray(t.keys.toList())))
        sp.edit().putString("tabs", arr.toString()).apply()
        bump()
    }

    // ------------------------------------------------------------ backup
    fun exportJson(): JSONObject {
        val o = JSONObject()
        for ((k, v) in sp.all) {
            if (k == "_version") continue
            val e = JSONObject()
            when (v) {
                is Int -> e.put("t", "i").put("v", v)
                is Boolean -> e.put("t", "b").put("v", v)
                is String -> e.put("t", "s").put("v", v)
                is Long -> e.put("t", "l").put("v", v)
                is Float -> e.put("t", "f").put("v", v.toDouble())
                is Set<*> -> e.put("t", "set").put("v", JSONArray(v.map { it.toString() }))
                else -> continue
            }
            o.put(k, e)
        }
        return o
    }

    fun importJson(o: JSONObject) {
        val ed = sp.edit().clear()
        for (k in o.keys()) {
            val e = o.getJSONObject(k)
            when (e.getString("t")) {
                "i" -> ed.putInt(k, e.getInt("v"))
                "b" -> ed.putBoolean(k, e.getBoolean("v"))
                "s" -> ed.putString(k, e.getString("v"))
                "l" -> ed.putLong(k, e.getLong("v"))
                "f" -> ed.putFloat(k, e.getDouble("v").toFloat())
                "set" -> {
                    val a = e.getJSONArray("v")
                    ed.putStringSet(k, (0 until a.length()).map { a.getString(it) }.toHashSet())
                }
            }
        }
        ed.apply()
        overridesCache = null
        bump()
    }
}
