package com.eugene.golftrace

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

const val C_BACK = 0xFF4FC3F7.toInt()   // 上杆
const val C_DOWN = 0xFFFF5252.toInt()   // 下杆
const val C_AFTER = 0xFFBDBDBD.toInt()  // 击球后
const val C_YELLOW = 0xFFFFEB3B.toInt()
// 双手轨迹：60fps 素材上实测太乱（散在身上、下杆落到脸上），先不画；数据仍然算
const val SHOW_HANDS = false

/** 轨迹 + 参考线的绘制，屏幕和导出图共用。 */
object Overlay {
    fun colorOf(i: Int, ph: Analysis.Phases?): Int = when {
        ph == null -> C_BACK
        i <= ph.top -> C_BACK
        i <= ph.impact -> C_DOWN
        else -> C_AFTER
    }

    fun draw(c: Canvas, m: Matrix, clip: Clip, head: Track, grip: Track, hands: Track?, addr: Int,
             ph: Analysis.Phases?, cur: Int, upTo: Int, unit: Float) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val pts = FloatArray(4)
        // 黄线：地址杆身向两端延长
        if (head.has(addr) && grip.has(addr)) {
            val dx = head.x[addr] - grip.x[addr]; val dy = head.y[addr] - grip.y[addr]
            val k = 4f * max(clip.w, clip.h) / max(1f, kotlin.math.hypot(dx, dy))
            pts[0] = grip.x[addr] - dx * k; pts[1] = grip.y[addr] - dy * k
            pts[2] = head.x[addr] + dx * k; pts[3] = head.y[addr] + dy * k
            m.mapPoints(pts)
            p.color = C_YELLOW; p.strokeWidth = 2.5f * unit
            c.drawLine(pts[0], pts[1], pts[2], pts[3], p)
        }
        // 双手轨迹：空心小圈，颜色同阶段（杆头认不出的帧靠它看路径）
        val r = 2.2f * unit
        if (SHOW_HANDS && hands != null) {
            p.style = Paint.Style.STROKE; p.strokeWidth = 1.2f * unit
            for (i in 0..min(upTo, clip.n - 1)) {
                if (!hands.has(i)) continue
                if (ph != null && ph.impact > 0 && i > ph.impact) continue
                if (ph != null && ph.impact > 0 && i > ph.top && i < ph.impact) continue   // 下杆实测认成身体轮廓，不画
                pts[0] = hands.x[i]; pts[1] = hands.y[i]; m.mapPoints(pts, 0, pts, 0, 1)
                p.color = colorOf(i, ph); c.drawCircle(pts[0], pts[1], r * 0.8f, p)
            }
        }
        // 杆头轨迹：每帧一个实心点，上杆一种颜色、下杆一种颜色；点距 = 速度。只画有把握的帧
        p.style = Paint.Style.FILL
        for (i in 0..min(upTo, clip.n - 1)) {
            if (!head.has(i)) continue
            if (ph != null && ph.impact > 0 && i > ph.impact) continue   // 击球后不可靠，不画
            if (!head.sure[i]) continue                                // 杆头糊掉认不出，不猜
            // 下杆（分界到击球之间）60fps 下杆头糊成拖影，实测认成手/身体轮廓：没有反光贴纸前不画
            if (ph != null && ph.impact > 0 && i > ph.top && i < ph.impact && i !in head.anchors) continue
            if (i > 0 && head.has(i - 1) && head.x[i] == head.x[i - 1] && head.y[i] == head.y[i - 1] && i != cur) continue
            pts[0] = head.x[i]; pts[1] = head.y[i]; m.mapPoints(pts, 0, pts, 0, 1)
            p.color = 0xAA000000.toInt(); c.drawCircle(pts[0], pts[1], r + unit, p)
            p.color = colorOf(i, ph); c.drawCircle(pts[0], pts[1], r, p)
        }
        p.strokeCap = Paint.Cap.ROUND
        // 当前帧杆身（握把→杆头）
        if (cur >= 0 && head.has(cur) && grip.has(cur)) {
            pts[0] = grip.x[cur]; pts[1] = grip.y[cur]; pts[2] = head.x[cur]; pts[3] = head.y[cur]
            m.mapPoints(pts)
            p.color = Color.WHITE; p.strokeWidth = 2f * unit
            c.drawLine(pts[0], pts[1], pts[2], pts[3], p)
        }
        p.style = Paint.Style.FILL
        // 手动点
        for (tr in listOf(head, grip)) for (a in tr.anchors) {
            pts[0] = tr.x[a]; pts[1] = tr.y[a]; m.mapPoints(pts, 0, pts, 0, 1)
            p.color = 0xFF00E676.toInt(); c.drawCircle(pts[0], pts[1], 3f * unit, p)
        }
        // 当前点
        if (cur >= 0) for ((tr, col) in listOf(head to Color.WHITE, grip to 0xFFFF9800.toInt())) {
            if (!tr.has(cur)) continue
            pts[0] = tr.x[cur]; pts[1] = tr.y[cur]; m.mapPoints(pts, 0, pts, 0, 1)
            p.style = Paint.Style.STROKE; p.strokeWidth = 2f * unit; p.color = col
            c.drawCircle(pts[0], pts[1], 9f * unit, p)
            p.style = Paint.Style.FILL
        }
    }
}

/** 显示一帧（或叠加图），双指缩放 / 单指拖动，单击回调图像坐标。 */
@SuppressLint("ClickableViewAccessibility")
class FrameView(ctx: Context) : View(ctx) {
    var bitmap: Bitmap? = null
        set(v) { val first = field == null || v == null || field!!.width != v.width || field!!.height != v.height; field = v; if (first) fit(); invalidate() }
    var drawer: ((Canvas, Matrix) -> Unit)? = null
    var onTapImage: ((Float, Float) -> Unit)? = null

    private val base = Matrix()
    private val user = Matrix()
    private val m = Matrix()
    private val inv = Matrix()
    private val bmpPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    fun fit() {
        val b = bitmap ?: return
        if (width == 0) return
        val s = min(width.toFloat() / b.width, height.toFloat() / b.height)
        base.setScale(s, s)
        base.postTranslate((width - b.width * s) / 2, (height - b.height * s) / 2)
        user.reset()
    }

    override fun onSizeChanged(w: Int, h: Int, ow: Int, oh: Int) { fit() }

    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            user.postScale(d.scaleFactor, d.scaleFactor, d.focusX, d.focusY); invalidate(); return true
        }
    })
    private val gest = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            user.postTranslate(-dx, -dy); invalidate(); return true
        }
        override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
            matrix().invert(inv)
            val p = floatArrayOf(e.x, e.y); inv.mapPoints(p)
            onTapImage?.invoke(p[0], p[1]); return true
        }
        override fun onDoubleTap(e: MotionEvent): Boolean {
            val v = FloatArray(9); user.getValues(v)
            if (v[0] > 1.5f) user.reset() else user.postScale(3f, 3f, e.x, e.y)
            invalidate(); return true
        }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e); gest.onTouchEvent(e); return true
    }

    private fun matrix(): Matrix { m.set(base); m.postConcat(user); return m }

    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        val b = bitmap ?: return
        val mm = matrix()
        c.save()
        val r = android.graphics.RectF(0f, 0f, b.width.toFloat(), b.height.toFloat()); mm.mapRect(r)
        c.clipRect(r)
        c.drawBitmap(b, mm, bmpPaint)
        drawer?.invoke(c, mm)
        c.restore()
    }
}

/** 运动能量曲线：点击选挥杆。 */
class EnergyView(ctx: Context) : View(ctx) {
    var energy = FloatArray(0)
    var times = LongArray(0)
    var peaks = listOf<Long>()
    var selected = -1L
    var progress = 1f
    var onPick: ((Long) -> Unit)? = null
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF1E1E1E.toInt())
        val n = energy.size
        if (n < 2) return
        val tMax = max(1L, times[n - 1]).toFloat()
        val eMax = (energy.maxOrNull() ?: 1f).coerceAtLeast(1e-3f)
        p.color = 0xFF80CBC4.toInt(); p.strokeWidth = 1.5f; p.style = Paint.Style.STROKE
        val path = Path()
        for (i in 0 until n) {
            val x = times[i] / tMax * width * progress
            val y = height - energy[i] / eMax * (height - 8)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        c.drawPath(path, p)
        p.style = Paint.Style.FILL
        for (t in peaks) {
            val x = t / tMax * width * progress
            p.color = if (t == selected) C_YELLOW else 0xFFFF5252.toInt()
            c.drawRect(x - 3, 0f, x + 3, height.toFloat(), p)
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(e: MotionEvent): Boolean {
        if (e.action == MotionEvent.ACTION_UP && energy.size > 1) {
            val tMax = times[times.size - 1].toFloat() * progress
            val t = (e.x / width * tMax).toLong()
            // 点在峰附近就吸附到峰
            val near = peaks.minByOrNull { kotlin.math.abs(it - t) }
            val pick = if (near != null && kotlin.math.abs(near - t) < tMax * 0.03f) near else t
            onPick?.invoke(pick)
        }
        return true
    }
}

/** 3D：x、y 为画面坐标，第三轴为时间。单指拖动旋转，双指缩放。 */
@SuppressLint("ClickableViewAccessibility")
class Trace3DView(ctx: Context) : View(ctx) {
    var clip: Clip? = null
    var head: Track? = null
    var phases: Analysis.Phases? = null
    private var yaw = -0.6f; private var pitch = 0.35f; private var zoom = 1f
    private var lx = 0f; private var ly = 0f
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val scaleDet = ScaleGestureDetector(ctx, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean { zoom = (zoom * d.scaleFactor).coerceIn(0.3f, 5f); invalidate(); return true }
    })

    override fun onTouchEvent(e: MotionEvent): Boolean {
        scaleDet.onTouchEvent(e)
        if (e.pointerCount == 1) when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> { lx = e.x; ly = e.y }
            MotionEvent.ACTION_MOVE -> {
                yaw += (e.x - lx) * 0.01f; pitch = (pitch + (e.y - ly) * 0.01f).coerceIn(-1.5f, 1.5f)
                lx = e.x; ly = e.y; invalidate()
            }
        }
        return true
    }

    private fun proj(x: Float, y: Float, z: Float, out: FloatArray) {
        val cx = cos(yaw); val sx = sin(yaw); val cp = cos(pitch); val sp = sin(pitch)
        val x1 = x * cx + z * sx; val z1 = -x * sx + z * cx
        val y1 = y * cp - z1 * sp
        val s = min(width, height) * 0.42f * zoom
        out[0] = width / 2f + x1 * s; out[1] = height / 2f + y1 * s
    }

    override fun onDraw(c: Canvas) {
        c.drawColor(0xFF121212.toInt())
        val cl = clip ?: return; val tr = head ?: return
        val idx = (0 until cl.n).filter { tr.has(it) }
        if (idx.size < 2) return
        val sc = 2f / max(cl.w, cl.h)
        val t0 = cl.tUs[idx.first()]; val t1 = max(t0 + 1, cl.tUs[idx.last()])
        val o = FloatArray(2); val o2 = FloatArray(2)
        fun P(i: Int, out: FloatArray) = proj((tr.x[i] - cl.w / 2f) * sc, (tr.y[i] - cl.h / 2f) * sc,
            ((cl.tUs[i] - t0).toFloat() / (t1 - t0) - 0.5f) * 1.6f, out)
        // 画面框（t=0 与 t=末）和时间轴
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = 0xFF444444.toInt()
        for (z in floatArrayOf(-0.8f, 0.8f)) {
            val hw = cl.w * sc / 2; val hh = cl.h * sc / 2
            val cs = arrayOf(floatArrayOf(-hw, -hh), floatArrayOf(hw, -hh), floatArrayOf(hw, hh), floatArrayOf(-hw, hh))
            for (k in 0 until 4) {
                proj(cs[k][0], cs[k][1], z, o); proj(cs[(k + 1) % 4][0], cs[(k + 1) % 4][1], z, o2)
                c.drawLine(o[0], o[1], o2[0], o2[1], p)
            }
        }
        p.strokeWidth = 4f; p.strokeCap = Paint.Cap.ROUND
        var prev = -1
        for (i in idx) {
            if (prev >= 0 && i == prev + 1) {
                P(prev, o); P(i, o2); p.color = Overlay.colorOf(i, phases)
                c.drawLine(o[0], o[1], o2[0], o2[1], p)
            }
            prev = i
        }
        // 在底面投影一份（去掉时间轴 = 普通轨迹图）
        p.strokeWidth = 1.5f
        prev = -1
        for (i in idx) {
            if (prev >= 0 && i == prev + 1) {
                proj((tr.x[prev] - cl.w / 2f) * sc, (tr.y[prev] - cl.h / 2f) * sc, 0.8f, o)
                proj((tr.x[i] - cl.w / 2f) * sc, (tr.y[i] - cl.h / 2f) * sc, 0.8f, o2)
                p.color = Overlay.colorOf(i, phases) and 0x80FFFFFF.toInt()
                c.drawLine(o[0], o[1], o2[0], o2[1], p)
            }
            prev = i
        }
        p.style = Paint.Style.FILL; p.color = Color.GRAY; p.textSize = 30f
        c.drawText("纵深轴 = 时间（单机位无法测真实深度）", 20f, height - 20f, p)
    }
}
