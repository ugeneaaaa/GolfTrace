package com.eugene.golftrace

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.provider.MediaStore
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** 独立回看：MediaCodec 解到 Surface。逐帧 / fps 慢放 / 时间线 / 截图 / 双指放大。不进分析流程。 */
class PlayerActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tex: TextureView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: TextView
    private lateinit var burstBtn: TextView
    private val fpsBtns = ArrayList<Pair<Int, TextView>>()

    private var uri: Uri? = null
    private var player: FramePlayer? = null
    private var displayFps = 3
    private var playing = false
    private var userSeeking = false
    private var burstOn = false
    private var burstCount = 0
    private var lastBurstFrame = -1

    private val saveTh = HandlerThread("shot-save").apply { start() }
    private val saveBg = Handler(saveTh.looper)

    // 画面缩放
    private var vw = 1; private var vh = 1
    private var zoom = 1f; private var panX = 0f; private var panY = 0f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
        displayFps = getSharedPreferences("player", MODE_PRIVATE).getInt("display_fps", 3)
        val content = build()
        content.setOnApplyWindowInsetsListener { v, ins ->
            val sb = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(v.paddingLeft, maxOf(v.paddingTop, sb.top), v.paddingRight, sb.bottom); ins
        }
        setContentView(content)
        uri = intent?.data
        if (uri == null) pick()
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun pill(text: String, size: Float = 14f, padH: Int = 14, padV: Int = 9, onClick: () -> Unit) =
        TextView(this).apply {
            this.text = text; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            setPadding(dp(padH), dp(padV), dp(padH), dp(padV))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(0xFF2A2A2A.toInt())
            }
            setOnClickListener { onClick() }
        }

    private fun build(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
        }

        // 顶栏
        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }
        top.addView(pill("‹ 返回", 14f) { finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(pill("换视频", 14f) { pick() })
        root.addView(top)

        // 画面
        tex = TextureView(this)
        tex.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) { uri?.let { load(it) } }
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = applyTransform()
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { release(); return true }
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) {}
        }
        val frame = FrameLayout(this)
        frame.addView(tex, FrameLayout.LayoutParams(-1, -1))
        installGestures(frame)
        root.addView(frame, LinearLayout.LayoutParams(-1, 0, 1f))

        // 帧信息
        timeText = TextView(this).apply {
            setTextColor(0xFFCCCCCC.toInt()); textSize = 13f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE
            setPadding(dp(12), dp(10), dp(12), dp(4))
            text = "—"
        }
        root.addView(timeText)

        seek = SeekBar(this).apply { setPadding(dp(18), dp(8), dp(18), dp(8)) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                if (fromUser) player?.seekFrame(p)
            }
            override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true; setPlaying(false) }
            override fun onStopTrackingTouch(s: SeekBar) { userSeeking = false }
        })
        root.addView(seek)

        // 逐帧控制
        val ctl = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(8), dp(6), dp(8), dp(8))
        }
        fun gap(w: Int = 10) = View(this).also { ctl.addView(it, LinearLayout.LayoutParams(dp(w), 1)) }
        ctl.addView(pill("−10", 15f, 12, 10) { step(-10) }); gap()
        ctl.addView(pill("◀ 帧", 16f, 14, 10) { step(-1) }); gap(12)
        playBtn = pill("▶", 20f, 28, 10) { setPlaying(!playing) }
        ctl.addView(playBtn); gap(12)
        ctl.addView(pill("帧 ▶", 16f, 14, 10) { step(1) }); gap()
        ctl.addView(pill("+10", 15f, 12, 10) { step(10) })
        root.addView(ctl)

        // fps 行
        val fpsRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(6))
        }
        fpsRow.addView(TextView(this).apply {
            text = "fps"; textSize = 12f; setTextColor(0xFF888888.toInt())
            setPadding(0, 0, dp(8), 0)
        })
        val fpsScroll = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        for (fps in listOf(1, 3, 5, 10, 15, 30)) {
            val b = pill("$fps", 13f, 12, 7) {
                displayFps = fps
                getSharedPreferences("player", MODE_PRIVATE).edit().putInt("display_fps", fps).apply()
                refreshFps()
            }
            fpsBtns.add(fps to b)
            fpsScroll.addView(b)
            fpsScroll.addView(View(this), LinearLayout.LayoutParams(dp(6), 1))
        }
        fpsRow.addView(fpsScroll, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(fpsRow)

        // 截图行
        val shotRow = LinearLayout(this).apply {
            gravity = Gravity.CENTER; setPadding(dp(12), dp(4), dp(12), dp(16))
        }
        val shotBtn = pill("截图", 14f, 18, 10) { captureOnce() }
        shotBtn.setOnLongClickListener {
            toast("截图：保存当前这一帧到相册/杆头回看")
            true
        }
        shotRow.addView(shotBtn)
        shotRow.addView(View(this), LinearLayout.LayoutParams(dp(12), 1))
        burstBtn = pill("连截", 14f, 18, 10) { toggleBurst() }
        burstBtn.setOnLongClickListener {
            toast("连截：开后每换一帧自动存一张（慢放跟显示 fps；点◀▶/±10 也存）。同帧不重复，不是按秒。")
            true
        }
        shotRow.addView(burstBtn)
        root.addView(shotRow)

        refreshFps()
        refreshBurst()
        return root
    }

    private fun refreshFps() = fpsBtns.forEach { (v, b) ->
        (b.background as GradientDrawable).setColor(if (v == displayFps) C_YELLOW else 0xFF2A2A2A.toInt())
        b.setTextColor(if (v == displayFps) Color.BLACK else Color.WHITE)
        player?.let { onFrame(it.cur.coerceAtLeast(0)) }
    }

    private fun refreshBurst() {
        (burstBtn.background as GradientDrawable).setColor(if (burstOn) C_YELLOW else 0xFF2A2A2A.toInt())
        burstBtn.setTextColor(if (burstOn) Color.BLACK else Color.WHITE)
    }

    private fun step(d: Int) {
        setPlaying(false)
        player?.let {
            it.seekFrame((it.requested + d).coerceIn(0, it.count - 1)) {
                maybeBurstSave()
            }
        }
    }

    private fun setPlaying(p: Boolean) {
        val pl = player ?: return
        playing = p
        playBtn.text = if (p) "❚❚" else "▶"
        if (p) {
            if (pl.cur >= pl.count - 1) pl.seekFrame(0)
            tick()
        }
    }

    /** 播放节拍：按显示帧率推进，每 (1000/displayFps) ms 进一帧，与片源 fps 无关。 */
    private fun tick() {
        val pl = player ?: return
        if (!playing) return
        if (pl.cur >= pl.count - 1) { setPlaying(false); return }
        val t0 = System.nanoTime()
        pl.seekFrame(pl.cur + 1) {
            maybeBurstSave()
            val waitMs = (1000f / displayFps - (System.nanoTime() - t0) / 1e6f).toLong()
            ui.postDelayed({ tick() }, max(0L, waitMs))
        }
    }

    private fun onFrame(i: Int) {
        val pl = player ?: return
        if (i < 0 || pl.count == 0) return
        if (!userSeeking) seek.progress = i
        val t = if (pl.pts.isNotEmpty()) (pl.pts[i.coerceIn(0, pl.count - 1)] - pl.pts[0]) / 1e6 else 0.0
        timeText.text = String.format(
            Locale.US, "帧 %d / %d · %.3fs · %d fps",
            i + 1, pl.count, t, displayFps
        )
    }

    // ---------------- 截图 ----------------

    private fun toggleBurst() {
        if (burstOn) {
            burstOn = false
            toast("连截关，已存 $burstCount 张")
            burstCount = 0
            lastBurstFrame = -1
        } else {
            burstOn = true
            burstCount = 0
            lastBurstFrame = -1
            toast("连截开")
        }
        refreshBurst()
    }

    private fun maybeBurstSave() {
        if (!burstOn) return
        val i = player?.cur ?: return
        if (i < 0 || i == lastBurstFrame) return
        lastBurstFrame = i
        saveCurrentFrame(silent = true) { ok ->
            if (ok) burstCount++
        }
    }

    private fun captureOnce() {
        saveCurrentFrame(silent = false)
    }

    /** UI 线程取 bitmap，后台压缩写入 MediaStore。 */
    private fun saveCurrentFrame(silent: Boolean, done: ((Boolean) -> Unit)? = null) {
        val bmp = try { tex.getBitmap() } catch (_: Exception) { null }
        if (bmp == null) {
            if (!silent) toast("截图失败：画面未就绪")
            done?.invoke(false)
            return
        }
        val frameIdx = player?.cur ?: -1
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
        val name = if (frameIdx >= 0) "golf_frame_${stamp}_f${frameIdx + 1}.png"
        else "golf_frame_$stamp.png"
        saveBg.post {
            var ok = false
            try {
                val cvs = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/杆头回看")
                }
                val dst = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cvs)
                if (dst != null) {
                    contentResolver.openOutputStream(dst)?.use {
                        ok = bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                    }
                }
            } catch (_: Exception) {
                ok = false
            } finally {
                if (!bmp.isRecycled) bmp.recycle()
            }
            ui.post {
                if (!silent) {
                    if (ok) toast("已保存到 相册/Pictures/杆头回看")
                    else toast("截图失败")
                }
                done?.invoke(ok)
            }
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()

    // ---------------- 视频 ----------------

    private fun pick() {
        setPlaying(false)
        @Suppress("DEPRECATION")
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
        }, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) data?.data?.let { uri = it; if (tex.isAvailable) load(it) }
        else if (uri == null) finish()
    }

    private fun load(u: Uri) {
        release()
        timeText.text = "打开中…"
        val st = tex.surfaceTexture ?: return
        player = FramePlayer(this, u, Surface(st), onReady = { w, h, n ->
            vw = w; vh = h; zoom = 1f; panX = 0f; panY = 0f
            seek.max = max(0, n - 1)
            applyTransform()
            player?.seekFrame(0)
        }, onFrame = { onFrame(it) }, onError = { timeText.text = "打不开：$it" })
    }

    private fun release() { playing = false; player?.close(); player = null }

    override fun onPause() {
        super.onPause()
        setPlaying(false)
        // 连截状态可保留，但暂停时不写（仅 step/tick 会触发）
    }

    override fun onDestroy() {
        super.onDestroy()
        release()
        saveTh.quitSafely()
    }

    // ---------------- 缩放 / 平移 ----------------

    private fun applyTransform() {
        val W = tex.width.toFloat(); val H = tex.height.toFloat()
        if (W <= 0 || H <= 0) return
        val fit = min(W / vw, H / vh)
        val m = Matrix()
        m.setScale(vw * fit / W, vh * fit / H, W / 2, H / 2)
        m.postScale(zoom, zoom, W / 2, H / 2)
        m.postTranslate(panX, panY)
        tex.setTransform(m); tex.invalidate()
    }

    private fun installGestures(v: View) {
        val sg = ScaleGestureDetector(this, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(d: ScaleGestureDetector): Boolean {
                val nz = (zoom * d.scaleFactor).coerceIn(1f, 8f)
                val f = nz / zoom
                val cx = d.focusX - tex.width / 2f; val cy = d.focusY - tex.height / 2f
                panX = cx - (cx - panX) * f; panY = cy - (cy - panY) * f
                zoom = nz; clampPan(); applyTransform(); return true
            }
        })
        var lx = 0f; var ly = 0f; var lastTap = 0L; var moved = false
        v.setOnTouchListener { _, e ->
            sg.onTouchEvent(e)
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y; moved = false }
                MotionEvent.ACTION_MOVE -> if (!sg.isInProgress && e.pointerCount == 1) {
                    val dx = e.x - lx; val dy = e.y - ly
                    if (abs(dx) + abs(dy) > 6) moved = true
                    if (zoom > 1f) { panX += dx; panY += dy; clampPan(); applyTransform() }
                    lx = e.x; ly = e.y
                }
                MotionEvent.ACTION_UP -> if (!moved && e.pointerCount == 1) {
                    val now = System.currentTimeMillis()
                    if (now - lastTap < 300) {
                        if (zoom > 1f) { zoom = 1f; panX = 0f; panY = 0f }
                        else {
                            zoom = 3f
                            panX = -(e.x - tex.width / 2f) * 2f
                            panY = -(e.y - tex.height / 2f) * 2f
                            clampPan()
                        }
                        applyTransform(); lastTap = 0
                    } else {
                        lastTap = now
                        ui.postDelayed({ if (lastTap == now) setPlaying(!playing) }, 300)
                    }
                }
            }
            true
        }
    }

    private fun clampPan() {
        val lim = { s: Float -> s * (zoom - 1f) / 2f }
        val fit = min(tex.width.toFloat() / vw, tex.height.toFloat() / vh)
        val mx = lim(vw * fit); val my = lim(vh * fit)
        panX = panX.coerceIn(-mx, mx); panY = panY.coerceIn(-my, my)
    }
}

/**
 * 精确到帧的解码器。所有操作在后台线程串行执行；
 * 前进一小段直接顺序解码，后退或跳远时从前一个关键帧解到目标帧，只渲染目标帧。
 * 打开时优先用 duration + frame rate 构建 CFR pts 表，避免全量扫描。
 */
class FramePlayer(
    ctx: android.content.Context, uri: Uri, private val surface: Surface,
    onReady: (Int, Int, Int) -> Unit, private val onFrame: (Int) -> Unit, onError: (String) -> Unit,
) {
    private val th = HandlerThread("frames").apply { start() }
    private val bg = Handler(th.looper)
    private val ui = Handler(Looper.getMainLooper())
    private val ex = MediaExtractor()
    private var codec: MediaCodec? = null
    @Volatile var cur = -1; private set
    @Volatile private var target = -1
    val requested get() = if (target < 0) cur else target
    var pts = LongArray(0); private set
    val count get() = pts.size
    var frameUs = 16667f; private set
    private var lastOut = Long.MIN_VALUE
    private var inputDone = false
    private var closed = false

    init {
        bg.post {
            try {
                ex.setDataSource(ctx, uri, null)
                val ti = (0 until ex.trackCount).first {
                    ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/")
                }
                ex.selectTrack(ti)
                val fmt = ex.getTrackFormat(ti)
                buildPts(fmt)
                var w = fmt.getInteger(MediaFormat.KEY_WIDTH)
                var h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
                val rot = if (fmt.containsKey("rotation-degrees")) fmt.getInteger("rotation-degrees") else 0
                if (rot % 180 != 0) { val t = w; w = h; h = t }
                val c = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
                c.configure(fmt, surface, null, 0); c.start(); codec = c
                ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
                val n = pts.size
                ui.post { if (!closed) onReady(w, h, n) }
            } catch (e: Exception) {
                ui.post { onError(e.message ?: e.toString()) }
            }
        }
    }

    /** 优先 KEY_DURATION + KEY_FRAME_RATE 建 CFR 表；缺帧率则抽样前缀估 frameUs。 */
    private fun buildPts(fmt: MediaFormat) {
        val durationUs = when {
            fmt.containsKey(MediaFormat.KEY_DURATION) -> fmt.getLong(MediaFormat.KEY_DURATION)
            else -> -1L
        }
        val metaFps = readFrameRate(fmt)

        if (durationUs > 0 && metaFps > 0f) {
            frameUs = 1_000_000f / metaFps
            val n = max(1, (durationUs / frameUs).roundToInt())
            pts = LongArray(n) { i -> (i * frameUs).toLong() }
            return
        }

        // 抽样前缀估帧间隔
        val sample = ArrayList<Long>(128)
        while (sample.size < 120) {
            val t = ex.sampleTime
            if (t < 0) break
            sample.add(t)
            ex.advance()
        }
        if (sample.size > 1) {
            frameUs = (sample.last() - sample.first()).toFloat() / (sample.size - 1)
        } else if (metaFps > 0f) {
            frameUs = 1_000_000f / metaFps
        }

        if (durationUs > 0 && frameUs > 1f) {
            val n = max(1, (durationUs / frameUs).roundToInt())
            pts = LongArray(n) { i -> (i * frameUs).toLong() }
            ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            return
        }

        // 无 duration：把已抽样的留下，并继续扫完（罕见兜底）
        while (true) {
            val t = ex.sampleTime
            if (t < 0) break
            sample.add(t)
            ex.advance()
        }
        sample.sort()
        pts = sample.toLongArray()
        if (pts.size > 1) frameUs = (pts.last() - pts.first()).toFloat() / (pts.size - 1)
        ex.seekTo(0, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
    }

    private fun readFrameRate(fmt: MediaFormat): Float {
        if (!fmt.containsKey(MediaFormat.KEY_FRAME_RATE)) return -1f
        return try {
            fmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat()
        } catch (_: ClassCastException) {
            try { fmt.getFloat(MediaFormat.KEY_FRAME_RATE) } catch (_: Exception) { -1f }
        } catch (_: Exception) { -1f }
    }

    /** 请求显示第 i 帧；连续请求只执行最新的（拖动时间线不会排队）。 */
    fun seekFrame(i: Int, done: (() -> Unit)? = null) {
        if (count == 0) return
        target = i.coerceIn(0, count - 1)
        bg.post {
            val t = target
            if (closed || codec == null || t < 0) return@post
            if (t != cur) try { show(t) } catch (_: Exception) {}
            val shown = cur
            ui.post { if (!closed) { onFrame(shown); done?.invoke() } }
        }
    }

    private fun show(i: Int) {
        val c = codec!!
        val want = pts[i]
        if (want <= lastOut || want - maxOf(lastOut, pts[0]) > 1_500_000 || cur < 0) {
            ex.seekTo(want, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            c.flush(); lastOut = Long.MIN_VALUE; inputDone = false
        }
        val info = MediaCodec.BufferInfo()
        var guard = 0
        while (guard++ < 5000) {
            if (!inputDone) {
                val ii = c.dequeueInputBuffer(2000)
                if (ii >= 0) {
                    val n = ex.readSampleData(c.getInputBuffer(ii)!!, 0)
                    if (n < 0) {
                        c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        inputDone = true
                    } else {
                        c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance()
                    }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 2000)
            if (oi >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val hit = info.presentationTimeUs >= want - 500 || eos
                c.releaseOutputBuffer(oi, hit && info.size > 0)
                if (info.size > 0) lastOut = info.presentationTimeUs
                if (hit) {
                    // CFR 表：用 pts 反查；若表为估算则按时间最近帧
                    cur = nearestFrame(lastOut)
                    return
                }
            }
        }
    }

    private fun nearestFrame(tUs: Long): Int {
        if (pts.isEmpty()) return 0
        // 对 CFR 均匀表可用除法；对实测表退回线性搜索邻近
        if (frameUs > 1f) {
            val i = ((tUs - pts[0]) / frameUs).roundToInt()
            return i.coerceIn(0, count - 1)
        }
        var best = 0
        var bestD = Long.MAX_VALUE
        for (i in pts.indices) {
            val d = abs(pts[i] - tUs)
            if (d < bestD) { bestD = d; best = i }
        }
        return best
    }

    fun close() {
        closed = true
        bg.post {
            try { codec?.stop() } catch (_: Exception) {}
            try { codec?.release() } catch (_: Exception) {}
            try { ex.release() } catch (_: Exception) {}
            surface.release()
            th.quitSafely()
        }
    }
}
