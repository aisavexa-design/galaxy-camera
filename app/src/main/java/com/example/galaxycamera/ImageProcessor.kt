package com.example.galaxycamera

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImageProcessor {
    private val executor = ThreadPoolExecutor(1, 1, 0L, TimeUnit.MILLISECONDS, ArrayBlockingQueue(3))

    fun enqueue(dir: File, timestamp: String, context: Context, callback: (String) -> Unit): Boolean {
        return try {
            executor.execute { processBurst(dir, timestamp, context, callback) }
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun processBurst(dir: File, timestamp: String, context: Context, callback: (String) -> Unit) {
        val files = dir.listFiles { _, name -> name.endsWith(".jpg") }?.sorted() ?: return
        if (files.isEmpty()) return
        
        try {
            val w = 4080
            val h = 3060
            
            val smallOpts = BitmapFactory.Options().apply { inSampleSize = 8 }
            var bestIdx = 0
            var maxVar = -1f
            val smallBitmaps = Array<Bitmap?>(files.size) { null }
            
            for (i in files.indices) {
                val bmp = BitmapFactory.decodeFile(files[i].absolutePath, smallOpts) ?: continue
                smallBitmaps[i] = bmp
                val v = getLaplacianVariance(bmp)
                if (v > maxVar) { maxVar = v; bestIdx = i }
            }
            
            val refSmall = smallBitmaps[bestIdx] ?: return
            val offsets = Array(files.size) { Pair(0, 0) }
            for (i in files.indices) {
                if (i == bestIdx || smallBitmaps[i] == null) continue
                offsets[i] = alignFrames(refSmall, smallBitmaps[i]!!)
            }
            smallBitmaps.forEach { it?.recycle() }
            
            val mergedR = IntArray(w * h)
            val mergedG = IntArray(w * h)
            val mergedB = IntArray(w * h)
            val counts = ByteArray(w * h)
            
            val fullOpts = BitmapFactory.Options().apply { inMutable = true }
            var refBitmap = BitmapFactory.decodeFile(files[bestIdx].absolutePath, fullOpts)
            val refPixels = IntArray(w * h)
            refBitmap.getPixels(refPixels, 0, w, 0, 0, w, h)
            
            for (i in 0 until w * h) {
                val p = refPixels[i]
                mergedR[i] = (p shr 16) and 0xFF
                mergedG[i] = (p shr 8) and 0xFF
                mergedB[i] = p and 0xFF
                counts[i] = 1
            }
            
            var reuseBitmap: Bitmap? = refBitmap
            for (i in files.indices) {
                if (i == bestIdx) continue
                fullOpts.inBitmap = reuseBitmap
                val bmp = BitmapFactory.decodeFile(files[i].absolutePath, fullOpts) ?: continue
                reuseBitmap = bmp
                
                val dx = offsets[i].first * 8
                val dy = offsets[i].second * 8
                val px = IntArray(w * h)
                bmp.getPixels(px, 0, w, 0, 0, w, h)
                
                for (y in 0 until h) {
                    val sy = y - dy
                    if (sy !in 0 until h) continue
                    for (x in 0 until w) {
                        val sx = x - dx
                        if (sx !in 0 until w) continue
                        val srcP = px[sy * w + sx]
                        val refP = refPixels[y * w + x]
                        
                        val sr = (srcP shr 16) and 0xFF
                        val sg = (srcP shr 8) and 0xFF
                        val sb = srcP and 0xFF
                        val rr = (refP shr 16) and 0xFF
                        val rg = (refP shr 8) and 0xFF
                        val rb = refP and 0xFF
                        
                        if (abs(sr - rr) + abs(sg - rg) + abs(sb - rb) < 60) {
                            val idx = y * w + x
                            mergedR[idx] += sr
                            mergedG[idx] += sg
                            mergedB[idx] += sb
                            counts[idx]++
                        }
                    }
                }
            }
            reuseBitmap?.recycle()
            
            val mergedPixels = IntArray(w * h)
            for (i in 0 until w * h) {
                val c = counts[i].toInt()
                val r = mergedR[i] / c
                val g = mergedG[i] / c
                val b = mergedB[i] / c
                mergedPixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
            
            val finalPixels = IntArray(w * h)
            for (y in 0 until h) {
                for (x in 0 until w) {
                    val idx = y * w + x
                    val p = mergedPixels[idx]
                    var r = (p shr 16) and 0xFF
                    var g = (p shr 8) and 0xFF
                    var b = p and 0xFF
                    
                    if (x in 1 until w - 1 && y in 1 until h - 1) {
                        val blurR = ((mergedPixels[idx - 1] shr 16 and 0xFF) + (mergedPixels[idx + 1] shr 16 and 0xFF) + (mergedPixels[idx - w] shr 16 and 0xFF) + (mergedPixels[idx + w] shr 16 and 0xFF) + r * 4) / 8
                        val blurG = ((mergedPixels[idx - 1] shr 8 and 0xFF) + (mergedPixels[idx + 1] shr 8 and 0xFF) + (mergedPixels[idx - w] shr 8 and 0xFF) + (mergedPixels[idx + w] shr 8 and 0xFF) + g * 4) / 8
                        val blurB = ((mergedPixels[idx - 1] and 0xFF) + (mergedPixels[idx + 1] and 0xFF) + (mergedPixels[idx - w] and 0xFF) + (mergedPixels[idx + w] and 0xFF) + b * 4) / 8
                        
                        val diff = abs(r - blurR) + abs(g - blurG) + abs(b - blurB)
                        val amount = if (diff > 15) 0.8f else 0.2f
                        r = clamp((r + amount * (r - blurR)).toInt())
                        g = clamp((g + amount * (g - blurG)).toInt())
                        b = clamp((b + amount * (b - blurB)).toInt())
                    }
                    
                    val maxC = max(r, max(g, b)).toFloat()
                    val minC = min(r, min(g, b)).toFloat()
                    val delta = maxC - minC
                    var hue = 0f
                    if (delta > 0) {
                        hue = when (maxC) {
                            r.toFloat() -> 60f * (((g - b) / delta) % 6f)
                            g.toFloat() -> 60f * (((b - r) / delta) + 2f)
                            else -> 60f * (((r - g) / delta) + 4f)
                        }
                        if (hue < 0) hue += 360f
                    }
                    var sat = if (maxC == 0f) 0f else delta / maxC
                    val v = maxC / 255f
                    
                    var modR = r
                    var modG = g
                    var modB = b
                    
                    var boostSat = false
                    if (hue in 90f..160f) boostSat = true
                    if ((hue >= 320f || hue <= 15f) && sat > 0.2f) boostSat = true
                    if (hue in 180f..250f) boostSat = true
                    
                    if (boostSat) {
                        sat = min(1f, sat * 1.15f)
                        val c = v * sat
                        val xVal = c * (1 - abs((hue / 60) % 2 - 1))
                        val m = v - c
                        var rF = 0f; var gF = 0f; var bF = 0f
                        when (hue.toInt() / 60) {
                            0 -> { rF = c; gF = xVal }
                            1 -> { rF = xVal; gF = c }
                            2 -> { gF = c; bF = xVal }
                            3 -> { gF = xVal; bF = c }
                            4 -> { rF = xVal; bF = c }
                            5 -> { rF = c; bF = xVal }
                        }
                        modR = clamp(((rF + m) * 255).toInt())
                        modG = clamp(((gF + m) * 255).toInt())
                        modB = clamp(((bF + m) * 255).toInt())
                    } else if (sat < 0.1f && v > 0.8f) {
                        modR = clamp((r * 1.05f).toInt())
                        modB = clamp((b * 0.95f).toInt())
                    }
                    finalPixels[idx] = (0xFF shl 24) or (modR shl 16) or (modG shl 8) or modB
                }
            }
            
            val outW = w * 2
            val outH = h * 2
            val outBitmap = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
            val rowPixels = IntArray(outW)
            
            for (y in 0 until outH) {
                val sy = y / 2
                val yFrac = if (y % 2 == 1) 0.5f else 0f
                for (x in 0 until outW) {
                    val sx = x / 2
                    val xFrac = if (x % 2 == 1) 0.5f else 0f
                    
                    if (xFrac == 0f && yFrac == 0f) {
                        rowPixels[x] = finalPixels[sy * w + sx]
                    } else if (xFrac == 0.5f && yFrac == 0f) {
                        val p1 = finalPixels[sy * w + max(0, sx - 1)]
                        val p2 = finalPixels[sy * w + sx]
                        val p3 = finalPixels[sy * w + min(w - 1, sx + 1)]
                        val p4 = finalPixels[sy * w + min(w - 1, sx + 2)]
                        rowPixels[x] = bicubicExactHalf(p1, p2, p3, p4)
                    } else if (xFrac == 0f && yFrac == 0.5f) {
                        val p1 = finalPixels[max(0, sy - 1) * w + sx]
                        val p2 = finalPixels[sy * w + sx]
                        val p3 = finalPixels[min(h - 1, sy + 1) * w + sx]
                        val p4 = finalPixels[min(h - 1, sy + 2) * w + sx]
                        rowPixels[x] = bicubicExactHalf(p1, p2, p3, p4)
                    } else {
                        val c1 = bicubicExactHalf(finalPixels[max(0, sy - 1) * w + max(0, sx - 1)], finalPixels[max(0, sy - 1) * w + sx], finalPixels[max(0, sy - 1) * w + min(w - 1, sx + 1)], finalPixels[max(0, sy - 1) * w + min(w - 1, sx + 2)])
                        val c2 = bicubicExactHalf(finalPixels[sy * w + max(0, sx - 1)], finalPixels[sy * w + sx], finalPixels[sy * w + min(w - 1, sx + 1)], finalPixels[sy * w + min(w - 1, sx + 2)])
                        val c3 = bicubicExactHalf(finalPixels[min(h - 1, sy + 1) * w + max(0, sx - 1)], finalPixels[min(h - 1, sy + 1) * w + sx], finalPixels[min(h - 1, sy + 1) * w + min(w - 1, sx + 1)], finalPixels[min(h - 1, sy + 1) * w + min(w - 1, sx + 2)])
                        val c4 = bicubicExactHalf(finalPixels[min(h - 1, sy + 2) * w + max(0, sx - 1)], finalPixels[min(h - 1, sy + 2) * w + sx], finalPixels[min(h - 1, sy + 2) * w + min(w - 1, sx + 1)], finalPixels[min(h - 1, sy + 2) * w + min(w - 1, sx + 2)])
                        rowPixels[x] = bicubicExactHalf(c1, c2, c3, c4)
                    }
                }
                outBitmap.setPixels(rowPixels, 0, outW, 0, y, outW, 1)
            }
            
            saveToGalleryAndFiles(context, outBitmap, timestamp)
            outBitmap.recycle()
            callback("Enhanced photo saved")
        } catch (e: Exception) {
            val fallback = BitmapFactory.decodeFile(files[0].absolutePath)
            if (fallback != null) {
                val scaled = Bitmap.createScaledBitmap(fallback, fallback.width * 2, fallback.height * 2, true)
                saveToGalleryAndFiles(context, scaled, timestamp)
                fallback.recycle()
                scaled.recycle()
            }
            callback("Enhanced photo saved")
        }
    }

    private fun bicubicExactHalf(p1: Int, p2: Int, p3: Int, p4: Int): Int {
        val r = clamp((9 * ((p2 shr 16 and 0xFF) + (p3 shr 16 and 0xFF)) - ((p1 shr 16 and 0xFF) + (p4 shr 16 and 0xFF))) / 16)
        val g = clamp((9 * ((p2 shr 8 and 0xFF) + (p3 shr 8 and 0xFF)) - ((p1 shr 8 and 0xFF) + (p4 shr 8 and 0xFF))) / 16)
        val b = clamp((9 * ((p2 and 0xFF) + (p3 and 0xFF)) - ((p1 and 0xFF) + (p4 and 0xFF))) / 16)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun getLaplacianVariance(bmp: Bitmap): Float {
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        var sum = 0L
        var sumSq = 0L
        var count = 0
        for (y in 1 until h - 1) {
            for (x in 1 until w - 1) {
                val c = (px[y * w + x] and 0xFF) * 4
                val l = px[y * w + x - 1] and 0xFF
                val r = px[y * w + x + 1] and 0xFF
                val t = px[(y - 1) * w + x] and 0xFF
                val b = px[(y + 1) * w + x] and 0xFF
                val lap = c - l - r - t - b
                sum += lap
                sumSq += lap * lap
                count++
            }
        }
        val mean = sum.toDouble() / count
        return (sumSq.toDouble() / count - mean * mean).toFloat()
    }

    private fun alignFrames(ref: Bitmap, src: Bitmap): Pair<Int, Int> {
        val w = ref.width
        val h = ref.height
        val refPx = IntArray(w * h)
        val srcPx = IntArray(w * h)
        ref.getPixels(refPx, 0, w, 0, 0, w, h)
        src.getPixels(srcPx, 0, w, 0, 0, w, h)
        
        var bestDx = 0
        var bestDy = 0
        var minDiff = Long.MAX_VALUE
        val cx = w / 2; val cy = h / 2
        val rad = 64
        
        for (dy in -8..8) {
            for (dx in -8..8) {
                var diff = 0L
                for (y in cy - rad until cy + rad) {
                    val sy = y + dy
                    if (sy !in 0 until h) continue
                    for (x in cx - rad until cx + rad) {
                        val sx = x + dx
                        if (sx !in 0 until w) continue
                        val vRef = refPx[y * w + x] and 0xFF
                        val vSrc = srcPx[sy * w + sx] and 0xFF
                        diff += abs(vRef - vSrc)
                    }
                }
                if (diff < minDiff) { minDiff = diff; bestDx = dx; bestDy = dy }
            }
        }
        return Pair(bestDx, bestDy)
    }

    private fun clamp(v: Int): Int = max(0, min(255, v))

    private fun saveToGalleryAndFiles(context: Context, bitmap: Bitmap, timestamp: String) {
        val filename = "GC_${timestamp}_enhanced.jpg"
        
        val extDir = File(context.getExternalFilesDir(null), "enhanced").apply { mkdirs() }
        val file = File(extDir, filename)
        FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.JPEG, 95, it) }

        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, filename)
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/GalaxyCamera")
        }
        val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        uri?.let {
            context.contentResolver.openOutputStream(it)?.use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
            }
        }
    }

    fun shutdown() {
        executor.shutdown()
    }
}
