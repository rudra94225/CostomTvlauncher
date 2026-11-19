package com.tvos.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.*
import android.graphics.drawable.AdaptiveIconDrawable
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.DisplayMetrics
import android.util.LruCache
import kotlin.math.max
import kotlin.math.min

data class AppItem(
    val label: String,
    val packageName: String,
    val isTvApp: Boolean
)

object TvIconComposer {

    private const val PREFS = "tvos_launcher_prefs"
    private const val KEY_PINNED = "pinned_dock_pkgs"
    private const val KEY_HIDDEN = "hidden_app_pkgs"

    // Balanced 6 MB cap for crisp 400x235 RGB_565 app cards (~188 KB each, ~32 apps cached)
    private val cardCache = object : LruCache<String, Bitmap>(6 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = (value.byteCount / 1024).coerceAtLeast(1)
    }

    private val defaultPriority = listOf(
        "com.google.android.youtube.tv", "com.google.android.youtube",
        "com.android.vending",
        "com.android.tv.settings", "com.android.settings",
        "com.amazon.amazonvideo.livingroom", "com.amazon.avod.thirdpartyclient",
        "com.netflix.ninja", "com.netflix.mediaclient",
        "com.lagradost.cloudstream3"
    )

    fun clearCache() {
        cardCache.evictAll()
    }

    /**
     * Trims the card cache down to a target KB budget while keeping the most
     * recently used dock cards hot in memory.
     */
    fun trimCache(maxSizeKb: Int) {
        cardCache.trimToSize(maxSizeKb.coerceAtLeast(0))
    }

    fun getCachedCard(packageName: String): Bitmap? = cardCache.get(packageName)

    fun getOrComposeCard(context: Context, packageName: String): Bitmap {
        cardCache.get(packageName)?.let { if (!it.isRecycled) return it }
        val bmp = composeTvCard(context, packageName)
        cardCache.put(packageName, bmp)
        return bmp
    }

    fun getPinnedPackages(context: Context): MutableList<String> {
        val raw = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_PINNED, "") ?: ""
        return raw.split(",").map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
    }

    private fun savePinnedPackages(context: Context, list: List<String>) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putString(KEY_PINNED, list.distinct().joinToString(",")).apply()
    }

    fun getHiddenPackages(context: Context): MutableSet<String> {
        val set = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_HIDDEN, emptySet())
        return set?.toMutableSet() ?: mutableSetOf()
    }

    fun toggleHideApp(context: Context, pkg: String): Boolean {
        val hidden = getHiddenPackages(context)
        val nowHidden = if (hidden.contains(pkg)) {
            hidden.remove(pkg); false
        } else {
            hidden.add(pkg); true
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putStringSet(KEY_HIDDEN, hidden).apply()
        return nowHidden
    }

    fun unhideAllApps(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().remove(KEY_HIDDEN).apply()
    }

    fun pinAppToFront(context: Context, pkg: String) {
        val pinned = getPinnedPackages(context)
        pinned.remove(pkg)
        pinned.add(0, pkg)
        savePinnedPackages(context, pinned)
    }

    fun moveAppInOrder(context: Context, allApps: List<AppItem>, pkg: String, delta: Int) {
        val order = allApps.map { it.packageName }.toMutableList()
        val idx = order.indexOf(pkg)
        if (idx < 0) return
        val target = (idx + delta).coerceIn(0, order.size - 1)
        if (target == idx) return
        order.removeAt(idx)
        order.add(target, pkg)
        savePinnedPackages(context, order)
    }

    fun loadInstalledApps(context: Context, includeHidden: Boolean = false): List<AppItem> {
        val pm = context.packageManager
        val seen = LinkedHashMap<String, AppItem>()
        val hidden = if (includeHidden) emptySet() else getHiddenPackages(context)

        fun collect(category: String, isTv: Boolean) {
            val intent = Intent(Intent.ACTION_MAIN).addCategory(category)
            val resolved = pm.queryIntentActivities(intent, 0)
            for (info in resolved) {
                val pkg = info.activityInfo.packageName
                if (pkg == context.packageName || seen.containsKey(pkg) || hidden.contains(pkg)) continue
                val label = info.loadLabel(pm)?.toString()?.trim().orEmpty().ifEmpty { pkg }
                seen[pkg] = AppItem(label, pkg, isTv)
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER, true)
        collect(Intent.CATEGORY_LAUNCHER, false)

        val customPinned = getPinnedPackages(context)
        return seen.values.sortedWith(compareBy<AppItem> { item ->
            val pIdx = customPinned.indexOf(item.packageName)
            if (pIdx >= 0) pIdx else 500
        }.thenBy { item ->
            val dIdx = defaultPriority.indexOf(item.packageName)
            if (dIdx >= 0) dIdx else 999
        }.thenBy { it.label.lowercase() })
    }

    private fun composeTvCard(
        context: Context,
        packageName: String,
        width: Int = 400,
        height: Int = 235
    ): Bitmap {
        val out = Bitmap.createBitmap(width, height, Bitmap.Config.RGB_565)
        val canvas = Canvas(out)

        // 1. Use our hand-crafted 3D vector icon from LauncherIcons.kt if available
        if (LauncherIcons.drawIntoCard(packageName, canvas, width, height)) {
            return out
        }

        // 2. Fallback: Auto-compose any remaining app with matching 3D tvOS depth lighting
        val pm = context.packageManager
        val drawable = loadHighResIcon(pm, packageName)
        val w = width.toFloat()
        val h = height.toFloat()
        val rect = RectF(0f, 0f, w, h)

        val isAdaptive = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && drawable is AdaptiveIconDrawable
        val baseColor = extractSeamlessCardColor(drawable, isAdaptive)

        val lum = (Color.red(baseColor) * 299 + Color.green(baseColor) * 587 + Color.blue(baseColor) * 114) / 1000
        val isLight = lum > 220
        val topColor = if (lum > 235) baseColor else shiftBrightness(baseColor, 1.15f)
        val bottomColor = if (lum > 235) shiftBrightness(baseColor, 0.88f) else shiftBrightness(baseColor, 0.74f)

        val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(0f, 0f, 0f, h, topColor, bottomColor, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(rect, bgPaint)

        // Overhead 3D convex radial glow
        val sheenAlpha = if (isLight) 0x50 else 0x3E
        val radialGlowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = RadialGradient(
                w * 0.5f, h * 0.05f, w * 0.85f,
                intArrayOf(
                    Color.argb(sheenAlpha, 255, 255, 255),
                    Color.argb(sheenAlpha / 3, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                floatArrayOf(0f, 0.45f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, radialGlowPaint)

        // Pillowed bottom depth shading
        val bottomShadePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.TRANSPARENT,
                    Color.TRANSPARENT,
                    Color.argb(if (isLight) 28 else 64, 0, 0, 0)
                ),
                floatArrayOf(0f, 0.62f, 1f),
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(rect, bottomShadePaint)

        // Soft centered elevation shadow behind the app icon
        val iconShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = RadialGradient(
                w * 0.5f, h * 0.54f, h * 0.36f,
                intArrayOf(
                    Color.argb(if (isLight) 28 else 52, 0, 0, 0),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawCircle(w * 0.5f, h * 0.54f, h * 0.36f, iconShadowPaint)

        // Draw crisp foreground glyph cleanly centered
        if (drawable != null) {
            if (isAdaptive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val fg = (drawable as AdaptiveIconDrawable).foreground ?: drawable
                val iconSize = (height * 0.86f).toInt()
                val left = (width - iconSize) / 2
                val top = (height - iconSize) / 2
                fg.setBounds(left, top, left + iconSize, top + iconSize)
                fg.draw(canvas)
            } else {
                val iconSize = (height * 0.62f).toInt()
                val left = (width - iconSize) / 2
                val top = (height - iconSize) / 2
                drawable.setBounds(left, top, left + iconSize, top + iconSize)
                drawable.draw(canvas)
            }
        }

        // Crisp top specular edge sheen
        val topEdgePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
            shader = LinearGradient(
                0f, 0f, 0f, h * 0.14f,
                intArrayOf(
                    Color.argb(if (isLight) 150 else 85, 255, 255, 255),
                    Color.TRANSPARENT
                ),
                null,
                Shader.TileMode.CLAMP
            )
        }
        canvas.drawRect(0f, 0f, w, h * 0.14f, topEdgePaint)

        return out
    }

    private fun loadHighResIcon(pm: PackageManager, pkg: String): Drawable? {
        return try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            if (appInfo.icon != 0) {
                val res = pm.getResourcesForApplication(appInfo)
                res.getDrawableForDensity(appInfo.icon, DisplayMetrics.DENSITY_XXXHIGH, null)
                    ?: pm.getApplicationIcon(appInfo)
            } else {
                pm.getApplicationIcon(appInfo)
            }
        } catch (_: Exception) {
            try { pm.getApplicationIcon(pkg) } catch (_: Exception) { null }
        }
    }

    /**
     * Samples adaptive background OR outer perimeter ring so square icons
     * blend 100% seamlessly into the Apple tvOS card with zero box seams.
     */
    private fun extractSeamlessCardColor(drawable: Drawable?, isAdaptive: Boolean): Int {
        if (drawable == null) return Color.rgb(32, 37, 48)
        val size = 40
        val sample = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(sample)

        if (isAdaptive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val bg = (drawable as AdaptiveIconDrawable).background
            if (bg != null) {
                bg.setBounds(0, 0, size, size)
                bg.draw(c)
                val avg = averageOpaquePixels(sample, 4, size - 4)
                sample.recycle()
                return avg ?: Color.rgb(245, 247, 250)
            }
        }

        drawable.setBounds(0, 0, size, size)
        drawable.draw(c)

        var edgeR = 0L; var edgeG = 0L; var edgeB = 0L; var edgeOpaque = 0; var edgeTotal = 0
        for (i in 1 until size - 1) {
            val pts = intArrayOf(
                sample.getPixel(i, 2),
                sample.getPixel(i, size - 3),
                sample.getPixel(2, i),
                sample.getPixel(size - 3, i)
            )
            for (px in pts) {
                edgeTotal++
                if (Color.alpha(px) > 210) {
                    edgeR += Color.red(px)
                    edgeG += Color.green(px)
                    edgeB += Color.blue(px)
                    edgeOpaque++
                }
            }
        }

        if (edgeOpaque > (edgeTotal * 0.55f)) {
            val r = (edgeR / edgeOpaque).toInt()
            val g = (edgeG / edgeOpaque).toInt()
            val b = (edgeB / edgeOpaque).toInt()
            sample.recycle()
            return Color.rgb(r, g, b)
        }

        val interior = averageOpaquePixels(sample, 6, size - 6)
        sample.recycle()
        return if (interior != null) {
            val lum = (Color.red(interior) * 299 + Color.green(interior) * 587 + Color.blue(interior) * 114) / 1000
            if (lum > 180) Color.rgb(242, 245, 250) else shiftBrightness(interior, 0.55f)
        } else {
            Color.rgb(28, 34, 46)
        }
    }

    private fun averageOpaquePixels(bmp: Bitmap, start: Int, end: Int): Int? {
        var r = 0L; var g = 0L; var b = 0L; var count = 0
        for (y in start until end step 2) {
            for (x in start until end step 2) {
                val px = bmp.getPixel(x, y)
                if (Color.alpha(px) > 200) {
                    r += Color.red(px); g += Color.green(px); b += Color.blue(px); count++
                }
            }
        }
        return if (count > 0) Color.rgb((r / count).toInt(), (g / count).toInt(), (b / count).toInt()) else null
    }

    private fun shiftBrightness(color: Int, factor: Float): Int {
        val r = min(255, max(0, (Color.red(color) * factor).toInt()))
        val g = min(255, max(0, (Color.green(color) * factor).toInt()))
        val b = min(255, max(0, (Color.blue(color) * factor).toInt()))
        return Color.rgb(r, g, b)
    }
}