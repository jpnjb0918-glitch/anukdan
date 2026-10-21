package com.anuki.launcher

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.app.WallpaperManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.os.Build
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import java.io.File

/**
 * The moving background as the phone's own (live) wallpaper, so it can also show on the lock screen.
 * Plays whatever 설정 → 배경화면 → 움직이는 배경 is set to; when that is off it shows the 아늑단 pattern standing still.
 * While this is the system wallpaper the launcher doesn't draw its own copy (see HomeActivity / MovingWallpaper.Host).
 */
class AnukiWallpaperService : WallpaperService() {

    override fun onCreateEngine(): Engine = AnukiEngine()

    inner class AnukiEngine : Engine() {
        private var built: String? = null
        private var player: MediaPlayer? = null
        private var anim: AnimatedImageDrawable? = null
        private var tile: Bitmap? = null
        private var moving = false
        private var visible = false
        private var start = System.nanoTime()
        private var offset = 0f
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private val frame = Runnable { draw() }

        override fun onSurfaceCreated(holder: SurfaceHolder) {
            super.onSurfaceCreated(holder)
            build()
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            if (player == null) draw()
        }

        override fun onVisibilityChanged(v: Boolean) {
            visible = v
            if (v) {
                if (LauncherApp.instance.prefs.movingBg != built) { teardown(); build() }
                play()
            } else pause()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            teardown()
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            teardown()
            super.onDestroy()
        }

        private fun build() {
            val value = LauncherApp.instance.prefs.movingBg
            built = value
            moving = value.isNotEmpty()
            val dir = MovingWallpaper.dir(this@AnukiWallpaperService)
            when {
                value.startsWith("video:") -> {
                    val f = File(dir, value.removePrefix("video:"))
                    if (f.exists()) try {
                        player = MediaPlayer().apply {
                            setDataSource(f.path)
                            setSurface(surfaceHolder.surface)
                            setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
                            isLooping = true
                            setVolume(0f, 0f)
                            setOnPreparedListener {
                                LauncherApp.dlog("system wallpaper video ${it.videoWidth}x${it.videoHeight} ready")
                                if (visible) it.start()
                            }
                            prepareAsync()
                        }
                        return
                    } catch (e: Exception) { LauncherApp.dlog("system wallpaper video failed: $e"); player = null }
                }
                value.startsWith("anim:") && Build.VERSION.SDK_INT >= 28 -> {
                    val f = File(dir, value.removePrefix("anim:"))
                    anim = try {
                        android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(f)) as? AnimatedImageDrawable
                    } catch (_: Exception) { null }
                    if (anim != null) return
                }
            }
            tile = try {
                assets.open("wallpapers/cat_pattern.jpg").use {
                    BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
                }
            } catch (_: Exception) { null }
        }

        private fun play() {
            try { player?.let { if (!it.isPlaying) it.start() } } catch (_: Exception) { }
            anim?.start()
            start = System.nanoTime() - (offset / speed() * 1e9f).toLong()
            mainHandler.removeCallbacks(frame)
            if (player == null) draw()
        }

        private fun pause() {
            try { player?.let { if (it.isPlaying) it.pause() } } catch (_: Exception) { }
            anim?.stop()
            mainHandler.removeCallbacks(frame)
        }

        private fun teardown() {
            mainHandler.removeCallbacks(frame)
            try { player?.release() } catch (_: Exception) { }
            player = null
            anim?.stop(); anim = null
            built = null
        }

        private fun speed() = 22f * resources.displayMetrics.density

        /** Pattern / animated image: one frame on the canvas, then the next one ~30 times a second while visible. */
        private fun draw() {
            if (player != null) return
            val holder = surfaceHolder
            val c: Canvas = try { holder.lockCanvas() } catch (_: Exception) { null } ?: return
            try {
                c.drawColor(Color.BLACK)
                val w = c.width.toFloat(); val h = c.height.toFloat()
                val a = anim
                val t = tile
                if (a != null) {
                    val s = maxOf(w / a.intrinsicWidth, h / a.intrinsicHeight)
                    val dw = a.intrinsicWidth * s; val dh = a.intrinsicHeight * s
                    a.setBounds(((w - dw) / 2).toInt(), ((h - dh) / 2).toInt(), ((w + dw) / 2).toInt(), ((h + dh) / 2).toInt())
                    a.draw(c)
                } else if (t != null) {
                    val s = maxOf(w / t.width, h / t.height)
                    val tw = t.width * s; val th = t.height * s
                    if (moving && visible) offset = (System.nanoTime() - start) / 1e9f * speed()
                    c.save()
                    c.translate(-(offset % tw), -((offset * 0.6f) % th))
                    c.scale(s, s)
                    for (ix in 0..1) for (iy in 0..1) c.drawBitmap(t, ix * t.width.toFloat(), iy * t.height.toFloat(), paint)
                    c.restore()
                }
            } finally {
                try { holder.unlockCanvasAndPost(c) } catch (_: Exception) { }
            }
            mainHandler.removeCallbacks(frame)
            if (visible && (moving || anim != null)) mainHandler.postDelayed(frame, 33)
        }
    }

    companion object {
        fun component(ctx: Context) = ComponentName(ctx, AnukiWallpaperService::class.java)

        /** True while this service is the phone's wallpaper. */
        fun isActive(ctx: Context): Boolean = try {
            WallpaperManager.getInstance(ctx).wallpaperInfo?.component == component(ctx)
        } catch (_: Exception) { false }

        /** Opens the system preview where the user confirms (and on many phones picks home / home + lock). */
        fun openSystemPreview(ctx: Context): Boolean {
            val preview = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER)
                .putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT, component(ctx))
            for (i in listOf(preview, Intent(WallpaperManager.ACTION_LIVE_WALLPAPER_CHOOSER))) {
                try { ctx.startActivity(i); return true } catch (_: Exception) { }
            }
            return false
        }
    }
}
