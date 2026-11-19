package com.tvos.launcher

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin

/**
 * Central registry for all hand-crafted, code-drawn 3D tvOS-style launcher icons.
 * Every new icon we build goes in this single file—zero extra PNG/JPG bloat.
 */
object LauncherIcons {

    fun getIcon(packageName: String): BaseLauncherIconDrawable? = when (packageName) {
        // 1. YouTube & SmartTube variants
        "com.google.android.youtube.tv",
        "com.google.android.youtube",
        "com.liskovsoft.smarttubetv",
        "com.liskovsoft.smarttubetv.beta",
        "com.teamsmart.videomanager.tv" -> YouTubeIconDrawable()

        // 2. Amazon Prime Video (Android TV & Mobile)
        "com.amazon.amazonvideo.livingroom",
        "com.amazon.avod.thirdpartyclient" -> PrimeVideoIconDrawable()

        // 3. Google Play Store
        "com.android.vending" -> PlayStoreIconDrawable()

        else -> null
    }

    /**
     * Draws a custom 3D icon directly into TvIconComposer's RGB_565 card canvas.
     * Returns true if a custom icon was drawn, or false to fall back to auto-composing.
     */
    fun drawIntoCard(packageName: String, canvas: Canvas, width: Int, height: Int): Boolean {
        val drawable = getIcon(packageName) ?: return false
        // Disable internal squircle clipping so the RGB_565 card stays full-bleed
        // with zero black corner artifacts while the launcher's card view clips the corners.
        drawable.clipToSquircle = false
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return true
    }

    /**
     * Standalone ARGB_8888 bitmap renderer with Apple-like continuous curved corners.
     */
    fun renderBitmap(packageName: String, width: Int, height: Int): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val drawable = getIcon(packageName) ?: return null
        drawable.clipToSquircle = true
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, width, height)
        drawable.draw(canvas)
        return bitmap
    }
}

/**
 * Base class providing:
 * - Apple-like continuous curved corners (squircle) when used standalone
 * - 3D popped-out tvOS pillowed lighting, glossy top highlight & bottom inner depth
 * - Dithered gradients so RGB_565 cards stay band-free on 4K TVs
 */
abstract class BaseLauncherIconDrawable(
    private val isLightTile: Boolean = false
) : Drawable() {

    var clipToSquircle: Boolean = true

    private val clipPath = Path()
    private val rimPath = Path()
    private val rectF = RectF()
    private val rimRectF = RectF()

    private val topSheenPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val bottomShadePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val topEdgeHighlightPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val bevelStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG).apply {
        style = Paint.Style.STROKE
    }

    protected val shadowPaintSoft = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    protected val shadowPaintCore = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    override fun onBoundsChange(bounds: Rect) {
        super.onBoundsChange(bounds)
        if (bounds.isEmpty) return

        val w = bounds.width().toFloat()
        val h = bounds.height().toFloat()
        rectF.set(bounds)
        buildAppleSquirclePath(clipPath, rectF)

        val strokeW = (min(w, h) * 0.022f).coerceAtLeast(2f)
        bevelStrokePaint.strokeWidth = strokeW
        rimRectF.set(
            bounds.left + strokeW * 0.5f,
            bounds.top + strokeW * 0.5f,
            bounds.right - strokeW * 0.5f,
            bounds.bottom - strokeW * 0.5f
        )
        buildAppleSquirclePath(rimPath, rimRectF)

        // 1. Convex overhead 3D glow
        val sheenAlpha = if (isLightTile) 0x58 else 0x46
        topSheenPaint.shader = RadialGradient(
            bounds.exactCenterX(),
            bounds.top + h * 0.04f,
            w * 0.88f,
            intArrayOf(
                Color.argb(sheenAlpha, 255, 255, 255),
                Color.argb(sheenAlpha / 3, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // 2. Soft pillowed bottom depth shading
        val shadeAlpha = if (isLightTile) 0x2A else 0x4E
        bottomShadePaint.shader = LinearGradient(
            0f, bounds.top.toFloat(),
            0f, bounds.bottom.toFloat(),
            intArrayOf(
                Color.TRANSPARENT,
                Color.TRANSPARENT,
                Color.argb(shadeAlpha / 2, 0, 0, 0),
                Color.argb(shadeAlpha, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.56f, 0.84f, 1f),
            Shader.TileMode.CLAMP
        )

        // 3. Soft glossy top rim sheen (works cleanly inside any launcher card radius)
        val topBarAlpha = if (isLightTile) 170 else 95
        topEdgeHighlightPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(),
            0f, bounds.top + h * 0.14f,
            intArrayOf(
                Color.argb(topBarAlpha, 255, 255, 255),
                Color.argb(topBarAlpha / 4, 255, 255, 255),
                Color.TRANSPARENT
            ),
            floatArrayOf(0f, 0.35f, 1f),
            Shader.TileMode.CLAMP
        )

        // 4. Standalone squircle rim bevel
        val topRimColor = if (isLightTile) Color.argb(235, 255, 255, 255) else Color.argb(135, 255, 255, 255)
        val bottomRimColor = if (isLightTile) Color.argb(55, 40, 55, 80) else Color.argb(115, 0, 0, 0)
        bevelStrokePaint.shader = LinearGradient(
            0f, bounds.top.toFloat(),
            0f, bounds.bottom.toFloat(),
            intArrayOf(topRimColor, Color.TRANSPARENT, Color.TRANSPARENT, bottomRimColor),
            floatArrayOf(0f, 0.24f, 0.78f, 1f),
            Shader.TileMode.CLAMP
        )

        shadowPaintSoft.color = if (isLightTile) Color.argb(28, 20, 30, 50) else Color.argb(46, 0, 0, 0)
        shadowPaintCore.color = if (isLightTile) Color.argb(42, 20, 30, 50) else Color.argb(72, 0, 0, 0)

        onGeometryUpdate(bounds)
    }

    protected open fun onGeometryUpdate(bounds: Rect) {}
    protected abstract fun onDrawBackground(canvas: Canvas, bounds: Rect)
    protected abstract fun onDrawForeground(canvas: Canvas, bounds: Rect)

    override fun draw(canvas: Canvas) {
        if (bounds.isEmpty) return
        val saveCount = canvas.save()
        if (clipToSquircle) {
            canvas.clipPath(clipPath)
        }

        // Layer 1: App base gradient
        onDrawBackground(canvas, bounds)

        // Layer 2: 3D tvOS pillowed convex lighting
        canvas.drawRect(bounds, topSheenPaint)
        canvas.drawRect(bounds, bottomShadePaint)
        canvas.drawRect(bounds, topEdgeHighlightPaint)

        // Layer 3: Elevated 3D foreground logo
        onDrawForeground(canvas, bounds)

        // Layer 4: Outer squircle bevel rim (when standalone)
        if (clipToSquircle) {
            canvas.drawPath(rimPath, bevelStrokePaint)
        }

        canvas.restoreToCount(saveCount)
    }

    protected fun drawLiftedPathShadow(canvas: Canvas, path: Path, h: Float) {
        canvas.save()
        canvas.translate(0f, h * 0.034f)
        canvas.drawPath(path, shadowPaintSoft)
        canvas.restore()

        canvas.save()
        canvas.translate(0f, h * 0.017f)
        canvas.drawPath(path, shadowPaintCore)
        canvas.restore()
    }

    @Deprecated("Deprecated in Java")
    override fun getOpacity(): Int = PixelFormat.TRANSLUCENT

    override fun setAlpha(alpha: Int) {
        topSheenPaint.alpha = alpha
        bottomShadePaint.alpha = alpha
        topEdgeHighlightPaint.alpha = alpha
        bevelStrokePaint.alpha = alpha
        onUpdateAlpha(alpha)
        invalidateSelf()
    }

    override fun setColorFilter(colorFilter: ColorFilter?) {
        onUpdateColorFilter(colorFilter)
        invalidateSelf()
    }

    protected open fun onUpdateAlpha(alpha: Int) {}
    protected open fun onUpdateColorFilter(colorFilter: ColorFilter?) {}

    companion object {
        fun buildAppleSquirclePath(path: Path, rect: RectF, cornerRatio: Float = 0.24f) {
            path.reset()
            val w = rect.width()
            val h = rect.height()
            val l = rect.left
            val t = rect.top
            val r = rect.right
            val b = rect.bottom

            val baseRadius = min(w, h) * cornerRatio
            val s = min(baseRadius * 1.25f, min(w, h) * 0.48f)
            val k = 0.28f

            path.moveTo(l + s, t)
            path.lineTo(r - s, t)
            path.cubicTo(r - s * k, t, r, t + s * k, r, t + s)
            path.lineTo(r, b - s)
            path.cubicTo(r, b - s * k, r - s * k, b, r - s, b)
            path.lineTo(l + s, b)
            path.cubicTo(l + s * k, b, l, b - s * k, l, b - s)
            path.lineTo(l, t + s)
            path.cubicTo(l, t + s * k, l + s * k, t, l + s, t)
            path.close()
        }

        fun buildRoundedPolygonPath(
            path: Path,
            points: List<Pair<Float, Float>>,
            cornerRadius: Float
        ) {
            path.reset()
            val n = points.size
            if (n < 3) return

            for (i in 0 until n) {
                val prev = points[(i + n - 1) % n]
                val curr = points[i]
                val next = points[(i + 1) % n]

                val v1x = prev.first - curr.first
                val v1y = prev.second - curr.second
                val len1 = hypot(v1x, v1y)

                val v2x = next.first - curr.first
                val v2y = next.second - curr.second
                val len2 = hypot(v2x, v2y)

                if (len1 == 0f || len2 == 0f) continue

                val r = min(cornerRadius, min(len1, len2) * 0.45f)

                val inX = curr.first + (v1x / len1) * r
                val inY = curr.second + (v1y / len1) * r
                val outX = curr.first + (v2x / len2) * r
                val outY = curr.second + (v2y / len2) * r

                if (i == 0) path.moveTo(inX, inY) else path.lineTo(inX, inY)
                path.quadTo(curr.first, curr.second, outX, outY)
            }
            path.close()
        }
    }
}

/**
 * 1. YouTube Icon (White-Border 3D) — Luminous off-white 3D tile with a central
 * elevated, rounded YouTube red play button and play triangle.
 */
class YouTubeIconDrawable : BaseLauncherIconDrawable(isLightTile = true) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val buttonPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val playPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.FILL
    }

    private val buttonPath = Path()
    private val playPath = Path()

    override fun onGeometryUpdate(bounds: Rect) {
        val h = bounds.height().toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        // White-to-silver-gray 3D background gradient (matching our Play Store model)
        bgPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(),
            0f, bounds.bottom.toFloat(),
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#F2F5F8"),
                Color.parseColor("#D5DCE5")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        // Set up the distinct red play button (around 48% height, 64% width)
        val btnH = h * 0.48f
        val btnW = btnH * 1.34f
        val bl = cx - btnW * 0.50f
        val bt = cy - btnH * 0.50f
        val br = cx + btnW * 0.50f
        val bb = cy + btnH * 0.50f

        buildAppleSquirclePath(buttonPath, RectF(bl, bt, br, bb), cornerRatio = 0.32f)

        buttonPaint.shader = LinearGradient(
            0f, bt,
            0f, bb,
            intArrayOf(
                Color.parseColor("#FF2020"),
                Color.parseColor("#E60000"),
                Color.parseColor("#B00000")
            ),
            floatArrayOf(0f, 0.45f, 1f),
            Shader.TileMode.CLAMP
        )

        // Precise centered white play triangle inside the button
        val triH = btnH * 0.42f
        val triW = triH * 0.88f
        val triL = cx - triW * 0.38f
        val triR = cx + triW * 0.62f
        val triT = cy - triH * 0.5f
        val triB = cy + triH * 0.5f

        buildRoundedPolygonPath(
            path = playPath,
            points = listOf(triL to triT, triR to cy, triL to triB),
            cornerRadius = triH * 0.15f
        )
    }

    override fun onDrawBackground(canvas: Canvas, bounds: Rect) {
        canvas.drawRect(bounds, bgPaint)
    }

    override fun onDrawForeground(canvas: Canvas, bounds: Rect) {
        val h = bounds.height().toFloat()

        // Elevated drop shadow for the whole red play button
        drawLiftedPathShadow(canvas, buttonPath, h)
        canvas.drawPath(buttonPath, buttonPaint)

        // Render the softly rounded white play triangle in the center
        canvas.drawPath(playPath, playPaint)
    }

    override fun onUpdateAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        buttonPaint.alpha = alpha
        playPaint.alpha = alpha
    }

    override fun onUpdateColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        buttonPaint.colorFilter = colorFilter
        playPaint.colorFilter = colorFilter
    }
}

/**
 * 2. Prime Video Icon (3D) — Electric-to-royal blue 3D tile with stacked
 * "prime / video" wordmark and curved Amazon smile arrow.
 */
class PrimeVideoIconDrawable : BaseLauncherIconDrawable(isLightTile = false) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val textShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 0, 20, 75)
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private val smilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val smileShadowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(65, 0, 20, 75)
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }

    private val smilePath = Path()
    private val arrowPath = Path()
    private var primeY = 0f
    private var videoY = 0f

    override fun onGeometryUpdate(bounds: Rect) {
        val h = bounds.height().toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        bgPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(),
            0f, bounds.bottom.toFloat(),
            intArrayOf(
                Color.parseColor("#2DA8FF"),
                Color.parseColor("#1172EB"),
                Color.parseColor("#0A3CB4")
            ),
            floatArrayOf(0f, 0.50f, 1f),
            Shader.TileMode.CLAMP
        )

        val textSize = h * 0.255f
        textPaint.textSize = textSize
        textPaint.letterSpacing = -0.025f
        textShadowPaint.textSize = textSize
        textShadowPaint.letterSpacing = -0.025f

        primeY = cy - h * 0.085f
        videoY = cy + h * 0.145f

        val smileWidth = h * 0.54f
        val startX = cx - smileWidth * 0.52f
        val endX = cx + smileWidth * 0.46f
        val startY = cy + h * 0.245f
        val endY = cy + h * 0.255f
        val dipY = cy + h * 0.375f

        val strokeWidth = h * 0.032f
        smilePaint.strokeWidth = strokeWidth
        smileShadowPaint.strokeWidth = strokeWidth

        smilePath.reset()
        smilePath.moveTo(startX, startY)
        smilePath.quadTo(cx - smileWidth * 0.02f, dipY, endX, endY)

        val arrowLen = h * 0.074f
        val tipX = endX + h * 0.015f
        val tipY = endY - h * 0.012f
        val angle1 = Math.toRadians(172.0)
        val angle2 = Math.toRadians(102.0)

        arrowPath.reset()
        arrowPath.moveTo(
            tipX + (cos(angle1) * arrowLen).toFloat(),
            tipY + (sin(angle1) * arrowLen).toFloat()
        )
        arrowPath.lineTo(tipX, tipY)
        arrowPath.lineTo(
            tipX + (cos(angle2) * arrowLen).toFloat(),
            tipY + (sin(angle2) * arrowLen).toFloat()
        )
    }

    override fun onDrawBackground(canvas: Canvas, bounds: Rect) {
        canvas.drawRect(bounds, bgPaint)
    }

    override fun onDrawForeground(canvas: Canvas, bounds: Rect) {
        val cx = bounds.exactCenterX()
        val shadowDy = bounds.height() * 0.020f

        canvas.drawText("prime", cx, primeY + shadowDy, textShadowPaint)
        canvas.drawText("video", cx, videoY + shadowDy, textShadowPaint)
        canvas.save()
        canvas.translate(0f, shadowDy)
        canvas.drawPath(smilePath, smileShadowPaint)
        canvas.drawPath(arrowPath, smileShadowPaint)
        canvas.restore()

        canvas.drawText("prime", cx, primeY, textPaint)
        canvas.drawText("video", cx, videoY, textPaint)
        canvas.drawPath(smilePath, smilePaint)
        canvas.drawPath(arrowPath, smilePaint)
    }

    override fun onUpdateAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        textPaint.alpha = alpha
        smilePaint.alpha = alpha
    }

    override fun onUpdateColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        textPaint.colorFilter = colorFilter
        smilePaint.colorFilter = colorFilter
    }
}

/**
 * 3. Google Play Store Icon (3D) — Luminous white/silver 3D tile with
 * geometrically exact 4-color rounded Google Play triangle.
 */
class PlayStoreIconDrawable : BaseLauncherIconDrawable(isLightTile = true) {

    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)
    private val bluePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#4285F4") }
    private val greenPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#34A853") }
    private val redPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#EA4335") }
    private val yellowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#FBBC04") }
    private val logoGlossPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.DITHER_FLAG)

    private val triangleClipPath = Path()
    private val greenPath = Path()
    private val redPath = Path()
    private val yellowPath = Path()

    override fun onGeometryUpdate(bounds: Rect) {
        val h = bounds.height().toFloat()
        val cx = bounds.exactCenterX()
        val cy = bounds.exactCenterY()

        bgPaint.shader = LinearGradient(
            0f, bounds.top.toFloat(),
            0f, bounds.bottom.toFloat(),
            intArrayOf(
                Color.parseColor("#FFFFFF"),
                Color.parseColor("#F1F4F8"),
                Color.parseColor("#D4DCE6")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )

        val triH = h * 0.54f
        val triW = triH * 0.90f

        val l = cx - triW * 0.43f
        val r = cx + triW * 0.57f
        val t = cy - triH * 0.5f
        val b = cy + triH * 0.5f

        buildRoundedPolygonPath(
            path = triangleClipPath,
            points = listOf(l to t, r to cy, l to b),
            cornerRadius = triH * 0.12f
        )

        val ix = l + triW * 0.56f
        val iy = cy
        val slope = (iy - t) / (ix - l)

        val pad = triH * 0.5f
        val xLeft = l - pad
        val xRight = r + pad

        val y1Left = t - slope * pad
        val y1Right = iy + slope * (xRight - ix)

        val y2Left = b + slope * pad
        val y2Right = iy - slope * (xRight - ix)

        greenPath.reset()
        greenPath.moveTo(xLeft, y1Left)
        greenPath.lineTo(ix, iy)
        greenPath.lineTo(xRight, y2Right)
        greenPath.lineTo(xRight, t - pad)
        greenPath.lineTo(xLeft, t - pad)
        greenPath.close()

        redPath.reset()
        redPath.moveTo(xLeft, y2Left)
        redPath.lineTo(ix, iy)
        redPath.lineTo(xRight, y1Right)
        redPath.lineTo(xRight, b + pad)
        redPath.lineTo(xLeft, b + pad)
        redPath.close()

        yellowPath.reset()
        yellowPath.moveTo(ix, iy)
        yellowPath.lineTo(xRight, y2Right)
        yellowPath.lineTo(xRight, y1Right)
        yellowPath.close()

        logoGlossPaint.shader = LinearGradient(
            0f, t,
            0f, b,
            intArrayOf(
                Color.argb(48, 255, 255, 255),
                Color.TRANSPARENT,
                Color.argb(30, 0, 0, 0)
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
    }

    override fun onDrawBackground(canvas: Canvas, bounds: Rect) {
        canvas.drawRect(bounds, bgPaint)
    }

    override fun onDrawForeground(canvas: Canvas, bounds: Rect) {
        drawLiftedPathShadow(canvas, triangleClipPath, bounds.height().toFloat())

        val save = canvas.save()
        canvas.clipPath(triangleClipPath)
        canvas.drawRect(bounds, bluePaint)
        canvas.drawPath(greenPath, greenPaint)
        canvas.drawPath(redPath, redPaint)
        canvas.drawPath(yellowPath, yellowPaint)
        canvas.drawRect(bounds, logoGlossPaint)
        canvas.restoreToCount(save)
    }

    override fun onUpdateAlpha(alpha: Int) {
        bgPaint.alpha = alpha
        bluePaint.alpha = alpha
        greenPaint.alpha = alpha
        redPaint.alpha = alpha
        yellowPaint.alpha = alpha
    }

    override fun onUpdateColorFilter(colorFilter: ColorFilter?) {
        bgPaint.colorFilter = colorFilter
        bluePaint.colorFilter = colorFilter
        greenPaint.colorFilter = colorFilter
        redPaint.colorFilter = colorFilter
        yellowPaint.colorFilter = colorFilter
    }
}
