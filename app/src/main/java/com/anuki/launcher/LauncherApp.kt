package com.anuki.launcher

import android.app.Application
import android.content.pm.ApplicationInfo
import android.util.Log

class LauncherApp : Application() {
    lateinit var prefs: Prefs
        private set
    lateinit var apps: AppRepository
        private set
    lateinit var icons: IconProvider
        private set
    lateinit var model: LayoutModel
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        debug = (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        prefs = Prefs(this)
        icons = IconProvider(this)
        apps = AppRepository(this)
        model = LayoutModel(this)
        model.load()
        apps.reload()
        Updater.register(this)
    }

    companion object {
        lateinit var instance: LauncherApp
            private set

        /** Debug builds (the emulator check) write diagnostic logs; release builds skip them. */
        var debug = false
            private set

        /** Diagnostic log for the emulator check. Wrap busy call sites in `if (debug)` so release builds don't even build the text. */
        fun dlog(msg: String) {
            if (debug) Log.d("Anuki", msg)
        }
    }
}
