package com.nyang.launcher

import android.app.Notification
import android.app.admin.DeviceAdminReceiver
import android.appwidget.AppWidgetHost
import android.appwidget.AppWidgetHostView
import android.appwidget.AppWidgetProviderInfo
import android.content.ComponentName
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.UserManager
import android.provider.Settings
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.hypot

// ---------------------------------------------------------------- notification badges

object Badges {
    private val main = Handler(Looper.getMainLooper())
    @Volatile var counts: Map<String, Int> = emptyMap()
        private set
    val listeners = ArrayList<() -> Unit>()

    fun update(m: Map<String, Int>) {
        main.post {
            counts = m
            for (l in listeners.toList()) l()
        }
    }

    fun countFor(pkg: String, serial: Long): Int = counts["$pkg#$serial"] ?: 0

    fun isAccessGranted(ctx: Context): Boolean {
        val flat = Settings.Secure.getString(ctx.contentResolver, "enabled_notification_listeners") ?: return false
        val me = ComponentName(ctx, BadgeService::class.java)
        return flat.split(':').any { ComponentName.unflattenFromString(it) == me }
    }
}

class BadgeService : NotificationListenerService() {
    override fun onListenerConnected() = recount()
    override fun onListenerDisconnected() = Badges.update(emptyMap())
    override fun onNotificationPosted(sbn: StatusBarNotification?) = recount()
    override fun onNotificationRemoved(sbn: StatusBarNotification?) = recount()

    private fun recount() {
        val list = try { activeNotifications } catch (_: Exception) { null } ?: return
        val um = getSystemService(UserManager::class.java)
        val m = HashMap<String, Int>()
        for (s in list) {
            val n = s.notification ?: continue
            if (s.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) continue
            val k = s.packageName + "#" + um.getSerialNumberForUser(s.user)
            m[k] = (m[k] ?: 0) + 1
        }
        Badges.update(m)
    }
}

// ---------------------------------------------------------------- screen-off gesture

class LockAdminReceiver : DeviceAdminReceiver()

// ---------------------------------------------------------------- widgets

class NyangWidgetHost(ctx: Context) : AppWidgetHost(ctx, HOST_ID) {
    override fun onCreateView(context: Context, appWidgetId: Int, appWidget: AppWidgetProviderInfo?): AppWidgetHostView =
        NyangWidgetView(context)

    companion object { const val HOST_ID = 2026 }
}

/** Widget frame that detects a long press even though the widget's own views consume touches. */
class NyangWidgetView(ctx: Context) : AppWidgetHostView(ctx) {
    var item: HomeItem? = null
    var onLongPress: ((NyangWidgetView) -> Unit)? = null
    var lastRawX = 0f
        private set
    var lastRawY = 0f
        private set
    private var downX = 0f
    private var downY = 0f
    private val slop = ViewConfiguration.get(ctx).scaledTouchSlop
    private val handler = Handler(Looper.getMainLooper())
    private val longPress = Runnable {
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPress?.invoke(this)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        lastRawX = ev.rawX; lastRawY = ev.rawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = ev.rawX; downY = ev.rawY
                handler.postDelayed(longPress, ViewConfiguration.getLongPressTimeout().toLong())
            }
            MotionEvent.ACTION_MOVE -> if (hypot(ev.rawX - downX, ev.rawY - downY) > slop) handler.removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        return false
    }

    /** Static widgets that don't consume touches: keep the stream so the long-press timer is cancelled on lift. */
    override fun onTouchEvent(event: MotionEvent): Boolean {
        lastRawX = event.rawX; lastRawY = event.rawY
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> if (hypot(event.rawX - downX, event.rawY - downY) > slop) handler.removeCallbacks(longPress)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> handler.removeCallbacks(longPress)
        }
        return true
    }

    override fun onDetachedFromWindow() {
        handler.removeCallbacks(longPress)
        super.onDetachedFromWindow()
    }
}
