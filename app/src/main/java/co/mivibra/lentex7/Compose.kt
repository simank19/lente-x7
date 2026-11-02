package co.mivibra.lentex7

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF

/** Composición de fotos de varias cámaras (modo DUAL). */
object Compose {

    fun mirror(src: Bitmap): Bitmap {
        val m = Matrix().apply { preScale(-1f, 1f) }
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return if (out.isMutable) out else out.copy(Bitmap.Config.ARGB_8888, true).also { out.recycle() }
    }

    /** Foto principal a pantalla completa y las demás en recuadros redondeados en la esquina superior derecha. */
    fun pip(main: Bitmap, smalls: List<Bitmap>): Bitmap {
        val out = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG)
        c.drawBitmap(main, 0f, 0f, paint)
        val boxW = out.width * 0.30f
        val margin = out.width * 0.035f
        val radius = boxW * 0.08f
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; style = Paint.Style.STROKE; strokeWidth = out.width * 0.006f }
        var top = margin
        for (s in smalls) {
            val boxH = boxW * s.height / s.width
            val r = RectF(out.width - margin - boxW, top, out.width - margin, top + boxH)
            c.save()
            c.clipPath(Path().apply { addRoundRect(r, radius, radius, Path.Direction.CW) })
            c.drawBitmap(s, null, r, paint)
            c.restore()
            c.drawRoundRect(r, radius, radius, border)
            top += boxH + margin
        }
        return out
    }

    /** Fotos una al lado de la otra, todas a la misma altura. */
    fun sideBySide(list: List<Bitmap>, height: Int = 2400): Bitmap {
        val gap = height / 120
        val widths = list.map { it.width * height / it.height }
        val total = widths.sum() + gap * (list.size - 1)
        val out = Bitmap.createBitmap(total, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        c.drawColor(Color.BLACK)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        var x = 0
        list.forEachIndexed { i, b ->
            c.drawBitmap(b, null, RectF(x.toFloat(), 0f, (x + widths[i]).toFloat(), height.toFloat()), paint)
            x += widths[i] + gap
        }
        return out
    }
}
