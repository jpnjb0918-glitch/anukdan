package com.anuki.launcher

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.max

/** Icons made from the user's own photos, stored as PNG files in the app's private storage. */
object CustomIcons {
    const val STYLE_CAT = "cat"
    const val STYLE_ROUNDED = "rounded"
    private const val OUT = 512

    fun dir(ctx: Context): File = File(ctx.filesDir, "custom_icons").apply { mkdirs() }

    fun load(ctx: Context, name: String): Bitmap? = try {
        BitmapFactory.decodeFile(File(dir(ctx), name).path)
    } catch (_: Exception) { null }

    /** Decodes the picked image, crops the centre square and saves it in the chosen frame. Returns the file name. */
    fun create(ctx: Context, uri: Uri, style: String): String? {
        val src = decode(ctx, uri) ?: return null
        val side = minOf(src.width, src.height)
        val square = Bitmap.createBitmap(src, (src.width - side) / 2, (src.height - side) / 2, side, side)
        val scaled = Bitmap.createScaledBitmap(square, OUT, OUT, true)
        val framed = if (style == STYLE_CAT) catFrame(scaled) else rounded(scaled)
        val name = "photo_" + System.currentTimeMillis() + ".png"
        return try {
            FileOutputStream(File(dir(ctx), name)).use { framed.compress(Bitmap.CompressFormat.PNG, 100, it) }
            name
        } catch (_: Exception) { null }
    }

    private fun decode(ctx: Context, uri: Uri): Bitmap? = try {
        if (Build.VERSION.SDK_INT >= 28) {
            // ImageDecoder also applies the photo's EXIF rotation
            ImageDecoder.decodeBitmap(ImageDecoder.createSource(ctx.contentResolver, uri)) { decoder, info, _ ->
                val longest = max(info.size.width, info.size.height)
                if (longest > 1400) {
                    val f = 1400f / longest
                    decoder.setTargetSize((info.size.width * f).toInt(), (info.size.height * f).toInt())
                }
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
            }
        } else {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 1400) sample *= 2
            ctx.contentResolver.openInputStream(uri)?.use {
                BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
            }
        }
    } catch (_: Exception) { null }

    /** Photo inside a white cat head with cyan ears and a black outline, matching the cat icon theme. */
    private fun catFrame(photo: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val s = OUT / 100f
        val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = IconProvider.INK; style = Paint.Style.STROKE; strokeWidth = 4.5f * s; strokeJoin = Paint.Join.ROUND
        }
        val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        val cyan = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = IconProvider.CYAN }
        val earL = Path().apply {
            moveTo(10 * s, 54 * s); lineTo(14 * s, 11 * s); quadTo(15 * s, 4 * s, 21 * s, 7 * s); lineTo(44 * s, 24 * s); close()
        }
        val earR = Path().apply {
            moveTo(90 * s, 54 * s); lineTo(86 * s, 11 * s); quadTo(85 * s, 4 * s, 79 * s, 7 * s); lineTo(56 * s, 24 * s); close()
        }
        for (p in arrayOf(earL, earR)) { c.drawPath(p, white); c.drawPath(p, ink) }
        c.drawPath(Path().apply { moveTo(17 * s, 14 * s); lineTo(15 * s, 33 * s); lineTo(33 * s, 23 * s); close() }, cyan)
        c.drawPath(Path().apply { moveTo(83 * s, 14 * s); lineTo(85 * s, 33 * s); lineTo(67 * s, 23 * s); close() }, cyan)
        val circle = Path().apply { addCircle(50 * s, 57 * s, 39 * s, Path.Direction.CW) }
        c.save()
        c.clipPath(circle)
        c.drawBitmap(photo, null, RectF(11 * s, 18 * s, 89 * s, 96 * s), Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        c.drawCircle(50 * s, 57 * s, 39 * s, ink)
        return b
    }

    private fun rounded(photo: Bitmap): Bitmap {
        val b = Bitmap.createBitmap(OUT, OUT, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val r = OUT * 0.22f
        val inset = OUT * 0.04f
        val rect = RectF(inset, inset, OUT - inset, OUT - inset)
        c.save()
        c.clipPath(Path().apply { addRoundRect(rect, r, r, Path.Direction.CW) })
        c.drawBitmap(photo, null, rect, Paint(Paint.FILTER_BITMAP_FLAG))
        c.restore()
        return b
    }

    // ------------------------------------------------------------ backup

    fun exportJson(ctx: Context): JSONObject {
        val o = JSONObject()
        for (f in dir(ctx).listFiles() ?: emptyArray()) {
            if (f.isFile && f.name.endsWith(".png")) o.put(f.name, Base64.encodeToString(f.readBytes(), Base64.NO_WRAP))
        }
        return o
    }

    fun importJson(ctx: Context, o: JSONObject) {
        for (name in o.keys()) {
            if (!name.matches(Regex("[A-Za-z0-9_.-]+\\.png"))) continue
            try { File(dir(ctx), name).writeBytes(Base64.decode(o.getString(name), Base64.NO_WRAP)) } catch (_: Exception) { }
        }
    }
}
