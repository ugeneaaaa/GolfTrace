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
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import java.io.File
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 录制：手动快门（ISO 测光一次后锁定）、竖构图、换镜头/变焦、HEVC 低码率，
 * 水平仪 + 两个机位预设（DTL / 正面）各自记住参数和一张参考画面，下次半透明叠上去对齐。
 */
class CameraActivity : Activity(), SensorEventListener {

    // ---------- 可调参数 ----------
    private val shutters = longArrayOf(250, 500, 1000, 2000, 4000)   // 1/x 秒
    private val isoSteps = intArrayOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)

    private class Lens(val id: String, val label: String, val zoom: Float)

    private var lenses = listOf<Lens>()
    private var lensIdx = 0
    private var shutterIdx = 2
    private var iso = 800
    private var fps = 60
    private var res4k = false
    private var station = "DTL"
    private var ghostOn = true

    // ---------- Camera2 ----------
    private lateinit var cm: CameraManager
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var chars: CameraCharacteristics? = null
    private var recorder: MediaRecorder? = null
    private var recUri: Uri? = null
    private var recPfd: ParcelFileDescriptor? = null
    private var recStart = 0L
    private var lastUri: Uri? = null
    private var metering = 0          // >0：测光中，剩余帧数
    private var manualOk = true
    private val bgThread = HandlerThread("cam").apply { start() }
    private val bg = Handler(bgThread.looper)
    private val ui = Handler(Looper.getMainLooper())

    // ---------- 视图 ----------
    private lateinit var texture: TextureView
    private lateinit var ghost: ImageView
    private lateinit var hud: HudView
    private lateinit var info: TextView
    private lateinit var recBtn: Button
    private lateinit var stationBtn: Button
    private lateinit var lensBtn: Button
    private lateinit var shutterBtn: Button
    private lateinit var isoBtn: Button
    private lateinit var fpsBtn: Button
    private lateinit var resBtn: Button
    private lateinit var ghostBtn: Button
    private lateinit var analyzeBtn: Button

    // ---------- 水平仪 ----------
    private lateinit var sm: SensorManager
    private var roll = 0f
    private var pitch = 0f

    private val prefs by lazy { getSharedPreferences("camera", Context.MODE_PRIVATE) }

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cm = getSystemService(CameraManager::class.java)
        sm = getSystemService(SensorManager::class.java)
        lenses = findLenses()
        station = prefs.getString("station", "DTL") ?: "DTL"
        loadPreset(station)
        setContentView(buildUi())
        refreshButtons()
    }

    override fun onResume() {
        super.onResume()
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
        sm.unregisterListener(this)
        if (recorder != null) stopRecording()
        closeCamera()
    }

    override fun onDestroy() { super.onDestroy(); bgThread.quitSafely() }

    override fun onRequestPermissionsResult(rc: Int, p: Array<out String>, g: IntArray) {
        if (rc == 7 && g.firstOrNull() == PackageManager.PERMISSION_GRANTED) { if (texture.isAvailable) openCamera() }
        else { toast("需要相机权限"); finish() }
    }

    // ======================= UI =======================

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View) = HorizontalScrollView(this).apply {
        isHorizontalScrollBarEnabled = false
        addView(LinearLayout(this@CameraActivity).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            v.forEach { addView(it) }
        })
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            setPadding(dp(4), dp(28), dp(4), dp(24))
        }
        // 9:16 预览
        val stage = object : FrameLayout(this) {
            override fun onMeasure(w: Int, h: Int) {
                val aw = MeasureSpec.getSize(w); val ah = MeasureSpec.getSize(h)
                var pw = aw; var ph = aw * 16 / 9
                if (ph > ah) { ph = ah; pw = ah * 9 / 16 }
                super.onMeasure(MeasureSpec.makeMeasureSpec(pw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ph, MeasureSpec.EXACTLY))
            }
        }
        texture = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) openCamera()
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) {}
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
            }
        }
        ghost = ImageView(this).apply { scaleType = ImageView.ScaleType.FIT_XY; alpha = 0.38f }
        hud = HudView(this)
        stage.addView(texture); stage.addView(ghost); stage.addView(hud)
        val stageWrap = FrameLayout(this).apply { addView(stage, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)) }
        root.addView(stageWrap, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        info = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; setLines(2) }
        root.addView(info)

        stationBtn = btn("") { switchStation() }
        ghostBtn = btn("") { ghostOn = !ghostOn; refreshGhost(); refreshButtons() }
        root.addView(row(stationBtn, btn("存为此机位参考") { saveReference() }, ghostBtn))

        shutterBtn = btn("") { shutterIdx = (shutterIdx + 1) % shutters.size; applySettings(); refreshButtons() }
        isoBtn = btn("") {}
        root.addView(row(
            btn("快门−") { shutterIdx = max(0, shutterIdx - 1); applySettings(); refreshButtons() }, shutterBtn,
            btn("快门+") { shutterIdx = min(shutters.size - 1, shutterIdx + 1); applySettings(); refreshButtons() },
            btn("ISO−") { stepIso(-1) }, isoBtn, btn("ISO+") { stepIso(1) },
            btn("测光") { meter() }))

        lensBtn = btn("") { if (recorder == null && lenses.isNotEmpty()) { lensIdx = (lensIdx + 1) % lenses.size; reopen() } }
        fpsBtn = btn("") { if (recorder == null) { fps = if (fps == 60) 30 else 60; reopen() } }
        resBtn = btn("") { if (recorder == null) { res4k = !res4k; reopen() } }
        root.addView(row(lensBtn, fpsBtn, resBtn))

        recBtn = btn("● 录制") { if (recorder == null) startRecording() else stopRecording() }.apply { textSize = 16f }
        analyzeBtn = btn("▶ 回看刚录的") { lastUri?.let { openPlayer(it) } }.apply { isEnabled = false }
        root.addView(row(recBtn, analyzeBtn, btn("返回") { finish() }))
        return root
    }

    private fun lens() = lenses.getOrNull(lensIdx)

    private fun refreshButtons() {
        stationBtn.text = "机位：$station"
        ghostBtn.text = if (ghostOn) "参考叠加 ✓" else "参考叠加"
        shutterBtn.text = "1/${shutters[shutterIdx]}"
        isoBtn.text = "ISO $iso"
        lensBtn.text = "镜头：${lens()?.label ?: "-"}"
        fpsBtn.text = "${fps}fps"
        resBtn.text = if (res4k) "4K" else "1080p"
        recBtn.text = if (recorder == null) "● 录制" else "■ 停止"
        recBtn.setTextColor(if (recorder == null) Color.RED else Color.BLACK)
        updateInfo()
    }

    private fun bitrate(): Int = when {
        res4k && fps >= 60 -> 30_000_000
        res4k -> 20_000_000
        fps >= 60 -> 10_000_000
        else -> 6_000_000
    }

    private fun updateInfo() {
        val mbMin = bitrate() * 60f / 8f / 1e6f
        val rec = if (recorder != null) "  录制中 %.1fs".format((SystemClock.elapsedRealtime() - recStart) / 1000f) else ""
        val codec = if (hevcOk()) "HEVC" else "H.264"
        val manual = if (manualOk) "" else "  ⚠ 此镜头不支持手动曝光"
        val sz = if (chars != null) videoSize().let { "${it.height}x${it.width} " } else ""
        info.text = "$sz$codec ${bitrate() / 1_000_000}Mbps ≈ %.0fMB/分钟$rec$manual\n水平 %+.1f°  俯仰 %+.1f°%s".format(mbMin, roll, pitch, refDelta())
    }

    private fun refDelta(): String {
        if (!prefs.contains("${station}_roll")) return "  （此机位还没存参考）"
        val r0 = prefs.getFloat("${station}_roll", 0f); val p0 = prefs.getFloat("${station}_pitch", 0f)
        return "   与参考差 %+.1f° / %+.1f°".format(roll - r0, pitch - p0)
    }

    // ======================= 镜头 =======================

    private fun findLenses(): List<Lens> {
        val out = ArrayList<Lens>()
        for (id in cm.cameraIdList) {
            val c = cm.getCameraCharacteristics(id)
            if (c.get(CameraCharacteristics.LENS_FACING) != CameraCharacteristics.LENS_FACING_BACK) continue
            val f = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull() ?: 0f
            val zr = if (Build.VERSION.SDK_INT >= 30) c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE) else null
            // 逻辑多摄：用变焦倍率切到超广角/长焦
            val zooms = ArrayList<Float>()
            if (zr != null && zr.lower < 0.95f) zooms.add(zr.lower)
            zooms.add(1f)
            if (zr != null && zr.upper >= 2f) zooms.add(2f)
            for (z in zooms) out.add(Lens(id, "#$id %.1fmm %sx".format(f, if (z == 1f) "1" else "%.1f".format(z)), z))
        }
        return out
    }

    // ======================= 预设 / 参考 =======================

    private fun loadPreset(name: String) {
        val lid = prefs.getString("${name}_lens", null)
        lensIdx = lenses.indexOfFirst { "${it.id}|${it.zoom}" == lid }.takeIf { it >= 0 } ?: lenses.indexOfFirst { it.zoom == 1f }.coerceAtLeast(0)
        shutterIdx = prefs.getInt("${name}_shutter", 2).coerceIn(0, shutters.size - 1)
        iso = prefs.getInt("${name}_iso", 800)
        fps = prefs.getInt("${name}_fps", 60)
        res4k = prefs.getBoolean("${name}_4k", false)
    }

    private fun savePreset(name: String) {
        prefs.edit().putString("station", name)
            .putString("${name}_lens", lens()?.let { "${it.id}|${it.zoom}" })
            .putInt("${name}_shutter", shutterIdx).putInt("${name}_iso", iso)
            .putInt("${name}_fps", fps).putBoolean("${name}_4k", res4k).apply()
    }

    private fun switchStation() {
        if (recorder != null) return
        savePreset(station)
        station = if (station == "DTL") "正面" else "DTL"
        prefs.edit().putString("station", station).apply()
        loadPreset(station)
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

    private fun videoSize(): Size {
        val c = chars ?: return Size(1920, 1080)
        val map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)!!
        val sizes = map.getOutputSizes(MediaRecorder::class.java)
        val want = if (res4k) Size(3840, 2160) else Size(1920, 1080)
        return sizes.firstOrNull { it == want } ?: sizes.filter { it.width * 9 == it.height * 16 && it.width <= want.width }.maxByOrNull { it.width } ?: sizes[0]
    }

    private fun maxFps(size: Size): Int {
        val map = chars?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return 30
        val d = map.getOutputMinFrameDuration(MediaRecorder::class.java, size)
        return if (d > 0) (1e9 / d).roundToInt() else 30
    }

    @SuppressLint("MissingPermission")
    private fun openCamera() {
        val l = lens() ?: run { toast("没有后置摄像头"); return }
        if (device != null) return
        chars = cm.getCameraCharacteristics(l.id)
        val caps = chars!!.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
        manualOk = CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in caps
        val sz = videoSize()
        if (fps > maxFps(sz)) { fps = 30; toast("${sz.width}x${sz.height} 最高 ${maxFps(sz)}fps") }
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
        try { session?.close() } catch (_: Exception) {}
        session = null
        device?.close(); device = null
    }

    private fun reopen() { closeCamera(); refreshButtons(); if (texture.isAvailable) openCamera() }

    private var previewSurface: Surface? = null

    private fun startSession(recSurface: Surface?) {
        val d = device ?: return
        val sz = videoSize()
        val st = texture.surfaceTexture ?: return
        st.setDefaultBufferSize(sz.width, sz.height)
        previewSurface = Surface(st)
        val targets = listOfNotNull(previewSurface, recSurface)
        try { session?.close() } catch (_: Exception) {}
        @Suppress("DEPRECATION")
        d.createCaptureSession(targets, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(s: CameraCaptureSession) {
                session = s
                applySettings(recSurface)
                if (recSurface != null) ui.post {
                    try { recorder?.start(); recStart = SystemClock.elapsedRealtime(); tickRec() }
                    catch (e: Exception) { toast("录制启动失败：${e.message}"); stopRecording() }
                }
            }
            override fun onConfigureFailed(s: CameraCaptureSession) { ui.post { toast("相机会话配置失败（试试降分辨率/帧率）") } }
        }, bg)
    }

    private var activeRecSurface: Surface? = null

    private fun applySettings(recSurface: Surface? = activeRecSurface) {
        val d = device ?: return; val s = session ?: return
        val b = d.createCaptureRequest(if (recSurface != null) CameraDevice.TEMPLATE_RECORD else CameraDevice.TEMPLATE_PREVIEW)
        previewSurface?.let { b.addTarget(it) }
        recSurface?.let { b.addTarget(it) }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        b.set(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_VIDEO)
        b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF)
        b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, Range(fps, fps))
        if (Build.VERSION.SDK_INT >= 30) lens()?.let { b.set(CaptureRequest.CONTROL_ZOOM_RATIO, it.zoom) }
        if (manualOk && metering == 0) {
            val c = chars!!
            val er = c.get(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE)
            val ir = c.get(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE)
            var exp = 1_000_000_000L / shutters[shutterIdx]
            if (er != null) exp = exp.coerceIn(er.lower, er.upper)
            val isoC = if (ir != null) iso.coerceIn(ir.lower, ir.upper) else iso
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exp)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, isoC)
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
        } else {
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_ON)
        }
        try {
            s.setRepeatingRequest(b.build(), object : CameraCaptureSession.CaptureCallback() {
                override fun onCaptureCompleted(s: CameraCaptureSession, r: CaptureRequest, res: TotalCaptureResult) {
                    if (metering > 0) onMeterFrame(res)
                }
            }, bg)
        } catch (e: Exception) { ui.post { toast("设置失败：${e.message}") } }
    }

    // 测光：临时开自动曝光，读它给的 曝光时间×ISO，换算到当前快门下的 ISO 后锁定
    private fun meter() {
        if (!manualOk) { toast("此镜头不支持手动曝光"); return }
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

    private fun hevcOk(): Boolean = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos.any { ci ->
        ci.isEncoder && ci.supportedTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_HEVC, true) }
    }

    private fun startRecording() {
        if (device == null) return
        val sz = videoSize()
        val name = "swing_${station}_${System.currentTimeMillis()}.mp4"
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/杆头轨迹")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        try {
            val uri = contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv)!!
            val pfd = contentResolver.openFileDescriptor(uri, "w")!!
            val r = if (Build.VERSION.SDK_INT >= 31) MediaRecorder(this) else @Suppress("DEPRECATION") MediaRecorder()
            // 收音：击球声用来自动找挥杆（本 App 录的音画同步）
            val audio = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
            if (audio) r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER)
            r.setVideoSource(MediaRecorder.VideoSource.SURFACE)
            r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (audio) { r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC); r.setAudioEncodingBitRate(96_000); r.setAudioSamplingRate(48_000) }
            r.setOutputFile(pfd.fileDescriptor)
            r.setVideoEncoder(if (hevcOk()) MediaRecorder.VideoEncoder.HEVC else MediaRecorder.VideoEncoder.H264)
            r.setVideoEncodingBitRate(bitrate())
            r.setVideoFrameRate(fps)
            r.setVideoSize(sz.width, sz.height)
            // 竖构图：按传感器方向写旋转标记（手机竖着拿）
            r.setOrientationHint(chars?.get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90)
            r.prepare()
            recorder = r; recUri = uri; recPfd = pfd
            activeRecSurface = r.surface
            startSession(r.surface)
            refreshButtons()
        } catch (e: Exception) {
            toast("录制失败：${e.message}")
            recorder?.release(); recorder = null
        }
    }

    private fun stopRecording() {
        val r = recorder ?: return
        try { session?.stopRepeating() } catch (_: Exception) {}
        try { r.stop() } catch (e: Exception) { toast("录制太短或失败") }
        r.release(); recorder = null; activeRecSurface = null
        recPfd?.close(); recPfd = null
        recUri?.let {
            contentResolver.update(it, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            lastUri = it
            analyzeBtn.isEnabled = true
            toast("已存到 Movies/杆头轨迹")
        }
        recUri = null
        startSession(null)
        refreshButtons()
    }

    private fun tickRec() {
        if (recorder == null) return
        updateInfo()
        ui.postDelayed({ tickRec() }, 250)
    }

    private fun openPlayer(uri: Uri) {
        startActivity(Intent(this, PlayerActivity::class.java).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    private fun openAnalyzer(uri: Uri) {
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(uri, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    // ======================= 水平仪 =======================

    override fun onSensorChanged(e: SensorEvent) {
        val gx = e.values[0]; val gy = e.values[1]; val gz = e.values[2]
        // 竖持：重力沿 +y；roll = 左右歪，pitch = 前后仰
        val r = Math.toDegrees(atan2(gx.toDouble(), gy.toDouble())).toFloat()
        val p = Math.toDegrees(atan2(gz.toDouble(), sqrt((gx * gx + gy * gy).toDouble()))).toFloat()
        roll = roll * 0.8f + r * 0.2f; pitch = pitch * 0.8f + p * 0.2f
        hud.roll = roll; hud.invalidate()
        if (recorder == null) updateInfo()
    }

    override fun onAccuracyChanged(s: Sensor?, a: Int) {}

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    /** 取景框叠加：三分线 + 中线 + 水平线（|歪| < 0.5° 变绿）。 */
    private class HudView(ctx: Context) : View(ctx) {
        var roll = 0f
        private val p = Paint(Paint.ANTI_ALIAS_FLAG)
        override fun onDraw(c: Canvas) {
            val w = width.toFloat(); val h = height.toFloat()
            p.strokeWidth = 1f; p.color = 0x55FFFFFF
            for (k in 1..2) { c.drawLine(w * k / 3, 0f, w * k / 3, h, p); c.drawLine(0f, h * k / 3, w, h * k / 3, p) }
            p.color = 0x88FFEB3B.toInt(); c.drawLine(w / 2, 0f, w / 2, h, p)
            val ok = abs(roll) < 0.5f
            p.color = if (ok) 0xFF00E676.toInt() else 0xFFFF5252.toInt(); p.strokeWidth = 3f
            c.save(); c.rotate(-roll, w / 2, h / 2)
            c.drawLine(w * 0.2f, h / 2, w * 0.8f, h / 2, p)
            c.restore()
            p.style = Paint.Style.FILL; p.textSize = 34f
            c.drawText("%+.1f°".format(roll), w / 2 + 10, h / 2 - 12, p)
            p.style = Paint.Style.STROKE
        }
    }
}
