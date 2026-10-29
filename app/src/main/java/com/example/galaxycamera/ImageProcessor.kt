package com.example.galaxycamera

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.YuvImage
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class ImageProcessor(private val log: (String, String, Throwable?) -> Unit) {

    class Result(val file: File, val usedFallback: Boolean)

    private class Gray(val w: Int, val h: Int, val v: IntArray)
    private class Shift(val idx: Int, val dx: Int, val dy: Int)

    companion object {
        private const val BAND = 128
        private const val GHOST_THRESH = 40
        private const val ALIGN_MAX_ERR = 30.0
    }

    fun process(frames: List<File>, outFile: File): Result {
        var fallbackIdx = 0
        try {
            val g4 = frames.map { loadGray(it, 4) }
            var best = 0
            var bestSharp = -1.0
            for (i in g4.indices) {
                val s = sharpness(g4[i])
                if (s > bestSharp) {
                    bestSharp = s
                    best = i
                }
            }
            fallbackIdx = best
            log("sharp", "reference frame " + (best + 1), null)

            val ref4 = g4[best]
            val ref2 = loadGray(frames[best], 2)
            val aligned = ArrayList<Shift>()
            aligned.add(Shift(best, 0, 0))

            for (k in frames.indices) {
                if (k == best) continue
                var bdx = 0
                var bdy = 0
                var be = Double.MAX_VALUE
                for (dy in -16..16) {
                    for (dx in -16..16) {
                        val e = avgError(ref4, g4[k], dx, dy, 4)
                        if (e < be) {
                            be = e
                            bdx = dx
                            bdy = dy
                        }
                    }
                }
                val cur2 = loadGray(frames[k], 2)
                var rdx = bdx * 2
                var rdy = bdy * 2
                var re = Double.MAX_VALUE
                for (dy in (bdy * 2 - 3)..(bdy * 2 + 3)) {
                    for (dx in (bdx * 2 - 3)..(bdx * 2 + 3)) {
                        val e = avgError(ref2, cur2, dx, dy, 2)
                        if (e < re) {
                            re = e
                            rdx = dx
                            rdy = dy
                        }
                    }
                }
                log("align", "frame " + (k + 1) + " shift " + (rdx * 2) + "," + (rdy * 2) + " err " + re, null)
                if (re <= ALIGN_MAX_ERR) {
                    aligned.add(Shift(k, rdx * 2, rdy * 2))
                } else {
                    log("align", "frame " + (k + 1) + " skipped (too different)", null)
                }
            }

            val bo = BitmapFactory.Options()
            bo.inJustDecodeBounds = true
            BitmapFactory.decodeFile(frames[best].absolutePath, bo)
            val fullW = bo.outWidth
            val fullH = bo.outHeight

            val decs = HashMap<Int, BitmapRegionDecoder>()
            try {
                for (s in aligned) {
                    decs[s.idx] = BitmapRegionDecoder.newInstance(frames[s.idx].absolutePath, false)
                        ?: throw IllegalStateException("region decoder failed")
                }
                log("merge", "start merging " + aligned.size + " frames", null)
                val nv21 = toneAndColorNv21(mergeBands(aligned, best, decs, fullW, fullH), fullW, fullH)
                val yuv = YuvImage(nv21, ImageFormat.NV21, fullW, fullH, null)
                FileOutputStream(outFile).use { yuv.compressToJpeg(Rect(0, 0, fullW, fullH), 95, it) }
            } finally {
                for (d in decs.values) d.recycle()
            }
            log("merge", "merged " + aligned.size + " frames", null)
            return Result(outFile, false)
        } catch (t: Throwable) {
            log("pipeline", "failed, saving sharpest frame", t)
            try {
                frames[fallbackIdx].copyTo(outFile, overwrite = true)
            } catch (e: Throwable) {
                log("fallback", "copy failed", e)
            }
            return Result(outFile, true)
        }
    }

    private fun loadGray(file: File, sample: Int): Gray {
        val opts = BitmapFactory.Options()
        opts.inSampleSize = sample
        opts.inPreferredConfig = Bitmap.Config.ARGB_8888
        val bmp = BitmapFactory.decodeFile(file.absolutePath, opts)
            ?: throw IllegalStateException("decode failed " + file.name)
        val w = bmp.width
        val h = bmp.height
        val px = IntArray(w * h)
        bmp.getPixels(px, 0, w, 0, 0, w, h)
        bmp.recycle()
        val g = IntArray(w * h)
        for (i in px.indices) g[i] = lum(px[i])
        return Gray(w, h, g)
    }

    private fun lum(c: Int): Int =
        (((c shr 16) and 0xFF) * 77 + ((c shr 8) and 0xFF) * 150 + (c and 0xFF) * 29) shr 8

    private fun sharpness(g: Gray): Double {
        var sum = 0.0
        var sum2 = 0.0
        var n = 0
        for (y in 1 until g.h - 1) {
            for (x in 1 until g.w - 1) {
                val i = y * g.w + x
                val lap = 4 * g.v[i] - g.v[i - 1] - g.v[i + 1] - g.v[i - g.w] - g.v[i + g.w]
                sum += lap.toDouble()
                sum2 += lap.toDouble() * lap.toDouble()
                n++
            }
        }
        if (n == 0) return 0.0
        val mean = sum / n
        return sum2 / n - mean * mean
    }

    private fun avgError(ref: Gray, cur: Gray, dx: Int, dy: Int, step: Int): Double {
        val x0 = ref.w / 4
        val x1 = ref.w * 3 / 4
        val y0 = ref.h / 4
        val y1 = ref.h * 3 / 4
        var sum = 0L
        var n = 0L
        var y = y0
        while (y < y1) {
            var x = x0
            while (x < x1) {
                val cx = x + dx
                val cy = y + dy
                if (cx >= 0 && cx < cur.w && cy >= 0 && cy < cur.h) {
                    sum += abs(ref.v[y * ref.w + x] - cur.v[cy * cur.w + cx]).toLong()
                } else {
                    sum += 255L
                }
                n++
                x += step
            }
            y += step
        }
        if (n == 0L) return Double.MAX_VALUE
        return sum.toDouble() / n.toDouble()
    }

    private fun shiftedBand(
        dec: BitmapRegionDecoder, dx: Int, dy: Int, y0: Int, rows: Int, w: Int, h: Int
    ): IntArray {
        val srcTop = (y0 + dy).coerceIn(0, h - rows)
        val bmp = dec.decodeRegion(Rect(0, srcTop, w, srcTop + rows), null)
            ?: throw IllegalStateException("region decode failed")
        val src = IntArray(w * rows)
        bmp.getPixels(src, 0, w, 0, 0, w, rows)
        bmp.recycle()
        val out = IntArray(w * rows)
        for (ly in 0 until rows) {
            val sy = (ly + y0 + dy - srcTop).coerceIn(0, rows - 1)
            for (x in 0 until w) {
                val sx = (x + dx).coerceIn(0, w - 1)
                out[ly * w + x] = src[sy * w + sx]
            }
        }
        return out
    }

    // Merges aligned frames band by band into an RGB buffer (w*h*3 bytes).
    private fun mergeBands(
        aligned: List<Shift>,
        refIdx: Int,
        decs: Map<Int, BitmapRegionDecoder>,
        w: Int,
        h: Int
    ): ByteArray {
        val out = ByteArray(w * h * 3)
        var y0 = 0
        while (y0 < h) {
            val rows = min(BAND, h - y0)
            val n = w * rows
            val refBand = shiftedBand(decs.getValue(refIdx), 0, 0, y0, rows, w, h)
            val sr = FloatArray(n)
            val sg = FloatArray(n)
            val sb = FloatArray(n)
            val wt = FloatArray(n)
            for (s in aligned) {
                val isRef = s.idx == refIdx
                val pix = if (isRef) refBand else shiftedBand(decs.getValue(s.idx), s.dx, s.dy, y0, rows, w, h)
                for (i in 0 until n) {
                    val c = pix[i]
                    val ok = isRef || abs(lum(c) - lum(refBand[i])) <= GHOST_THRESH
                    if (ok) {
                        sr[i] += ((c shr 16) and 0xFF).toFloat()
                        sg[i] += ((c shr 8) and 0xFF).toFloat()
                        sb[i] += (c and 0xFF).toFloat()
                        wt[i] += 1f
                    }
                }
            }
            for (ly in 0 until rows) {
                for (x in 0 until w) {
                    val i = ly * w + x
                    val o = ((y0 + ly) * w + x) * 3
                    val inv = 1f / wt[i]
                    out[o] = (sr[i] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[o + 1] = (sg[i] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[o + 2] = (sb[i] * inv + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
            y0 += rows
        }
        return out
    }

    // Sharpen, neutral white balance, pink cast removal, highlight roll-off,
    // shadow denoise, green and sky handling, and NV21 output.
    private fun toneAndColorNv21(src: ByteArray, w: Int, h: Int): ByteArray {
        val total = w * h

        // White balance from neutral pixels only (grey/white, not too dark or bright).
        var sumR = 0L
        var sumG = 0L
        var sumB = 0L
        var cnt = 0L
        var p = 0
        for (k in 0 until total) {
            val pr = src[p].toInt() and 0xFF
            val pg = src[p + 1].toInt() and 0xFF
            val pb = src[p + 2].toInt() and 0xFF
            val mx = max(pr, max(pg, pb))
            val mn = min(pr, min(pg, pb))
            if (mx in 40..230 && mx - mn < 28) {
                sumR += pr
                sumG += pg
                sumB += pb
                cnt++
            }
            p += 3
        }
        var gainR = 1f
        var gainG = 1f
        var gainB = 1f
        if (cnt > total / 200L) {
            val mR = sumR.toFloat() / cnt
            val mG = sumG.toFloat() / cnt
            val mB = sumB.toFloat() / cnt
            val mGray = (mR + mG + mB) / 3f
            gainR = (mGray / mR).coerceIn(0.95f, 1.05f)
            gainG = (mGray / mG).coerceIn(0.95f, 1.05f)
            gainB = (mGray / mB).coerceIn(0.95f, 1.05f)
        }

        val out = ByteArray(total * 3 / 2)
        val uvBase = total
        for (y in 0 until h) {
            val ya = if (y > 0) y - 1 else 0
            val yb = if (y < h - 1) y + 1 else h - 1
            for (x in 0 until w) {
                val xa = if (x > 0) x - 1 else 0
                val xb = if (x < w - 1) x + 1 else w - 1
                val count = (yb - ya + 1) * (xb - xa + 1)
                val i = (y * w + x) * 3

                val gC = (src[i + 1].toInt() and 0xFF).toFloat()
                var sumG2 = 0
                for (oy in ya..yb) {
                    for (ox in xa..xb) {
                        sumG2 += src[(oy * w + ox) * 3 + 1].toInt() and 0xFF
                    }
                }
                val edge = abs(gC - sumG2.toFloat() / count)
                val amount = if (edge > 6f) 1.2f else 0.5f

                // Shadow denoise: in dark, flat areas use the smoothed value.
                val rawY = 0.299f * src[i].toInt().and(0xFF) + 0.587f * src[i + 1].toInt().and(0xFF) + 0.114f * src[i + 2].toInt().and(0xFF)
                val flatShadow = rawY < 70f && edge < 8f
                var r: Float
                var g: Float
                var b: Float
                if (flatShadow) {
                    r = meanChannel(src, w, ya, yb, xa, xb, count, 0) / 255f * gainR
                    g = meanChannel(src, w, ya, yb, xa, xb, count, 1) / 255f * gainG
                    b = meanChannel(src, w, ya, yb, xa, xb, count, 2) / 255f * gainB
                } else {
                    r = sharpChannel(src, w, ya, yb, xa, xb, count, i, 0, amount) / 255f * gainR
                    g = sharpChannel(src, w, ya, yb, xa, xb, count, i, 1, amount) / 255f * gainG
                    b = sharpChannel(src, w, ya, yb, xa, xb, count, i, 2, amount) / 255f * gainB
                }
                r = r.coerceIn(0f, 1f)
                g = g.coerceIn(0f, 1f)
                b = b.coerceIn(0f, 1f)

                // Brightness and colour info of this pixel.
                val y0 = 0.299f * r + 0.587f * g + 0.114f * b
                val mx0 = max(r, max(g, b))
                val mn0 = min(r, min(g, b))
                val d0 = mx0 - mn0
                val hue = if (d0 > 0.001f) hueOf(r, g, b, mx0, d0) else 0f
                val sky = isSky(r, g, b, y0)
                val skin = isSkin(hue, d0, y0)
                val green = d0 > 0.05f && hue >= 70f && hue <= 170f
                val pinkCast = d0 > 0.04f && hue >= 280f && hue <= 350f

                // Pink cast: pull magenta/pink pixels towards neutral, so ground and
                // dry twigs stop looking pink.
                if (pinkCast && !skin) {
                    val m = (r + g + b) / 3f
                    r = m + (r - m) * 0.7f
                    g = m + (g - m) * 0.7f
                    b = m + (b - m) * 0.7f
                }

                // Brightness curve: lift shadows, roll off highlights (kills the
                // white glow on leaves and sky).
                var ratio = if (y0 > 0.0001f) toneY(y0) / y0 else 1f
                ratio = min(ratio, 1.6f)
                if (sky && ratio > 1f) ratio = 1f
                if (skin && y0 < 0.6f) ratio *= 1.08f
                if (green && y0 < 0.6f) ratio *= 1.06f

                r *= ratio
                g *= ratio
                b *= ratio

                // Highlight glow: compress very bright, low-colour pixels (white shine).
                val lumNow = 0.299f * r + 0.587f * g + 0.114f * b
                val chromaNow = max(r, max(g, b)) - min(r, min(g, b))
                if (lumNow > 0.8f && chromaNow < 0.12f) {
                    val k = 1f - (lumNow - 0.8f) * 0.8f
                    r *= k
                    g *= k
                    b *= k
                }

                // Green dominance: green must stay above red and blue, so leaves do not turn pink.
                if (green) {
                    val rbMax = max(r, b)
                    if (g < rbMax * 1.12f) g = min(1f, rbMax * 1.12f)
                }

                // Soft clip: scale all channels together so the hue stays the same.
                val peak = max(r, max(g, b))
                if (peak > 1f) {
                    r /= peak
                    g /= peak
                    b /= peak
                }

                // Saturation: green strongest, then other colours, sky a bit,
                // skin and near-white left alone.
                val yy = 0.299f * r + 0.587f * g + 0.114f * b
                var sat = 1.0f
                if (sky) {
                    sat = 1.12f
                } else if (skin) {
                    sat = 1.0f
                } else if (green) {
                    sat = 1.35f
                } else if (d0 > 0.05f && inBoostHue(hue)) {
                    sat = 1.15f
                } else if (d0 > 0.05f) {
                    sat = 1.05f
                }
                if (yy > 0.85f) {
                    val fade = ((1f - yy) / 0.15f).coerceIn(0f, 1f)
                    sat = 1f + (sat - 1f) * fade
                }
                r = (yy + (r - yy) * sat).coerceIn(0f, 1f)
                g = (yy + (g - yy) * sat).coerceIn(0f, 1f)
                b = (yy + (b - yy) * sat).coerceIn(0f, 1f)

                val rr = r * 255f
                val gg = g * 255f
                val bb = b * 255f
                val yv = 0.299f * rr + 0.587f * gg + 0.114f * bb
                out[y * w + x] = (yv + 0.5f).toInt().coerceIn(0, 255).toByte()

                if ((y and 1) == 0 && (x and 1) == 0) {
                    val u = -0.169f * rr - 0.331f * gg + 0.5f * bb + 128f
                    val v = 0.5f * rr - 0.419f * gg - 0.081f * bb + 128f
                    val idx = uvBase + (y / 2) * w + x
                    out[idx] = (v + 0.5f).toInt().coerceIn(0, 255).toByte()
                    out[idx + 1] = (u + 0.5f).toInt().coerceIn(0, 255).toByte()
                }
            }
        }
        return out
    }

    // Gamma 0.8 lifts shadows and midtones.
    // Above 0.85 highlights are rolled off gently.
    private fun toneY(v: Float): Float {
        val x = v.coerceIn(0f, 1f)
        return if (x <= 0.85f) {
            Math.pow(x.toDouble(), 0.8).toFloat()
        } else {
            (0.878f + (x - 0.85f) * 0.5f).coerceIn(0f, 1f)
        }
    }

    // Blue sky: blue clearly dominates and the pixel is reasonably bright.
    private fun isSky(r: Float, g: Float, b: Float, y: Float): Boolean =
        b > r * 1.08f && b > g * 1.0f && (b - r) > 0.05f && y > 0.35f

    // Skin tones: warm hue, moderate colour, mid brightness.
    private fun isSkin(hue: Float, d: Float, y: Float): Boolean =
        hue >= 8f && hue <= 50f && d >= 0.08f && d <= 0.5f && y >= 0.2f && y <= 0.9f

    private fun meanChannel(
        src: ByteArray,
        w: Int,
        ya: Int,
        yb: Int,
        xa: Int,
        xb: Int,
        count: Int,
        c: Int
    ): Float {
        var s = 0
        for (oy in ya..yb) {
            for (ox in xa..xb) {
                s += src[(oy * w + ox) * 3 + c].toInt() and 0xFF
            }
        }
        return s.toFloat() / count
    }

    private fun sharpChannel(
        src: ByteArray,
        w: Int,
        ya: Int,
        yb: Int,
        xa: Int,
        xb: Int,
        count: Int,
        i: Int,
        c: Int,
        amount: Float
    ): Float {
        val avg = meanChannel(src, w, ya, yb, xa, xb, count, c)
        val v = (src[i + c].toInt() and 0xFF).toFloat()
        return (v + amount * (v - avg)).coerceIn(0f, 255f)
    }

    private fun hueOf(r: Float, g: Float, b: Float, mx: Float, d: Float): Float {
        val h = when (mx) {
            r -> 60f * (((g - b) / d) % 6f)
            g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }
        return if (h < 0f) h + 360f else h
    }

    private fun inBoostHue(h: Float): Boolean =
        (h >= 90f && h <= 160f) || (h >= 180f && h <= 250f) || h >= 330f || h <= 8f
}
