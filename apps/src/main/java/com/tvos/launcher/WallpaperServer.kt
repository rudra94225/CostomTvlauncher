package com.tvos.launcher

import android.content.Context
import android.graphics.*
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.io.*
import java.net.Inet4Address
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import kotlin.math.max
import kotlin.math.min

/**
 * Built-in local Wi-Fi 4K wallpaper server and memory-optimized wallpaper loader.
 *
 * Optimizations for 2 GB RAM 4K Android TVs:
 * - Decodes active 4K WebP/JPG wallpapers into Bitmap.Config.HARDWARE (full 32-bit
 *   true color with ZERO Java heap usage and zero RGB_565 color banding).
 * - Decodes blurred backdrops separately at 144x81 (~46 KB) so the full 4K image
 *   is never copied or read on CPU.
 * - Automatically ignores corrupt/0-byte assets (such as empty placeholder files)
 *   and sorts wallpapers in natural numeric order (wallpaper_1, wallpaper_2, ..., wallpaper_10).
 * - Uses atomic temp-file writes and socket reuseAddress to prevent partial uploads or bind errors.
 */
class WallpaperServer(
    private val context: Context,
    private val onUploaded: () -> Unit
) {
    private val mainHandler = Handler(Looper.getMainLooper())
    private var serverSocket: ServerSocket? = null
    private var activePort: Int = 8080
    @Volatile private var running = false

    fun isRunning(): Boolean = running && serverSocket?.isClosed == false

    fun start(port: Int = 8080): String {
        // Reuse existing running server if already bound on this port (prevents BindException on refresh)
        if (isRunning() && activePort == port) {
            return "http://${getLocalIpAddress()}:$port"
        }
        stop()
        activePort = port
        running = true
        Thread {
            try {
                val server = ServerSocket().apply {
                    reuseAddress = true
                    bind(InetSocketAddress(port))
                }
                serverSocket = server
                while (running) {
                    val socket = server.accept()
                    Thread { handleClient(socket) }.apply { isDaemon = true; start() }
                }
            } catch (_: Exception) {
                running = false
            }
        }.apply { isDaemon = true; start() }
        return "http://${getLocalIpAddress()}:$port"
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (_: Exception) {}
        serverSocket = null
    }

    private fun handleClient(socket: java.net.Socket) {
        socket.use { s ->
            try { s.soTimeout = 15000 } catch (_: Exception) {}
            val input = BufferedInputStream(s.getInputStream())
            val output = s.getOutputStream()

            val requestLine = readAsciiLine(input) ?: return
            var contentLength = 0L
            while (true) {
                val line = readAsciiLine(input) ?: break
                if (line.isEmpty()) break
                if (line.startsWith("Content-Length:", ignoreCase = true)) {
                    contentLength = line.substringAfter(":").trim().toLongOrNull() ?: 0L
                }
            }

            if (requestLine.startsWith("GET ")) {
                val html = buildUploadPageHtml().toByteArray(Charsets.UTF_8)
                val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/html; charset=utf-8\r\nContent-Length: ${html.size}\r\nConnection: close\r\n\r\n"
                output.write(headers.toByteArray())
                output.write(html)
                output.flush()
            } else if (requestLine.startsWith("POST /upload") && contentLength in 1L..MAX_UPLOAD_BYTES) {
                val tmpFile = File(context.filesDir, "wallpaper_4k.tmp")
                val outFile = getWallpaperFile(context)
                var valid = false
                try {
                    FileOutputStream(tmpFile).use { fos ->
                        val buf = ByteArray(16384)
                        var remaining = contentLength
                        while (remaining > 0) {
                            val read = input.read(buf, 0, min(buf.size.toLong(), remaining).toInt())
                            if (read <= 0) break
                            fos.write(buf, 0, read)
                            remaining -= read
                        }
                    }
                    // Verify uploaded file is a valid image before replacing active wallpaper
                    if (isValidImageFile(tmpFile)) {
                        if (outFile.exists()) outFile.delete()
                        valid = tmpFile.renameTo(outFile)
                    }
                } catch (_: Exception) {
                    valid = false
                } finally {
                    if (!valid && tmpFile.exists()) tmpFile.delete()
                }

                if (valid) {
                    setSelectedWallpaperId(context, "custom")
                    val resp = "OK".toByteArray()
                    val headers = "HTTP/1.1 200 OK\r\nContent-Type: text/plain\r\nContent-Length: ${resp.size}\r\nConnection: close\r\n\r\n"
                    output.write(headers.toByteArray())
                    output.write(resp)
                    output.flush()
                    mainHandler.post { onUploaded() }
                } else {
                    val resp = "Invalid image".toByteArray()
                    val headers = "HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: ${resp.size}\r\nConnection: close\r\n\r\n"
                    output.write(headers.toByteArray())
                    output.write(resp)
                    output.flush()
                }
            }
        }
    }

    private fun readAsciiLine(input: BufferedInputStream): String? {
        val sb = StringBuilder()
        var prev = -1
        while (true) {
            val b = input.read()
            if (b == -1) return if (sb.isEmpty()) null else sb.toString()
            if (b == '\n'.code && prev == '\r'.code) {
                sb.setLength(sb.length - 1)
                return sb.toString()
            }
            sb.append(b.toChar())
            prev = b
        }
    }

    companion object {
        private const val PREFS = "tvos_launcher_prefs"
        private const val KEY_WALLPAPER_ID = "selected_wallpaper_id"
        private const val ASSETS_DIR = "wallpapers"
        private const val MAX_UPLOAD_BYTES = 35L * 1024L * 1024L // 35 MB safety limit

        private val numRegex = Regex("\\d+")

        fun getWallpaperFile(context: Context): File = File(context.filesDir, "wallpaper_4k.jpg")
        fun hasCustomWallpaper(context: Context): Boolean = isValidImageFile(getWallpaperFile(context))

        /**
         * Lists all valid built-in wallpapers (WebP, JPG, PNG) in natural numeric order
         * and automatically skips empty/corrupted assets (e.g. 1-byte broken files).
         */
        fun listBuiltInWallpapers(context: Context): List<String> {
            return try {
                (context.assets.list(ASSETS_DIR) ?: emptyArray())
                    .filter { n ->
                        val low = n.lowercase()
                        (low.endsWith(".webp") || low.endsWith(".jpg") || low.endsWith(".jpeg") || low.endsWith(".png")) &&
                            isValidAssetImage(context, "$ASSETS_DIR/$n")
                    }
                    .sortedWith(compareBy<String> { extractNaturalIndex(it) }.thenBy { it.lowercase() })
            } catch (_: Exception) {
                emptyList()
            }
        }

        private fun extractNaturalIndex(name: String): Int {
            return numRegex.find(name)?.value?.toIntOrNull() ?: Int.MAX_VALUE
        }

        private fun isValidImageFile(file: File): Boolean {
            if (!file.exists() || file.length() < 64L) return false
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            return opts.outWidth > 0 && opts.outHeight > 0
        }

        private fun isValidAssetImage(context: Context, assetPath: String): Boolean {
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(assetPath).use { BitmapFactory.decodeStream(it, null, opts) }
                opts.outWidth > 0 && opts.outHeight > 0
            } catch (_: Exception) {
                false
            }
        }

        fun getSelectedWallpaperId(context: Context): String {
            val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_WALLPAPER_ID, null)
            if (!saved.isNullOrEmpty()) {
                // Ensure saved built-in asset still exists (handles .jpg -> .webp migration seamlessly!)
                if (saved.startsWith("builtin:")) {
                    val name = saved.removePrefix("builtin:")
                    if (isValidAssetImage(context, "$ASSETS_DIR/$name")) return saved
                    // Auto-migrate old saved .jpg name to matching .webp name if present
                    val webpCandidate = name.substringBeforeLast('.') + ".webp"
                    if (isValidAssetImage(context, "$ASSETS_DIR/$webpCandidate")) {
                        val migrated = "builtin:$webpCandidate"
                        setSelectedWallpaperId(context, migrated)
                        return migrated
                    }
                } else if (saved == "custom" && hasCustomWallpaper(context)) {
                    return saved
                }
            }
            val builtIns = listBuiltInWallpapers(context)
            return when {
                hasCustomWallpaper(context) -> "custom"
                builtIns.isNotEmpty() -> "builtin:${builtIns[0]}"
                else -> "default"
            }
        }

        fun setSelectedWallpaperId(context: Context, id: String) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_WALLPAPER_ID, id).apply()
        }

        fun saveFromUri(context: Context, uri: Uri): Boolean {
            val tmpFile = File(context.filesDir, "wallpaper_4k.tmp")
            val outFile = getWallpaperFile(context)
            return try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    FileOutputStream(tmpFile).use { output ->
                        input.copyTo(output)
                    }
                } ?: return false

                if (!isValidImageFile(tmpFile)) {
                    tmpFile.delete()
                    return false
                }
                if (outFile.exists()) outFile.delete()
                val ok = tmpFile.renameTo(outFile)
                if (ok) setSelectedWallpaperId(context, "custom")
                ok
            } catch (_: Exception) {
                if (tmpFile.exists()) tmpFile.delete()
                false
            }
        }

        /**
         * Decodes the active wallpaper at the TV UI's exact resolution into full 32-bit
         * true color (HARDWARE bitmap on API 26+ = 0 MB Java heap RAM, zero color banding).
         */
        fun loadActiveWallpaperBitmap(context: Context, reqW: Int, reqH: Int): Bitmap? {
            val id = getSelectedWallpaperId(context)
            val safeW = reqW.coerceAtLeast(1280)
            val safeH = reqH.coerceAtLeast(720)

            if (id == "custom" && hasCustomWallpaper(context)) {
                return decodeFileTrueColor(getWallpaperFile(context), safeW, safeH, preferHardware = true)
            }
            if (id.startsWith("builtin:")) {
                val assetName = id.removePrefix("builtin:")
                decodeAssetTrueColor(context, "$ASSETS_DIR/$assetName", safeW, safeH, preferHardware = true)?.let { return it }
            }
            val builtIns = listBuiltInWallpapers(context)
            if (builtIns.isNotEmpty()) {
                return decodeAssetTrueColor(context, "$ASSETS_DIR/${builtIns[0]}", safeW, safeH, preferHardware = true)
            }
            if (hasCustomWallpaper(context)) {
                return decodeFileTrueColor(getWallpaperFile(context), safeW, safeH, preferHardware = true)
            }
            return null
        }

        /**
         * Decodes a tiny 144x81 software copy directly from disk/assets and blurs it (~46 KB RAM).
         * Completely avoids touching the full-resolution HARDWARE wallpaper on CPU.
         */
        fun loadActiveBlurredBackdrop(context: Context): Bitmap {
            val id = getSelectedWallpaperId(context)
            val tiny = when {
                id == "custom" && hasCustomWallpaper(context) ->
                    decodeFileTrueColor(getWallpaperFile(context), 144, 81, preferHardware = false)
                id.startsWith("builtin:") -> {
                    val assetName = id.removePrefix("builtin:")
                    decodeAssetTrueColor(context, "$ASSETS_DIR/$assetName", 144, 81, preferHardware = false)
                        ?: listBuiltInWallpapers(context).firstOrNull()?.let {
                            decodeAssetTrueColor(context, "$ASSETS_DIR/$it", 144, 81, preferHardware = false)
                        }
                }
                else -> listBuiltInWallpapers(context).firstOrNull()?.let {
                    decodeAssetTrueColor(context, "$ASSETS_DIR/$it", 144, 81, preferHardware = false)
                }
            }
            return createBlurredBackdrop(tiny)
        }

        fun loadAssetThumbnail(context: Context, assetName: String): Bitmap? =
            decodeAssetThumbnail(context, "$ASSETS_DIR/$assetName", 240, 135)

        fun loadCustomThumbnail(context: Context): Bitmap? =
            decodeFileThumbnail(getWallpaperFile(context), 240, 135)

        private fun calculateSampleSize(outW: Int, outH: Int, reqW: Int, reqH: Int): Int {
            var sample = 1
            while ((outW / (sample * 2)) >= reqW && (outH / (sample * 2)) >= reqH) {
                sample *= 2
            }
            return sample
        }

        private fun decodeFileTrueColor(
            file: File,
            reqW: Int,
            reqH: Int,
            preferHardware: Boolean
        ): Bitmap? {
            if (!file.exists()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

            val sample = calculateSampleSize(opts.outWidth, opts.outHeight, reqW, reqH)
            val config = if (preferHardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Bitmap.Config.HARDWARE
            } else {
                Bitmap.Config.ARGB_8888
            }

            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = sample
                inScaled = false
                inDither = true
                inPreferredConfig = config
            }
            return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                ?: if (config == Bitmap.Config.HARDWARE) {
                    decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888
                    BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
                } else null
        }

        private fun decodeAssetTrueColor(
            context: Context,
            path: String,
            reqW: Int,
            reqH: Int,
            preferHardware: Boolean
        ): Bitmap? {
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null

                val sample = calculateSampleSize(opts.outWidth, opts.outHeight, reqW, reqH)
                val config = if (preferHardware && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    Bitmap.Config.HARDWARE
                } else {
                    Bitmap.Config.ARGB_8888
                }

                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inScaled = false
                    inDither = true
                    inPreferredConfig = config
                }
                val bmp = context.assets.open(path).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                if (bmp != null || config != Bitmap.Config.HARDWARE) {
                    bmp
                } else {
                    decodeOpts.inPreferredConfig = Bitmap.Config.ARGB_8888
                    context.assets.open(path).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
                }
            } catch (_: Exception) {
                null
            }
        }

        private fun decodeFileThumbnail(file: File, reqW: Int, reqH: Int): Bitmap? {
            if (!file.exists()) return null
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
            val decodeOpts = BitmapFactory.Options().apply {
                inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, reqW, reqH)
                inDither = true
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            return BitmapFactory.decodeFile(file.absolutePath, decodeOpts)
        }

        private fun decodeAssetThumbnail(context: Context, path: String, reqW: Int, reqH: Int): Bitmap? {
            return try {
                val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, opts) }
                if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
                val decodeOpts = BitmapFactory.Options().apply {
                    inSampleSize = calculateSampleSize(opts.outWidth, opts.outHeight, reqW, reqH)
                    inDither = true
                    inPreferredConfig = Bitmap.Config.RGB_565
                }
                context.assets.open(path).use { BitmapFactory.decodeStream(it, null, decodeOpts) }
            } catch (_: Exception) {
                null
            }
        }

        fun createBlurredBackdrop(source: Bitmap?): Bitmap {
            val w = 144; val h = 81
            val safeSource = if (source != null && !source.isRecycled &&
                !(Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && source.config == Bitmap.Config.HARDWARE)
            ) source else null

            val small = if (safeSource != null) {
                val scaled = Bitmap.createScaledBitmap(safeSource, w, h, true)
                if (scaled.config != Bitmap.Config.ARGB_8888) {
                    val copy = scaled.copy(Bitmap.Config.ARGB_8888, false)
                    if (scaled !== safeSource) scaled.recycle()
                    copy
                } else {
                    scaled
                }
            } else {
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply {
                    Canvas(this).drawColor(Color.rgb(20, 26, 40))
                }
            }
            val blurred = boxBlur(small, 7)
            if (small !== blurred && small !== safeSource) small.recycle()
            return blurred
        }

        private fun boxBlur(src: Bitmap, radius: Int): Bitmap {
            val w = src.width; val h = src.height
            val pixels = IntArray(w * h)
            val tmp = IntArray(w * h)
            src.getPixels(pixels, 0, w, 0, 0, w, h)
            repeat(2) {
                for (y in 0 until h) {
                    for (x in 0 until w) {
                        var r = 0; var g = 0; var b = 0; var c = 0
                        for (dx in -radius..radius) {
                            val nx = min(w - 1, max(0, x + dx))
                            val p = pixels[y * w + nx]
                            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; c++
                        }
                        tmp[y * w + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                    }
                }
                for (x in 0 until w) {
                    for (y in 0 until h) {
                        var r = 0; var g = 0; var b = 0; var c = 0
                        for (dy in -radius..radius) {
                            val ny = min(h - 1, max(0, y + dy))
                            val p = tmp[ny * w + x]
                            r += (p shr 16) and 0xFF; g += (p shr 8) and 0xFF; b += p and 0xFF; c++
                        }
                        pixels[y * w + x] = (0xFF shl 24) or ((r / c) shl 16) or ((g / c) shl 8) or (b / c)
                    }
                }
            }
            return Bitmap.createBitmap(pixels, w, h, Bitmap.Config.ARGB_8888)
        }

        fun getLocalIpAddress(): String {
            return try {
                val ifaces = NetworkInterface.getNetworkInterfaces()
                while (ifaces.hasMoreElements()) {
                    val addrs = ifaces.nextElement().inetAddresses
                    while (addrs.hasMoreElements()) {
                        val addr = addrs.nextElement()
                        if (!addr.isLoopbackAddress && addr is Inet4Address) {
                            return addr.hostAddress ?: "127.0.0.1"
                        }
                    }
                }
                "127.0.0.1"
            } catch (_: Exception) { "127.0.0.1" }
        }

        private fun buildUploadPageHtml(): String {
            val openTag = "<" + "script>"
            val closeTag = "<" + "/script>"
            return "<!DOCTYPE html><html><head><meta name='viewport' content='width=device-width,initial-scale=1'>" +
                "<title>TvOS 4K Wallpaper</title><style>" +
                "body{background:#090d16;color:#fff;font-family:system-ui,sans-serif;display:flex;align-items:center;justify-content:center;min-height:100vh;margin:0}" +
                ".card{background:rgba(255,255,255,.08);border:1px solid rgba(255,255,255,.18);border-radius:24px;padding:28px;max-width:340px;width:90%;text-align:center}" +
                "input{display:none}.btn{display:inline-block;background:#df6f38;color:#fff;padding:14px 24px;border-radius:999px;font-weight:600;cursor:pointer;margin-top:16px}" +
                "#st{margin-top:14px;font-size:14px;color:#9bb0c9}</style></head><body><div class='card'>" +
                "<h2>4K TV Wallpaper</h2><p style='opacity:.75;font-size:14px'>Choose a 4K WebP, JPG, or PNG image to apply immediately on your TV.</p>" +
                "<label class='btn'>Choose 4K Image<input type='file' id='f' accept='image/*'></label><div id='st'>Ready</div></div>" +
                openTag +
                "document.getElementById('f').onchange=async(e)=>{const f=e.target.files[0];if(!f)return;const s=document.getElementById('st');s.textContent='Uploading...';const r=await fetch('/upload',{method:'POST',body:f});s.textContent=r.ok?'Applied on TV!':'Failed';};" +
                closeTag + "</body></html>"
        }
    }
}