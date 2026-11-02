package co.mivibra.lentex7

import android.Manifest
import android.annotation.SuppressLint
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Typeface
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Size
import android.util.SizeF
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.ConcurrentCamera
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.extensions.ExtensionMode
import androidx.camera.extensions.ExtensionsManager
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.google.common.util.concurrent.ListenableFuture
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.math.acos
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.tan

@OptIn(ExperimentalCamera2Interop::class)
class MainActivity : AppCompatActivity() {

    enum class Mode(val label: String) { NOCHE("NOCHE"), LUNA("LUNA"), PANO("PANO"), ESFERA("360°"), FOTO("FOTO"), DUAL("DUAL"), MACRO("MACRO"), PRO("PRO"), COLOR("COLOR") }

    // Vistas
    private lateinit var previewView: PreviewView
    private lateinit var previewFront: PreviewView
    private lateinit var overlay: OverlayView
    private lateinit var histo: HistogramView
    private lateinit var txtInfo: TextView
    private lateinit var txtCenter: TextView
    private lateinit var txtZoom: TextView
    private lateinit var txtTripod: TextView
    private lateinit var zoomSeek: SeekBar
    private lateinit var lensRow: LinearLayout
    private lateinit var progress: ProgressBar
    private lateinit var panel: LinearLayout
    private lateinit var modeRow: LinearLayout
    private lateinit var modeScroll: HorizontalScrollView
    private lateinit var shutter: ImageButton
    private lateinit var shutterLabel: TextView
    private lateinit var thumb: ImageView
    private lateinit var btnFlash: TextView
    private lateinit var btnHdr: TextView
    private lateinit var btnTimer: TextView
    private lateinit var btnGrid: TextView

    // Cámara
    private var provider: ProcessCameraProvider? = null
    private var extMgr: ExtensionsManager? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private lateinit var camManager: CameraManager
    private var mainId: String? = null
    private var uwId: String? = null
    private var currentId: String? = null
    private var extensionActive = false
    private var mainMinZoom = 1f
    private var frontId: String? = null
    private var concurrentPair: List<CameraInfo>? = null
    private var concurrentActive = false
    private var frontCapture: ImageCapture? = null
    private lateinit var frontExecutor: ExecutorService
    private var dualTrio = false
    private var dualSide = false

    // Estado
    private var mode = Mode.FOTO
    private var busy = false
    private var lastUri: Uri? = null
    private var flash = ImageCapture.FLASH_MODE_OFF
    private var timerSec = 0
    private var hdrOn = false
    private var hdrAvailable = false
    private var nightExtAvailable = false
    private var res50 = false
    private var wantZoom = 1f
    private val color = ColorParams()
    private lateinit var motion: Motion

    // Pro
    private var proTab = "ISO"
    private var proIso: Int? = null
    private var proExp: Long? = null
    private var proEv = 0
    private var proWb = CaptureRequest.CONTROL_AWB_MODE_AUTO
    private var proFocus: Float? = null

    // Submodos
    private var nightSub = "Mano"
    private var moonZoom = 10f
    private var moonExp = 4_000_000L // 1/250 s
    private var panoSub = "Horizontal"
    private var sphereFast = true
    private var macroSub = "Macro 2×"
    private var torch = false

    // Lecturas en vivo
    @Volatile private var liveIso = 100
    @Volatile private var liveExp = 10_000_000L
    @Volatile private var liveFocus = 0f
    @Volatile private var liveAperture = 1.5f

    // Panorámica / 360
    private var panoRunning = false
    private var panoStop = false
    private var sphereRunning = false
    private var sphereStop = false

    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) startCamera() else toast("La app necesita permiso de cámara")
    }
    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            try { color.lut = ColorEngine.loadCube(this, uri); toast("LUT cargada: ${color.lut?.name}") } catch (t: Throwable) { toast(t.message ?: "LUT no válida") }
            buildPanel()
        }
    }

    private val dp get() = resources.displayMetrics.density

    // ------------------------------------------------------------------ ciclo de vida

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        previewView = findViewById(R.id.preview)
        overlay = findViewById(R.id.overlay)
        histo = findViewById(R.id.histo)
        txtInfo = findViewById(R.id.txtInfo)
        txtCenter = findViewById(R.id.txtCenter)
        txtZoom = findViewById(R.id.txtZoom)
        txtTripod = findViewById(R.id.txtTripod)
        zoomSeek = findViewById(R.id.zoomSeek)
        lensRow = findViewById(R.id.lensRow)
        progress = findViewById(R.id.progress)
        panel = findViewById(R.id.panel)
        modeRow = findViewById(R.id.modeRow)
        modeScroll = findViewById(R.id.modeScroll)
        shutter = findViewById(R.id.shutter)
        shutterLabel = findViewById(R.id.shutterLabel)
        thumb = findViewById(R.id.thumb)
        btnFlash = findViewById(R.id.btnFlash)
        btnHdr = findViewById(R.id.btnHdr)
        btnTimer = findViewById(R.id.btnTimer)
        btnGrid = findViewById(R.id.btnGrid)

        cameraExecutor = Executors.newSingleThreadExecutor()
        frontExecutor = Executors.newSingleThreadExecutor()
        previewFront = findViewById(R.id.previewFront)
        previewFront.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewFront.clipToOutline = true
        camManager = getSystemService(CAMERA_SERVICE) as CameraManager
        motion = Motion(this)
        motion.onLevel = { r -> runOnUiThread { overlay.rollDeg = r } }

        previewView.implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        previewView.scaleType = PreviewView.ScaleType.FILL_CENTER

        setupTopBar()
        setupGestures()
        buildModeStrip()
        shutter.setOnClickListener { onShutter() }
        thumb.setOnClickListener { openLast() }
        findViewById<TextView>(R.id.btnZoom).setOnClickListener {
            zoomSeek.visibility = if (zoomSeek.visibility == View.VISIBLE) View.GONE else View.VISIBLE
            syncZoomSeek()
        }
        zoomSeek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) {
                if (!fromUser) return
                val zs = camera?.cameraInfo?.zoomState?.value ?: return
                val lo = zs.minZoomRatio; val hi = zs.maxZoomRatio
                val r = (lo * Math.pow((hi / lo).toDouble(), p / 1000.0)).toFloat()
                wantZoom = r
                camera?.cameraControl?.setZoomRatio(r)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) startCamera()
        else permLauncher.launch(Manifest.permission.CAMERA)

        tickInfo()
    }

    override fun onResume() { super.onResume(); motion.start() }
    override fun onPause() { super.onPause(); motion.stop(); panoStop = true; sphereStop = true }
    override fun onDestroy() { super.onDestroy(); cameraExecutor.shutdown() }

    // ------------------------------------------------------------------ cámara

    private suspend fun <T> ListenableFuture<T>.await(): T = suspendCancellableCoroutine { c ->
        addListener({
            try { c.resume(get()) } catch (t: Throwable) { c.resumeWithException(t.cause ?: t) }
        }, ContextCompat.getMainExecutor(this@MainActivity))
    }

    private fun startCamera() {
        lifecycleScope.launch {
            try {
                val p = ProcessCameraProvider.getInstance(this@MainActivity).await()
                provider = p
                discoverCameras(p)
                try { extMgr = ExtensionsManager.getInstanceAsync(this@MainActivity, p).await() } catch (_: Throwable) {}
                checkExtensions()
                setMode(Mode.FOTO, force = true)
            } catch (t: Throwable) {
                toast("No se pudo abrir la cámara: ${t.message}")
            }
        }
    }

    private fun chars(id: String?): CameraCharacteristics? = try { if (id == null) null else camManager.getCameraCharacteristics(id) } catch (_: Throwable) { null }

    private fun eq35(c: CameraCharacteristics): Float {
        val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: return 26f
        val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return 26f
        val diag = kotlin.math.sqrt(s.width * s.width + s.height * s.height)
        return f * 43.27f / diag
    }

    private fun discoverCameras(p: ProcessCameraProvider) {
        val backs = p.availableCameraInfos.mapNotNull { info ->
            val ci = Camera2CameraInfo.from(info)
            if (ci.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) return@mapNotNull null
            val id = ci.cameraId
            val c = chars(id) ?: return@mapNotNull null
            val px = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE)
            Triple(id, eq35(c), (px?.width ?: 0).toLong() * (px?.height ?: 0))
        }
        val main = backs.firstOrNull { it.first == "0" } ?: backs.maxByOrNull { it.third }
        mainId = main?.first
        val mainEq = main?.second ?: 26f
        uwId = backs.filter { it.first != mainId && it.second < mainEq * 0.8f }.minByOrNull { it.second }?.first
        mainMinZoom = chars(mainId)?.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)?.lower ?: 1f
        currentId = mainId
        frontId = p.availableCameraInfos.map { Camera2CameraInfo.from(it) }
            .firstOrNull { it.getCameraCharacteristic(CameraCharacteristics.LENS_FACING) == CameraCharacteristics.LENS_FACING_FRONT }?.cameraId
        concurrentPair = try {
            p.availableConcurrentCameraInfos.firstOrNull { l -> l.any { facing(it) == CameraCharacteristics.LENS_FACING_BACK } && l.any { facing(it) == CameraCharacteristics.LENS_FACING_FRONT } }
        } catch (_: Throwable) { null }
    }

    private fun facing(info: CameraInfo): Int? = Camera2CameraInfo.from(info).getCameraCharacteristic(CameraCharacteristics.LENS_FACING)

    private fun simpleCapture(): ImageCapture = ImageCapture.Builder()
        .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
        .setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
                .setResolutionFilter { sizes, _ -> sizes.filter { it.width.toLong() * it.height <= 13_000_000L }.ifEmpty { sizes } }
                .build()
        )
        .setFlashMode(flash)
        .build()

    /** Modo DUAL: abre trasera y frontal a la vez si el teléfono lo permite. */
    private fun bindDual() {
        val p = provider ?: return
        camera?.cameraInfo?.zoomState?.removeObservers(this)
        p.unbindAll()
        concurrentActive = false
        frontCapture = null
        extensionActive = false
        val pair = concurrentPair
        if (pair != null) {
            try {
                val backInfo = pair.first { facing(it) == CameraCharacteristics.LENS_FACING_BACK }
                val frontInfo = pair.first { facing(it) == CameraCharacteristics.LENS_FACING_FRONT }
                val pb = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
                val cb = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).setFlashMode(flash).build()
                val pf = Preview.Builder().build().also { it.setSurfaceProvider(previewFront.surfaceProvider) }
                val cf = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY).build()
                val c1 = ConcurrentCamera.SingleCameraConfig(backInfo.cameraSelector, UseCaseGroup.Builder().addUseCase(pb).addUseCase(cb).build(), this)
                val c2 = ConcurrentCamera.SingleCameraConfig(frontInfo.cameraSelector, UseCaseGroup.Builder().addUseCase(pf).addUseCase(cf).build(), this)
                val cc = p.bindToLifecycle(listOf(c1, c2))
                camera = cc.cameras.firstOrNull()
                imageCapture = cb
                frontCapture = cf
                concurrentActive = true
            } catch (t: Throwable) {
                p.unbindAll()
                concurrentActive = false
            }
        }
        if (!concurrentActive) bindSimple(mainId, 1f)
        previewFront.visibility = if (concurrentActive) View.VISIBLE else View.GONE
        camera?.cameraInfo?.zoomState?.observe(this) { zs -> txtZoom.text = zoomText(zs.zoomRatio) }
        buildPanel()
    }

    /** Une una sola cámara (preview + foto), usado por el modo DUAL secuencial. */
    private fun bindSimple(id: String?, zoom: Float): Boolean {
        val p = provider ?: return false
        p.unbindAll()
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val ic = simpleCapture()
        return try {
            camera = p.bindToLifecycle(this, selectorFor(id), preview, ic)
            imageCapture = ic
            currentId = id
            camera?.cameraControl?.setZoomRatio(zoom)
            true
        } catch (_: Throwable) { false }
    }

    private fun selectorFor(id: String?): CameraSelector {
        if (id == null) return CameraSelector.DEFAULT_BACK_CAMERA
        return CameraSelector.Builder().addCameraFilter { infos ->
            infos.filter { Camera2CameraInfo.from(it).cameraId == id }
        }.build()
    }

    private fun checkExtensions() {
        val em = extMgr ?: return
        val sel = selectorFor(mainId)
        hdrAvailable = try { em.isExtensionAvailable(sel, ExtensionMode.HDR) } catch (_: Throwable) { false }
        nightExtAvailable = try { em.isExtensionAvailable(sel, ExtensionMode.NIGHT) } catch (_: Throwable) { false }
    }

    private fun wantedExtension(): Int? = when {
        mode == Mode.FOTO && hdrOn && hdrAvailable && !res50 -> ExtensionMode.HDR
        mode == Mode.NOCHE && nightSub == "Mano" && nightExtAvailable -> ExtensionMode.NIGHT
        else -> null
    }

    private val captureCb = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            result.get(CaptureResult.SENSOR_SENSITIVITY)?.let { liveIso = it }
            result.get(CaptureResult.SENSOR_EXPOSURE_TIME)?.let { liveExp = it }
            result.get(CaptureResult.LENS_FOCUS_DISTANCE)?.let { liveFocus = it }
            result.get(CaptureResult.LENS_APERTURE)?.let { liveAperture = it }
        }
    }

    private var histTick = 0

    @SuppressLint("UnsafeOptInUsageError")
    private fun bindCamera() {
        val p = provider ?: return
        if (mode == Mode.DUAL) { bindDual(); return }
        previewFront.visibility = View.GONE
        concurrentActive = false
        val baseSel = selectorFor(currentId)
        val ext = if (currentId == mainId) wantedExtension() else null
        var sel = baseSel
        extensionActive = false
        if (ext != null) {
            try { sel = extMgr!!.getExtensionEnabledCameraSelector(baseSel, ext); extensionActive = true } catch (_: Throwable) {}
        }

        val pb = Preview.Builder()
        if (!extensionActive) Camera2Interop.Extender(pb).setSessionCaptureCallback(captureCb)
        val preview = pb.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }

        val capRes = ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY)
        if (!(res50 && mode == Mode.FOTO)) {
            capRes.setResolutionFilter { sizes, _ ->
                val f = sizes.filter { it.width.toLong() * it.height <= 13_000_000L }
                f.ifEmpty { sizes }
            }
        }
        val ic = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .setResolutionSelector(capRes.build())
            .setFlashMode(flash)
            .setJpegQuality(95)
            .build()
        imageCapture = ic

        val analysis = ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder().setResolutionStrategy(
                    ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER)
                ).build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        analysis.setAnalyzer(cameraExecutor) { img ->
            try {
                if (histTick++ % 3 == 0) {
                    val plane = img.planes[0]
                    val buf = plane.buffer
                    val rs = plane.rowStride
                    val bins = IntArray(64)
                    var y = 0
                    while (y < img.height) {
                        var x = 0
                        while (x < img.width) { bins[(buf.get(y * rs + x).toInt() and 255) shr 2]++; x += 4 }
                        y += 4
                    }
                    histo.update(bins)
                }
            } catch (_: Throwable) {}
            img.close()
        }

        camera?.cameraInfo?.zoomState?.removeObservers(this)
        p.unbindAll()
        val attempts = listOf<List<UseCase>>(listOf(preview, ic, analysis), listOf(preview, ic))
        var bound: Camera? = null
        for (uc in attempts) {
            try { bound = p.bindToLifecycle(this, sel, *uc.toTypedArray()); break } catch (_: Throwable) { p.unbindAll() }
        }
        if (bound == null && extensionActive) {
            extensionActive = false
            try { bound = p.bindToLifecycle(this, baseSel, preview, ic) } catch (_: Throwable) {}
        }
        camera = bound
        if (bound == null) { toast("No se pudo iniciar esta cámara"); return }
        histo.visibility = if (attempts.isNotEmpty() && !extensionActive) View.VISIBLE else View.GONE

        bound.cameraInfo.zoomState.observe(this) { zs ->
            txtZoom.text = zoomText(zs.zoomRatio)
            syncZoomSeek()
        }
        bound.cameraControl.setZoomRatio(wantZoom)
        bound.cameraControl.enableTorch(torch && mode == Mode.MACRO)
        if (mode == Mode.PRO && proIso == null && proExp == null) bound.cameraControl.setExposureCompensationIndex(proEv)
        applyCaptureOptions()
        buildLensRow()
    }

    private fun syncZoomSeek() {
        val zs = camera?.cameraInfo?.zoomState?.value ?: return
        val lo = zs.minZoomRatio; val hi = zs.maxZoomRatio
        if (hi <= lo) return
        zoomSeek.progress = (1000 * ln(zs.zoomRatio / lo) / ln(hi / lo)).roundToInt()
    }

    private fun zoomText(r: Float): String {
        val onUw = currentId == uwId && uwId != null
        val shown = if (onUw) r * (eq35(chars(uwId)!!) / eq35(chars(mainId)!!)) else r
        val q = when {
            onUw || shown < 1.95f -> "ÓPTICO"
            shown < 2.2f -> "SIN PÉRDIDA"
            shown <= 10.5f -> "HÍBRIDO"
            else -> "DIGITAL"
        }
        val mm = (26f * shown).roundToInt()
        return String.format("%.1f× · %d mm · %s", shown, mm, q).replace('.', ',')
    }

    private fun cc(): Camera2CameraControl? = camera?.let { Camera2CameraControl.from(it.cameraControl) }

    private fun curChars() = chars(currentId)
    private fun isoRange() = curChars()?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
    private fun expRange() = curChars()?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
    private fun minFocus() = curChars()?.get(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f
    private fun clampIso(v: Int) = isoRange()?.let { v.coerceIn(it.lower, it.upper) } ?: v
    private fun clampExp(v: Long) = expRange()?.let { v.coerceIn(it.lower, it.upper) } ?: v

    /** Opciones Camera2 según el modo (manual en Pro, Luna, etc.). */
    private fun applyCaptureOptions() {
        if (extensionActive) return
        val ctl = cc() ?: return
        val b = CaptureRequestOptions.Builder()
        when (mode) {
            Mode.PRO -> {
                if (proIso != null || proExp != null) {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, clampIso(proIso ?: liveIso))
                    b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExp(proExp ?: liveExp))
                }
                if (proWb != CaptureRequest.CONTROL_AWB_MODE_AUTO) b.setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, proWb)
                proFocus?.let {
                    b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                    b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, min(it, minFocus()))
                }
            }
            Mode.LUNA -> {
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, clampIso(64))
                b.setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExp(moonExp))
                b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, 0f)
                b.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_HIGH_QUALITY)
            }
            else -> {}
        }
        try { ctl.setCaptureRequestOptions(b.build()) } catch (_: Throwable) {}
    }

    private fun manual(iso: Int, expNs: Long, focusInf: Boolean = false, focus: Float? = null) {
        val b = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, clampIso(iso))
            .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, clampExp(expNs))
        if (focusInf || focus != null) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            b.setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, focus ?: 0f)
        }
        try { cc()?.setCaptureRequestOptions(b.build()) } catch (_: Throwable) {}
    }

    private fun focusOnly(d: Float) {
        val b = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
            .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, d)
        try { cc()?.setCaptureRequestOptions(b.build()) } catch (_: Throwable) {}
    }

    /** Campo de visión (rad) del lado corto y largo del sensor con el zoom actual. */
    private fun fovs(): Pair<Double, Double> {
        val c = curChars()
        val f = c?.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 5.6f
        val s = c?.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: SizeF(6.4f, 4.8f)
        val z = camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f
        val fz = f * z
        val short = 2 * atan(min(s.width, s.height) / (2.0 * fz))
        val long = 2 * atan(max(s.width, s.height) / (2.0 * fz))
        return short to long
    }

    // ------------------------------------------------------------------ interfaz

    private fun setupTopBar() {
        btnFlash.setOnClickListener {
            flash = when (flash) { ImageCapture.FLASH_MODE_OFF -> ImageCapture.FLASH_MODE_AUTO; ImageCapture.FLASH_MODE_AUTO -> ImageCapture.FLASH_MODE_ON; else -> ImageCapture.FLASH_MODE_OFF }
            imageCapture?.flashMode = flash
            refreshTopBar()
        }
        btnHdr.setOnClickListener {
            if (!hdrAvailable) { toast("Tu teléfono no ofrece HDR a apps externas"); return@setOnClickListener }
            hdrOn = !hdrOn; refreshTopBar(); bindCamera()
        }
        btnTimer.setOnClickListener { timerSec = when (timerSec) { 0 -> 3; 3 -> 10; else -> 0 }; refreshTopBar() }
        btnGrid.setOnClickListener { overlay.showGrid = !overlay.showGrid; refreshTopBar() }
        refreshTopBar()
    }

    private fun refreshTopBar() {
        btnFlash.text = when (flash) { ImageCapture.FLASH_MODE_AUTO -> "FLASH AUTO"; ImageCapture.FLASH_MODE_ON -> "FLASH SÍ"; else -> "FLASH NO" }
        styleChip(btnFlash, flash != ImageCapture.FLASH_MODE_OFF)
        btnHdr.text = if (hdrOn) "HDR SÍ" else "HDR"
        styleChip(btnHdr, hdrOn)
        btnHdr.visibility = if (mode == Mode.FOTO) View.VISIBLE else View.GONE
        btnTimer.text = "TEMP ${timerSec}s"
        styleChip(btnTimer, timerSec > 0)
        styleChip(btnGrid, overlay.showGrid)
    }

    private fun styleChip(v: TextView, on: Boolean) {
        v.setBackgroundResource(if (on) R.drawable.chip_on else R.drawable.chip_off)
        v.setTextColor(if (on) 0xFF0B0B0C.toInt() else 0xFFF4F4F5.toInt())
    }

    @SuppressLint("ClickableViewAccessibility")
    private fun setupGestures() {
        val scale = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val zs = camera?.cameraInfo?.zoomState?.value ?: return false
                val r = (zs.zoomRatio * d.scaleFactor).coerceIn(zs.minZoomRatio, zs.maxZoomRatio)
                wantZoom = r
                camera?.cameraControl?.setZoomRatio(r)
                return true
            }
        })
        previewView.setOnTouchListener { v, e ->
            scale.onTouchEvent(e)
            if (e.action == MotionEvent.ACTION_UP && !scale.isInProgress && e.pointerCount == 1) {
                if (!(mode == Mode.PRO && proFocus != null) && mode != Mode.LUNA) {
                    val pt = previewView.meteringPointFactory.createPoint(e.x, e.y)
                    try { camera?.cameraControl?.startFocusAndMetering(FocusMeteringAction.Builder(pt).build()) } catch (_: Throwable) {}
                    overlay.showFocus(e.x, e.y)
                }
                v.performClick()
            }
            true
        }
    }

    private fun buildModeStrip() {
        modeRow.removeAllViews()
        for (m in Mode.values()) {
            val t = TextView(this).apply {
                text = m.label
                textSize = 14f
                typeface = Typeface.DEFAULT_BOLD
                letterSpacing = 0.05f
                setPadding((12 * dp).toInt(), 0, (12 * dp).toInt(), 0)
                gravity = Gravity.CENTER
                minHeight = (44 * dp).toInt()
                tag = m
                setOnClickListener { setMode(m) }
            }
            modeRow.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT))
        }
        refreshModeStrip()
    }

    private fun refreshModeStrip() {
        for (i in 0 until modeRow.childCount) {
            val t = modeRow.getChildAt(i) as TextView
            t.setTextColor(if (t.tag == mode) ACCENT else 0xFFA1A1AA.toInt())
        }
        modeRow.post {
            val sel = (0 until modeRow.childCount).map { modeRow.getChildAt(it) }.firstOrNull { it.tag == mode } ?: return@post
            modeScroll.smoothScrollTo(sel.left + sel.width / 2 - modeScroll.width / 2, 0)
        }
    }

    private fun buildLensRow() {
        lensRow.removeAllViews()
        val opts = mutableListOf<Pair<String, Float>>()
        if (uwId != null || mainMinZoom < 1f) opts.add(".6" to 0.6f)
        opts.add("1×" to 1f); opts.add("2×" to 2f); opts.add("5×" to 5f); opts.add("10×" to 10f)
        val onUw = currentId == uwId && uwId != null
        val cur = if (onUw) 0.6f else (camera?.cameraInfo?.zoomState?.value?.zoomRatio ?: 1f)
        for ((label, r) in opts) {
            val sel = if (r < 1f) (onUw || cur < 0.95f) else (!onUw && kotlin.math.abs(cur - r) < 0.15f)
            val t = TextView(this).apply {
                text = label
                gravity = Gravity.CENTER
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                contentDescription = "Lente $label"
                setTextColor(if (sel) 0xFF0B0B0C.toInt() else 0xFFF4F4F5.toInt())
                if (sel) setBackgroundResource(R.drawable.lens_on)
                setOnClickListener { pickLens(r) }
            }
            val lp = LinearLayout.LayoutParams((44 * dp).toInt(), (44 * dp).toInt()).apply { marginStart = (2 * dp).toInt(); marginEnd = (2 * dp).toInt() }
            lensRow.addView(t, lp)
        }
        lensRow.visibility = if (mode in listOf(Mode.FOTO, Mode.PRO, Mode.COLOR, Mode.NOCHE)) View.VISIBLE else View.GONE
    }

    private fun pickLens(r: Float) {
        if (busy) return
        if (r < 1f) {
            if (mainMinZoom < 1f) { switchCamera(mainId, max(mainMinZoom, 0.6f)) }
            else if (uwId != null) switchCamera(uwId, 1f)
        } else switchCamera(mainId, r)
    }

    private fun switchCamera(id: String?, zoom: Float) {
        wantZoom = zoom
        if (id != currentId) { currentId = id; bindCamera() }
        else { camera?.cameraControl?.setZoomRatio(zoom); lensRow.postDelayed({ buildLensRow() }, 150) }
    }

    private fun setMode(m: Mode, force: Boolean = false) {
        if (busy || panoRunning || sphereRunning) { if (!force) return }
        val prev = mode
        mode = m
        overlay.guide = when (m) {
            Mode.PANO -> if (panoSub == "Vertical") OverlayView.Guide.PANO_V else OverlayView.Guide.PANO_H
            Mode.ESFERA -> OverlayView.Guide.SPHERE
            Mode.LUNA -> OverlayView.Guide.MOON
            else -> OverlayView.Guide.NONE
        }
        overlay.sphereTargets = emptyList()
        torch = false
        // Cámara y zoom que pide cada modo.
        when (m) {
            Mode.LUNA -> { currentId = mainId; wantZoom = moonZoom }
            Mode.MACRO -> { currentId = mainId; wantZoom = if (macroSub == "Súper macro 4×") 4f else 2f }
            Mode.PANO -> if (panoSub == "Gran angular") pickWide() else { currentId = mainId; wantZoom = 1f }
            Mode.ESFERA -> if (sphereFast) pickWide() else { currentId = mainId; wantZoom = 1f }
            Mode.DUAL -> { currentId = mainId; wantZoom = 1f }
            else -> if (prev in listOf(Mode.LUNA, Mode.MACRO, Mode.PANO, Mode.ESFERA) || force) { currentId = mainId; wantZoom = 1f }
        }
        refreshModeStrip()
        refreshTopBar()
        buildPanel()
        updateShutterLabel()
        bindCamera()
    }

    private fun pickWide() {
        if (mainMinZoom < 1f) { currentId = mainId; wantZoom = max(mainMinZoom, 0.6f) }
        else if (uwId != null) { currentId = uwId; wantZoom = 1f }
        else { currentId = mainId; wantZoom = 1f; toast("El gran angular no está disponible para apps externas; se usa la principal") }
    }

    private fun updateShutterLabel() {
        shutterLabel.text = when {
            mode == Mode.PANO && panoRunning -> "STOP"
            mode == Mode.PANO -> "PANO"
            mode == Mode.ESFERA && sphereRunning -> "FIN"
            mode == Mode.ESFERA -> "360"
            mode == Mode.NOCHE -> "NOCHE"
            mode == Mode.LUNA -> "LUNA"
            mode == Mode.MACRO -> "MACRO"
            mode == Mode.DUAL -> "DUAL"
            else -> ""
        }
    }

    // ---------- panel inferior por modo

    private fun label(text: String, size: Float = 12f, colorInt: Int = 0xFFA1A1AA.toInt()) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(colorInt)
        setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
    }

    private fun chips(items: List<String>, selected: String?, onPick: (String) -> Unit): View {
        val sv = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (it in items) {
            val t = TextView(this).apply {
                text = it
                textSize = 13f
                gravity = Gravity.CENTER
                setPadding((14 * dp).toInt(), 0, (14 * dp).toInt(), 0)
                styleChip(this, it == selected)
                setOnClickListener { _ -> onPick(it) }
            }
            row.addView(t, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, (40 * dp).toInt()).apply { marginEnd = (8 * dp).toInt() })
        }
        sv.addView(row)
        return sv
    }

    private fun slider(name: String, value: Int, onChange: (Int) -> Unit): View {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val lbl = label("$name: $value", 13f, 0xFFF4F4F5.toInt())
        val sb = SeekBar(this).apply {
            max = 200; progress = value + 100
            progressTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            thumbTintList = android.content.res.ColorStateList.valueOf(ACCENT)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { lbl.text = "$name: ${p - 100}"; if (fromUser) onChange(p - 100) }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        box.addView(lbl); box.addView(sb, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, (36 * dp).toInt()))
        return box
    }

    private fun button(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        setTextColor(0xFFF4F4F5.toInt())
        setBackgroundResource(R.drawable.card)
        setOnClickListener { onClick() }
    }

    private fun buildPanel() {
        panel.removeAllViews()
        when (mode) {
            Mode.DUAL -> {
                panel.addView(chips(listOf("Dúo: trasera + frontal", "Trío: 1× + 0,6× + frontal"), if (dualTrio) "Trío: 1× + 0,6× + frontal" else "Dúo: trasera + frontal") {
                    dualTrio = it.startsWith("Trío"); buildPanel()
                })
                panel.addView(chips(listOf("Recuadro", "Lado a lado"), if (dualSide) "Lado a lado" else "Recuadro") { dualSide = it == "Lado a lado"; buildPanel() })
                panel.addView(label(
                    if (concurrentActive) "Cámaras simultáneas: COMPATIBLE. Trasera y frontal se disparan al mismo tiempo" + (if (dualTrio) "; el 0,6× va justo después." else ".")
                    else "Tu teléfono no deja a apps externas abrir dos cámaras a la vez: se toman una tras otra en ~1 s. Pide que posen quietos."
                ))
                panel.addView(label("Se guarda la foto combinada y cada cámara por separado."))
            }
            Mode.FOTO -> {
                panel.addView(label("Toca para enfocar · pellizca para hacer zoom"))
                panel.addView(chips(listOf("12,5 MP procesada", "50 MP directa"), if (res50) "50 MP directa" else "12,5 MP procesada") {
                    res50 = it.startsWith("50"); buildPanel(); bindCamera()
                })
                if (res50) panel.addView(label("En 50 MP se guarda tal cual: sin perfil de color ni HDR."))
                if (!color.isNeutral) panel.addView(label("Perfil activo: ${color.profile}" + (color.lut?.let { " + LUT ${it.name}" } ?: ""), 12f, ACCENT))
            }
            Mode.NOCHE -> {
                panel.addView(chips(listOf("Mano", "Trípode", "Estelas de luz", "Astro"), nightSub) { nightSub = it; setMode(Mode.NOCHE) })
                panel.addView(label(when (nightSub) {
                    "Mano" -> if (nightExtAvailable) "Usa el modo noche del sistema (multicuadro)." else "Fusiona 8 cuadros alineados para quitar ruido."
                    "Trípode" -> "ISO bajo y exposiciones de 1 s, 8 cuadros promediados."
                    "Estelas de luz" -> "Suma 8 exposiciones de 2 s: carros, fuentes, luces."
                    else -> "Enfoque a infinito, 10 cuadros largos. Lejos de la ciudad."
                }))
            }
            Mode.LUNA -> {
                panel.addView(label("Zoom"))
                panel.addView(chips(listOf("5×", "10×", "20×"), "${moonZoom.toInt()}×") { moonZoom = it.dropLast(1).toFloat(); wantZoom = moonZoom; camera?.cameraControl?.setZoomRatio(moonZoom); buildPanel() })
                panel.addView(label("Exposición de la Luna"))
                val exps = linkedMapOf("1/500" to 2_000_000L, "1/250" to 4_000_000L, "1/125" to 8_000_000L, "1/60" to 16_666_666L)
                panel.addView(chips(exps.keys.toList(), exps.entries.firstOrNull { it.value == moonExp }?.key) { moonExp = exps[it]!!; applyCaptureOptions(); buildPanel() })
                panel.addView(label("Usa trípode. Apila 10 cuadros y aplica nitidez."))
            }
            Mode.PANO -> {
                panel.addView(chips(listOf("Horizontal", "Vertical", "Gran angular"), panoSub) { panoSub = it; setMode(Mode.PANO) })
                panel.addView(label("Toca el disparador y gira despacio hacia la ${if (panoSub == "Vertical") "arriba" else "derecha"}. Toca otra vez para terminar."))
            }
            Mode.ESFERA -> {
                panel.addView(chips(listOf("Rápida · 0,6×", "Detalle · 1×"), if (sphereFast) "Rápida · 0,6×" else "Detalle · 1×") { sphereFast = it.startsWith("R"); setMode(Mode.ESFERA) })
                panel.addView(label("Toca 360 y apunta a cada punto amarillo; dispara solo. Gira sobre el celular, no sobre tu cuerpo. Se guarda en 2:1 con metadatos 360 para Google Fotos."))
            }
            Mode.MACRO -> {
                panel.addView(chips(listOf("Macro 2×", "Súper macro 4×", "Apilado de enfoque"), macroSub) { macroSub = it; setMode(Mode.MACRO) })
                panel.addView(chips(listOf("Linterna"), if (torch) "Linterna" else null) { torch = !torch; camera?.cameraControl?.enableTorch(torch); buildPanel() })
                panel.addView(label("Acércate a unos 10–12 cm. El Poco X7 Pro no trae lente macro: se usa la principal con recorte."))
            }
            Mode.PRO -> buildProPanel()
            Mode.COLOR -> buildColorPanel()
        }
        applyPreviewColor()
    }

    private fun buildProPanel() {
        panel.addView(chips(listOf("ISO", "S", "EV", "WB", "F"), proTab) { proTab = it; buildPanel() })
        when (proTab) {
            "ISO" -> {
                val r = isoRange()
                val list = listOf(50, 100, 200, 400, 800, 1600, 3200, 6400).filter { r == null || it in r.lower..r.upper }
                panel.addView(label("ISO ${r?.lower ?: "?"}–${r?.upper ?: "?"}"))
                panel.addView(chips(listOf("AUTO") + list.map { "$it" }, proIso?.toString() ?: "AUTO") { proIso = it.toIntOrNull(); applyCaptureOptions(); buildPanel() })
            }
            "S" -> {
                val r = expRange()
                val all = linkedMapOf(
                    "1/4000" to 250_000L, "1/2000" to 500_000L, "1/1000" to 1_000_000L, "1/500" to 2_000_000L, "1/250" to 4_000_000L,
                    "1/125" to 8_000_000L, "1/60" to 16_666_666L, "1/30" to 33_333_333L, "1/15" to 66_666_666L, "1/8" to 125_000_000L,
                    "1/4" to 250_000_000L, "1/2" to 500_000_000L, "1\"" to 1_000_000_000L, "2\"" to 2_000_000_000L, "4\"" to 4_000_000_000L,
                    "8\"" to 8_000_000_000L, "15\"" to 15_000_000_000L, "30\"" to 30_000_000_000L
                ).filter { r == null || it.value in r.lower..r.upper }
                panel.addView(label("Velocidad de obturación (máx. ${r?.upper?.let { fmtExp(it) } ?: "?"})"))
                panel.addView(chips(listOf("AUTO") + all.keys, all.entries.firstOrNull { it.value == proExp }?.key ?: "AUTO") { proExp = all[it]; applyCaptureOptions(); buildPanel() })
            }
            "EV" -> {
                val r = camera?.cameraInfo?.exposureState?.exposureCompensationRange
                val list = ((r?.lower ?: -6)..(r?.upper ?: 6)).toList()
                val step = camera?.cameraInfo?.exposureState?.exposureCompensationStep?.toFloat() ?: (1f / 3f)
                panel.addView(label("Compensación (solo con ISO y S en AUTO)"))
                panel.addView(chips(list.map { String.format("%+.1f", it * step) }, String.format("%+.1f", proEv * step)) { s ->
                    proEv = list[list.map { String.format("%+.1f", it * step) }.indexOf(s)]
                    camera?.cameraControl?.setExposureCompensationIndex(proEv); buildPanel()
                })
            }
            "WB" -> {
                val wb = linkedMapOf(
                    "AUTO" to CaptureRequest.CONTROL_AWB_MODE_AUTO,
                    "Cálida 2700K" to CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT,
                    "Fluor. 4000K" to CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT,
                    "Día 5500K" to CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT,
                    "Nublado 6500K" to CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT,
                    "Sombra 7500K" to CaptureRequest.CONTROL_AWB_MODE_SHADE
                )
                panel.addView(label("Balance de blancos (ajuste fino en COLOR)"))
                panel.addView(chips(wb.keys.toList(), wb.entries.first { it.value == proWb }.key) { proWb = wb[it]!!; applyCaptureOptions(); buildPanel() })
            }
            "F" -> {
                val mf = minFocus()
                val fs = linkedMapOf<String, Float?>("AF" to null, "Mín." to mf, "10 cm" to 10f, "30 cm" to 3.33f, "1 m" to 1f, "3 m" to 0.33f, "∞" to 0f)
                    .filter { it.value == null || it.value!! <= mf || mf == 0f }
                panel.addView(label(if (mf == 0f) "Este lente es de enfoque fijo" else "Enfoque manual (el cian marca lo nítido al tocar)"))
                panel.addView(chips(fs.keys.toList(), fs.entries.firstOrNull { it.value == proFocus }?.key ?: "AF") { proFocus = fs[it]; applyCaptureOptions(); buildPanel() })
            }
        }
        panel.addView(chips(listOf("Todo en AUTO"), null) {
            proIso = null; proExp = null; proEv = 0; proWb = CaptureRequest.CONTROL_AWB_MODE_AUTO; proFocus = null
            camera?.cameraControl?.setExposureCompensationIndex(0); applyCaptureOptions(); buildPanel()
        })
    }

    private fun buildColorPanel() {
        panel.addView(label("Perfil de color"))
        panel.addView(chips(ColorEngine.PROFILES, color.profile) { color.profile = it; buildPanel() })
        panel.addView(slider("Temperatura (frío ↔ cálido)", color.temp) { color.temp = it; applyPreviewColor() })
        panel.addView(slider("Matiz (verde ↔ magenta)", color.tint) { color.tint = it; applyPreviewColor() })
        panel.addView(slider("Saturación", color.saturation) { color.saturation = it; applyPreviewColor() })
        panel.addView(slider("Contraste", color.contrast) { color.contrast = it; applyPreviewColor() })
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val lp = LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f).apply { marginEnd = (8 * dp).toInt() }
        row.addView(button(if (color.lut == null) "Importar LUT .cube" else "Quitar LUT") {
            if (color.lut == null) lutPicker.launch(arrayOf("*/*")) else { color.lut = null; buildPanel() }
        }, lp)
        row.addView(button("Restablecer") {
            color.profile = "Natural"; color.temp = 0; color.tint = 0; color.saturation = 0; color.contrast = 0; color.lut = null; buildPanel()
        }, LinearLayout.LayoutParams(0, (48 * dp).toInt(), 1f))
        panel.addView(row)
        panel.addView(label("El perfil se aplica a todos los modos. La LUT solo se ve al guardar."))
    }

    private fun applyPreviewColor() {
        if (Build.VERSION.SDK_INT >= 31) {
            val neutral = color.profile == "Natural" && color.temp == 0 && color.tint == 0 && color.saturation == 0 && color.contrast == 0
            previewView.setRenderEffect(if (neutral) null else RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(ColorEngine.matrix(color))))
        }
    }

    private fun fmtExp(ns: Long): String = if (ns >= 1_000_000_000L) "${ns / 1_000_000_000L}\"" else "1/${(1_000_000_000.0 / ns).roundToInt()}"

    private fun tickInfo() {
        val lines = StringBuilder()
        if (camera != null) {
            lines.append("ISO $liveIso · ${fmtExp(liveExp)} · f/${String.format("%.1f", liveAperture)}")
            if (liveFocus > 0f) lines.append("\nENFOQUE ${String.format("%.2f", 1f / liveFocus)} m") else lines.append("\nENFOQUE ∞")
            if (extensionActive) lines.append("\n").append(if (mode == Mode.NOCHE) "NOCHE DEL SISTEMA" else "HDR DEL SISTEMA")
        }
        txtInfo.text = lines.toString()
        txtInfo.visibility = if (mode == Mode.DUAL) View.GONE else View.VISIBLE
        txtTripod.text = when {
            motion.tripod -> "TRÍPODE ✓"
            mode in listOf(Mode.NOCHE, Mode.LUNA, Mode.MACRO) && !motion.steady -> "MOVIMIENTO"
            else -> ""
        }
        txtInfo.postDelayed({ tickInfo() }, 300)
    }

    // ------------------------------------------------------------------ captura

    private fun onShutter() {
        when (mode) {
            Mode.PANO -> { if (panoRunning) panoStop = true else if (!busy) runPano(); return }
            Mode.ESFERA -> { if (sphereRunning) sphereStop = true else if (!busy) runSphere(); return }
            else -> {}
        }
        if (busy) return
        lifecycleScope.launch {
            try {
                setBusy(true)
                if (timerSec > 0) for (s in timerSec downTo 1) { center("$s"); delay(1000) }
                when (mode) {
                    Mode.NOCHE -> captureNight()
                    Mode.LUNA -> captureMoon()
                    Mode.MACRO -> captureMacro()
                    Mode.DUAL -> captureDual()
                    else -> captureNormal()
                }
            } catch (t: Throwable) {
                toast("Error: ${t.message}")
            } finally {
                applyCaptureOptions()
                setBusy(false)
            }
        }
    }

    private fun setBusy(b: Boolean, text: String? = null) {
        busy = b
        progress.isIndeterminate = true
        progress.visibility = if (b) View.VISIBLE else View.GONE
        shutter.alpha = if (b) 0.4f else 1f
        if (text != null) center(text) else if (!b) txtCenter.visibility = View.GONE
    }

    private fun center(text: String?) {
        if (text == null) { txtCenter.visibility = View.GONE; return }
        txtCenter.text = text; txtCenter.visibility = View.VISIBLE
    }

    private fun flashScreen() {
        previewView.animate().alpha(0.2f).setDuration(60).withEndAction { previewView.animate().alpha(1f).setDuration(120).start() }.start()
    }

    private suspend fun shot(): Bitmap = CamUtil.takeBitmap(imageCapture!!, cameraExecutor)

    private suspend fun finishAndSave(bmp: Bitmap, tag: String, xmp: String? = null) {
        center("Guardando…")
        val uri = withContext(Dispatchers.Default) {
            ColorEngine.apply(bmp, color)
            CamUtil.save(this@MainActivity, bmp, tag, xmp)
        }
        lastUri = uri
        val th = Bitmap.createScaledBitmap(bmp, 160, max(1, 160 * bmp.height / bmp.width), true)
        thumb.setImageBitmap(th)
        bmp.recycle()
        center(null)
        toast("Guardada en Imágenes/LenteX7")
    }

    /** Toma, guarda por separado y devuelve una copia reducida para la composición. */
    private suspend fun keep(b: Bitmap, tag: String, mirror: Boolean = false): Bitmap = withContext(Dispatchers.Default) {
        var x = b
        if (mirror) x = Compose.mirror(x)
        ColorEngine.apply(x, color)
        CamUtil.save(this@MainActivity, x, tag)
        CamUtil.scaleTo(x, 3000)
    }

    private suspend fun captureDual() {
        flashScreen()
        val back: Bitmap
        val front: Bitmap
        var wide: Bitmap? = null
        if (concurrentActive && frontCapture != null) {
            center("Disparando ambas cámaras")
            val pair = coroutineScope {
                val a = async { shot() }
                val f = async { CamUtil.takeBitmap(frontCapture!!, frontExecutor) }
                a.await() to f.await()
            }
            back = pair.first; front = pair.second
        } else {
            center("Trasera…")
            back = shot()
            center("¡Ahora la frontal!")
            if (!bindSimple(frontId, 1f)) throw IllegalStateException("No se pudo abrir la cámara frontal")
            delay(700)
            front = shot()
        }
        center("Guardando…")
        val b1 = keep(back, "DUAL_TRASERA")
        val f1 = keep(front, "DUAL_FRONTAL", mirror = true)
        if (dualTrio) {
            center("Gran angular…")
            val ok = if (mainMinZoom < 1f) bindSimple(mainId, max(mainMinZoom, 0.6f)) else if (uwId != null) bindSimple(uwId, 1f) else false
            if (ok) { delay(700); wide = keep(shot(), "DUAL_GRAN_ANGULAR") }
            else toast("El gran angular no está disponible para apps externas")
        }
        center("Combinando…")
        val list = listOfNotNull(b1, wide, f1)
        val out = withContext(Dispatchers.Default) {
            if (dualSide) Compose.sideBySide(list) else Compose.pip(b1, listOfNotNull(wide, f1))
        }
        list.forEach { it.recycle() }
        bindCamera()
        lastUri = withContext(Dispatchers.Default) { CamUtil.save(this@MainActivity, out, "DUAL") }
        thumb.setImageBitmap(Bitmap.createScaledBitmap(out, 160, max(1, 160 * out.height / out.width), true))
        out.recycle()
        center(null)
        toast("Guardadas: combinada + cada cámara")
    }

    private suspend fun captureNormal() {
        val ic = imageCapture ?: return
        flashScreen()
        if (color.isNeutral || (res50 && mode == Mode.FOTO)) {
            val name = CamUtil.fileName(mode.name)
            val opts = ImageCapture.OutputFileOptions.Builder(contentResolver, android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, CamUtil.newImageValues(name)).build()
            val uri = suspendCancellableCoroutine<Uri?> { c ->
                ic.takePicture(opts, cameraExecutor, object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(r: ImageCapture.OutputFileResults) { c.resume(r.savedUri) }
                    override fun onError(e: ImageCaptureException) { c.resumeWithException(e) }
                })
            }
            lastUri = uri
            if (uri != null) {
                try {
                    val th = withContext(Dispatchers.IO) { contentResolver.loadThumbnail(uri, Size(200, 200), null) }
                    thumb.setImageBitmap(th)
                } catch (_: Throwable) {}
            }
            toast("Guardada en Imágenes/LenteX7")
        } else {
            center("Procesando…")
            finishAndSave(shot(), mode.name)
        }
    }

    private suspend fun stack(n: Int, lighten: Boolean = false, align: Boolean = true, label: String): Bitmap {
        var st: FrameStacker? = null
        for (i in 1..n) {
            center("$label\n$i / $n")
            val b = shot()
            withContext(Dispatchers.Default) {
                if (st == null) st = FrameStacker(b, lighten, align) else st!!.add(b)
            }
            b.recycle()
        }
        center("Fusionando…")
        return withContext(Dispatchers.Default) { st!!.result() }
    }

    private suspend fun captureNight() {
        if (extensionActive) { captureNormal(); return }
        flashScreen()
        val out = when (nightSub) {
            "Mano" -> {
                camera?.cameraControl?.setExposureCompensationIndex(camera?.cameraInfo?.exposureState?.exposureCompensationRange?.upper?.div(2) ?: 0)
                delay(500)
                val r = stack(8, label = "Mantén firme")
                camera?.cameraControl?.setExposureCompensationIndex(0)
                withContext(Dispatchers.Default) { Tone.lift(r, 0.85f, 1.1f); Tone.sharpen(r, 0.35f) }
                r
            }
            "Trípode" -> {
                manual(400, 1_000_000_000L); delay(2500)
                stack(8, label = "No muevas el trípode").also { withContext(Dispatchers.Default) { Tone.sharpen(it, 0.3f) } }
            }
            "Estelas de luz" -> {
                manual(100, 2_000_000_000L); delay(3000)
                stack(8, lighten = true, align = false, label = "Captando estelas")
            }
            else -> {
                manual(1600, 4_000_000_000L, focusInf = true); delay(4500)
                stack(10, label = "Captando el cielo").also { withContext(Dispatchers.Default) { Tone.lift(it, 0.8f, 1.2f) } }
            }
        }
        finishAndSave(out, "NOCHE")
    }

    private suspend fun captureMoon() {
        flashScreen()
        delay(300)
        val r = stack(10, label = "Apilando la Luna")
        withContext(Dispatchers.Default) { Tone.sharpen(r, 0.9f) }
        finishAndSave(r, "LUNA")
    }

    private suspend fun captureMacro() {
        when (macroSub) {
            "Macro 2×" -> captureNormal()
            "Súper macro 4×" -> {
                flashScreen()
                val r = stack(4, label = "No te muevas")
                withContext(Dispatchers.Default) { Tone.sharpen(r, 0.6f) }
                finishAndSave(r, "MACRO")
            }
            else -> {
                val mf = minFocus()
                if (mf <= 0f) { captureNormal(); return }
                val base = if (liveFocus > 0f) liveFocus else mf * 0.8f
                val steps = 6
                val near = min(mf, base * 1.5f); val far = max(0.5f, base * 0.6f)
                var fs: FocusStacker? = null
                for (i in 0 until steps) {
                    val d = near + (far - near) * i / (steps - 1)
                    focusOnly(d); delay(350)
                    center("Apilando enfoque\n${i + 1} / $steps")
                    val b = shot()
                    withContext(Dispatchers.Default) { if (fs == null) fs = FocusStacker(b) else fs!!.add(b) }
                    b.recycle()
                }
                finishAndSave(fs!!.result, "MACRO_APILADO")
            }
        }
    }

    // ---------- panorámica

    private fun angleDiff(a: Double, b: Double): Double {
        var d = a - b
        while (d > Math.PI) d -= 2 * Math.PI
        while (d < -Math.PI) d += 2 * Math.PI
        return d
    }

    private fun runPano() {
        if (!motion.hasRotation) { toast("Tu teléfono no reporta orientación"); return }
        panoRunning = true; panoStop = false; updateShutterLabel()
        lifecycleScope.launch {
            val vertical = panoSub == "Vertical"
            val (fs, fl) = fovs()
            val hfov = if (vertical) fl else fs
            val step = hfov * 0.5
            var st: PanoStitcher? = null
            var last = Double.NaN
            progress.visibility = View.VISIBLE; progress.isIndeterminate = true
            center("Gira despacio")
            try {
                while (!panoStop) {
                    val (yaw, pitch) = motion.yawPitch()
                    val a = if (vertical) pitch else yaw
                    val adv = if (last.isNaN()) step else if (vertical) a - last else angleDiff(a, last)
                    if (adv >= step * 0.95 && motion.shake < 0.8f) {
                        val raw = shot()
                        val f = withContext(Dispatchers.Default) {
                            var b = CamUtil.scaleTo(raw, 1800)
                            if (vertical) b = CamUtil.rotate(b, 90)
                            b
                        }
                        val ok = withContext(Dispatchers.Default) {
                            val s = st ?: PanoStitcher(f.width, f.height, hfov).also { st = it }
                            s.add(f, a)
                        }
                        f.recycle()
                        if (ok) last = a
                        val s = st!!
                        center("${s.count} tomas · ${s.degreesCovered}°")
                        if (s.degreesCovered >= (if (vertical) 170 else 345)) break
                    } else if (adv < -step * 0.3 && !last.isNaN()) {
                        center(if (vertical) "Hacia arriba" else "Hacia la derecha")
                    }
                    delay(50)
                }
                center("Uniendo panorámica…")
                var res = withContext(Dispatchers.Default) { st?.result() }
                if (res == null) { toast("Hacen falta al menos 2 tomas"); return@launch }
                if (vertical) res = withContext(Dispatchers.Default) { CamUtil.rotate(res!!, 270) }
                finishAndSave(res!!, "PANO")
            } catch (t: Throwable) {
                toast("Error: ${t.message}")
            } finally {
                panoRunning = false; progress.visibility = View.GONE; center(null); updateShutterLabel()
            }
        }
    }

    // ---------- 360

    private data class Target(val yaw: Double, val pitch: Double, var done: Boolean = false)

    private fun sphereTargets(hfov: Double, vfov: Double): MutableList<Target> {
        val list = mutableListOf<Target>()
        val stepP = Math.toDegrees(vfov) * 0.7
        val rings = mutableListOf(0.0)
        var p = stepP
        while (p < 75) { rings.add(p); rings.add(-p); p += stepP }
        for (pitch in rings) {
            val n = max(3, ceil(360.0 * cos(Math.toRadians(pitch)) / (Math.toDegrees(hfov) * 0.7)).toInt())
            for (i in 0 until n) list.add(Target(Math.toRadians(360.0 * i / n), Math.toRadians(pitch)))
        }
        list.add(Target(0.0, Math.toRadians(90.0)))
        list.add(Target(0.0, Math.toRadians(-90.0)))
        return list
    }

    private fun runSphere() {
        if (!motion.hasRotation) { toast("Tu teléfono no reporta orientación"); return }
        sphereRunning = true; sphereStop = false; updateShutterLabel()
        lifecycleScope.launch {
            val (hfov, vfov) = fovs()
            val targets = sphereTargets(hfov, vfov)
            val yaw0 = motion.yawPitch().first
            val st = SphereStitcher(4096)
            progress.visibility = View.VISIBLE; progress.isIndeterminate = false; progress.max = targets.size; progress.progress = 0
            try {
                while (!sphereStop && targets.any { !it.done }) {
                    val m = motion.snapshot()
                    val cx = -m[2].toDouble(); val cy = -m[5].toDouble(); val cz = -m[8].toDouble()
                    val vw = overlay.width.toDouble(); val vh = overlay.height.toDouble()
                    val fv = (vw / 2) / tan(hfov / 2)
                    val pts = ArrayList<Triple<Float, Float, Boolean>>()
                    var best: Target? = null; var bestAng = 9.0
                    for (t in targets) {
                        val y = yaw0 + t.yaw
                        val dx = cos(t.pitch) * sin(y); val dy = cos(t.pitch) * cos(y); val dz = sin(t.pitch)
                        val ex = m[0] * dx + m[3] * dy + m[6] * dz
                        val ey = m[1] * dx + m[4] * dy + m[7] * dz
                        val ez = m[2] * dx + m[5] * dy + m[8] * dz
                        if (ez < -0.05) {
                            val sx = vw / 2 + fv * ex / -ez; val sy = vh / 2 - fv * ey / -ez
                            if (sx > -50 && sx < vw + 50 && sy > -50 && sy < vh + 50) pts.add(Triple(sx.toFloat(), sy.toFloat(), t.done))
                        }
                        if (!t.done) {
                            val ang = acos((cx * dx + cy * dy + cz * dz).coerceIn(-1.0, 1.0))
                            if (ang < bestAng) { bestAng = ang; best = t }
                        }
                    }
                    overlay.sphereTargets = pts
                    val left = targets.count { !it.done }
                    center(if (pts.none { !it.third }) "Busca el punto amarillo\nfaltan $left" else "Faltan $left")
                    if (best != null && bestAng < Math.toRadians(3.0) && motion.steady) {
                        val rm = motion.snapshot()
                        val raw = shot()
                        flashScreen()
                        withContext(Dispatchers.Default) {
                            val b = CamUtil.scaleTo(raw, 1400)
                            st.add(b, rm, hfov)
                            b.recycle()
                        }
                        best.done = true
                        progress.progress = targets.count { it.done }
                    }
                    delay(40)
                }
                if (st.count < 3) { toast("Muy pocas tomas para una foto 360"); return@launch }
                center("Creando foto 360…")
                val res = withContext(Dispatchers.Default) { st.result() }
                finishAndSave(res, "360", CamUtil.gpanoXmp(res.width, res.height))
            } catch (t: Throwable) {
                toast("Error: ${t.message}")
            } finally {
                sphereRunning = false; overlay.sphereTargets = emptyList(); progress.visibility = View.GONE; progress.isIndeterminate = true
                center(null); updateShutterLabel()
            }
        }
    }

    // ------------------------------------------------------------------ varios

    private fun openLast() {
        val u = lastUri ?: run { toast("Aún no hay fotos"); return }
        try {
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(u, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: Throwable) { toast("No hay galería para abrirla") }
    }

    private fun toast(s: String) = runOnUiThread { Toast.makeText(this, s, Toast.LENGTH_SHORT).show() }

    @Suppress("unused")
    private fun px(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, resources.displayMetrics)
}
