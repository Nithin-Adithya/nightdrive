package com.nithin.nightdrive

import android.content.Context
import android.content.res.Resources
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.ImageDecoder
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Build
import java.io.File
import java.util.Calendar

/** Whether cards, text and the map are drawn light or dark. AUTO follows the time of day. */
enum class Appearance { AUTO, LIGHT, DARK }

/** A built-in wallpaper, with a light and a dark version that follow the appearance. */
class LauncherTheme(
    val id: String,
    val name: String,
    val lightImage: Int,
    val darkImage: Int
)

object Themes {

    const val CUSTOM_ID = "custom"

    private val builtIn = listOf(
        LauncherTheme("aurora", "Aurora", R.drawable.wall_aurora_light, R.drawable.wall_aurora_dark),
        LauncherTheme("dusk", "Dusk", R.drawable.wall_dusk, R.drawable.wall_dusk),
        LauncherTheme("ember", "Ember", R.drawable.wall_ember, R.drawable.wall_ember),
        LauncherTheme("tide", "Tide", R.drawable.wall_tide, R.drawable.wall_tide),
        LauncherTheme("graphite", "Graphite", R.drawable.wall_graphite, R.drawable.wall_graphite)
    )

    private var themes: List<LauncherTheme>? = null

    /**
     * The built-in wallpapers, preceded by any personal ones. Personal wallpapers are images
     * that are not part of the project: put them in app/src/personal/res (git-ignored) with a
     * string-array "personal_themes" whose items read "id|Name|light_drawable|dark_drawable".
     */
    fun all(context: Context): List<LauncherTheme> {
        themes?.let { return it }
        val resources = context.resources
        val packageName = context.packageName
        val personal = ArrayList<LauncherTheme>()
        val array = resources.getIdentifier("personal_themes", "array", packageName)
        if (array != 0) {
            for (entry in resources.getStringArray(array)) {
                val parts = entry.split('|')
                if (parts.size != 4) continue
                val light = resources.getIdentifier(parts[2], "drawable", packageName)
                val dark = resources.getIdentifier(parts[3], "drawable", packageName)
                if (light != 0 && dark != 0) personal.add(LauncherTheme(parts[0], parts[1], light, dark))
            }
        }
        return (personal + builtIn).also { themes = it }
    }

    // ---- Saved choices ----------------------------------------------------------------------

    private const val PREFS = "nightdrive"
    private const val KEY_WALLPAPER = "theme"
    private const val KEY_APPEARANCE = "appearance"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun savedWallpaper(context: Context): String {
        val id = prefs(context).getString(KEY_WALLPAPER, null)
        val all = all(context)
        val valid = all.any { it.id == id } || (id == CUSTOM_ID && customFile(context).exists())
        return if (valid) id!! else all.first().id
    }

    fun saveWallpaper(context: Context, id: String) =
        prefs(context).edit().putString(KEY_WALLPAPER, id).apply()

    fun savedAppearance(context: Context): Appearance {
        val name = prefs(context).getString(KEY_APPEARANCE, null)
        return Appearance.values().firstOrNull { it.name == name } ?: Appearance.DARK
    }

    fun saveAppearance(context: Context, appearance: Appearance) =
        prefs(context).edit().putString(KEY_APPEARANCE, appearance.name).apply()

    /** AUTO is light from 6 in the morning until 6 in the evening. */
    fun isLight(appearance: Appearance): Boolean = when (appearance) {
        Appearance.LIGHT -> true
        Appearance.DARK -> false
        Appearance.AUTO -> Calendar.getInstance().get(Calendar.HOUR_OF_DAY) in 6..17
    }

    // ---- Wallpapers -------------------------------------------------------------------------

    fun wallpaper(context: Context, id: String, light: Boolean): Drawable {
        if (id == CUSTOM_ID) {
            BitmapFactory.decodeFile(customFile(context).path)?.let {
                return BitmapDrawable(context.resources, it)
            }
        }
        val all = all(context)
        val theme = all.firstOrNull { it.id == id } ?: all.first()
        return WallpaperDrawable(context.resources, if (light) theme.lightImage else theme.darkImage)
    }

    fun customFile(context: Context) = File(context.filesDir, "wallpaper.jpg")

    /**
     * Copies the picked image into the app, cropped to fill a [width] x [height] screen.
     * Call off the main thread. Returns false if the image could not be read.
     */
    fun importWallpaper(context: Context, uri: Uri, width: Int, height: Int): Boolean = try {
        val source = decode(context, uri, width, height)
        if (source == null) {
            false
        } else {
            val scale = maxOf(width / source.width.toFloat(), height / source.height.toFloat())
            val cropW = minOf(source.width, (width / scale).toInt())
            val cropH = minOf(source.height, (height / scale).toInt())
            val cropped = Bitmap.createBitmap(
                source, (source.width - cropW) / 2, (source.height - cropH) / 2, cropW, cropH
            )
            val fitted = Bitmap.createScaledBitmap(cropped, width, height, true)
            customFile(context).outputStream().use {
                fitted.compress(Bitmap.CompressFormat.JPEG, 92, it)
            }
            true
        }
    } catch (e: Exception) {
        false
    }

    private fun decode(context: Context, uri: Uri, width: Int, height: Int): Bitmap? {
        val resolver = context.contentResolver
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            return ImageDecoder.decodeBitmap(ImageDecoder.createSource(resolver, uri)) { decoder, info, _ ->
                decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                decoder.setTargetSampleSize(sampleSize(info.size.width, info.size.height, width, height))
            }
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        val options = BitmapFactory.Options().apply {
            inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, width, height)
        }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) }
    }

    /** Largest power-of-two shrink that still leaves the image at least screen sized. */
    private fun sampleSize(srcW: Int, srcH: Int, width: Int, height: Int): Int {
        var sample = 1
        while (srcW / (sample * 2) >= width && srcH / (sample * 2) >= height) sample *= 2
        return sample
    }
}

/**
 * Fits a bundled image to the screen once, then just blits the result.
 *
 * Wide images are cropped to fill. Square ones are shown a little larger than screen height,
 * so most of the composition stays in view, and are mirrored outward at both sides to fill
 * the rest (their backgrounds are plain there, so the join is invisible).
 */
class WallpaperDrawable(private val resources: Resources, private val image: Int) : Drawable() {

    private var cache: Bitmap? = null
    private val blit = Paint(Paint.FILTER_BITMAP_FLAG)

    override fun onBoundsChange(bounds: Rect) {
        cache = null
    }

    override fun draw(canvas: Canvas) {
        val b = bounds
        if (b.isEmpty) return
        val bitmap = cache ?: render(b.width(), b.height()).also { cache = it }
        canvas.drawBitmap(bitmap, b.left.toFloat(), b.top.toFloat(), blit)
    }

    private fun render(width: Int, height: Int): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // Small previews don't need the full-size source.
        val options = BitmapFactory.Options().apply { inSampleSize = if (width < 400) 4 else 1 }
        val source = BitmapFactory.decodeResource(resources, image, options) ?: return out
        val canvas = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.DITHER_FLAG)

        val cover = maxOf(width / source.width.toFloat(), height / source.height.toFloat())
        val wide = source.width >= source.height * 1.2f
        val scale = if (wide) cover else height * SQUARE_ZOOM / source.height
        val drawnW = source.width * scale
        val drawnH = source.height * scale
        val left = (width - drawnW) / 2f
        val top = (height - drawnH) / 2f
        val dest = RectF(left, top, left + drawnW, top + drawnH)
        // Skip the outermost columns: JPEG edge pixels would show as a line where mirrored.
        val crop = Rect(3, 0, source.width - 3, source.height)
        canvas.drawBitmap(source, crop, dest, paint)

        if (left > 0f) {
            // Mirror the image outward on both sides to cover the gutters.
            for (pivot in floatArrayOf(dest.left, dest.right)) {
                canvas.save()
                canvas.scale(-1f, 1f, pivot, 0f)
                canvas.drawBitmap(source, crop, dest, paint)
                canvas.restore()
            }
        }
        source.recycle()
        return out
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.OPAQUE

    private companion object {
        const val SQUARE_ZOOM = 1.5f
    }
}

/** A miniature of the home screen (sidebar and three cards) drawn over a wallpaper preview. */
class DashboardMock(private val light: Boolean, private val largeOnRight: Boolean) : Drawable() {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun draw(canvas: Canvas) {
        val b = bounds
        val unit = b.width() / 1024f
        val left = b.left.toFloat()
        val top = b.top.toFloat()

        // Sidebar with three app tiles.
        val dock = 68 * unit
        paint.color = 0x990B0F1A.toInt()
        canvas.drawRect(left, top, left + dock, b.bottom.toFloat(), paint)
        paint.color = 0xD9FFFFFF.toInt()
        for (i in 0 until 3) {
            val y = top + (220 + i * 56) * unit
            canvas.drawRoundRect(left + 14 * unit, y, left + 54 * unit, y + 40 * unit, 10 * unit, 10 * unit, paint)
        }

        // One large card and two stacked small ones, in the user's current arrangement.
        // Spacing is exaggerated so the three cards still read as separate at thumbnail size.
        val pad = 26 * unit
        val gap = 22 * unit
        val areaLeft = left + dock + pad
        val areaWidth = b.right - pad - areaLeft
        val areaTop = top + pad
        val areaHeight = b.height() - 2 * pad
        val large = (areaWidth - gap) * 0.6f
        val small = areaWidth - gap - large
        val largeLeft = if (largeOnRight) areaLeft + small + gap else areaLeft
        val smallLeft = if (largeOnRight) areaLeft else areaLeft + large + gap
        val upper = (areaHeight - gap) * 0.545f
        val radius = 30 * unit
        paint.color = if (light) 0xE6F2F2F7.toInt() else 0xD91C1C1E.toInt()
        canvas.drawRoundRect(largeLeft, areaTop, largeLeft + large, areaTop + areaHeight, radius, radius, paint)
        canvas.drawRoundRect(smallLeft, areaTop, smallLeft + small, areaTop + upper, radius, radius, paint)
        canvas.drawRoundRect(
            smallLeft, areaTop + upper + gap, smallLeft + small, areaTop + areaHeight, radius, radius, paint
        )
    }

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT
}
