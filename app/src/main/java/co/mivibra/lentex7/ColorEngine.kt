package co.mivibra.lentex7

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ColorMatrix
import android.net.Uri

/** Perfiles de color, balance fino y LUTs .cube. */
data class ColorParams(
    var profile: String = "Natural",
    var temp: Int = 0,        // -100..100 (frío..cálido)
    var tint: Int = 0,        // -100..100 (verde..magenta)
    var saturation: Int = 0,  // -100..100
    var contrast: Int = 0,    // -100..100
    var lut: Lut? = null
) {
    val isNeutral get() = profile == "Natural" && temp == 0 && tint == 0 && saturation == 0 && contrast == 0 && lut == null
}

class Lut(val size: Int, val data: FloatArray, val name: String)

object ColorEngine {

    val PROFILES = listOf("Natural", "Vívido", "Cine LOG", "Cálido", "Frío", "Película", "B/N")

    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
    private fun contrast(c: Float, lift: Float = 0f): ColorMatrix {
        val t = 128f * (1f - c) + lift
        return ColorMatrix(floatArrayOf(
            c, 0f, 0f, 0f, t,
            0f, c, 0f, 0f, t,
            0f, 0f, c, 0f, t,
            0f, 0f, 0f, 1f, 0f))
    }
    private fun gains(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }

    fun matrix(p: ColorParams): ColorMatrix {
        val m = ColorMatrix()
        when (p.profile) {
            "Vívido" -> { m.postConcat(sat(1.35f)); m.postConcat(contrast(1.08f)) }
            "Cine LOG" -> { m.postConcat(sat(0.7f)); m.postConcat(contrast(0.72f, 10f)) }
            "Cálido" -> { m.postConcat(gains(1.08f, 1.0f, 0.90f)); m.postConcat(sat(1.08f)) }
            "Frío" -> { m.postConcat(gains(0.94f, 1.0f, 1.08f)) }
            "Película" -> { m.postConcat(gains(1.04f, 1.0f, 0.94f)); m.postConcat(sat(0.85f)); m.postConcat(contrast(1.12f)) }
            "B/N" -> { m.postConcat(sat(0f)); m.postConcat(contrast(1.15f)) }
        }
        val t = p.temp / 100f
        val ti = p.tint / 100f
        if (t != 0f || ti != 0f) m.postConcat(gains(1f + 0.18f * t, 1f - 0.12f * ti, 1f - 0.18f * t))
        if (p.saturation != 0) m.postConcat(sat(1f + p.saturation / 100f))
        if (p.contrast != 0) m.postConcat(contrast(1f + p.contrast / 200f))
        return m
    }

    /** Aplica matriz + LUT sobre el bitmap (mutable) fila por fila, sin duplicar memoria. */
    fun apply(bmp: Bitmap, p: ColorParams) {
        if (p.isNeutral) return
        val a = matrix(p).array
        val identity = p.profile == "Natural" && p.temp == 0 && p.tint == 0 && p.saturation == 0 && p.contrast == 0
        val w = bmp.width
        val row = IntArray(w)
        val lut = p.lut
        for (y in 0 until bmp.height) {
            bmp.getPixels(row, 0, w, 0, y, w, 1)
            for (x in 0 until w) {
                val c = row[x]
                var r = ((c shr 16) and 255).toFloat()
                var g = ((c shr 8) and 255).toFloat()
                var b = (c and 255).toFloat()
                if (!identity) {
                    val nr = a[0] * r + a[1] * g + a[2] * b + a[4]
                    val ng = a[5] * r + a[6] * g + a[7] * b + a[9]
                    val nb = a[10] * r + a[11] * g + a[12] * b + a[14]
                    r = nr; g = ng; b = nb
                }
                var ir = r.toInt().coerceIn(0, 255)
                var ig = g.toInt().coerceIn(0, 255)
                var ib = b.toInt().coerceIn(0, 255)
                if (lut != null) {
                    val o = sampleLut(lut, ir / 255f, ig / 255f, ib / 255f)
                    ir = o shr 16 and 255; ig = o shr 8 and 255; ib = o and 255
                }
                row[x] = (0xFF shl 24) or (ir shl 16) or (ig shl 8) or ib
            }
            bmp.setPixels(row, 0, w, 0, y, w, 1)
        }
    }

    private fun sampleLut(l: Lut, r: Float, g: Float, b: Float): Int {
        val n = l.size - 1
        val fr = r * n; val fg = g * n; val fb = b * n
        val r0 = fr.toInt().coerceIn(0, n - 1); val g0 = fg.toInt().coerceIn(0, n - 1); val b0 = fb.toInt().coerceIn(0, n - 1)
        val dr = fr - r0; val dg = fg - g0; val db = fb - b0
        var outR = 0f; var outG = 0f; var outB = 0f
        for (k in 0..1) for (j in 0..1) for (i in 0..1) {
            val wgt = (if (i == 0) 1 - dr else dr) * (if (j == 0) 1 - dg else dg) * (if (k == 0) 1 - db else db)
            // Formato .cube: el rojo cambia más rápido, luego verde, luego azul.
            val idx = 3 * ((r0 + i) + (g0 + j) * l.size + (b0 + k) * l.size * l.size)
            outR += wgt * l.data[idx]; outG += wgt * l.data[idx + 1]; outB += wgt * l.data[idx + 2]
        }
        val ir = (outR * 255f).toInt().coerceIn(0, 255)
        val ig = (outG * 255f).toInt().coerceIn(0, 255)
        val ib = (outB * 255f).toInt().coerceIn(0, 255)
        return (ir shl 16) or (ig shl 8) or ib
    }

    fun loadCube(ctx: Context, uri: Uri): Lut {
        var size = 0
        val values = ArrayList<Float>()
        var minV = floatArrayOf(0f, 0f, 0f); var maxV = floatArrayOf(1f, 1f, 1f)
        ctx.contentResolver.openInputStream(uri)!!.bufferedReader().useLines { lines ->
            for (raw in lines) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("#")) continue
                val parts = line.split(Regex("\\s+"))
                when {
                    parts[0] == "LUT_3D_SIZE" -> size = parts[1].toInt()
                    parts[0] == "DOMAIN_MIN" -> minV = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    parts[0] == "DOMAIN_MAX" -> maxV = floatArrayOf(parts[1].toFloat(), parts[2].toFloat(), parts[3].toFloat())
                    parts[0].first().isDigit() || parts[0].first() == '-' || parts[0].first() == '.' -> {
                        if (parts.size >= 3) for (i in 0..2) values.add((parts[i].toFloat() - minV[i]) / (maxV[i] - minV[i]))
                    }
                }
            }
        }
        require(size >= 2 && values.size >= size * size * size * 3) { "Archivo .cube no válido (solo LUT 3D)" }
        val name = uri.lastPathSegment?.substringAfterLast('/') ?: "LUT"
        return Lut(size, values.toFloatArray(), name)
    }
}
