package com.eugene.golftrace

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraConstrainedHighSpeedCaptureSession
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CameraMetadata
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.media.MediaCodecList
import android.media.MediaFormat
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Range
import android.util.Size
import android.view.Gravity
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 录制页：手动快门 / ISO、按机器实际能力列出的镜头与 分辨率×帧率、水平仪、
 * DTL / 正面 两个机位各自记住参数和一张参考画面。
 */
class CameraActivity : Activity(), SensorEventListener {

    // ---------- 可调参数 ----------
    private val shutters = longArrayOf(250, 500, 1000, 2000, 4000)   // 1/x 秒
    private val isoSteps = intArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800,
        1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)

    /** 一个可选的“镜头”：物理摄像头 + 在它上面的变焦倍率。label 是相对主摄的倍率。 */
    private class Lens(val id: String, val zoom: Float, val x: Float, val mm: Int, val kind: String) {
        val label: String get() = if (x >= 1f) "%.0fx".format(x).replace(".0", "") else "%.1fx".format(x)
        val detail: String get() = "$kind ${mm}mm"
    }

    private class Mode(val size: Size, val fps: Int, val highSpeed: Boolean) {
        val resLabel: String get() = if (size.width >= 3000) "4K" else "${min(size.width, size.height)}p"
    }

    private var lenses = listOf<Lens>()
    private var lensIdx = 0
    private var modes = listOf<Mode>()          // 当前镜头实际支持的 分辨率×帧率
    private var modeIdx = 0
    private var shutterIdx = 2
    private var iso = 800
    private var station = "DTL"
    private var ghostOn = true
    private var gridOn = true

    // ---------- Camera2 ----------
    private lateinit var cm: CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private val sessionGeneration = AtomicLong()
    private var chars: CameraCharacteristics? = null
    private var recorder: MediaRecorder? = null
    // OnePlus 15 上 MediaCodec 输入 Surface 会收满 HAL 缓冲但不产出编码帧。
    // 高速档改为在建会话前就 prepare MediaRecorder，并让它的 Surface 常驻高速会话。
    private var hsRecorder: MediaRecorder? = null
    private var hsWriting = false
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recStart = 0L
    private var lastUri: Uri? = null
    private var cameraResumed = false
    private var metering = 0          // >0：测光中，剩余帧数
    private var manualOk = true
    private var hsShutterPriority = false
    @Volatile private var actualExposureNs = 0L
    @Volatile private var actualIso = 0
    private var lastExposureUiMs = 0L
    private val bgThread = HandlerThread("cam").apply { start() }
    private val bg = Handler(bgThread.looper)
    private val ui = Handler(Looper.getMainLooper())

    // ---------- 视图 ----------
    private lateinit var texture: TextureView
    private lateinit var ghost: ImageViewCompat
    private lateinit var hud: HudView
    private lateinit var info: TextView
    private lateinit var recDot: View
    private lateinit var recTime: TextView
    private lateinit var zoomRow: LinearLayout
    private lateinit var shutterText: TextView
    private lateinit var isoText: TextView
    private lateinit var meterBtn: TextView
    private lateinit var stationBtn: TextView
    private lateinit var resBtn: TextView
    private lateinit var fpsBtn: TextView
    private lateinit var ghostBtn: TextView
    private lateinit var gridBtn: TextView
    private lateinit var playBtn: TextView

    // ---------- 水平仪 ----------
    private lateinit var sm: SensorManager
    private var roll = 0f
    private var pitch = 0f

    private val prefs by lazy { getSharedPreferences("camera", Context.MODE_PRIVATE) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        cm = getSystemService(CameraManager::class.java)
        sm = getSystemService(SensorManager::class.java)
        lenses = findLenses()
        station = prefs.getString("station", "DTL") ?: "DTL"
        val content = buildUi()
        content.setOnApplyWindowInsetsListener { v, ins ->
            val sb = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(v.paddingLeft, sb.top, v.paddingRight, sb.bottom); ins
        }
        setContentView(content)
        loadPreset(station)
        rebuildModes()
        refreshButtons()
    }

    override fun onResume() {
        super.onResume()
        cameraResumed = true
        (sm.getDefaultSensor(Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER))?.let {
            sm.registerListener(this, it, SensorManager.SENSOR_DELAY_UI)
        }
        if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO), 7); return
        }
        if (texture.isAvailable) openCamera()
    }

    override fun onPause() {
        super.onPause()
        cameraResumed = false
        sm.unregisterListener(this)
        if (recording()) stopRecording()
        closeCamera()
    }

    override fun onDestroy() { super.onDestroy(); bgThread.quitSafely() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (rc == 7 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (texture.isAvailable) openCamera() }
        else { toast("需要相机权限"); finish() }
    }

    // ======================= UI =======================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildUi(): View {
        val root = FrameLayout(this).apply { setBackgroundColor(Color.BLACK) }

        // ---------- 取景区：9:16，顶到状态栏下方 ----------
        val stage = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val aw = MeasureSpec.getSize(w)
                super.onMeasure(MeasureSpec.makeMeasureSpec(aw, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(aw * 16 / 9, MeasureSpec.EXACTLY))
            }
        }
        texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {
                    mode()?.let { applyPreviewTransform(it.size) }
                }
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        ghost = ImageViewCompat(this)
        hud = HudView(this)
        stage.addView(texture); stage.addView(ghost); stage.addView(hud)
        root.addView(stage, FrameLayout.LayoutParams(-1, -2, Gravity.TOP))

        // ---------- 左上角：分辨率 / 帧率 两个下拉 ----------
        val topLeft = LinearLayout(this).apply { setPadding(dp(12), dp(10), dp(12), 0) }
        resBtn = pill("1080p") { pickRes() }
        fpsBtn = pill("60 FPS") { pickFps() }
        topLeft.addView(resBtn); topLeft.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        topLeft.addView(fpsBtn)
        root.addView(topLeft, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.START))

        // ---------- 右上角：录制计时 / 网格 / 参考 ----------
        val topRight = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(10), dp(12), 0)
        }
        recDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(Color.RED) }
            visibility = View.INVISIBLE
        }
        recTime = TextView(this).apply { setTextColor(Color.WHITE); textSize = 13f }
        topRight.addView(recDot, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(5) })
        topRight.addView(recTime)
        topRight.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        gridBtn = pill("网格") { gridOn = !gridOn; hud.grid = gridOn; hud.invalidate(); refreshButtons() }
        ghostBtn = pill("参考") { ghostOn = !ghostOn; refreshGhost(); refreshButtons() }
        topRight.addView(gridBtn); topRight.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        topRight.addView(ghostBtn)
        root.addView(topRight, FrameLayout.LayoutParams(-2, -2, Gravity.TOP or Gravity.END))

        // ---------- 下半部分：倍率 → 曝光 → 操作条 ----------
        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_HORIZONTAL
        }
        zoomRow = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(10)) }
        bottom.addView(zoomRow)

        info = TextView(this).apply {
            setTextColor(0xFF8E8E93.toInt()); textSize = 11f; setLines(3)
            gravity = Gravity.CENTER; setPadding(dp(10), 0, dp(10), dp(6))
        }

        // 曝光：两个下拉 + 测光（Samsung 专业模式那一条）
        val expo = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(8)) }
        shutterText = pill("1/1000", "快门") { pickShutter() }
        isoText = pill("800", "ISO") { pickIso() }
        meterBtn = pill("测光") { meter() }
        expo.addView(shutterText); expo.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        expo.addView(isoText); expo.addView(View(this), LinearLayout.LayoutParams(dp(10), 1))
        expo.addView(meterBtn)
        bottom.addView(expo)
        bottom.addView(info)

        // 操作条：回看 / 录制 / 机位
        val bar = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL; setPadding(dp(24), 0, dp(24), dp(18))
        }
        fun slot(v: View) = FrameLayout(this).apply {
            addView(v, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        }
        playBtn = pill("回看", null, round = true) { lastUri?.let { openPlayer(it) } ?: toast("先录一段") }
        bar.addView(slot(playBtn), LinearLayout.LayoutParams(0, -2, 1f))
        bar.addView(ShutterButton(this) { if (!recording()) startRecording() else stopRecording() }
            .also { shutterBtnView = it }, LinearLayout.LayoutParams(dp(72), dp(72)))
        stationBtn = pill("DTL", "机位", round = true) { switchStation() }
        stationBtn.setOnLongClickListener { saveReference(); true }
        bar.addView(slot(stationBtn), LinearLayout.LayoutParams(0, -2, 1f))
        bottom.addView(bar)
        root.addView(bottom, FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM))
        return root
    }

    /** 半透明胶囊按钮：主文字 + 可选小标签。 */
    private fun pill(text: String, tag: String? = null, round: Boolean = false, onClick: () -> Unit) =
        TextView(this).apply {
            setTextColor(Color.WHITE); gravity = Gravity.CENTER; textSize = 13f
            setPadding(dp(14), dp(7), dp(14), dp(7))
            background = GradientDrawable().apply { cornerRadius = dp(if (round) 26 else 18).toFloat(); setColor(BTN_OFF) }
            setOnClickListener { onClick() }
            setLabel(text, tag)
        }

    private fun TextView.setLabel(text: String, tag: String?) {
        if (tag == null) { this.text = text; return }
        val full = "$tag\n$text"
        val sp = android.text.SpannableString(full)
        sp.setSpan(android.text.style.RelativeSizeSpan(0.72f), 0, tag.length, 0)
        sp.setSpan(android.text.style.ForegroundColorSpan(0xFF9A9A9A.toInt()), 0, tag.length, 0)
        setLineSpacing(0f, 0.95f)
        this.text = sp
    }

    private var shutterBtnView: ShutterButton? = null

    private fun TextView.on(sel: Boolean) {
        (background as GradientDrawable).setColor(if (sel) C_YELLOW else BTN_OFF)
        setTextColor(if (sel) Color.BLACK else Color.WHITE)
    }

    /** 开关类按钮：开着是浅白底，关着更暗、文字灰。 */
    private fun TextView.dim(on: Boolean) {
        (background as GradientDrawable).setColor(if (on) 0x55FFFFFF else BTN_OFF)
        setTextColor(if (on) Color.WHITE else 0xFF9A9A9A.toInt())
    }

    private fun lens() = lenses.getOrNull(lensIdx)
    private fun mode() = modes.getOrNull(modeIdx)
    private fun recording() = recorder != null || hsWriting

    // ---------- 下拉菜单 ----------

    private fun menu(anchor: View, items: List<String>, checked: Int, onPick: (Int) -> Unit) {
        val pm = android.widget.PopupMenu(this, anchor)
        items.forEachIndexed { i, t -> pm.menu.add(0, i, i, if (i == checked) "✓  $t" else "     $t") }
        pm.setOnMenuItemClickListener { onPick(it.itemId); true }
        pm.show()
    }

    private fun pickRes() {
        if (recording()) return
        val res = modes.map { it.size }.distinct()
        menu(resBtn, res.map { if (it.width >= 3000) "4K" else "${min(it.width, it.height)}p" },
            res.indexOf(mode()?.size)) { i ->
            val want = res[i]
            val keep = modes.indexOfFirst { it.size == want && it.fps == mode()?.fps }
            modeIdx = if (keep >= 0) keep else modes.indexOfFirst { it.size == want }
            reopen()
        }
    }

    private fun pickFps() {
        if (recording()) return
        val sz = mode()?.size ?: return
        val list = modes.filter { it.size == sz }
        menu(fpsBtn, list.map {
            when {
                canSetManualExposure(it) -> "${it.fps} FPS（手动快门）"
                it.highSpeed && hsShutterPriority -> "${it.fps} FPS（快门优先）"
                it.highSpeed || !manualOk -> "${it.fps} FPS（自动曝光）"
                else -> "${it.fps} FPS（手动快门）"
            }
        },
            list.indexOfFirst { it.fps == mode()?.fps }) { i ->
            modeIdx = modes.indexOf(list[i]); reopen()
            if (list[i].highSpeed && !canSetManualExposure(list[i]) && !hsShutterPriority)
                toast("${list[i].fps} FPS 高速档无法锁快门；拍清杆头请选手动快门档")
        }
    }

    private fun pickShutter() {
        if (mode()?.highSpeed == true && !canSetManualExposure() && !hsShutterPriority) {
            toast("此高速档不支持快门优先；请选手动快门档"); return
        }
        if (mode()?.highSpeed != true && !manualOk) { toast("此镜头不支持手动曝光"); return }
        menu(shutterText, shutters.map { "1/$it" }, shutterIdx) { i ->
            shutterIdx = i; actualExposureNs = 0L; actualIso = 0
            applySettings(); refreshButtons()
        }
    }

    private fun pickIso() {
        if (mode()?.highSpeed == true && !canSetManualExposure()) { toast("此高速档使用自动 ISO；请选手动快门档"); return }
        if (!manualOk) { toast("此镜头不支持手动曝光"); return }
        menu(isoText, isoSteps.map { it.toString() }, isoSteps.indexOfFirst { it >= iso }) { i ->
            iso = isoSteps[i]; actualExposureNs = 0L; actualIso = 0
            applySettings(); refreshButtons()
        }
    }

    private fun refreshButtons() {
        val m = mode()
        resBtn.text = if (m == null) "—" else if (m.size.width >= 3000) "4K" else "${min(m.size.width, m.size.height)}p"
        fpsBtn.text = when {
            m == null -> "—"
            canSetManualExposure(m) -> "${m.fps} FPS 手动"
            m.highSpeed && hsShutterPriority -> "${m.fps} FPS 快门优先"
            m.highSpeed || !manualOk -> "${m.fps} FPS 自动"
            else -> "${m.fps} FPS 手动"
        }
        stationBtn.setLabel(station, "机位")
        val canSetIso = canSetManualExposure(m)
        val canSetShutter = canSetIso || (m?.highSpeed == true && hsShutterPriority)
        shutterText.setLabel(if (canSetShutter) "1/${shutters[shutterIdx]}" else "自动", "快门")
        isoText.setLabel(if (canSetIso) "$iso" else "自动", "ISO")
        shutterText.alpha = if (canSetShutter) 1f else 0.45f
        isoText.alpha = if (canSetIso) 1f else 0.45f
        meterBtn.alpha = if (canSetIso) 1f else 0.45f
        gridBtn.dim(gridOn); ghostBtn.dim(ghostOn)
        playBtn.alpha = if (lastUri == null) 0.45f else 1f
        shutterBtnView?.recording = recording()
        shutterBtnView?.invalidate()
        recDot.visibility = if (recording()) View.VISIBLE else View.INVISIBLE

        zoomRow.removeAllViews()
        lenses.forEachIndexed { i, l ->
            val b = pill(l.label) {
                if (!recording() && i != lensIdx) { lensIdx = i; rebuildModes(); reopen() }
            }
            if (i == lensIdx) b.on(true)
            b.setPadding(dp(13), dp(8), dp(13), dp(8))
            zoomRow.addView(b, LinearLayout.LayoutParams(-2, -2).apply { rightMargin = dp(8) })
        }
        updateInfo()
    }

    private fun bitrate(): Int {
        val m = mode() ?: return 10_000_000
        if (m.highSpeed) highSpeedProfile(m)?.let { return it.videoBitRate }
        val px = m.size.width.toLong() * m.size.height
        // 约 0.10 bit/像素/帧，HEVC 下画质够用又省空间
        return (px * m.fps * 0.10).toLong().coerceIn(6_000_000L, 60_000_000L).toInt()
    }

    private fun updateInfo() {
        val m = mode()
        val mbMin = bitrate() * 60f / 8f / 1e6f
        val codec = if (m?.highSpeed == true) "H.264" else if (hevcOk()) "HEVC" else "H.264"
        val expectedNs = 1_000_000_000L / shutters[shutterIdx]
        val canSetShutter = canSetManualExposure(m) || (m?.highSpeed == true && hsShutterPriority)
        val mismatch = canSetShutter && actualExposureNs > 0 &&
            abs(actualExposureNs - expectedNs) > expectedNs / 4
        val manual = when {
            mismatch -> "⚠ 实际快门与设定不符"
            canSetManualExposure(m) -> "手动曝光"
            m?.highSpeed == true && hsShutterPriority -> "高速快门优先 · ISO 自动"
            m?.highSpeed == true -> "高速档自动曝光，快门不能锁定"
            !manualOk -> "此镜头不支持手动曝光"
            else -> "手动曝光"
        }
        val actual = if (actualExposureNs > 0)
            "回报 1/%.0f%s".format(1e9 / actualExposureNs,
                if (actualIso > 0) " ISO $actualIso" else "")
            else "回报读取中"
        val sz = m?.let { "${it.size.height}×${it.size.width} " } ?: ""
        recTime.text = if (recording())
            "%.1fs".format((SystemClock.elapsedRealtime() - recStart) / 1000f) else ""
        info.text = "$sz$codec ${bitrate() / 1_000_000}Mbps ≈ %.0fMB/分钟\n$manual · $actual\n水平 %+.1f°  俯仰 %+.1f°%s"
            .format(mbMin, roll, pitch, refDelta())
    }

    private fun refDelta(): String {
        if (!prefs.contains("${station}_roll")) return "   （长按机位存参考）"
        val r0 = prefs.getFloat("${station}_roll", 0f); val p0 = prefs.getFloat("${station}_pitch", 0f)
        return "   与参考差 %+.1f° / %+.1f°".format(roll - r0, pitch - p0)
    }

    // ======================= 镜头 =======================

    /** 用等效焦距算出相对主摄的倍率：0.6x 超广角 / 1x 主摄 / 5x 长焦。 */
    private fun findLenses(): List<Lens> {
        class Phys(val id: String, val eq35: Float, val zr: Range<Float>?, val logical: Boolean)
        val phys = ArrayList<Phys>()
        for (id in cm.cameraIdList) {
            val c = try { cm.getCameraCharacteristics(id) } catch (_: Exception) { continue }
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: continue
            val s = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: continue
            // 等效焦距（按 35mm 画幅对角线 43.3mm 折算）
            val diag = sqrt((s.width * s.width + s.height * s.height).toDouble()).toFloat()
            val eq = if (diag > 0) f * 43.27f / diag else f
            val zr = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
            val logical = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in caps
            phys.add(Phys(id, eq, zr, logical))
        }
        if (phys.isEmpty()) return emptyList()
        // 主摄：等效焦距最接近 24mm 的那颗
        // PLK110 同时暴露逻辑主摄 0 和物理主摄 2；高速 CamcorderProfile 只绑定在物理 2。
        val main = phys.minWithOrNull(compareBy<Phys>({ abs(it.eq35 - 24f) }, { if (it.logical) 1 else 0 }))!!
        val out = ArrayList<Lens>()
        // 同画角去重时优先保留物理 ID，避免逻辑相机与厂商媒体 profile 对不上。
        for (p in phys.sortedBy { if (it.logical) 1 else 0 }) {
            val x = p.eq35 / main.eq35
            val kind = when {
                x < 0.8f -> "超广角"
                x < 1.6f -> "主摄"
                else -> "长焦"
            }
            // 这颗镜头的光学倍率
            out.add(Lens(p.id, 1f, x, p.eq35.roundToInt(), kind))
            // 逻辑多摄：主摄上如果能往下变焦（部分机器 0.6x 走同一个 id），补一档
            val zr = p.zr
            if (p.id == main.id && zr != null && zr.lower < 0.9f && phys.none { it.eq35 / main.eq35 < 0.9f })
                out.add(Lens(p.id, zr.lower, zr.lower, (p.eq35 * zr.lower).roundToInt(), "超广角"))
        }
        return out.distinctBy { "%.2f".format(it.x) }.sortedBy { it.x }
    }

    // ======================= 分辨率 × 帧率 =======================

    private fun canSetManualExposure(m: Mode? = mode()): Boolean {
        if (!manualOk || m == null) return false
        if (!m.highSpeed) return true
        // PLK110 Android 16 主摄：AE priority 仅回显设定，传感器仍按自动曝光运行。
        // 此组合的 AE OFF 已通过 120fps 编码视频 A/B 与厂商传感器曝光字段验证。
        // 其他设备/镜头仍走自身支持的路径，不能从这台手机推广高速手动能力。
        return Build.MODEL == "PLK110" && Build.VERSION.SDK_INT == 36 &&
            lens()?.id == "2" && m.fps == 120
    }

    /**
     * 本机 HAL 实测忽略 AE 快门优先：申请 1/4000（CaptureResult 回报 0.25ms）时，
     * 厂商 org.quic.camera2.properties_sensor.SensorActualExposureTime 仍是 5ms，
     * 1/500 与 1/4000 的成片亮度也几乎相同；所以本机不再把快门优先当可用功能，
     * 一律走已验证的 AE OFF 手动曝光，或如实标为自动曝光。
     */
    private fun priorityModeIgnoredOnThisDevice() =
        Build.MODEL == "PLK110" && Build.VERSION.SDK_INT >= 36

    /** Android 16 的快门优先由 AE 控制 ISO；SDK 35 构建时通过公开的 Key 构造器读取新键。 */
    private fun supportsShutterPriority(c: CameraCharacteristics): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        if (priorityModeIgnoredOnThisDevice()) return false
        return try {
            val modes = c.get(AE_PRIORITY_AVAILABLE_KEY) ?: return false
            AE_PRIORITY_EXPOSURE_TIME in modes &&
                c.availableCaptureRequestKeys.any { it.name == AE_PRIORITY_REQUEST_KEY.name }
        } catch (_: Exception) { false }
    }

    /** 普通会话优先提供手动 120fps；受限高速会话按能力提供快门优先或全自动。 */
    private fun rebuildModes() {
        val l = lens() ?: return
        val c = try { cm.getCameraCharacteristics(l.id) } catch (_: Exception) { return }
        hsShutterPriority = supportsShutterPriority(c)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return
        val recSizes = map.getOutputSizes(MediaRecorder::class.java) ?: emptyArray()
        val hsSizes = try { map.highSpeedVideoSizes?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        val normalFps = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.toList() ?: emptyList()
        val caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        val canManual = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val out = ArrayList<Mode>()
        for (want in listOf(Size(1920, 1080), Size(3840, 2160))) {
            val sz = recSizes.firstOrNull { it == want } ?: continue
            val minDur = map.getOutputMinFrameDuration(MediaRecorder::class.java, sz)
            val maxFps = if (minDur > 0) (1e9 / minDur).roundToInt() else 30
            for (f in intArrayOf(30, 60)) if (f <= maxFps + 1) out.add(Mode(sz, f, false))
            val manual120 = canManual && maxFps >= 119 && normalFps.any { it.lower == 120 && it.upper == 120 }
            if (manual120) out.add(Mode(sz, 120, false))
            // 高速档：这台机器的 240fps（1080p/720p）实测始终是假的（HAL 接受请求，传感器不切高速模式），
            // 已确认只有 120fps 是真实可用的，所以只提供 120，不再展示 240。
            if (hsSizes.contains(sz)) {
                val ranges = try { map.getHighSpeedVideoFpsRangesFor(sz).toList() } catch (_: Exception) { emptyList() }
                if (!manual120 && ranges.any { it.lower == 120 && it.upper == 120 }) {
                    out.add(Mode(sz, 120, true))
                }
            }
        }
        modes = out.sortedWith(compareBy({ it.size.width }, { it.fps }))
        val savedRes = prefs.getInt("${station}_w", 1920); val savedFps = prefs.getInt("${station}_fps", 60)
        modeIdx = modes.indexOfFirst { it.size.width == savedRes && it.fps == savedFps }
            .takeIf { it >= 0 } ?: modes.indexOfFirst { it.size.width == 1920 && it.fps == 60 }
            .takeIf { it >= 0 } ?: 0
    }

    // ======================= 预设 / 参考 =======================

    private fun loadPreset(name: String) {
        val lid = prefs.getString("${name}_lens", null)
        lensIdx = lenses.indexOfFirst { "${it.id}|${it.zoom}" == lid }.takeIf { it >= 0 }
            ?: lenses.indexOfFirst { it.x >= 0.95f }.coerceAtLeast(0)
        shutterIdx = prefs.getInt("${name}_shutter", 2).coerceIn(0, shutters.size - 1)
        iso = prefs.getInt("${name}_iso", 800)
    }

    private fun savePreset(name: String) {
        prefs.edit().putString("station", name)
            .putString("${name}_lens", lens()?.let { "${it.id}|${it.zoom}" })
            .putInt("${name}_shutter", shutterIdx).putInt("${name}_iso", iso)
            .putInt("${name}_fps", mode()?.fps ?: 60).putInt("${name}_w", mode()?.size?.width ?: 1920).apply()
    }

    private fun switchStation() {
        if (recording()) return
        savePreset(station)
        station = if (station == "DTL") "正面" else "DTL"
        prefs.edit().putString("station", station).apply()
        loadPreset(station)
        rebuildModes()
        refreshGhost()
        reopen()
    }

    private fun refFile() = File(filesDir, "ref_${if (station == "DTL") "dtl" else "fo"}.jpg")

    private fun saveReference() {
        val b = texture.getBitmap(texture.width / 2, texture.height / 2) ?: return
        refFile().outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        prefs.edit().putFloat("${station}_roll", roll).putFloat("${station}_pitch", pitch).apply()
        savePreset(station)
        refreshGhost()
        toast("已存 $station 参考：画面 + 角度 + 镜头/快门/ISO")
    }

    private fun refreshGhost() {
        val f = refFile()
        ghost.setImageBitmap(if (ghostOn && f.exists()) BitmapFactory.decodeFile(f.path) else null)
        updateInfo()
    }

    // ======================= Camera2 =======================

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val l = lens() ?: run { toast("没有后置摄像头"); return }
        if (device != null) return
        chars = cm.getCameraCharacteristics(l.id)
        hsShutterPriority = supportsShutterPriority(chars!!)
        if (modes.isEmpty()) rebuildModes()
        val caps = chars!!.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        manualOk = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        ui.post { refreshButtons() }
        try {
            cm.openCamera(l.id, object : CameraDevice.StateCallback() {
                override fun onOpened(d: CameraDevice) { device = d; startSession(null) }
                override fun onDisconnected(d: CameraDevice) { d.close(); device = null }
                override fun onError(d: CameraDevice, e: Int) { d.close(); device = null; ui.post { toast("相机错误 $e") } }
            }, bg)
        } catch (e: Exception) { toast("打开相机失败：${e.message}") }
    }


    private fun closeCamera() {
        sessionGeneration.incrementAndGet()
        try { session?.close() } catch (_: Exception) {}
        session = null
        actualExposureNs = 0L; actualIso = 0
        device?.close(); device = null
        try { hsRecorder?.release() } catch (_: Exception) {}
        hsRecorder = null; hsWriting = false
        // 高速档打开时会预先创建 pending 文件；未按录制就离开时不能留下空文件。
        if (recUri != null && recorder == null) discardPendingRecording()
    }

    private fun reopen() { closeCamera(); refreshButtons(); if (cameraResumed && texture.isAvailable) openCamera() }

    /** SurfaceTexture 已处理传感器方向；这里只补偿屏幕旋转并保持居中裁切。 */
    private fun applyPreviewTransform(sz: Size) {
        val w = texture.width.toFloat(); val h = texture.height.toFloat()
        if (w <= 0 || h <= 0) return
        val cx = w / 2; val cy = h / 2
        val vr = android.graphics.RectF(0f, 0f, w, h)
        val m = android.graphics.Matrix()
        when (texture.display?.rotation ?: Surface.ROTATION_0) {
            Surface.ROTATION_90, Surface.ROTATION_270 -> {
                val br = android.graphics.RectF(0f, 0f, sz.height.toFloat(), sz.width.toFloat())
                br.offset(cx - br.centerX(), cy - br.centerY())
                m.setRectToRect(vr, br, android.graphics.Matrix.ScaleToFit.FILL)
                val scale = max(h / sz.height.toFloat(), w / sz.width.toFloat())
                m.postScale(scale, scale, cx, cy)
                val rotation = texture.display?.rotation ?: Surface.ROTATION_0
                m.postRotate(90f * (rotation - 2), cx, cy)
            }
            Surface.ROTATION_180 -> m.postRotate(180f, cx, cy)
        }
        texture.setTransform(m)
        // TextureView 每次布局会把缓冲尺寸改回视图大小，这里再钉回相机尺寸
        texture.surfaceTexture?.setDefaultBufferSize(sz.width, sz.height)
    }

    private var previewSurface: Surface? = null
    private var activeRecSurface: Surface? = null

    private fun startSession(recSurface: Surface?) {
        val d = device ?: return
        val m = mode() ?: return
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(m.size.width, m.size.height)
        ui.post { applyPreviewTransform(m.size) }
        previewSurface = Surface(st)
        if (m.highSpeed && hsRecorder == null && !prepareHighSpeedRecorder(m)) return
        if (!m.highSpeed) {
            try { hsRecorder?.release() } catch (_: Exception) {}
            hsRecorder = null
        }
        val targets = listOfNotNull(previewSurface, if (m.highSpeed) hsRecorder?.surface else recSurface)
        val generation = sessionGeneration.incrementAndGet()
        try { session?.close() } catch (_: Exception) {}
        val cb = object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                if (generation != sessionGeneration.get()) { s.close(); return }
                android.util.Log.i("GT", "session ok hs=${m.highSpeed} targets=${targets.size} fps=${m.fps}")
                session = s
                applySettings(recSurface)
                if (recSurface != null) ui.post {
                    try { recorder?.start(); recStart = SystemClock.elapsedRealtime(); tickRec() }
                    catch (e: Exception) {
                        android.util.Log.e("GT", "recorder.start failed", e)
                        toast("录制启动失败：${e.message}"); stopRecording()
                    }
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) {
                if (generation != sessionGeneration.get()) { s.close(); return }
                android.util.Log.e("GT", "configure failed hs=${m.highSpeed} ${m.size} ${m.fps} targets=${targets.size}")
                ui.post {
                    if (m.highSpeed) {
                        if (recording()) stopRecording()
                        val back = modes.indexOfLast { !it.highSpeed && it.size == m.size }
                        if (back >= 0) modeIdx = back
                        toast("这台机器的系统接口不支持 ${m.fps}fps 录制，已退回 ${mode()?.fps ?: 60}fps")
                        reopen()
                    } else if (m.fps == 120) {
                        if (recSurface != null) {
                            try { recorder?.release() } catch (_: Exception) {}
                            recorder = null; activeRecSurface = null
                            discardPendingRecording()
                        }
                        modeIdx = modes.indexOfLast { !it.highSpeed && it.size == m.size && it.fps == 60 }
                            .takeIf { it >= 0 } ?: modeIdx
                        toast("普通 120 FPS 会话未启动；已切到 ${mode()?.fps} FPS 手动快门")
                        reopen()
                    } else toast("这个规格配不上（换分辨率或帧率）")
                }
            }
        }
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                val cfgs = targets.map { android.hardware.camera2.params.OutputConfiguration(it) }
                val type = if (m.highSpeed) android.hardware.camera2.params.SessionConfiguration.SESSION_HIGH_SPEED
                    else android.hardware.camera2.params.SessionConfiguration.SESSION_REGULAR
                val config = android.hardware.camera2.params.SessionConfiguration(
                    type, cfgs, { r -> bg.post(r) }, cb)
                if (m.fps >= 60) {
                    // FPS 是 session 参数，创建会话时就带上，避免预览模板先锁到 30fps。
                    val params = d.createCaptureRequest(CameraDevice.TEMPLATE_RECORD)
                    params.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(m.fps, m.fps))
                    params.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                        CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
                    config.setSessionParameters(params.build())
                }
                d.createCaptureSession(config)
            } else {
                @Suppress("DEPRECATION")
                if (m.highSpeed) d.createConstrainedHighSpeedCaptureSession(targets, cb, bg)
                else d.createCaptureSession(targets, cb, bg)
            }
        } catch (e: Exception) {
            android.util.Log.e("GT", "session failed", e)
            ui.post { toast("会话失败：${e.message}") }
        }
    }

    private fun applySettings(recSurface: Surface? = activeRecSurface) {
        val d = device ?: return; val s = session ?: return
        val m = mode() ?: return
        // 高速档全程用 RECORD 模板：PREVIEW 模板下 HAL 不会往视频面送帧
        val b = d.createCaptureRequest(
            if (recSurface != null || m.highSpeed) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { b.addTarget(it) }
        // 高速 Surface 虽然从建会话起就存在，但只有 MediaRecorder.start() 成功后才向它送帧。
        if (m.highSpeed && hsWriting) hsRecorder?.surface?.let { b.addTarget(it) }
        else if (!m.highSpeed) recSurface?.let { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(m.fps, m.fps))
        if (Build.VERSION.SDK_INT >= 30) lens()?.let { if (it.zoom != 1f) b.set(CaptureRequest.CONTROL_ZOOM_RATIO, it.zoom) }
        // 优先使用经过验证的手动路径；其余高速模式按设备支持提供快门优先。
        if (canSetManualExposure(m) && metering == 0) {
            val c = chars!!
            val er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            var exp = 1_000_000_000L / shutters[shutterIdx]
            if (er != null) exp = exp.coerceIn(er.lower, er.upper)
            val isoC = if (ir != null) iso.coerceIn(ir.lower, ir.upper) else iso
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoC)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / m.fps)
        } else if (m.highSpeed && hsShutterPriority && metering == 0) {
            var exp = 1_000_000_000L / shutters[shutterIdx]
            chars?.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)?.let {
                exp = exp.coerceIn(it.lower, it.upper)
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(AE_PRIORITY_REQUEST_KEY, AE_PRIORITY_EXPOSURE_TIME)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        // 高速会话的回调可能传入内部 CaptureSession，而非 onConfigured 的包装对象。
        val generation = sessionGeneration.get()
        val cb = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                if (generation != sessionGeneration.get()) return
                if (metering > 0) onMeterFrame(res)
                val exp = res.get(CaptureResult.SENSOR_EXPOSURE_TIME)
                val gain = res.get(CaptureResult.SENSOR_SENSITIVITY)
                val now = SystemClock.elapsedRealtime()
                if (exp != null || gain != null) {
                    if (exp != null) actualExposureNs = exp
                    if (gain != null) actualIso = gain
                    if (now - lastExposureUiMs >= 500) {
                        lastExposureUiMs = now
                        if (BuildConfig.DEBUG && File(filesDir, "exposure-probe.flag").exists()) {
                            val keys = res.keys.filter { k ->
                                val name = k.name.lowercase()
                                name.contains("exposure") || name.contains("sensitivity") ||
                                    name.contains("gain") || name.contains("aepriority") ||
                                    name.contains("aemode") || name.contains("frameduration")
                            }
                            android.util.Log.i("GTExposure", "fps=${m.fps} hs=${m.highSpeed} " +
                                "recording=$hsWriting requestExp=${r.get(CaptureRequest.SENSOR_EXPOSURE_TIME)} " +
                                "requestAE=${r.get(CaptureRequest.CONTROL_AE_MODE)} " +
                                "requestPriority=${if (Build.VERSION.SDK_INT >= 36) r.get(AE_PRIORITY_REQUEST_KEY) else null} " +
                                keys.joinToString { k ->
                                    val value = res.get(k)
                                    val text = when (value) {
                                        is FloatArray -> value.contentToString()
                                        is LongArray -> value.contentToString()
                                        is IntArray -> value.contentToString()
                                        is ByteArray -> value.take(32).joinToString("") { "%02x".format(it.toInt() and 255) }
                                        else -> value.toString()
                                    }
                                    "${k.name}=$text"
                                })
                        }
                        ui.post { if (generation == sessionGeneration.get()) updateInfo() }
                    }
                }
            }
        }
        try {
            if (m.highSpeed && s is CameraConstrainedHighSpeedCaptureSession) {
                val list = s.createHighSpeedRequestList(b.build())
                android.util.Log.i("GT", "hs burst size=${list.size} recorder=${hsRecorder != null}")
                s.setRepeatingBurst(list, cb, bg)
            } else s.setRepeatingRequest(b.build(), cb, bg)
        } catch (e: Exception) {
            if (m.highSpeed && hsShutterPriority) {
                android.util.Log.w("GT", "high-speed shutter priority rejected", e)
                hsShutterPriority = false
                ui.post {
                    refreshButtons()
                    toast("此高速会话拒绝快门优先；已恢复自动曝光")
                }
                applySettings(recSurface)
            } else ui.post { toast("设置失败：${e.message}") }
        }
    }

    // 测光：临时开自动曝光，读它给的 曝光时间×ISO，换算到当前快门下的 ISO 后锁定
    private fun meter() {
        if (!manualOk) { toast("此镜头不支持手动曝光"); return }
        if (!canSetManualExposure()) { toast("此档使用自动曝光"); return }
        metering = 25; applySettings(); toast("测光中…保持画面不动")
    }

    private fun onMeterFrame(r: TotalCaptureResult) {
        metering--
        if (metering > 0) return
        val e = r.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
        val i = r.get(CaptureResult.SENSOR_SENSITIVITY) ?: return
        val target = e.toDouble() * i / (1e9 / shutters[shutterIdx])
        val ir = chars?.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
        val snapped = isoSteps.minByOrNull { abs(Math.log(it / target)) } ?: 800
        iso = if (ir != null) snapped.coerceIn(ir.lower, ir.upper) else snapped
        ui.post {
            actualExposureNs = 0L; actualIso = 0
            applySettings(); refreshButtons()
            toast("自动曝光 1/%.0f ISO %d → 1/%d 需要 ISO %d".format(1e9 / e, i, shutters[shutterIdx], iso))
        }
    }

    private fun stepIso(d: Int) {
        val k = isoSteps.indexOfFirst { it >= iso }.let { if (it < 0) isoSteps.size - 1 else it }
        iso = isoSteps[(k + d).coerceIn(0, isoSteps.size - 1)]
        applySettings(); refreshButtons()
    }

    // ======================= 录制 =======================

    private fun getHighSpeedProfile(cameraId: String, size: Size): android.media.CamcorderProfile? {
        val id = cameraId.toIntOrNull() ?: return null
        val q = when {
            size.width >= 3000 -> android.media.CamcorderProfile.QUALITY_HIGH_SPEED_2160P
            size.width >= 1900 -> android.media.CamcorderProfile.QUALITY_HIGH_SPEED_1080P
            size.width >= 1200 -> android.media.CamcorderProfile.QUALITY_HIGH_SPEED_720P
            else -> android.media.CamcorderProfile.QUALITY_HIGH_SPEED_480P
        }
        return try {
            if (android.media.CamcorderProfile.hasProfile(id, q)) android.media.CamcorderProfile.get(id, q) else null
        } catch (_: Exception) { null }
    }

    /** 厂商给的高速录制档，拿不到就返回 null（这台机器只有 1080p 有；4K 高速档没有厂商 profile，走通用兜底参数）。 */
    private fun highSpeedProfile(m: Mode) = lens()?.let { getHighSpeedProfile(it.id, m.size) }

    private fun hevcOk() = encoderOk(MediaFormat.MIMETYPE_VIDEO_HEVC, mode())

    /** 这台机器的编码器能不能吃下这个 尺寸×帧率。 */
    private fun encoderOk(mime: String, m: Mode?): Boolean {
        val sz = m?.size ?: Size(1920, 1080); val fps = m?.fps ?: 30
        return MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
            ci.isEncoder && ci.supportedTypes.any { it.equals(mime, true) } && try {
                ci.getCapabilitiesForType(mime).videoCapabilities
                    .areSizeAndRateSupported(sz.width, sz.height, fps.toDouble())
            } catch (_: Exception) { false }
        }
    }

    private fun createPendingVideo(): Pair<Uri, ParcelFileDescriptor> {
        val name = "swing_${station}_${System.currentTimeMillis()}.mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/杆头轨迹")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)
            ?: throw IllegalStateException("不能创建视频文件")
        return try {
            uri to (contentResolver.openFileDescriptor(uri, "w")
                ?: throw IllegalStateException("不能打开视频文件"))
        } catch (e: Exception) {
            try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    private fun discardPendingRecording() {
        val uri = recUri
        recUri = null
        try { recPfd?.close() } catch (_: Exception) {}
        recPfd = null
        if (uri != null) try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
    }

    /**
     * 高速会话创建前先把 MediaRecorder 准备好。OnePlus 15 不能在高速预览运行后
     * 再换成第二个 Surface；Surface 必须从第一帧起就在同一个高速会话中。
     */
    private fun prepareHighSpeedRecorder(m: Mode): Boolean {
        var r: MediaRecorder? = null
        var uri: Uri? = null
        var pfd: ParcelFileDescriptor? = null
        return try {
            val target = createPendingVideo()
            uri = target.first; pfd = target.second
            r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this)
                else @Suppress("DEPRECATION") MediaRecorder()
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            r.setOutputFile(pfd.fileDescriptor)
            val profile = highSpeedProfile(m)
            if (profile != null) {
                android.util.Log.i("GT", "hs profile codec=${profile.videoCodec} " +
                    "${profile.videoFrameWidth}x${profile.videoFrameHeight}@${profile.videoFrameRate} " +
                    "bitrate=${profile.videoBitRate}")
                // OPlus 的高速管线依赖厂商 CamcorderProfile 中的编码器配置。
                r.setVideoEncoder(profile.videoCodec)
                r.setVideoEncodingBitRate(profile.videoBitRate)
                // 交给 OPlus 的高速 profile 原样配置。额外 setCaptureRate/profileLevel 会让
                // 720p240 落回 30fps，尽管相机会话仍显示 240fps。
                r.setVideoFrameRate(profile.videoFrameRate)
                r.setVideoSize(profile.videoFrameWidth, profile.videoFrameHeight)
            } else {
                r.setVideoEncoder(MediaRecorder.VideoEncoder.H264)
                // PLK110 的 media_profiles 把高速 profile 错挂在其他 cameraId；按其标准值兜底。
                r.setVideoEncodingBitRate(50_000_000)
                r.setVideoFrameRate(m.fps)
                r.setVideoSize(m.size.width, m.size.height)
                // 只有没有厂商高速 profile 时才使用通用 H.264 兜底参数。
                r.setCaptureRate(m.fps.toDouble())
            }
            r.setOrientationHint(chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
            r.prepare()
            recUri = uri; recPfd = pfd; hsRecorder = r
            android.util.Log.i("GT", "hs MediaRecorder armed ${m.size.width}x${m.size.height}@${m.fps}")
            true
        } catch (e: Exception) {
            android.util.Log.e("GT", "arm hs MediaRecorder failed", e)
            try { r?.release() } catch (_: Exception) {}
            try { pfd?.close() } catch (_: Exception) {}
            if (uri != null) try { contentResolver.delete(uri, null, null) } catch (_: Exception) {}
            recUri = null; recPfd = null; hsRecorder = null
            ui.post { toast("高速录制器准备失败：${e.message}") }
            false
        }
    }

    private fun startRecording() {
        if (device == null) return
        val m = mode() ?: return
        val orient = chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
        try {
            if (m.highSpeed) {
                // Surface 已在会话配置中，但待机请求没有向它送帧；先启动消费者，再切双目标请求。
                val hs = hsRecorder ?: throw IllegalStateException("高速录制器没准备好")
                hs.start()
                hsWriting = true
                applySettings()
                recStart = SystemClock.elapsedRealtime(); tickRec()
                android.util.Log.i("GT", "hs MediaRecorder started ${m.fps}fps")
            } else {
                val target = createPendingVideo()
                recUri = target.first; recPfd = target.second
                val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
                // 收音：击球声用来自动找挥杆（本 App 录的音画同步）
                val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
                if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
                r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
                r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                if (audio) { r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioEncodingBitRate(96_000); r.setAudioSamplingRate(48_000) }
                r.setOutputFile(target.second.fileDescriptor)
                r.setVideoEncoder(if (encoderOk(MediaFormat.MIMETYPE_VIDEO_HEVC, m))
                    MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
                r.setVideoEncodingBitRate(bitrate())
                r.setVideoFrameRate(m.fps)
                r.setVideoSize(m.size.width, m.size.height)
                // 竖构图：按传感器方向写旋转标记（手机竖着拿）
                r.setOrientationHint(orient)
                r.prepare()
                recorder = r
                activeRecSurface = r.surface
                startSession(r.surface)
            }
            refreshButtons()
        } catch (e: Exception) {
            android.util.Log.e("GT", "startRecording failed", e)
            toast("录制失败：${e.message}")
            recorder?.release(); recorder = null
            hsWriting = false
            if (m.highSpeed) reopen() else discardPendingRecording()
        }
    }

    private fun stopRecording() {
        val r = recorder
        if (r == null && !hsWriting) return
        var saved = true
        if (r != null) {
            try { session?.stopRepeating() } catch (_: Exception) {}
            try { r.stop() } catch (_: Exception) { saved = false; toast("录制太短或失败") }
            r.release()
            recorder = null; activeRecSurface = null
        }
        if (hsWriting) {
            // 先停止向编码 Surface 投递，再结束封装，避免留下未消费缓冲。
            hsWriting = false
            try { applySettings() } catch (_: Exception) {}
            try { hsRecorder?.stop() } catch (e: Exception) {
                saved = false
                android.util.Log.e("GT", "hs MediaRecorder.stop failed", e)
            }
        }
        if (!saved) {
            discardPendingRecording()
            toast("这一段没录到画面")
        } else {
            recPfd?.close(); recPfd = null
            recUri?.let {
                contentResolver.update(it, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
                lastUri = it
                toast("已存到 Movies/杆头轨迹")
            }
            recUri = null
        }
        // MediaRecorder stop 后 Surface 不能复用；完整重开相机，为下一段预先准备新 Surface。
        if (r != null) startSession(null) else reopen()
        refreshButtons()
    }

    private fun tickRec() {
        if (!recording()) return
        updateInfo()
        ui.postDelayed({ tickRec() }, 250)
    }

    private fun openPlayer(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    // ======================= 水平仪 =======================

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]; val gz = e.values[2]
        // 竖持：重力沿 +y；roll = 左右歪，pitch = 前后仰
        val r = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        val p = Math.toDegrees(atan2(gz.toDouble(), sqrt((gx * gx + gy * gy).toDouble()))).toFloat()
        roll = roll * 0.8f + r * 0.2f; pitch = pitch * 0.8f + p * 0.2f
        hud.roll = roll; hud.invalidate()
        if (!recording()) updateInfo()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 圆形录制键：白圈 + 红心，录制时变红色方块。 */
    private class ShutterButton(ctx: Context, onTap: () -> Unit) : View(ctx) {
        var recording = false
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        init { setOnClickListener { onTap() } }
        override fun onDraw(c: Canvas) {
            val cx = width / 2f; val cy = height / 2f; val r = min(cx, cy) - 3f
            p.style = Paint.Style.STROKE; p.strokeWidth = 4f; p.color = Color.WHITE
            c.drawCircle(cx, cy, r, p)
            p.style = Paint.Style.FILL; p.color = 0xFFFF3B30.toInt()
            if (recording) {
                val s = r * 0.52f
                c.drawRoundRect(cx - s, cy - s, cx + s, cy + s, 8f, 8f, p)
            } else c.drawCircle(cx, cy, r * 0.78f, p)
        }
    }

    /** 参考画面叠加层（半透明）。 */
    private class ImageViewCompat(ctx: Context) : android.widget.ImageView(ctx) {
        init { scaleType = ScaleType.FIT_XY; alpha = 0.38f }
    }

    /** 取景框叠加：三分线 + 中线 + 水平线（|歪| < 0.5° 变绿）。 */
    private class HudView(ctx: Context) : View(ctx) {
        var roll = 0f
        var grid = true
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.style = Paint.Style.STROKE
            if (grid) {
                p.strokeWidth = 1f; p.color = 0x55FFFFFF
                for (k in 1..2) { c.drawLine(w * k / 3, 0f, w * k / 3, h, p); c.drawLine(0f, h * k / 3, w, h * k / 3, p) }
                p.color = 0x88FFEB3B.toInt(); c.drawLine(w / 2, 0f, w / 2, h, p)
            }
            val ok = abs(roll) < 0.5f
            p.color = if (ok) 0xFF00E676.toInt() else 0xFFFF5252.toInt(); p.strokeWidth = 3f
            c.save(); c.rotate(-roll, w / 2, h / 2)
            c.drawLine(w * 0.2f, h / 2, w * 0.8f, h / 2, p)
            c.restore()
            p.style = Paint.Style.FILL; p.textSize = 34f
            c.drawText("%+.1f°".format(roll), w / 2 + 10, h / 2 - 12, p)
        }
    }

    companion object {
        private const val BTN_OFF = 0xCC2A2A2A.toInt()
        // API 36: CameraMetadata.CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY
        private const val AE_PRIORITY_EXPOSURE_TIME = 2
        private val AE_PRIORITY_AVAILABLE_KEY = CameraCharacteristics.Key(
            "android.control.aeAvailablePriorityModes", IntArray::class.java)
        private val AE_PRIORITY_REQUEST_KEY = CaptureRequest.Key(
            "android.control.aePriorityMode", Int::class.javaObjectType)
    }
}
