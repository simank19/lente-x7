package co.mivibra.lentex7

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

const val ACCENT = 0xFFFFC21A.toInt()
const val OK_GREEN = 0xFF86EFAC.toInt()

/** Capa sobre el visor: rejilla, nivel, cuadro de enfoque y guías de panorámica / 360. */
class OverlayView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {

    enum class Guide { NONE, PANO_H, PANO_V, SPHERE, MOON }

    var showGrid = true
        set(v) { field = v; invalidate() }
    var rollDeg = 0f
        set(v) { field = v; invalidate() }
    var showLevel = true
    var guide = Guide.NONE
        set(v) { field = v; invalidate() }

    /** Puntos objetivo del modo 360 ya proyectados a pantalla (x, y, capturado). */
    var sphereTargets: List<Triple<Float, Float, Boolean>> = emptyList()
        set(v) { field = v; invalidate() }

    private var focusX = -1f
    private var focusY = -1f
    private var focusAt = 0L

    private val dp = resources.displayMetrics.density
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(70, 255, 255, 255); strokeWidth = 1f * dp
    }
    private val levelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 2f * dp; style = Paint.Style.STROKE }
    private val accentStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ACCENT; strokeWidth = 2f * dp; style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
    }
    private val whiteStroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; strokeWidth = 3f * dp; style = Paint.Style.STROKE
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 194, 26); strokeWidth = 1.5f * dp; style = Paint.Style.STROKE
        pathEffect = android.graphics.DashPathEffect(floatArrayOf(8f * dp, 6f * dp), 0f)
    }

    fun showFocus(x: Float, y: Float) {
        focusX = x; focusY = y; focusAt = System.currentTimeMillis(); invalidate()
        postDelayed({ invalidate() }, 1600)
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        if (showGrid && guide != Guide.SPHERE) {
            for (i in 1..2) {
                canvas.drawLine(w * i / 3f, 0f, w * i / 3f, h, gridPaint)
                canvas.drawLine(0f, h * i / 3f, w, h * i / 3f, gridPaint)
            }
        }
        if (showLevel && guide != Guide.SPHERE && guide != Guide.MOON) {
            val level = abs(rollDeg) < 1f
            levelPaint.color = if (level) OK_GREEN else Color.argb(200, 255, 255, 255)
            val len = w * 0.22f
            val r = Math.toRadians(rollDeg.toDouble())
            val dx = (cos(r) * len).toFloat(); val dy = (sin(r) * len).toFloat()
            val cx = w / 2; val cy = h / 2
            canvas.drawLine(cx - dx, cy - dy, cx - dx * 0.25f, cy - dy * 0.25f, levelPaint)
            canvas.drawLine(cx + dx * 0.25f, cy + dy * 0.25f, cx + dx, cy + dy, levelPaint)
        }
        if (focusX >= 0 && System.currentTimeMillis() - focusAt < 1500) {
            val s = 36f * dp
            canvas.drawRect(focusX - s, focusY - s, focusX + s, focusY + s, accentStroke)
        }
        when (guide) {
            Guide.PANO_H, Guide.PANO_V -> drawPanoArrow(canvas, w, h, guide == Guide.PANO_V)
            Guide.SPHERE -> drawSphere(canvas, w, h)
            Guide.MOON -> canvas.drawCircle(w / 2, h / 2, w * 0.33f, dashPaint)
            else -> {}
        }
    }

    private fun drawPanoArrow(c: Canvas, w: Float, h: Float, vertical: Boolean) {
        c.save()
        c.rotate(if (vertical) -90f else 0f, w / 2, h / 2)
        c.drawLine(0f, h / 2, w, h / 2, Paint(gridPaint).apply { color = Color.argb(160, 255, 255, 255) })
        val p = Path()
        val cx = w / 2; val cy = h / 2; val L = 40f * dp
        p.moveTo(cx - L, cy); p.lineTo(cx + L, cy)
        p.moveTo(cx + L - 14f * dp, cy - 12f * dp); p.lineTo(cx + L, cy); p.lineTo(cx + L - 14f * dp, cy + 12f * dp)
        c.drawPath(p, Paint(accentStroke).apply { strokeWidth = 4f * dp })
        c.restore()
    }

    private fun drawSphere(c: Canvas, w: Float, h: Float) {
        val cx = w / 2; val cy = h / 2
        c.drawCircle(cx, cy, 30f * dp, whiteStroke)
        for ((x, y, done) in sphereTargets) {
            dotPaint.color = if (done) Color.argb(90, 134, 239, 172) else ACCENT
            c.drawCircle(x, y, if (done) 6f * dp else 10f * dp, dotPaint)
        }
    }
}

/** Histograma de luminancia en vivo. */
class HistogramView @JvmOverloads constructor(ctx: Context, attrs: AttributeSet? = null) : View(ctx, attrs) {
    private var bins = IntArray(64)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(110, 244, 244, 245) }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(230, 244, 244, 245); style = Paint.Style.STROKE; strokeWidth = 1.2f * resources.displayMetrics.density
    }
    private val bg = Paint().apply { color = Color.argb(140, 0, 0, 0) }

    fun update(b: IntArray) { bins = b; postInvalidate() }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat(); val h = height.toFloat()
        canvas.drawRoundRect(0f, 0f, w, h, 12f, 12f, bg)
        val max = (bins.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val p = Path()
        p.moveTo(4f, h - 4f)
        val n = bins.size
        for (i in 0 until n) {
            val x = 4f + (w - 8f) * i / (n - 1)
            val y = h - 4f - (h - 8f) * (bins[i] / max)
            p.lineTo(x, y)
        }
        p.lineTo(w - 4f, h - 4f)
        p.close()
        canvas.drawPath(p, fill)
        canvas.drawPath(p, line)
    }
}
