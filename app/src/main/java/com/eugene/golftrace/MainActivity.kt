package com.eugene.golftrace

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt

class MainActivity : Activity() {

    private enum class Mark { HEAD, GRIP }
    private enum class Show { FRAME, DARK, BRIGHT, D3 }

    private lateinit var status: TextView
    private lateinit var energyView: EnergyView
    private lateinit var peakRow: LinearLayout
    private lateinit var frameView: FrameView
    private lateinit var view3d: Trace3DView
    private lateinit var seek: SeekBar
    private lateinit var frameLabel: TextView
    private lateinit var stats: TextView
    private lateinit var fpsEdit: EditText
    private val markButtons = HashMap<Mark, Button>()
    private val showButtons = HashMap<Show, Button>()
    private lateinit var brightBtn: Button
    private lateinit var tapeBtn: Button
    private lateinit var playBtn: Button

    private var uri: Uri? = null
    private var info: VideoInfo? = null
    private var clip: Clip? = null
    private var head = Track(0)
    private var grip = Track(0)
    private var hands = Track(0)   // 双手轨迹（杆身线段近端），杆头认不出的帧靠它
    private var cur = 0
    private var mark = Mark.HEAD
    private var show = Show.FRAME
    private var brighten = true
    private val params = Analysis.TrackParams()
    private var busy = false
    private var playing = false
    private val ui = Handler(Looper.getMainLooper())

    private var frameBmp: Bitmap? = null
    private var compBmp: Bitmap? = null
    private var finishRaw: Bitmap? = null   // 收杆帧（彩色），轨迹点画在它上面
    private val lut = IntArray(256)

    override fun onCreate(s: Bundle?) {
        super.onCreate(s)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        buildLut()
        setContentView(buildUi())
        if (intent?.action == Intent.ACTION_VIEW) intent.data?.let { open(it) }
        if (intent?.action == Intent.ACTION_SEND) {
            @Suppress("DEPRECATION")
            (intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM))?.let { open(it) }
        }
    }

    // ---------------- UI ----------------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun btn(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text; isAllCaps = false; textSize = 13f
        minWidth = 0; minimumWidth = 0; setPadding(dp(10), 0, dp(10), 0)
        setOnClickListener { onClick() }
    }

    private fun row(vararg v: View): View {
        val l = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        v.forEach { l.addView(it) }
        return HorizontalScrollView(this).apply { addView(l); isHorizontalScrollBarEnabled = false }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF000000.toInt())
            setPadding(dp(4), dp(28), dp(4), dp(28))
        }
        brightBtn = btn("提亮 ✓") { brighten = !brighten; brightBtn.text = if (brighten) "提亮 ✓" else "提亮"; buildLut(); refreshComposite(); render() }
        fpsEdit = EditText(this).apply {
            hint = "拍摄fps"; inputType = InputType.TYPE_CLASS_NUMBER; textSize = 13f
            setTextColor(Color.WHITE); setHintTextColor(Color.GRAY); width = dp(80)
        }
        root.addView(row(btn("● 录制") { startActivity(Intent(this, CameraActivity::class.java)) }, btn("选视频") { pick() }, brightBtn, fpsEdit, btn("说明") { help() }))
        status = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; setLines(2); text = "选一个挥杆视频开始。也可以在相册里“分享”视频到本 App。" }
        root.addView(status)
        energyView = EnergyView(this).apply { onPick = { t -> loadSwing(t) } }
        root.addView(energyView, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)))
        peakRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        root.addView(HorizontalScrollView(this).apply { addView(peakRow) })

        val stage = android.widget.FrameLayout(this)
        frameView = FrameView(this).apply {
            onTapImage = { x, y -> onTap(x, y) }
            drawer = { c, m -> drawOverlay(c, m) }
        }
        view3d = Trace3DView(this).apply { visibility = View.GONE }
        stats = TextView(this).apply {
            setTextColor(Color.WHITE); textSize = 11f; typeface = Typeface.MONOSPACE
            setBackgroundColor(0x99000000.toInt()); setPadding(dp(6), dp(4), dp(6), dp(4))
        }
        stage.addView(frameView); stage.addView(view3d)
        // 数据放底部（画面下方多是地面），点一下可收起/展开
        stage.addView(stats, android.widget.FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM or Gravity.START))
        stats.setOnClickListener { statsCollapsed = !statsCollapsed; updateStats() }
        root.addView(stage, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))

        seek = SeekBar(this).apply {
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, p: Int, fromUser: Boolean) { if (fromUser) { cur = p; render() } }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) {}
            })
        }
        frameLabel = TextView(this).apply { setTextColor(Color.LTGRAY); textSize = 12f; width = dp(90) }
        playBtn = btn("▶") { togglePlay() }
        val seekRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            addView(btn("◀") { step(-1) }); addView(playBtn); addView(btn("▶|") { step(1) })
            addView(seek, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(frameLabel)
        }
        root.addView(seekRow)

        markButtons[Mark.HEAD] = btn("点杆头") { setMark(Mark.HEAD) }
        markButtons[Mark.GRIP] = btn("点握把") { setMark(Mark.GRIP) }
        tapeBtn = btn("反光胶带") { params.brightTarget = !params.brightTarget; tapeBtn.text = if (params.brightTarget) "反光胶带 ✓" else "反光胶带" }
        root.addView(row(markButtons[Mark.HEAD]!!, markButtons[Mark.GRIP]!!,
            btn("自动跟踪") { runTrack() }, btn("补空档") { runInterp() },
            btn("删本帧点") { deleteAnchor() }, btn("清空") { clearTracks() }))
        showButtons[Show.FRAME] = btn("单帧") { setShow(Show.FRAME) }
        showButtons[Show.DARK] = btn("收杆图") { setShow(Show.DARK) }
        showButtons[Show.BRIGHT] = btn("叠加") { setShow(Show.BRIGHT) }
        showButtons[Show.D3] = btn("3D") { setShow(Show.D3) }
        root.addView(row(showButtons[Show.FRAME]!!, showButtons[Show.DARK]!!, showButtons[Show.BRIGHT]!!,
            showButtons[Show.D3]!!, btn("保存图片") { save() }))
        setMark(Mark.HEAD); setShow(Show.FRAME)
        return root
    }

    private fun setMark(m: Mark) {
        mark = m
        markButtons.forEach { (k, b) -> b.setTextColor(if (k == m) C_YELLOW else Color.BLACK) }
    }

    private fun setShow(s: Show) {
        show = s
        showButtons.forEach { (k, b) -> b.setTextColor(if (k == s) C_YELLOW else Color.BLACK) }
        view3d.visibility = if (s == Show.D3) View.VISIBLE else View.GONE
        frameView.visibility = if (s == Show.D3) View.GONE else View.VISIBLE
        refreshComposite()
        render()
    }

    private fun help() {
        android.app.AlertDialog.Builder(this).setTitle("用法").setMessage(
            """
            1. 选视频 → 自动扫描：画面里“静止→上杆→静止→下杆”的节奏 + 击球声（🔊）都列为候选，点候选或曲线任意位置，载入前 5 秒、后 1.5 秒（🔊 候选再往前 1 秒）。
            2. 拖到地址位，“点杆头”点杆头，再“点握把”在同一帧点手 → 出黄线。
            3. 点“自动跟踪”：在运动图里找杆身直线，杆头 = 离胸口远的那端，整段一起求最平滑的路径；击球帧自动找（球区再次出现运动）。
            4. 哪帧不对，就在那帧点一下杆头：它变成锚点，整段重算。只跟到击球后约 0.05s（之后腿的轮廓会干扰）。
            5. 节奏数据用运动能量分段（峰值 15%），和轨迹无关。握把可以另外跟踪（切到“点握把”再“自动跟踪”），用于击球前 0.10s 杆身角。
            双击放大，双指缩放，单指拖动。

            拍法：竖构图，整个挥杆（包括顶点杆头）都在画面里；快门 1/1000 以上，杆身才是细线。
            慢动作如果被存成 30fps 播放，在“拍摄fps”里填真实帧率（如 240），时间数据才对。
            """.trimIndent()
        ).setPositiveButton("好", null).show()
    }

    // ---------------- 打开视频 & 扫描 ----------------

    private fun pick() {
        if (busy) return
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE); type = "video/*"
        }, 1)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(req: Int, res: Int, data: Intent?) {
        super.onActivityResult(req, res, data)
        if (req == 1 && res == RESULT_OK) data?.data?.let { open(it) }
    }

    private fun open(u: Uri) {
        if (busy) return
        uri = u; clip = null; stopPlay()
        busy = true
        peakRow.removeAllViews()
        thread {
            try {
                val inf = Decoder.info(this, u)
                info = inf
                val es = ArrayList<Float>(); val ts = ArrayList<Long>()
                var prev: ByteArray? = null
                var lastUi = 0L
                val t0 = System.currentTimeMillis()
                Decoder.decode(this, u, inf, 96, 0, Long.MAX_VALUE, null) { t, g ->
                    es.add(Analysis.motionEnergy(prev, g)); ts.add(t); prev = g
                    val now = System.currentTimeMillis()
                    if (now - lastUi > 300) {
                        lastUi = now
                        val frac = t.toFloat() / max(1L, inf.durationUs)
                        val e = es.toFloatArray(); val tt = ts.toLongArray()
                        ui.post {
                            status.text = "扫描中 %.0f%%（%.0f s）".format(frac * 100, (now - t0) / 1000f)
                            energyView.energy = e; energyView.times = tt; energyView.progress = frac.coerceIn(0.01f, 1f)
                            energyView.invalidate()
                        }
                    }
                    true
                }
                val e = es.toFloatArray(); val tt = ts.toLongArray()
                val visual = Analysis.findSwingPeaks(e, tt)
                ui.post { status.text = "画面扫描完成，听击球声…" }
                val audio = Decoder.audioOnsets(this, u)
                val peaks = Analysis.mergeTimes(visual, audio)
                ui.post {
                    busy = false
                    energyView.energy = e; energyView.times = tt; energyView.peaks = peaks; energyView.progress = 1f
                    energyView.invalidate()
                    status.text = "%dx%d %.0ffps %.1fs，找到 %d 个候选挥杆。点一个载入。".format(
                        inf.srcW, inf.srcH, inf.fps, inf.durationUs / 1e6, peaks.size)
                    peaks.forEachIndexed { i, t ->
                        val tag = if (t in audio && t !in visual) "🔊" else ""
                        // 旧 BMD 文件击球声比画面晚 0.7–2.7s：声音候选往前多截
                        peakRow.addView(btn("${i + 1}: %.1fs$tag".format(t / 1e6)) { loadSwing(if (tag.isEmpty()) t else t - 1_000_000) })
                    }
                }
            } catch (ex: Throwable) {
                ui.post { busy = false; status.text = "打开失败：${ex.message}" }
            }
        }
    }

    private fun loadSwing(peakUs: Long) {
        val u = uri ?: return; val inf = info ?: return
        if (busy) return
        busy = true; stopPlay()
        energyView.selected = peakUs; energyView.invalidate()
        val start = max(0L, peakUs - 5_000_000); val end = peakUs + 1_500_000
        // 按帧数控制分辨率，灰度帧总量约 120MB 以内
        val nFrames = ((end - start) / 1e6 * inf.fps).coerceAtLeast(1.0)
        val longSide = sqrt(120e6 / nFrames * 16 / 9).toInt().coerceIn(240, 720)
        thread {
            try {
                val (w, h) = Decoder.outSize(inf, longSide)
                val acc = Decoder.ColorAccum(w, h)
                val frames = ArrayList<ByteArray>(); val ts = ArrayList<Long>()
                Decoder.decode(this, u, inf, longSide, start, end, acc) { t, g ->
                    frames.add(g); ts.add(t)
                    if (frames.size % 20 == 0) ui.post { status.text = "载入 %.1fs 附近：%d 帧".format(peakUs / 1e6, frames.size) }
                    true
                }
                val c = Clip(w, h, frames, ts.toLongArray(), acc)
                ui.post {
                    busy = false
                    clip = c; head = Track(c.n); grip = Track(c.n); hands = Track(c.n); cur = 0; finishRaw = null
                    frameBmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
                    refreshComposite()
                    seek.max = c.n - 1; seek.progress = 0
                    view3d.clip = c; view3d.head = head
                    status.text = "已载入 ${c.n} 帧（${w}x$h）。拖到地址位，点杆头。"
                    frameView.bitmap = null
                    setMark(Mark.HEAD); setShow(Show.FRAME)
                    updateStats()
                }
            } catch (ex: Throwable) {
                ui.post { busy = false; status.text = "载入失败：${ex.message}" }
            }
        }
    }

    // ---------------- 显示 ----------------

    private fun buildLut() {
        for (i in 0 until 256) {
            val v = i / 255f
            // 暗场提亮：gamma 0.55 + 轻微拉伸
            lut[i] = if (brighten) (v.pow(0.55f) * 255).toInt().coerceIn(0, 255) else i
        }
    }

    private fun refreshComposite() { compBmp = composite(show == Show.BRIGHT) }

    /** 收杆图（Show.DARK）用一张彩色收杆帧做底图；“叠加”（Show.BRIGHT）是多帧最暗叠加。 */
    private fun composite(bright: Boolean): Bitmap? {
        val c = clip ?: return null
        val src: IntArray = if (!bright) {
            val f = finishRaw
            if (f != null) IntArray(c.w * c.h).also { f.getPixels(it, 0, c.w, 0, 0, c.w, c.h) }
            else { val g = c.frames[c.n - 1]; IntArray(g.size) { val v = g[it].toInt() and 0xff; (0xff shl 24) or (v shl 16) or (v shl 8) or v } }
        } else c.color.dark
        val px = IntArray(src.size)
        for (i in src.indices) {
            val v = src[i]
            px[i] = (0xff shl 24) or (lut[(v shr 16) and 0xff] shl 16) or (lut[(v shr 8) and 0xff] shl 8) or lut[v and 0xff]
        }
        return Bitmap.createBitmap(px, c.w, c.h, Bitmap.Config.ARGB_8888)
    }

    /** 取某时刻的彩色帧并缩放成片段尺寸（旋转后宽高不对时转 90°）。 */
    private fun colorFrame(tUs: Long, w: Int, h: Int): Bitmap? {
        val u = uri ?: return null
        val r = android.media.MediaMetadataRetriever()
        try {
            r.setDataSource(this, u)
            var b = r.getFrameAtTime(tUs, android.media.MediaMetadataRetriever.OPTION_CLOSEST) ?: return null
            if ((b.width > b.height) != (w > h)) {
                val rot = info?.rotation ?: 90
                b = Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(if (rot == 0) 90f else rot.toFloat()) }, true)
            }
            return Bitmap.createScaledBitmap(b, w, h, true)
        } finally { r.release() }
    }

    private fun render() {
        val c = clip ?: return
        cur = cur.coerceIn(0, c.n - 1)
        seek.progress = cur
        val dt = (c.tUs[cur] - c.tUs[0]) / 1e6
        frameLabel.text = "%d  %.3fs".format(cur, dt * timeScale())
        when (show) {
            Show.FRAME -> {
                val g = c.frames[cur]; val px = IntArray(g.size)
                for (i in g.indices) { val v = lut[g[i].toInt() and 0xff]; px[i] = (0xff shl 24) or (v shl 16) or (v shl 8) or v }
                frameBmp!!.setPixels(px, 0, c.w, 0, 0, c.w, c.h)
                frameView.bitmap = frameBmp
            }
            Show.DARK, Show.BRIGHT -> frameView.bitmap = compBmp
            Show.D3 -> { view3d.phases = phases(); view3d.invalidate() }
        }
        frameView.invalidate()
    }

    private fun addrFrame(): Int = head.anchors.firstOrNull() ?: 0
    private var tempoCache: Analysis.Tempo? = null
    private var statsCollapsed = false
    private var phasesCache: Analysis.Phases? = null

    /** 跟踪结果变了之后重算分段（能量法），着色和统计都用它。 */
    private fun recomputePhases() {
        val c = clip ?: run { tempoCache = null; phasesCache = null; return }
        val tp = Analysis.tempo(c, head, addrFrame())
        tempoCache = tp
        phasesCache = if (tp != null) Analysis.Phases(tp.takeaway, tp.stillStart, tp.stillEnd, tp.top, tp.impact)
            else Analysis.phases(head, c, addrFrame())
    }

    private fun phases(): Analysis.Phases? = phasesCache

    private fun drawOverlay(c: Canvas, m: Matrix) {
        val cl = clip ?: return
        val upTo = if (show == Show.FRAME) cur else cl.n - 1
        Overlay.draw(c, m, cl, head, grip, hands, addrFrame(), phases(), if (show == Show.FRAME) cur else -1, upTo, resources.displayMetrics.density)
    }

    private fun timeScale(): Float {
        val c = clip ?: return 1f
        val cap = fpsEdit.text.toString().toFloatOrNull() ?: return 1f
        if (cap <= 0 || c.n < 2) return 1f
        val vidFps = (c.n - 1) * 1e6f / (c.tUs[c.n - 1] - c.tUs[0])
        return vidFps / cap
    }

    private fun updateStats() {
        val c = clip ?: return
        recomputePhases()
        stats.visibility = if (head.anchors.isEmpty()) View.GONE else View.VISIBLE
        val full = if (head.anchors.isEmpty()) "" else Analysis.report(c, head, grip, addrFrame(), timeScale())
        stats.text = if (statsCollapsed) "数据 ▸" else full
    }

    // ---------------- 标记 & 跟踪 ----------------

    private fun track() = if (mark == Mark.HEAD) head else grip

    private fun onTap(x: Float, y: Float) {
        val c = clip ?: return
        if (show != Show.FRAME) { toast("切到“单帧”再点"); return }
        if (x < 0 || y < 0 || x >= c.w || y >= c.h) return
        if (busy) return
        val tr = track()
        val tracked = tr.lostAt >= 0 || (cur + 1 until c.n).any { tr.has(it) }
        tr.x[cur] = x; tr.y[cur] = y; tr.anchors.add(cur)
        render(); updateStats()
        if (!tracked) return
        if (mark == Mark.HEAD) {
            // 杆头：把这一帧当锚点，全局重算（其他帧也可能跟着变对）
            runShaft(c, "加了第 $cur 帧的锚点，重算中…")
            return
        }
        // 握把：从这一帧重新往后跟到下一个手动点
        val from = cur
        val next = tr.anchors.higher(from) ?: c.n
        busy = true; status.text = "从第 $from 帧重新跟踪…"
        thread {
            for (i in from + 1 until next) { tr.x[i] = Float.NaN; tr.y[i] = Float.NaN }
            tr.lostAt = -1
            Analysis.trackForward(c, tr, from, next - 1, params)
            ui.post {
                busy = false
                status.text = if (tr.lostAt >= 0) "第 ${tr.lostAt} 帧又跟丢，继续往后点，最后“补空档”。" else "已修正。"
                render(); updateStats()
            }
        }
    }

    private fun runShaft(c: Clip, msg: String) {
        busy = true; status.text = msg
        thread {
            // 调试：files/dump 存在时导出帧和锚点，电脑上回放（平时不占空间）
            val dd = getExternalFilesDir(null)
            if (dd != null && java.io.File(dd, "dump").exists()) try {
                java.io.File(dd, "clip.gray").outputStream().buffered().use { o -> c.frames.forEach { o.write(it) } }
                val a = addrFrame()
                java.io.File(dd, "clip.txt").writeText("${c.w} ${c.h} ${c.n} $a ${head.x[a]} ${head.y[a]} ${grip.x[a]} ${grip.y[a]}\n")
            } catch (_: Exception) {}

            val t0 = System.currentTimeMillis()
            val res = try { ShaftTracker.track(c, head, grip, hands) { p -> ui.post { status.text = "杆身跟踪 %.0f%%".format(p * 100) } } } catch (e: Throwable) { null }
            // 收杆帧：击球后 ~0.9s（或片段最后一帧），按彩色重新取一张
            var fin: Bitmap? = null
            if (res != null) try {
                val fps = (c.n - 1) * 1e6f / (c.tUs[c.n - 1] - c.tUs[0])
                val fi = if (res.impact > 0) min(c.n - 1, res.impact + (0.9f * fps).toInt()) else c.n - 1
                fin = colorFrame(c.tUs[fi], c.w, c.h)
            } catch (_: Exception) {}
            ui.post {
                busy = false
                if (res == null) { status.text = "跟踪失败：确认地址位同时点了杆头和握把"; return@post }
                if (fin != null) finishRaw = fin
                setShow(Show.DARK)
                head.impact = res.impact
                android.util.Log.i("GolfTrace", "clip ${c.w}x${c.h} n=${c.n} seed=${addrFrame()} head=(${head.x[addrFrame()]},${head.y[addrFrame()]}) grip=(${grip.x[addrFrame()]},${grip.y[addrFrame()]}) " +
                    (0 until c.n step 8).filter { head.has(it) }.joinToString(" ") { "$it:${head.x[it].toInt()},${head.y[it].toInt()}" })
                status.text = (if (res.impact > 0) "完成（%.1fs），击球第 ${res.impact} 帧。" else "完成（%.1fs），没找到击球帧。")
                    .format((System.currentTimeMillis() - t0) / 1000f) + "哪帧不对就在那帧点一下杆头，会整体重算。"
                render(); updateStats()
            }
        }
    }

    private fun runTrack() {
        val c = clip ?: return
        val tr = track()
        if (tr.anchors.isEmpty()) { toast("先在地址位点一下${if (mark == Mark.HEAD) "杆头" else "握把"}"); return }
        if (busy) return
        if (mark == Mark.HEAD) {
            if (!grip.has(addrFrame())) { toast("杆头跟踪需要地址位的握把：切到“点握把”，在同一帧点手的位置"); return }
            runShaft(c, "杆身跟踪中…"); return
        }
        busy = true; status.text = "握把跟踪中…"
        thread {
            val t0 = System.currentTimeMillis()
            Analysis.trackAll(c, tr, params)
            ui.post {
                busy = false
                val gaps = (0 until c.n).count { !tr.has(it) && it > (tr.anchors.firstOrNull() ?: 0) }
                status.text = if (gaps == 0) "跟踪完成（%.1fs）。拖动检查，不对的帧点一下即可修正。".format((System.currentTimeMillis() - t0) / 1000f)
                    else "握把第 ${tr.lostAt} 帧起跟丢（共 $gaps 帧空缺）。隔几帧手点一次，再点“补空档”。"
                cur = if (tr.lostAt >= 0) tr.lostAt else cur
                render(); updateStats()
            }
        }
    }

    private fun runInterp() {
        val c = clip ?: return
        val tr = track()
        if ((0 until c.n).count { tr.has(it) } < 2) { toast("至少要有 2 个点"); return }
        Analysis.interpolate(tr, c.n); render(); updateStats()
    }

    private fun deleteAnchor() {
        val tr = track()
        if (tr.anchors.remove(cur)) { tr.x[cur] = Float.NaN; tr.y[cur] = Float.NaN; render(); updateStats() }
    }

    private fun clearTracks() {
        val tr = track(); tr.clear(); render(); updateStats()
    }

    private fun step(d: Int) { stopPlay(); cur += d; render() }

    private fun togglePlay() {
        if (playing) { stopPlay(); return }
        val c = clip ?: return
        playing = true; playBtn.text = "❚❚"
        if (cur >= c.n - 1) cur = 0
        val tick = object : Runnable {
            override fun run() {
                if (!playing) return
                cur++
                if (cur >= c.n) { cur = c.n - 1; stopPlay(); return }
                render(); ui.postDelayed(this, 33)
            }
        }
        ui.post(tick)
    }

    private fun stopPlay() { playing = false; if (::playBtn.isInitialized) playBtn.text = "▶" }

    // ---------------- 导出 ----------------

    private fun save() {
        val c = clip ?: return
        val scale = max(1f, 1080f / c.w)
        val iw = (c.w * scale).toInt(); val ih = (c.h * scale).toInt()
        val txt = if (head.anchors.isEmpty()) "" else Analysis.report(c, head, grip, addrFrame(), timeScale())
        val lines = txt.lines().filter { it.isNotBlank() }
        val lineH = 40
        val out = Bitmap.createBitmap(iw, ih + lines.size * lineH + 30, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(Color.BLACK)
        val bg = composite(show == Show.BRIGHT) ?: return
        val m = Matrix().apply { setScale(scale, scale) }
        cv.drawBitmap(bg, m, Paint(Paint.FILTER_BITMAP_FLAG))
        cv.save(); cv.clipRect(0, 0, iw, ih)
        Overlay.draw(cv, m, c, head, grip, hands, addrFrame(), phases(), -1, c.n - 1, 2f)
        cv.restore()
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 30f; typeface = Typeface.MONOSPACE }
        lines.forEachIndexed { i, l -> cv.drawText(l, 20f, ih + 40f + i * lineH, p) }
        p.textSize = 26f
        var x = iw - 20f
        for ((label, col) in listOf("击球后" to C_AFTER, "下杆" to C_DOWN, "上杆" to C_BACK)) {
            p.color = col; val tw = p.measureText(label); x -= tw; cv.drawText(label, x, 36f, p); x -= 24f
        }
        val name = "golftrace_${System.currentTimeMillis()}.png"
        val cvs = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, name)
            put(MediaStore.Images.Media.MIME_TYPE, "image/png")
            put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/杆头轨迹")
        }
        try {
            val dst = contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cvs)!!
            contentResolver.openOutputStream(dst)!!.use { out.compress(Bitmap.CompressFormat.PNG, 100, it) }
            toast("已保存到 相册/Pictures/杆头轨迹/$name")
        } catch (ex: Exception) {
            toast("保存失败：${ex.message}")
        }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
}
