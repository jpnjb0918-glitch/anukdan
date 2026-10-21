package com.anuki.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.drawable.AnimatedImageDrawable
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import java.io.File

/**
 * Moving background drawn by the launcher itself behind the home screen (not the system wallpaper, so it
 * works the same on every phone, but only inside 아늑이 런처 — not on the lock screen or behind other apps).
 *
 * Kinds (Prefs.movingBg): "" off, "pattern" the 아늑단 pattern drifting, "video:<file>", "anim:<file>" (GIF / animated WebP).
 * Files are copied into the app's private storage so they keep working after the original is moved or deleted.
 */
object MovingWallpaper {
    const val REQ_PICK = 21
    const val PATTERN = "pattern"
    private const val DIR = "moving"

    fun dir(ctx: Context) = File(ctx.filesDir, DIR).apply { mkdirs() }

    fun label(ctx: Context): String {
        val v = LauncherApp.instance.prefs.movingBg
        return when {
            v.isEmpty() -> "끔"
            v == PATTERN -> "아늑단 패턴 흐르기"
            v.startsWith("video:") -> "내 동영상"
            v.startsWith("anim:") -> "내 GIF"
            else -> "끔"
        }
    }

    /** Opens the system file picker for a video or an animated image; the result comes back in [onPicked]. */
    fun pick(a: Activity) {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
            .setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("video/*", "image/gif", "image/webp"))
        try {
            @Suppress("DEPRECATION") a.startActivityForResult(i, REQ_PICK)
        } catch (_: Exception) {
            a.toast("파일을 고를 수 있는 앱이 없어요")
        }
    }

    /** Copies the picked file and turns the moving background on (off the main thread). */
    fun onPicked(a: Activity, uri: Uri) {
        val type = a.contentResolver.getType(uri) ?: ""
        val kind = when {
            type.startsWith("video/") -> "video"
            type == "image/gif" || type == "image/webp" -> "anim"
            else -> null
        }
        if (kind == null) {
            a.toast("동영상이나 GIF 파일만 쓸 수 있어요")
            return
        }
        if (kind == "anim" && Build.VERSION.SDK_INT < 28) {
            a.toast("움직이는 GIF는 안드로이드 9 이상에서 돼요")
            return
        }
        a.toast("배경을 준비하는 중…")
        val ext = when (type) {
            "image/gif" -> "gif"; "image/webp" -> "webp"; "video/webm" -> "webm"; "video/3gpp" -> "3gp"; else -> "mp4"
        }
        val name = "bg_" + System.currentTimeMillis() + "." + ext
        inBackground({
            try {
                val out = File(dir(a), name)
                a.contentResolver.openInputStream(uri)?.use { input -> out.outputStream().use { input.copyTo(it) } } != null
            } catch (_: Exception) { false }
        }) { ok ->
            if (ok) {
                set("$kind:$name", a)
                a.toast("움직이는 배경을 켰어요. 홈 화면에서 보여요.")
            } else {
                a.toast("파일을 읽지 못했어요")
            }
        }
    }

    /** Sets the kind and removes copied files that are no longer used. */
    fun set(value: String, ctx: Context) {
        LauncherApp.instance.prefs.movingBg = value
        val keep = value.substringAfter(':', "")
        for (f in dir(ctx).listFiles() ?: emptyArray()) if (f.name != keep) f.delete()
        LauncherApp.dlog("moving bg -> ${value.ifEmpty { "off" }}")
    }

    /** What the launcher itself should draw: nothing while the phone's wallpaper is already our moving one. */
    fun launcherValue(ctx: Context): String =
        if (AnukiWallpaperService.isActive(ctx)) "" else LauncherApp.instance.prefs.movingBg

    /** Builds the view that draws [value] (null when off or the file is gone). */
    fun createView(ctx: Context, value: String): Layer? = when {
        value == PATTERN -> PatternLayer(ctx)
        value.startsWith("video:") -> File(dir(ctx), value.removePrefix("video:")).takeIf { it.exists() }?.let { VideoLayer(ctx, it) }
        value.startsWith("anim:") && Build.VERSION.SDK_INT >= 28 ->
            File(dir(ctx), value.removePrefix("anim:")).takeIf { it.exists() }?.let { AnimLayer(ctx, it) }
        else -> null
    }

    /** A moving background; [play]/[pause] follow the home screen being visible. */
    interface Layer {
        val view: View
        fun play()
        fun pause()
        fun release()
    }

    /** The 아늑단 pattern wallpaper drifting slowly up and to the left, tiled so it never ends. */
    private class PatternLayer(ctx: Context) : View(ctx), Layer {
        override val view: View get() = this
        private val tile: Bitmap? = try {
            ctx.assets.open("wallpapers/cat_pattern.jpg").use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = 2 })
            }
        } catch (_: Exception) { null }
        private val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        private var running = false
        private var start = 0L
        private var offset = 0f

        override fun onDraw(canvas: Canvas) {
            val t = tile ?: return
            val scale = maxOf(width.toFloat() / t.width, height.toFloat() / t.height)
            val tw = t.width * scale; val th = t.height * scale
            if (running) offset = ((System.nanoTime() - start) / 1e9f) * 22f * resources.displayMetrics.density
            val ox = -(offset % tw); val oy = -((offset * 0.6f) % th)
            canvas.save()
            canvas.translate(ox, oy)
            canvas.scale(scale, scale)
            for (ix in 0..1) for (iy in 0..1) canvas.drawBitmap(t, ix * t.width.toFloat(), iy * t.height.toFloat(), paint)
            canvas.restore()
            if (running) postInvalidateOnAnimation()
        }

        override fun play() {
            if (running) return
            running = true
            start = System.nanoTime() - (offset / (22f * resources.displayMetrics.density) * 1e9f).toLong()
            postInvalidateOnAnimation()
        }

        override fun pause() { running = false }
        override fun release() { running = false }
    }

    /** A muted, looping video, cropped to fill the screen. */
    private class VideoLayer(ctx: Context, private val file: File) : TextureView(ctx), Layer, TextureView.SurfaceTextureListener {
        override val view: View get() = this
        private var player: MediaPlayer? = null
        private var surface: Surface? = null
        private var wantPlay = false
        private var videoW = 0
        private var videoH = 0

        init { surfaceTextureListener = this }

        override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
            val s = Surface(st)
            surface = s
            try {
                player = MediaPlayer().apply {
                    setDataSource(file.path)
                    setSurface(s)
                    isLooping = true
                    setVolume(0f, 0f)
                    setOnVideoSizeChangedListener { _, vw, vh -> videoW = vw; videoH = vh; fit() }
                    setOnPreparedListener {
                        LauncherApp.dlog("moving bg video ${it.videoWidth}x${it.videoHeight} ready")
                        if (wantPlay) it.start()
                    }
                    setOnErrorListener { _, what, extra -> LauncherApp.dlog("moving bg video error $what/$extra"); true }
                    prepareAsync()
                }
            } catch (e: Exception) {
                LauncherApp.dlog("moving bg video failed: $e")
            }
        }

        override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = fit()
        override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
        override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}

        /** Centre-crop the video into the view. */
        private fun fit() {
            if (videoW <= 0 || videoH <= 0 || width <= 0 || height <= 0) return
            val vw = width.toFloat(); val vh = height.toFloat()
            val s = maxOf(vw / videoW, vh / videoH)
            val m = Matrix()
            // TextureView stretches the video to the view first; undo that and apply a uniform crop scale
            m.setScale(videoW * s / vw, videoH * s / vh, vw / 2f, vh / 2f)
            setTransform(m)
        }

        override fun play() {
            wantPlay = true
            try { player?.let { if (!it.isPlaying) it.start() } } catch (_: Exception) { }
        }

        override fun pause() {
            wantPlay = false
            try { player?.let { if (it.isPlaying) it.pause() } } catch (_: Exception) { }
        }

        override fun release() {
            try { player?.release() } catch (_: Exception) { }
            player = null
            surface?.release()
            surface = null
        }
    }

    /** An animated GIF / WebP, cropped to fill the screen (Android 9+). */
    private class AnimLayer(ctx: Context, file: File) : ImageView(ctx), Layer {
        override val view: View get() = this
        private val anim: AnimatedImageDrawable? = try {
            if (Build.VERSION.SDK_INT >= 28) {
                (android.graphics.ImageDecoder.decodeDrawable(android.graphics.ImageDecoder.createSource(file)) as? AnimatedImageDrawable)
            } else null
        } catch (e: Exception) { LauncherApp.dlog("moving bg anim failed: $e"); null }

        init {
            scaleType = ScaleType.CENTER_CROP
            setImageDrawable(anim)
            LauncherApp.dlog("moving bg anim ${anim?.intrinsicWidth}x${anim?.intrinsicHeight}")
        }

        override fun play() { anim?.start() }
        override fun pause() { anim?.stop() }
        override fun release() { anim?.stop() }
    }

    /** Full-screen holder placed behind the home screen. */
    class Host(ctx: Context) : FrameLayout(ctx) {
        private var applied: String? = null
        private var layer: Layer? = null
        private var visibleNow = false

        /** Rebuilds the layer when the setting changed. */
        fun apply(value: String) {
            if (value == applied) return
            applied = value
            layer?.release()
            removeAllViews()
            layer = createView(context, value)
            layer?.let { addView(it.view, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT)) }
            visibility = if (layer == null) GONE else VISIBLE
            if (visibleNow) layer?.play()
        }

        fun onHomeVisible(v: Boolean) {
            visibleNow = v
            if (v) layer?.play() else layer?.pause()
        }

        fun release() { layer?.release(); layer = null; applied = null; removeAllViews() }
    }
}
