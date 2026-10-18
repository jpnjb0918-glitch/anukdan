package com.nyang.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.Toast
import kotlin.math.hypot

class DragInfo(val source: Int, val item: HomeItem?, val app: AppEntry?, val view: View) {
    companion object {
        const val SRC_WORKSPACE = 0
        const val SRC_DOCK = 1
        const val SRC_FOLDER = 2
        const val SRC_DRAWER = 3
    }
}

/**
 * Long press -> PENDING (popup menu is showing). Move past the touch slop -> DRAGGING (popup closes,
 * a floating copy of the icon follows the finger). Release -> drop on a cell, an icon (makes a folder),
 * a folder (adds to it) or one of the targets in the top bar.
 */
class DragController(private val act: HomeActivity) {
    private enum class State { IDLE, PENDING, DRAGGING }

    private var state = State.IDLE
    private var info: DragInfo? = null
    private var floating: ImageView? = null
    private var startX = 0f
    private var startY = 0f
    private var offX = 0f
    private var offY = 0f
    private val slop = ViewConfiguration.get(act).scaledTouchSlop * 1.5f
    private val handler = Handler(Looper.getMainLooper())
    private var edgeRunnable: Runnable? = null
    private var edgeDir = 0

    /** where a drop would land right now */
    private var target: Target? = null

    private sealed class Target {
        class Bar(val kind: Int) : Target()
        class Cell(val container: Long, val screen: Int, val x: Int, val y: Int, val merge: HomeItem?) : Target()
    }

    val isActive: Boolean get() = state != State.IDLE

    fun startPending(info: DragInfo, rawX: Float, rawY: Float) {
        this.info = info
        startX = rawX; startY = rawY
        state = State.PENDING
    }

    fun onTouch(ev: MotionEvent) {
        if (ev.actionMasked != MotionEvent.ACTION_MOVE) Log.d("Nyang", "drag.onTouch action=${ev.actionMasked} state=$state")
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> if (state == State.PENDING) { state = State.IDLE; info = null }
            MotionEvent.ACTION_MOVE -> {
                if (state == State.PENDING && hypot(ev.rawX - startX, ev.rawY - startY) > slop) beginDrag()
                if (state == State.DRAGGING) move(ev.rawX, ev.rawY)
            }
            MotionEvent.ACTION_UP -> {
                if (state == State.DRAGGING) drop() else { state = State.IDLE; info = null }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (state == State.DRAGGING) cancel() else { state = State.IDLE; info = null }
            }
        }
    }

    private fun beginDrag() {
        val i = info ?: return
        state = State.DRAGGING
        act.closePopup()
        act.dragLayer.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)

        val v = i.view
        val bmp = snapshot(v)
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        val rootLoc = IntArray(2)
        act.dragLayer.getLocationOnScreen(rootLoc)
        offX = startX - loc[0]
        offY = startY - loc[1]
        val iv = ImageView(act).apply {
            setImageBitmap(bmp)
            layoutParams = FrameLayout.LayoutParams(bmp.width, bmp.height)
            alpha = 0.92f
            scaleX = 1.08f; scaleY = 1.08f
            elevation = 24f
        }
        act.dragLayer.addView(iv)
        floating = iv

        when (i.source) {
            DragInfo.SRC_DRAWER -> act.closeDrawer(true)
            DragInfo.SRC_FOLDER -> act.closeFolder()
            else -> v.visibility = View.INVISIBLE
        }
        act.showDropBar(i)
        move(startX, startY)
    }

    private fun snapshot(v: View): Bitmap {
        val w = maxOf(1, v.width); val h = maxOf(1, v.height)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val sx = v.scaleX; val sy = v.scaleY
        v.draw(Canvas(b))
        v.scaleX = sx; v.scaleY = sy
        return b
    }

    private fun move(rawX: Float, rawY: Float) {
        val iv = floating ?: return
        val rootLoc = IntArray(2)
        act.dragLayer.getLocationOnScreen(rootLoc)
        iv.x = rawX - offX - rootLoc[0]
        iv.y = rawY - offY - rootLoc[1]
        updateTarget(rawX, rawY)
    }

    private fun hit(v: View, rawX: Float, rawY: Float): Boolean {
        if (!v.isShown) return false
        val loc = IntArray(2)
        v.getLocationOnScreen(loc)
        return rawX >= loc[0] && rawX < loc[0] + v.width && rawY >= loc[1] && rawY < loc[1] + v.height
    }

    private fun updateTarget(rawX: Float, rawY: Float) {
        val i = info ?: return
        val item = i.item
        val sx = item?.spanX ?: 1
        val sy = item?.spanY ?: 1
        act.workspaceAllPages { it.setHighlight(null) }
        act.dock.setHighlight(null)
        target = null

        val kind = act.dropBar.kindAt(rawX, rawY)
        act.dropBar.setHot(kind)
        if (kind != null) { target = Target.Bar(kind); cancelEdge(); return }

        val draggingAppLike = item == null || item.type == HomeItem.TYPE_APP
        val isWidget = item?.type == HomeItem.TYPE_WIDGET

        if (!isWidget && hit(act.dock, rawX, rawY)) {
            cancelEdge()
            val loc = IntArray(2); act.dock.getLocationOnScreen(loc)
            val cell = act.dock.cellAt(rawX - loc[0], rawY - loc[1])
            val occupant = act.model.itemAt(HomeItem.DOCK, 0, cell[0], 0, item)
            val merge = if (draggingAppLike && occupant != null && occupant.type != HomeItem.TYPE_WIDGET) occupant else null
            act.dock.setHighlight(intArrayOf(cell[0], 0, 1, 1, if (merge != null) 1 else 0))
            target = Target.Cell(HomeItem.DOCK, 0, cell[0], 0, merge)
            return
        }

        val ws = act.workspace
        if (hit(ws, rawX, rawY)) {
            val loc = IntArray(2); ws.getLocationOnScreen(loc)
            val localX = rawX - loc[0]
            val edge = ws.width * 0.08f
            when {
                localX < edge -> scheduleEdge(-1)
                localX > ws.width - edge -> scheduleEdge(1)
                else -> cancelEdge()
            }
            val page = ws.currentPage
            if (page >= ws.pageCount) return
            val cl = ws.page(page)
            val cell = cl.cellAt(localX, rawY - loc[1], sx, sy)
            val occupant = if (sx == 1 && sy == 1) act.model.itemAt(HomeItem.DESKTOP, page, cell[0], cell[1], item) else null
            val merge = if (draggingAppLike && occupant != null && occupant.type != HomeItem.TYPE_WIDGET) occupant else null
            cl.setHighlight(intArrayOf(cell[0], cell[1], sx, sy, if (merge != null) 1 else 0))
            target = Target.Cell(HomeItem.DESKTOP, page, cell[0], cell[1], merge)
            return
        }
        cancelEdge()
    }

    private fun scheduleEdge(dir: Int) {
        if (edgeRunnable != null && edgeDir == dir) return
        cancelEdge()
        edgeDir = dir
        val r = Runnable {
            edgeRunnable = null
            val ws = act.workspace
            val next = ws.currentPage + dir
            if (next >= ws.pageCount && dir > 0) {
                // allow dragging onto a fresh page, but never leave two empty pages in a row
                val lastEmpty = act.model.items.none { it.container == HomeItem.DESKTOP && it.screen == ws.pageCount - 1 }
                if (!lastEmpty) act.addEmptyPage()
            }
            if (next in 0 until ws.pageCount) ws.snapToPage(next)
            edgeDir = 0
            scheduleEdge(dir) // keep paging while the finger stays at the edge
        }
        edgeRunnable = r
        handler.postDelayed(r, 650)
    }

    private fun cancelEdge() {
        edgeRunnable?.let { handler.removeCallbacks(it) }
        edgeRunnable = null
        edgeDir = 0
    }

    private fun finishVisuals() {
        cancelEdge()
        floating?.let { act.dragLayer.removeView(it) }
        floating = null
        act.hideDropBar()
        act.workspaceAllPages { it.setHighlight(null) }
        act.dock.setHighlight(null)
    }

    private fun cancel() {
        finishVisuals()
        state = State.IDLE
        info = null
        act.rebindAll()
    }

    private fun drop() {
        val i = info
        val t = target
        finishVisuals()
        state = State.IDLE
        info = null
        if (i == null) return
        when (t) {
            is Target.Bar -> act.onDropBar(t.kind, i)
            is Target.Cell -> place(i, t)
            null -> { }
        }
        act.model.removeTrailingEmptyPages(act.workspace.currentPage)
        act.model.save()
        act.rebindAll()
    }

    private fun place(i: DragInfo, t: Target.Cell) {
        val model = act.model
        val prefs = act.prefs
        val oldContainer = i.item?.container
        val dragged: HomeItem = i.item ?: run {
            val app = i.app ?: return
            HomeItem(model.newId(), HomeItem.TYPE_APP, HomeItem.DESKTOP, key = app.key, title = app.label)
        }
        val isNew = i.item == null

        val merge = t.merge
        if (merge != null && dragged.type == HomeItem.TYPE_APP && merge !== dragged) {
            if (merge.type == HomeItem.TYPE_FOLDER) {
                dragged.container = merge.id
                dragged.x = model.folderContents(merge.id).size
                dragged.spanX = 1; dragged.spanY = 1
            } else {
                val folder = HomeItem(
                    model.newId(), HomeItem.TYPE_FOLDER, merge.container, merge.screen, merge.x, merge.y,
                    title = "폴더",
                )
                model.items += folder
                merge.container = folder.id; merge.x = 0; merge.y = 0
                dragged.container = folder.id; dragged.x = 1; dragged.y = 0
                dragged.spanX = 1; dragged.spanY = 1
            }
            if (isNew) model.items += dragged
            if (oldContainer != null && oldContainer >= 0 && oldContainer != dragged.container) model.cleanupFolder(oldContainer)
            return
        }

        val cols = if (t.container == HomeItem.DOCK) prefs.dockCount else prefs.homeCols
        val rows = if (t.container == HomeItem.DOCK) 1 else prefs.homeRows
        val g = model.occupied(t.container, t.screen, cols, rows, dragged)
        val pos = if (model.fits(g, t.x, t.y, dragged.spanX, dragged.spanY)) intArrayOf(t.x, t.y)
        else model.findVacant(t.container, t.screen, cols, rows, dragged.spanX, dragged.spanY, t.x, t.y, dragged)
        if (pos == null) {
            Toast.makeText(act, "빈 자리가 없어요", Toast.LENGTH_SHORT).show()
            return
        }
        dragged.container = t.container
        dragged.screen = t.screen
        dragged.x = pos[0]; dragged.y = pos[1]
        if (isNew) model.items += dragged
        if (oldContainer != null && oldContainer >= 0) model.cleanupFolder(oldContainer)
    }
}
