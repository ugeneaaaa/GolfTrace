package com.eugene.golftrace

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.graphics.drawable.GradientDrawable
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
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
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** 逐帧精确回看：MediaCodec 直接解到 Surface，支持 1/20 慢放、单帧步进、拖动时间线、双指放大。 */
class PlayerActivity : Activity() {

    private val ui = Handler(Looper.getMainLooper())
    private lateinit var tex: TextureView
    private lateinit var seek: SeekBar
    private lateinit var timeText: TextView
    private lateinit var playBtn: TextView
    private val speedBtns = ArrayList<Pair<Float, TextView>>()

    private var uri: Uri? = null
    private var player: FramePlayer? = null
    private var speed = 0.05f
    private var playing = false
    private var userSeeking = false

    // 画面缩放
    private var vw = 1; private var vh = 1
    private var zoom = 1f; private var panX = 0f; private var panY = 0f

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.statusBarColor = Color.BLACK
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

    private fun pill(text: String, size: Float = 14f, onClick: () -> Unit) = TextView(this).apply {
        this.text = text; textSize = size; setTextColor(Color.WHITE); gravity = Gravity.CENTER
        setPadding(dp(12), dp(8), dp(12), dp(8))
        background = GradientDrawable().apply { cornerRadius = dp(18).toFloat(); setColor(0xFF2A2A2A.toInt()) }
        setOnClickListener { onClick() }
    }

    private fun build(): View {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK) }

        val top = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(8), dp(8), dp(8), dp(4)) }
        top.addView(pill("‹ 返回") { finish() })
        top.addView(View(this), LinearLayout.LayoutParams(0, 1, 1f))
        top.addView(pill("换视频") { pick() })
        top.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        top.addView(pill("杆头分析") { uri?.let { analyze(it) } })
        root.addView(top)

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

        timeText = TextView(this).apply {
            setTextColor(0xFFDDDDDD.toInt()); textSize = 13f; gravity = Gravity.CENTER
            typeface = android.graphics.Typeface.MONOSPACE; setPadding(0, dp(6), 0, 0)
        }
        root.addView(timeText)

        seek = SeekBar(this).apply { setPadding(dp(20), dp(10), dp(20), dp(10)) }
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) player?.seekFrame(p) }
            override fun onStartTrackingTouch(s: SeekBar) { userSeeking = true; setPlaying(false) }
            override fun onStopTrackingTouch(s: SeekBar) { userSeeking = false }
        })
        root.addView(seek)

        val ctl = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, dp(4), 0, dp(6)) }
        fun gap() = View(this).also { ctl.addView(it, LinearLayout.LayoutParams(dp(14), 1)) }
        ctl.addView(pill("−10", 15f) { step(-10) }); gap()
        ctl.addView(pill("◀ 帧", 17f) { step(-1) }); gap()
        playBtn = pill("▶", 22f) { setPlaying(!playing) }.apply { setPadding(dp(26), dp(8), dp(26), dp(8)) }
        ctl.addView(playBtn); gap()
        ctl.addView(pill("帧 ▶", 17f) { step(1) }); gap()
        ctl.addView(pill("+10", 15f) { step(10) })
        root.addView(ctl)

        val sp = LinearLayout(this).apply { gravity = Gravity.CENTER; setPadding(0, 0, 0, dp(14)) }
        for ((v, label) in listOf(0.05f to "1/20", 0.1f to "1/10", 0.25f to "1/4", 0.5f to "1/2", 1f to "1x")) {
            val b = pill(label, 13f) { speed = v; refreshSpeed() }
            speedBtns.add(v to b)
            sp.addView(b); sp.addView(View(this), LinearLayout.LayoutParams(dp(8), 1))
        }
        root.addView(sp)
        refreshSpeed()
        return root
    }

    private fun refreshSpeed() = speedBtns.forEach { (v, b) ->
        (b.background as GradientDrawable).setColor(if (v == speed) C_YELLOW else 0xFF2A2A2A.toInt())
        b.setTextColor(if (v == speed) Color.BLACK else Color.WHITE)
    }

    private fun step(d: Int) {
        setPlaying(false)
        player?.let { it.seekFrame((it.requested + d).coerceIn(0, it.count - 1)) }
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

    /** 播放节拍：每帧按 原始帧间隔/speed 排期（60fps × 1/20 = 3 帧/秒）。 */
    private fun tick() {
        val pl = player ?: return
        if (!playing) return
        if (pl.cur >= pl.count - 1) { setPlaying(false); return }
        val t0 = System.nanoTime()
        pl.seekFrame(pl.cur + 1) {
            val waitMs = (pl.frameUs / speed / 1000f - (System.nanoTime() - t0) / 1e6f).toLong()
            ui.postDelayed({ tick() }, max(0L, waitMs))
        }
    }

    private fun onFrame(i: Int) {
        val pl = player ?: return
        if (!userSeeking) seek.progress = i
        val t = (pl.pts[i] - pl.pts[0]) / 1e6
        val tot = (pl.pts[pl.count - 1] - pl.pts[0]) / 1e6
        timeText.text = String.format("帧 %d / %d    %.3f s / %.1f s    %.0f fps", i + 1, pl.count, t, tot, 1e6 / pl.frameUs)
    }

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
        timeText.text = "读取中…"
        val st = tex.surfaceTexture ?: return
        player = FramePlayer(this, u, Surface(st), onReady = { w, h, n ->
            vw = w; vh = h; zoom = 1f; panX = 0f; panY = 0f
            seek.max = n - 1
            applyTransform()
            player?.seekFrame(0)
        }, onFrame = { onFrame(it) }, onError = { timeText.text = "打不开：$it" })
    }

    private fun release() { playing = false; player?.close(); player = null }

    override fun onPause() { super.onPause(); setPlaying(false) }
    override fun onDestroy() { super.onDestroy(); release() }

    private fun analyze(u: Uri) {
        startActivity(Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setDataAndType(u, "video/mp4")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
    }

    // ---------------- 缩放 / 平移 ----------------

    private fun applyTransform() {
        val W = tex.width.toFloat(); val H = tex.height.toFloat()
        if (W <= 0 || H <= 0) return
        val fit = min(W / vw, H / vh)
        val m = Matrix()
        // TextureView 默认把视频拉满视图；先还原成等比，再缩放平移
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
                    if (now - lastTap < 300) { // 双击：1x ↔ 3x
                        if (zoom > 1f) { zoom = 1f; panX = 0f; panY = 0f }
                        else { zoom = 3f; panX = -(e.x - tex.width / 2f) * 2f; panY = -(e.y - tex.height / 2f) * 2f; clampPan() }
                        applyTransform(); lastTap = 0
                    } else { lastTap = now; ui.postDelayed({ if (lastTap == now) setPlaying(!playing) }, 300) }
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
    private var lastOut = Long.MIN_VALUE   // 解码器最近输出的 pts
    private var inputDone = false
    private var closed = false

    init {
        bg.post {
            try {
                ex.setDataSource(ctx, uri, null)
                val ti = (0 until ex.trackCount).first { ex.getTrackFormat(it).getString(MediaFormat.KEY_MIME)!!.startsWith("video/") }
                ex.selectTrack(ti)
                val fmt = ex.getTrackFormat(ti)
                val list = ArrayList<Long>()
                while (true) { val t = ex.sampleTime; if (t < 0) break; list.add(t); ex.advance() }
                list.sort(); pts = list.toLongArray()
                if (pts.size > 1) frameUs = (pts.last() - pts.first()).toFloat() / (pts.size - 1)
                var w = fmt.getInteger(MediaFormat.KEY_WIDTH); var h = fmt.getInteger(MediaFormat.KEY_HEIGHT)
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
        // 已经越过目标或距离太远：从前一个关键帧重来
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
                    if (n < 0) { c.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                    else { c.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                }
            }
            val oi = c.dequeueOutputBuffer(info, 2000)
            if (oi >= 0) {
                val eos = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                val hit = info.presentationTimeUs >= want - 500 || eos
                c.releaseOutputBuffer(oi, hit && info.size > 0)
                if (info.size > 0) lastOut = info.presentationTimeUs
                if (hit) {
                    cur = pts.indexOfFirst { it >= lastOut - 500 }.let { if (it < 0) count - 1 else it }
                    return
                }
            }
        }
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
