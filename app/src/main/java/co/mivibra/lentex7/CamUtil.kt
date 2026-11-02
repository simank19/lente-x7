package co.mivibra.lentex7

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executor
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.sqrt

object CamUtil {

    /** Toma una foto en memoria y la devuelve derecha (ya rotada) y editable. */
    suspend fun takeBitmap(ic: ImageCapture, ex: Executor): Bitmap = suspendCancellableCoroutine { cont ->
        ic.takePicture(ex, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                try {
                    val rot = image.imageInfo.rotationDegrees
                    val b = image.toBitmap()
                    image.close()
                    cont.resume(upright(b, rot))
                } catch (t: Throwable) {
                    try { image.close() } catch (_: Throwable) {}
                    cont.resumeWithException(t)
                }
            }
            override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
        })
    }

    fun upright(src: Bitmap, rot: Int): Bitmap {
        if (rot % 360 == 0) {
            return if (src.isMutable) src else src.copy(Bitmap.Config.ARGB_8888, true).also { src.recycle() }
        }
        val swap = rot % 180 != 0
        val w = if (swap) src.height else src.width
        val h = if (swap) src.width else src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val m = Matrix()
        m.postTranslate(-src.width / 2f, -src.height / 2f)
        m.postRotate(rot.toFloat())
        m.postTranslate(w / 2f, h / 2f)
        Canvas(out).drawBitmap(src, m, Paint(Paint.FILTER_BITMAP_FLAG))
        src.recycle()
        return out
    }

    fun rotate(src: Bitmap, deg: Int): Bitmap = upright(src, (deg + 360) % 360)

    fun scaleTo(src: Bitmap, maxLong: Int): Bitmap {
        val long = maxOf(src.width, src.height)
        if (long <= maxLong) return src
        val s = maxLong.toFloat() / long
        val out = Bitmap.createScaledBitmap(src, (src.width * s).toInt(), (src.height * s).toInt(), true)
        if (out !== src) src.recycle()
        return out
    }

    fun fileName(tag: String): String =
        "LX7_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + "_" + tag

    fun newImageValues(name: String): ContentValues = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$name.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/LenteX7")
        put(MediaStore.Images.Media.DATE_TAKEN, System.currentTimeMillis())
    }

    /** Guarda un JPEG en Imágenes/LenteX7. Si se pasa xmp, se inserta como bloque APP1 (para fotos 360). */
    fun save(ctx: Context, bmp: Bitmap, tag: String, xmp: String? = null, quality: Int = 95): Uri {
        val bos = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, quality, bos)
        var bytes = bos.toByteArray()
        if (xmp != null) bytes = insertXmp(bytes, xmp)
        val values = newImageValues(fileName(tag)).apply { put(MediaStore.Images.Media.IS_PENDING, 1) }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            ?: throw IllegalStateException("No se pudo crear el archivo")
        resolver.openOutputStream(uri)!!.use { it.write(bytes) }
        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return uri
    }

    private fun insertXmp(jpeg: ByteArray, xmp: String): ByteArray {
        val header = "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1)
        val body = xmp.toByteArray(Charsets.UTF_8)
        val len = 2 + header.size + body.size
        val seg = ByteArrayOutputStream()
        seg.write(0xFF); seg.write(0xE1); seg.write(len shr 8 and 255); seg.write(len and 255)
        seg.write(header); seg.write(body)
        // Inserta después de SOI y, si existe, después del bloque JFIF (APP0).
        var pos = 2
        if (jpeg.size > 6 && (jpeg[2].toInt() and 255) == 0xFF && (jpeg[3].toInt() and 255) == 0xE0) {
            val l = ((jpeg[4].toInt() and 255) shl 8) or (jpeg[5].toInt() and 255)
            pos = 4 + l
        }
        val out = ByteArrayOutputStream()
        out.write(jpeg, 0, pos)
        out.write(seg.toByteArray())
        out.write(jpeg, pos, jpeg.size - pos)
        return out.toByteArray()
    }

    fun gpanoXmp(w: Int, h: Int): String = """<x:xmpmeta xmlns:x="adobe:ns:meta/"><rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#"><rdf:Description rdf:about="" xmlns:GPano="http://ns.google.com/photos/1.0/panorama/" GPano:ProjectionType="equirectangular" GPano:UsePanoramaViewer="True" GPano:CroppedAreaImageWidthPixels="$w" GPano:CroppedAreaImageHeightPixels="$h" GPano:FullPanoWidthPixels="$w" GPano:FullPanoHeightPixels="$h" GPano:CroppedAreaLeftPixels="0" GPano:CroppedAreaTopPixels="0" GPano:PoseHeadingDegrees="0"/></rdf:RDF></x:xmpmeta>"""
}

/** Orientación del teléfono: matriz de rotación, rumbo, inclinación, nivel y estabilidad. */
class Motion(ctx: Context) : SensorEventListener {
    private val sm = ctx.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotSensor = sm.getDefaultSensor(Sensor.TYPE_GAME_ROTATION_VECTOR) ?: sm.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val gyro = sm.getDefaultSensor(Sensor.TYPE_GYROSCOPE)
    private val accel = sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

    val r = FloatArray(9).apply { this[0] = 1f; this[4] = 1f; this[8] = 1f }
    @Volatile var hasRotation = false
    @Volatile var rollDeg = 0f
    /** Velocidad angular suavizada (rad/s). */
    @Volatile var shake = 1f
    private var stillSince = 0L
    var onLevel: ((Float) -> Unit)? = null

    val tripod: Boolean get() = stillSince > 0 && System.currentTimeMillis() - stillSince > 1200
    val steady: Boolean get() = shake < 0.12f

    fun start() {
        rotSensor?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        gyro?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
        accel?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI) }
    }

    fun stop() = sm.unregisterListener(this)

    fun snapshot(): FloatArray = synchronized(r) { r.copyOf() }

    /** Dirección de la cámara (mira por la parte de atrás): rumbo y elevación en radianes. */
    fun yawPitch(m: FloatArray = snapshot()): Pair<Double, Double> {
        val x = -m[2].toDouble(); val y = -m[5].toDouble(); val z = -m[8].toDouble()
        return atan2(x, y) to asin(z.coerceIn(-1.0, 1.0))
    }

    override fun onSensorChanged(e: SensorEvent) {
        when (e.sensor.type) {
            Sensor.TYPE_GAME_ROTATION_VECTOR, Sensor.TYPE_ROTATION_VECTOR -> {
                synchronized(r) { SensorManager.getRotationMatrixFromVector(r, e.values) }
                hasRotation = true
            }
            Sensor.TYPE_GYROSCOPE -> {
                val m = sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2])
                shake = shake * 0.85f + m * 0.15f
                val now = System.currentTimeMillis()
                if (shake < 0.015f) { if (stillSince == 0L) stillSince = now } else stillSince = 0L
            }
            Sensor.TYPE_ACCELEROMETER -> {
                val ax = e.values[0]; val ay = e.values[1]
                if (abs(ay) + abs(ax) > 3f) {
                    rollDeg = Math.toDegrees(atan2(ax.toDouble(), ay.toDouble())).toFloat()
                    onLevel?.invoke(rollDeg)
                }
            }
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
}
