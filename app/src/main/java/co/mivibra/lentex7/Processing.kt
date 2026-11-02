package co.mivibra.lentex7

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.tan

/** Imagen en escala de grises reducida, usada para alinear cuadros. */
class Gray(val w: Int, val h: Int, val d: ByteArray) {
    operator fun get(x: Int, y: Int) = d[y * w + x].toInt() and 255

    companion object {
        fun of(bmp: Bitmap, factor: Int): Gray {
            val w = max(8, bmp.width / factor); val h = max(8, bmp.height / factor)
            val s = Bitmap.createScaledBitmap(bmp, w, h, true)
            val px = IntArray(w * h)
            s.getPixels(px, 0, w, 0, 0, w, h)
            if (s !== bmp) s.recycle()
            val d = ByteArray(w * h)
            for (i in px.indices) {
                val c = px[i]
                d[i] = (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8).toByte()
            }
            return Gray(w, h, d)
        }
    }
}

object Align {
    /** Busca el desplazamiento (dx,dy) tal que img(x+dx, y+dy) ≈ ref(x, y), dentro de ±range alrededor de (gx, gy). */
    fun search(ref: Gray, img: Gray, gx: Int, gy: Int, range: Int): Pair<Int, Int> {
        val x0 = ref.w / 5; val x1 = ref.w - ref.w / 5
        val y0 = ref.h / 5; val y1 = ref.h - ref.h / 5
        val step = max(1, (x1 - x0) / 160)
        var best = Long.MAX_VALUE; var bx = gx; var by = gy
        for (dy in gy - range..gy + range) for (dx in gx - range..gx + range) {
            var sad = 0L; var n = 0
            var y = y0
            while (y < y1) {
                val sy = y + dy
                if (sy in 0 until img.h) {
                    var x = x0
                    while (x < x1) {
                        val sx = x + dx
                        if (sx in 0 until img.w) { sad += abs(ref[x, y] - img[sx, sy]); n++ }
                        x += step
                    }
                }
                y += step
            }
            if (n > 50) {
                val norm = sad * 1000 / n
                if (norm < best) { best = norm; bx = dx; by = dy }
            }
        }
        return bx to by
    }

    class Pyramid(bmp: Bitmap) {
        val g16 = Gray.of(bmp, 16)
        val g4 = Gray.of(bmp, 4)
        val g2 = Gray.of(bmp, 2)
    }

    /** Desplazamiento a resolución completa (precisión de 2 px). */
    fun shift(ref: Pyramid, img: Pyramid): Pair<Int, Int> {
        val (cx, cy) = search(ref.g16, img.g16, 0, 0, 8)
        val (mx, my) = search(ref.g4, img.g4, cx * 4, cy * 4, 4)
        val (fx, fy) = search(ref.g2, img.g2, mx * 2, my * 2, 2)
        return fx * 2 to fy * 2
    }
}

/** Promedia (o mezcla por máximo, para estelas de luz) varios cuadros alineados. */
class FrameStacker(first: Bitmap, private val lighten: Boolean = false, private val align: Boolean = true) {
    val w = first.width
    val h = first.height
    private val ref = if (align) Align.Pyramid(first) else null
    private val sr = if (lighten) null else ShortArray(w * h)
    private val sg = if (lighten) null else ShortArray(w * h)
    private val sb = if (lighten) null else ShortArray(w * h)
    private val cnt = if (lighten) null else ByteArray(w * h)
    private val mx = if (lighten) IntArray(w * h) else null
    var frames = 0
        private set

    init { add(first, isRef = true) }

    fun add(input: Bitmap, isRef: Boolean = false) {
        val bmp = if (input.width != w || input.height != h) Bitmap.createScaledBitmap(input, w, h, true) else input
        var dx = 0; var dy = 0
        if (!isRef && ref != null) {
            val s = Align.shift(ref, Align.Pyramid(bmp)); dx = s.first; dy = s.second
            // Si el movimiento es enorme, el cuadro está movido: se descarta.
            if (abs(dx) > w / 6 || abs(dy) > h / 6) return
        }
        val row = IntArray(w)
        for (y in 0 until h) {
            val sy = y + dy
            if (sy !in 0 until h) continue
            bmp.getPixels(row, 0, w, 0, sy, w, 1)
            val base = y * w
            for (x in 0 until w) {
                val sx = x + dx
                if (sx !in 0 until w) continue
                val c = row[sx]
                val i = base + x
                if (lighten) {
                    val o = mx!![i]
                    val r = max(o shr 16 and 255, c shr 16 and 255)
                    val g = max(o shr 8 and 255, c shr 8 and 255)
                    val b = max(o and 255, c and 255)
                    mx[i] = (r shl 16) or (g shl 8) or b
                } else {
                    sr!![i] = (sr[i] + (c shr 16 and 255)).toShort()
                    sg!![i] = (sg[i] + (c shr 8 and 255)).toShort()
                    sb!![i] = (sb[i] + (c and 255)).toShort()
                    cnt!![i] = (cnt[i] + 1).toByte()
                }
            }
        }
        if (bmp !== input) bmp.recycle()
        frames++
    }

    fun result(): Bitmap {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val row = IntArray(w)
        for (y in 0 until h) {
            val base = y * w
            for (x in 0 until w) {
                val i = base + x
                row[x] = if (lighten) {
                    mx!![i] or (0xFF shl 24)
                } else {
                    val n = max(1, cnt!![i].toInt() and 255)
                    val r = (sr!![i].toInt() and 0xFFFF) / n
                    val g = (sg!![i].toInt() and 0xFFFF) / n
                    val b = (sb!![i].toInt() and 0xFFFF) / n
                    (0xFF shl 24) or (r shl 16) or (g shl 8) or b
                }
            }
            out.setPixels(row, 0, w, 0, y, w, 1)
        }
        return out
    }
}

/** Apilado de enfoque: conserva, por zonas, la parte más nítida de cada cuadro. */
class FocusStacker(first: Bitmap) {
    private val w = first.width
    private val h = first.height
    private val ref = Align.Pyramid(first)
    val result: Bitmap = first.copy(Bitmap.Config.ARGB_8888, true)
    private val cw = w / 4
    private val ch = h / 4
    private val best = sharpness(Gray.of(first, 4), 0, 0)

    private fun sharpness(g: Gray, ox: Int, oy: Int): FloatArray {
        val lap = FloatArray(cw * ch)
        for (y in 1 until ch - 1) for (x in 1 until cw - 1) {
            val sx = x + ox; val sy = y + oy
            if (sx < 1 || sy < 1 || sx >= g.w - 1 || sy >= g.h - 1) continue
            val v = 4 * g[sx, sy] - g[sx - 1, sy] - g[sx + 1, sy] - g[sx, sy - 1] - g[sx, sy + 1]
            lap[y * cw + x] = abs(v).toFloat()
        }
        // Suavizado de caja 5x5 para decidir por zonas y no por píxel suelto.
        val out = FloatArray(cw * ch)
        for (y in 2 until ch - 2) for (x in 2 until cw - 2) {
            var s = 0f
            for (j in -2..2) for (i in -2..2) s += lap[(y + j) * cw + x + i]
            out[y * cw + x] = s
        }
        return out
    }

    fun add(input: Bitmap) {
        val bmp = if (input.width != w || input.height != h) Bitmap.createScaledBitmap(input, w, h, true) else input
        val (dx, dy) = Align.shift(ref, Align.Pyramid(bmp))
        val s = sharpness(Gray.of(bmp, 4), dx / 4, dy / 4)
        val row = IntArray(w)
        val outRow = IntArray(w)
        for (y in 0 until h) {
            val sy = y + dy
            if (sy !in 0 until h) continue
            bmp.getPixels(row, 0, w, 0, sy, w, 1)
            result.getPixels(outRow, 0, w, 0, y, w, 1)
            val cy = min(ch - 1, y / 4)
            var changed = false
            for (x in 0 until w) {
                val sx = x + dx
                if (sx !in 0 until w) continue
                val ci = cy * cw + min(cw - 1, x / 4)
                if (s[ci] > best[ci]) { outRow[x] = row[sx]; changed = true }
            }
            if (changed) result.setPixels(outRow, 0, w, 0, y, w, 1)
        }
        for (i in best.indices) if (s[i] > best[i]) best[i] = s[i]
        if (bmp !== input) bmp.recycle()
    }
}

object Tone {
    /** Levanta sombras (gamma < 1) y añade un poco de ganancia. */
    fun lift(bmp: Bitmap, gamma: Float, gain: Float = 1f) {
        val lut = IntArray(256) { (255.0 * (min(1.0, it / 255.0 * gain)).pow(gamma.toDouble())).toInt().coerceIn(0, 255) }
        val w = bmp.width; val row = IntArray(w)
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                row[x] = (0xFF shl 24) or (lut[c shr 16 and 255] shl 16) or (lut[c shr 8 and 255] shl 8) or lut[c and 255]
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    /** Enfoque (unsharp 3x3) en el sitio, con tres filas en memoria. */
    fun sharpen(bmp: Bitmap, amount: Float) {
        val w = bmp.width; val h = bmp.height
        if (h < 3) return
        var prev = IntArray(w); var cur = IntArray(w); var next = IntArray(w)
        val out = IntArray(w)
        bmp.getPixels(prev, 0, w, 0, 0, w, 1)
        bmp.getPixels(cur, 0, w, 0, 1, w, 1)
        for (y in 1 until h - 1) {
            bmp.getPixels(next, 0, w, 0, y + 1, w, 1)
            out[0] = cur[0]; out[w - 1] = cur[w - 1]
            for (x in 1 until w - 1) {
                var res = 0xFF shl 24
                for (sh in intArrayOf(16, 8, 0)) {
                    val c = cur[x] shr sh and 255
                    val blur = ((prev[x] shr sh and 255) + (next[x] shr sh and 255) + (cur[x - 1] shr sh and 255) + (cur[x + 1] shr sh and 255)) / 4
                    val v = (c + amount * (c - blur)).toInt().coerceIn(0, 255)
                    res = res or (v shl sh)
                }
                out[x] = res
            }
            bmp.setPixels(out, 0, w, 0, y, w, 1)
            val t = prev; prev = cur; cur = next; next = t
        }
    }
}

/** Panorámica cilíndrica incremental. Los cuadros llegan en orden, girando hacia la derecha. */
class PanoStitcher(private val frameW: Int, private val frameH: Int, hfovRad: Double) {
    private val f = (frameW / 2.0) / tan(hfovRad / 2.0)
    private val canvasW = (2 * Math.PI * f).toInt() + frameW + 16
    private val margin = frameH / 10
    private val canvasH = frameH + 2 * margin
    private val canvas = IntArray(canvasW * canvasH)
    private var prevRight = 0
    private var prevX = 0
    private var prevY = 0
    private var prevGray: Gray? = null
    private var firstAngle = Double.NaN
    var used = 0
        private set
    var count = 0
        private set

    // Tabla de deformación cilíndrica (igual para todos los cuadros).
    private val mapX = IntArray(frameW)
    private val yScale = DoubleArray(frameW)

    init {
        val cx = frameW / 2.0
        for (xw in 0 until frameW) {
            val th = (xw - cx) / f
            val x = f * tan(th)
            mapX[xw] = (x + cx).toInt()
            yScale[xw] = sqrt(x * x + f * f) / f
        }
    }

    private fun warp(bmp: Bitmap): IntArray {
        val src = IntArray(frameW * frameH)
        bmp.getPixels(src, 0, frameW, 0, 0, frameW, frameH)
        val out = IntArray(frameW * frameH)
        val cy = frameH / 2.0
        for (xw in 0 until frameW) {
            val sx = mapX[xw]
            if (sx !in 0 until frameW) continue
            val ys = yScale[xw]
            for (yw in 0 until frameH) {
                val sy = ((yw - cy) * ys + cy).toInt()
                if (sy in 0 until frameH) out[yw * frameW + xw] = src[sy * frameW + sx]
            }
        }
        return out
    }

    private fun grayOf(px: IntArray): Gray {
        val gw = frameW / 4; val gh = frameH / 4
        val d = ByteArray(gw * gh)
        for (y in 0 until gh) for (x in 0 until gw) {
            val c = px[(y * 4) * frameW + x * 4]
            d[y * gw + x] = (((c shr 16 and 255) * 77 + (c shr 8 and 255) * 150 + (c and 255) * 29) shr 8).toByte()
        }
        return Gray(gw, gh, d)
    }

    /** Busca el mejor encaje del cuadro nuevo sobre el anterior (en 1/4 de escala). */
    private fun refine(prev: Gray, cur: Gray, guessDx: Int): Pair<Int, Int> {
        var best = Long.MAX_VALUE; var bx = guessDx; var by = 0
        val rx = max(4, prev.w / 12); val ry = max(2, prev.h / 30)
        for (dy in -ry..ry) for (dx in guessDx - rx..guessDx + rx) {
            if (dx <= 0 || dx >= prev.w - 8) continue
            var sad = 0L; var n = 0
            var y = prev.h / 6
            while (y < prev.h - prev.h / 6) {
                val cyy = y - dy
                if (cyy in 0 until cur.h) {
                    var x = dx
                    while (x < prev.w) {
                        val a = prev[x, y]; val b = cur[x - dx, cyy]
                        if (a != 0 && b != 0) { sad += abs(a - b); n++ }
                        x += 2
                    }
                }
                y += 2
            }
            if (n > 40) { val v = sad * 1000 / n; if (v < best) { best = v; bx = dx; by = dy } }
        }
        return bx to by
    }

    /** Devuelve false si el cuadro no aporta (giro hacia atrás). */
    fun add(bmp: Bitmap, angleRad: Double): Boolean {
        if (firstAngle.isNaN()) firstAngle = angleRad
        var a = angleRad - firstAngle
        while (a < -Math.PI) a += 2 * Math.PI
        while (a > Math.PI) a -= 2 * Math.PI
        if (count > 0 && a < 0) a += 2 * Math.PI
        val px = warp(bmp)
        val g = grayOf(px)
        var x = (f * a).toInt()
        var y = margin
        val pg = prevGray
        if (count > 0 && pg != null) {
            val guess = (x - prevX) / 4
            if (guess <= 0) return false
            val (rx, ry) = refine(pg, g, guess)
            x = prevX + rx * 4
            y = prevY + ry * 4
        }
        if (x + frameW > canvasW) return false
        y = y.coerceIn(0, canvasH - frameH)
        val overlapStart = x
        val overlapEnd = if (count > 0) prevRight else x
        for (yy in 0 until frameH) {
            val cyy = yy + y
            for (xx in 0 until frameW) {
                val c = px[yy * frameW + xx]
                if (c == 0) continue
                val cx = xx + x
                val i = cyy * canvasW + cx
                val old = canvas[i]
                canvas[i] = if (old != 0 && cx < overlapEnd && overlapEnd > overlapStart) {
                    val t = (cx - overlapStart).toFloat() / (overlapEnd - overlapStart)
                    mix(old, c, t)
                } else c
            }
        }
        prevX = x; prevY = y; prevRight = x + frameW; prevGray = g
        used = max(used, x + frameW)
        count++
        return true
    }

    val degreesCovered: Int get() = ((prevRight - frameW / 2) / f * 180 / Math.PI).toInt().coerceAtLeast(0)

    private fun mix(a: Int, b: Int, t: Float): Int {
        val u = 1 - t
        val r = ((a shr 16 and 255) * u + (b shr 16 and 255) * t).toInt()
        val g = ((a shr 8 and 255) * u + (b shr 8 and 255) * t).toInt()
        val bl = ((a and 255) * u + (b and 255) * t).toInt()
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or bl
    }

    fun result(): Bitmap? {
        if (count < 2) return null
        // Recorta filas con huecos (bordes curvos del cilindro y desplazamientos).
        var top = 0; var bottom = canvasH - 1
        fun rowFull(y: Int): Boolean {
            var empty = 0
            for (x in 0 until used) if (canvas[y * canvasW + x] == 0) empty++
            return empty < used / 100
        }
        while (top < canvasH / 2 && !rowFull(top)) top++
        while (bottom > canvasH / 2 && !rowFull(bottom)) bottom--
        val hh = bottom - top + 1
        if (hh < 50) return null
        val out = Bitmap.createBitmap(used, hh, Bitmap.Config.ARGB_8888)
        out.setPixels(canvas, top * canvasW, canvasW, 0, 0, used, hh)
        return out
    }
}

/** Foto 360° equirrectangular (2:1). Cada cuadro trae su matriz de rotación dispositivo→mundo. */
class SphereStitcher(val outW: Int = 4096) {
    val outH = outW / 2
    private val out = IntArray(outW * outH)
    private val weight = ByteArray(outW * outH)
    private val sinLon = DoubleArray(outW)
    private val cosLon = DoubleArray(outW)
    private val sinLat = DoubleArray(outH)
    private val cosLat = DoubleArray(outH)
    var count = 0
        private set

    init {
        for (x in 0 until outW) { val lon = (x + 0.5) / outW * 2 * Math.PI - Math.PI; sinLon[x] = kotlin.math.sin(lon); cosLon[x] = kotlin.math.cos(lon) }
        for (y in 0 until outH) { val lat = Math.PI / 2 - (y + 0.5) / outH * Math.PI; sinLat[y] = kotlin.math.sin(lat); cosLat[y] = kotlin.math.cos(lat) }
    }

    /** r: matriz 3x3 fila-mayor (SensorManager.getRotationMatrixFromVector). hfovRad: campo horizontal del cuadro. */
    fun add(bmp: Bitmap, r: FloatArray, hfovRad: Double) {
        val w = bmp.width; val h = bmp.height
        val f = (w / 2.0) / tan(hfovRad / 2.0)
        val src = IntArray(w * h)
        bmp.getPixels(src, 0, w, 0, 0, w, h)
        val cx = w / 2.0; val cy = h / 2.0
        // Centro del cuadro en el mundo: R * (0,0,-1)
        val wx = -r[2].toDouble(); val wy = -r[5].toDouble(); val wz = -r[8].toDouble()
        val latC = kotlin.math.asin(wz.coerceIn(-1.0, 1.0))
        val lonC = kotlin.math.atan2(wx, wy)
        val half = atan(sqrt(cx * cx + cy * cy) / f) * 1.05
        val latMin = max(-Math.PI / 2, latC - half); val latMax = min(Math.PI / 2, latC + half)
        val fullLon = latC + half >= Math.PI / 2 - 0.01 || latC - half <= -Math.PI / 2 + 0.01
        val lonHalf = if (fullLon) Math.PI else min(Math.PI, half / kotlin.math.cos(max(abs(latMin), abs(latMax))))
        val y0 = ((Math.PI / 2 - latMax) / Math.PI * outH).toInt().coerceIn(0, outH - 1)
        val y1 = ((Math.PI / 2 - latMin) / Math.PI * outH).toInt().coerceIn(0, outH - 1)
        val xc = ((lonC + Math.PI) / (2 * Math.PI) * outW).toInt()
        val xr = (lonHalf / (2 * Math.PI) * outW).toInt() + 1
        val xs = if (fullLon) 0 else xc - xr
        val xe = if (fullLon) outW - 1 else xc + xr
        val r0 = r[0]; val r1 = r[1]; val r2 = r[2]; val r3 = r[3]; val r4 = r[4]; val r5 = r[5]; val r6 = r[6]; val r7 = r[7]; val r8 = r[8]
        val maxW = min(cx, cy)
        for (y in y0..y1) {
            val cl = cosLat[y]; val sl = sinLat[y]
            for (xi in xs..xe) {
                val x = ((xi % outW) + outW) % outW
                val dx = cl * sinLon[x]; val dy = cl * cosLon[x]; val dz = sl
                // dispositivo = R^T * mundo
                val ex = r0 * dx + r3 * dy + r6 * dz
                val ey = r1 * dx + r4 * dy + r7 * dz
                val ez = r2 * dx + r5 * dy + r8 * dz
                if (ez >= -1e-6) continue
                val u = cx + f * ex / -ez
                val v = cy - f * ey / -ez
                if (u < 0 || v < 0 || u >= w - 1 || v >= h - 1) continue
                val edge = min(min(u, w - 1 - u), min(v, h - 1 - v))
                val wt = (1 + 254 * min(1.0, edge / maxW)).toInt()
                val i = y * outW + x
                if (wt > (weight[i].toInt() and 255)) {
                    weight[i] = wt.toByte()
                    out[i] = src[v.toInt() * w + u.toInt()] or (0xFF shl 24)
                }
            }
        }
        count++
    }

    fun result(): Bitmap {
        val b = Bitmap.createBitmap(outW, outH, Bitmap.Config.ARGB_8888)
        for (i in out.indices) if (out[i] == 0) out[i] = 0xFF000000.toInt()
        b.setPixels(out, 0, outW, 0, 0, outW, outH)
        return b
    }
}
