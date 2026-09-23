package com.eugene.golftrace

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 杆身跟踪（2026-09-19 用 9/18 正面机位素材调出来的）：
 * 1. 三帧差 min(|I(t)-I(t-1)|, |I(t+1)-I(t)|) 里杆身是一条细亮线，每帧用 Hough 找出线段；
 * 2. 杆头 = 线段上离挥杆中心 C（≈胸口）远的一端：手永远在胸口和杆头之间；
 * 3. 所有帧一起做 Viterbi（全局最平滑路径），手点的帧 + 起杆帧 + 自动找到的击球帧作为锚点；
 * 4. C 从握把点估计，但对点击误差很敏感（25px 就会整段失败），所以在估计值周围试 9 个 C，
 *    取 Viterbi 总代价最低的那个——等于从数据里把 C 找出来。
 * 单帧看错会被前后帧纠正；贪心逐帧推会一错到底。
 */
object ShaftTracker {

    class Result(val impact: Int, val end: Int)

    private class Path(val x: FloatArray, val y: FloatArray, val hx: FloatArray, val hy: FloatArray, val sure: BooleanArray)
    private class Seg(val ax: Float, val ay: Float, val bx: Float, val by: Float, val cov: Float)
    private class Cand(val x: Float, val y: Float, val cost: Float, val hx: Float = Float.NaN, val hy: Float = Float.NaN, val sure: Boolean = true)

    fun track(clip: Clip, head: Track, grip: Track, hands: Track? = null, onProgress: (Float) -> Unit = {}): Result? {
        val seed = head.anchors.firstOrNull() ?: return null
        if (!grip.has(seed)) return null
        val w = clip.w; val h = clip.h; val n = clip.n
        val hx0 = head.x[seed]; val hy0 = head.y[seed]
        val L = hypot(hx0 - grip.x[seed], hy0 - grip.y[seed]).coerceAtLeast(10f)
        val cx0 = grip.x[seed]; val cy0 = grip.y[seed] - 0.6f * L
        val px = max(w, h) / 960f   // 像素常数按 960 长边标定

        // ---- 起杆 / 击球：球区（地址杆头处）的亮度变化 ----
        val rr = max(4, (0.08f * L).roundToInt())
        val e = FloatArray(n)
        for (i in 1 until n) e[i] = regionDiff(clip.frames[i], clip.frames[i - 1], w, h, hx0.roundToInt(), hy0.roundToInt(), rr)
        val sorted = e.sortedArray()
        // 下限 6：手机硬解 HEVC 每个关键帧（每秒一次）有 4–5 的亮度跳动，真运动 ≥10
        val thr = max(6f, sorted[(n * 0.95).toInt().coerceAtMost(n - 1)] * 0.4f)
        // 球区的运动分成几段（burst）。击球 = 最后一段“前面安静 ≥0.6s”的运动：
        // 上杆+顶点期间杆头不在球区，球区是静的；击球后球区也是静的。
        // （最早用“第一次动→安静→再动”，真机素材开头的晃杆会被当成起杆；用“最大变化”又会选中起杆。）
        val fps0 = if (n > 1) (n - 1) * 1e6f / (clip.tUs[n - 1] - clip.tUs[0]) else 60f
        val needQuiet = (0.6f * fps0).roundToInt()
        val bursts = ArrayList<IntArray>()   // [开始, 结束]
        run {
            var i = seed + 1
            while (i < n - 1) {
                if (e[i] >= thr) { var j = i; while (j + 1 < n - 1 && e[j + 1] >= thr * 0.5f) j++; bursts.add(intArrayOf(i, j)); i = j + 1 } else i++
            }
        }
        // 击球 = 起杆之后第一段“短促”的球区变化（≤0.12s，且前面安静 ≥0.6s）：杆头 1–3 帧就扫过球区、球随即飞走；
        // 起杆、晃杆、收杆时扫过的影子都是慢慢变的。
        // （试过：“第一次动→安静→再动”被开头晃杆骗；“最大变化”选中起杆；“挥杆区能量峰附近”选中收杆的影子。）
        val shortLen = max(2, (0.12f * fps0).roundToInt())
        var impact = -1; var takeawayBurst = -1
        for (b in 1 until bursts.size) {
            val len = bursts[b][1] - bursts[b][0] + 1
            if (len <= shortLen && bursts[b][0] - bursts[b - 1][1] >= needQuiet) {
                var bi = bursts[b][0]; for (z in bursts[b][0]..bursts[b][1]) if (e[z] > e[bi]) bi = z
                impact = bi; takeawayBurst = b - 1; break
            }
        }
        if (impact < 0) for (b in bursts.indices.reversed()) {
            val prevEnd = if (b > 0) bursts[b - 1][1] else seed
            if (b > 0 && bursts[b][0] - prevEnd >= needQuiet) {
                var bi = bursts[b][0]; for (z in bursts[b][0]..bursts[b][1]) if (e[z] > e[bi]) bi = z
                impact = bi; takeawayBurst = b - 1; break
            }
        }
        // 起杆那段：击球前、且前面安静 ≥0.6s 的最近一段（跳过晃杆后的小动作）
        if (takeawayBurst >= 0) {
            var tb = takeawayBurst
            while (tb > 0 && bursts[tb][0] - bursts[tb - 1][1] < needQuiet / 2) tb--
            takeawayBurst = tb
        }
        // 起杆 = 击球前那一段运动的开始（杆头离开球区）
        var start = if (takeawayBurst >= 0) max(seed, bursts[takeawayBurst][0] - 2) else seed
        start = start.coerceIn(seed, max(seed, (if (impact > 0) impact else n - 1) - 1))
        val anchors = HashMap<Int, Pair<Float, Float>>()
        for (a in head.anchors) if (a >= start) anchors[a] = Pair(head.x[a], head.y[a])
        if (start !in anchors) anchors[start] = Pair(hx0, hy0)
        if (impact > 0 && impact !in anchors) anchors[impact] = Pair(hx0, hy0)
        // 击球后腿的轮廓会抢走杆身，只跟到击球后 ~0.05s（或最后一个手点）
        val fps = fps0
        var end = if (impact > 0) min(n - 2, impact + max(2, (0.05f * fps).roundToInt())) else n - 2
        head.anchors.lastOrNull()?.let { if (it > end) end = min(n - 2, it) }
        if (end <= start) return null

        // ---- 每帧杆身线段（与 C 无关，只算一次） ----
        val segs = ArrayList<List<Seg>>()
        val m = IntArray(w * h)
        val roiR = 2.4f * L
        for (i in start..end) {
            if (i in anchors || i == 0 || i + 1 >= n) { segs.add(emptyList()); continue }
            segs.add(segments(clip, i, m, cx0, cy0, roiR, L, px))
            if ((i - start) % 10 == 0) onProgress((i - start).toFloat() / (end - start + 1) * 0.85f)
        }

        // ---- 在估计的 C 周围试 9 个，取总代价最低 ----
        var bestPath: Path? = null; var bestCost = Float.MAX_VALUE
        for (dy in floatArrayOf(-0.2f, 0f, 0.2f)) for (dx in floatArrayOf(-0.2f, 0f, 0.2f)) {
            val cx = cx0 + dx * L; val cy = cy0 + dy * L
            val r0 = hypot(hx0 - cx, hy0 - cy)
            val layers = ArrayList<List<Cand>>()
            for (li in segs.indices) {
                val a = anchors[start + li]
                layers.add(if (a != null) listOf(Cand(a.first, a.second, 0f)) else cands(segs[li], cx, cy, r0, px))
            }
            val (path, cost) = viterbi(clip, layers, start, anchors, L, px) ?: continue
            if (cost < bestCost) { bestCost = cost; bestPath = path }
        }
        val bp = bestPath ?: return null
        val bx = bp.x; val by = bp.y
        for (i in seed + 1 until n) { head.x[i] = Float.NaN; head.y[i] = Float.NaN }
        for (i in seed + 1..start) { head.x[i] = hx0; head.y[i] = hy0 }
        for (li in bx.indices) { head.x[start + li] = bx[li]; head.y[start + li] = by[li] }
        // 把握度 + 手的位置（杆身线段近端）
        head.sure.fill(false)
        for (i in 0..start) head.sure[i] = true
        if (hands != null) { hands.clear(); for (i in 0..start) { hands.x[i] = grip.x[seed]; hands.y[i] = grip.y[seed] } }
        for (li in bx.indices) {
            val f = start + li
            head.sure[f] = bp.sure[li] || f in anchors
            if (hands != null && !bp.hx[li].isNaN()) { hands.x[f] = bp.hx[li]; hands.y[f] = bp.hy[li] }
        }
        // 5 帧中值去掉单帧跳点（锚点不动）
        val sx = head.x.copyOf(); val sy = head.y.copyOf()
        for (i in start + 2..end - 2) {
            if (i in anchors) continue
            val wx = FloatArray(5) { sx[i - 2 + it] }; val wy = FloatArray(5) { sy[i - 2 + it] }
            if (wx.any { it.isNaN() }) continue
            wx.sort(); wy.sort(); head.x[i] = wx[2]; head.y[i] = wy[2]
        }
        onProgress(1f)
        return Result(impact, end)
    }

    /** 线段 → 杆头候选：离 C 远的那端；太近/太远的丢掉。 */
    private fun cands(ss: List<Seg>, cx: Float, cy: Float, r0: Float, px: Float): List<Cand> {
        val out = ArrayList<Cand>()
        for (s in ss) {
            val ra = hypot(s.ax - cx, s.ay - cy); val rb = hypot(s.bx - cx, s.by - cy)
            for (z in 0..1) {
                val ex = if (z == 0) s.bx else s.ax; val ey = if (z == 0) s.by else s.ay
                val nx = if (z == 0) s.ax else s.bx; val ny = if (z == 0) s.ay else s.by
                val re = if (z == 0) rb else ra; val ro = if (z == 0) ra else rb
                if (re < 0.35f * r0 || re > 1.9f * r0) continue
                val cost = -s.cov * 0.6f + abs(re / r0 - 1f) * 0.3f + (if (re < ro) 0.6f else 0f)
                // 有把握 = 线段够长（看得到整根杆到末端）；下杆糊掉时只到手/半截，远端不是杆头
                out.add(Cand(ex, ey, cost, nx, ny, s.cov >= 0.75f))
            }
        }
        out.sortBy { it.cost }
        val ded = ArrayList<Cand>()
        for (c in out) {
            if (ded.all { hypot(it.x - c.x, it.y - c.y) > 8 * px }) ded.add(c)
            if (ded.size >= 14) break
        }
        return ded
    }

    /** 状态 = 候选点 或 “保持”（杆头没动）。返回最优路径和总代价。 */
    private fun viterbi(clip: Clip, layers: List<List<Cand>>, start: Int, anchors: Map<Int, Pair<Float, Float>>,
                        L: Float, px: Float): Pair<Path, Float>? {
        val w = clip.w; val h = clip.h
        val lx = ArrayList<FloatArray>(); val ly = ArrayList<FloatArray>()
        val lc = ArrayList<FloatArray>(); val lb = ArrayList<IntArray>(); val lo = ArrayList<IntArray>()
        val lhx = ArrayList<FloatArray>(); val lhy = ArrayList<FloatArray>(); val lsu = ArrayList<BooleanArray>()
        val c0 = layers[0]
        if (c0.isEmpty()) return null
        lx.add(FloatArray(c0.size) { c0[it].x }); ly.add(FloatArray(c0.size) { c0[it].y })
        lc.add(FloatArray(c0.size) { c0[it].cost }); lb.add(IntArray(c0.size) { -1 }); lo.add(IntArray(c0.size) { start })
        lhx.add(FloatArray(c0.size) { c0[it].hx }); lhy.add(FloatArray(c0.size) { c0[it].hy }); lsu.add(BooleanArray(c0.size) { c0[it].sure })
        val hr = max(2, (3 * px).roundToInt())
        for (li in 1 until layers.size) {
            val px_ = lx[li - 1]; val py_ = ly[li - 1]; val pc = lc[li - 1]; val po = lo[li - 1]
            val phx = lhx[li - 1]; val phy = lhy[li - 1]; val psu = lsu[li - 1]
            val cs = layers[li]
            val isAnchor = (start + li) in anchors
            val fCur = clip.frames[start + li]
            val nNew = cs.size; val nHold = if (isAnchor) 0 else px_.size
            val tot = nNew + nHold
            if (tot == 0) return null
            var nx = FloatArray(tot); var ny = FloatArray(tot); var nc = FloatArray(tot); var nb = IntArray(tot); var no = IntArray(tot) { start + li }
            var nhx = FloatArray(tot); var nhy = FloatArray(tot); var nsu = BooleanArray(tot)
            for (j in 0 until nNew) {
                val c = cs[j]
                var best = Float.MAX_VALUE; var bj = -1
                for (q in px_.indices) {
                    val d = hypot(c.x - px_[q], c.y - py_[q]) / L
                    val v = pc[q] + c.cost + d * d * 2f
                    if (v < best) { best = v; bj = q }
                }
                nx[j] = c.x; ny[j] = c.y; nc[j] = best; nb[j] = bj; nhx[j] = c.hx; nhy[j] = c.hy; nsu[j] = c.sure
            }
            for (q in 0 until nHold) {
                val j = nNew + q
                // “保持”= 杆头没动：拿当前帧和这个位置最初被确认的那一帧比，杆头离开后差异会一直在
                val lm = regionDiff(clip.frames[po[q]], fCur, w, h, px_[q].roundToInt(), py_[q].roundToInt(), hr)
                nx[j] = px_[q]; ny[j] = py_[q]; nc[j] = pc[q] - 0.45f + min(0.8f, lm / 15f); nb[j] = q; no[j] = po[q]; nhx[j] = phx[q]; nhy[j] = phy[q]; nsu[j] = psu[q]
            }
            if (nx.size > 50) {
                val idx = nc.indices.sortedBy { nc[it] }.take(50)
                nx = FloatArray(50) { nx[idx[it]] }; ny = FloatArray(50) { ny[idx[it]] }
                nb = IntArray(50) { nb[idx[it]] }; nc = FloatArray(50) { nc[idx[it]] }; no = IntArray(50) { no[idx[it]] }
                nhx = FloatArray(50) { nhx[idx[it]] }; nhy = FloatArray(50) { nhy[idx[it]] }; nsu = BooleanArray(50) { nsu[idx[it]] }
            }
            lx.add(nx); ly.add(ny); lc.add(nc); lb.add(nb); lo.add(no); lhx.add(nhx); lhy.add(nhy); lsu.add(nsu)
        }
        val last = lc.last()
        var j = last.indices.minByOrNull { last[it] } ?: return null
        val cost = last[j]
        val ox = FloatArray(layers.size); val oy = FloatArray(layers.size)
        val oh = FloatArray(layers.size); val ov = FloatArray(layers.size); val os = BooleanArray(layers.size)
        for (li in layers.size - 1 downTo 0) {
            ox[li] = lx[li][j]; oy[li] = ly[li][j]; oh[li] = lhx[li][j]; ov[li] = lhy[li][j]; os[li] = lsu[li][j]
            j = lb[li][j]
            if (j < 0 && li > 0) return null
        }
        return Pair(Path(ox, oy, oh, ov, os), cost)
    }

    private fun regionDiff(a: ByteArray, b: ByteArray, w: Int, h: Int, x: Int, y: Int, r: Int): Float {
        var s = 0L; var c = 0
        for (yy in max(0, y - r) until min(h, y + r)) for (xx in max(0, x - r) until min(w, x + r)) {
            val i = yy * w + xx
            s += abs((a[i].toInt() and 0xff) - (b[i].toInt() and 0xff)); c++
        }
        return if (c == 0) 0f else s.toFloat() / c
    }

    private val COS = FloatArray(120) { cos(Math.toRadians(it * 1.5)).toFloat() }
    private val SIN = FloatArray(120) { sin(Math.toRadians(it * 1.5)).toFloat() }

    /** 一帧里的杆身线段候选（Hough：每个角度取票数前 3 的 ρ）。 */
    private fun segments(clip: Clip, i: Int, m: IntArray, cx: Float, cy: Float, R: Float, L: Float, px: Float): List<Seg> {
        val w = clip.w; val h = clip.h
        val f0 = clip.frames[i - 1]; val f1 = clip.frames[i]; val f2 = clip.frames[i + 1]
        val x0 = max(0, (cx - R).toInt()); val x1 = min(w, (cx + R).toInt())
        val y0 = max(0, (cy - R).toInt()); val y1 = min(h, (cy + R).toInt())
        java.util.Arrays.fill(m, 0)
        var xs = IntArray(4096); var ys = IntArray(4096); var np = 0
        for (y in y0 until y1) for (x in x0 until x1) {
            val k = y * w + x
            val a = f0[k].toInt() and 0xff; val b = f1[k].toInt() and 0xff; val c = f2[k].toInt() and 0xff
            val v = min(abs(b - a), abs(c - b))
            m[k] = v
            if (v > 9) {
                if (np == xs.size) { xs = xs.copyOf(np * 2); ys = ys.copyOf(np * 2) }
                xs[np] = x; ys[np] = y; np++
            }
        }
        if (np < 40) return emptyList()
        val stride = max(1, np / 12000)
        val out = ArrayList<Seg>()
        val binW = 3f * px
        val nb = (2 * R / binW).toInt() + 4
        val hist = IntArray(nb)
        val sel = FloatArray(np / stride + 1)
        for (t in 0 until 120) {
            val ct = COS[t]; val st = SIN[t]
            hist.fill(0)
            var q = 0
            while (q < np) {
                val b = (((xs[q] - cx) * ct + (ys[q] - cy) * st + R) / binW).roundToInt()
                if (b in 0 until nb) hist[b]++
                q += stride
            }
            for (rank in 0 until 3) {
                var bb = -1; var bv = 0
                for (b in 0 until nb) if (hist[b] > bv) { bv = hist[b]; bb = b }
                if (bb < 0 || bv < 15 / stride) break
                hist[bb] = -1
                val rho0 = bb * binW - R
                val ux = -st; val uy = ct
                var ns = 0
                q = 0
                while (q < np) {
                    if ((((xs[q] - cx) * ct + (ys[q] - cy) * st + R) / binW).roundToInt() == bb)
                        sel[ns++] = (xs[q] - cx) * ux + (ys[q] - cy) * uy
                    q += stride
                }
                if (ns < 8) continue
                java.util.Arrays.sort(sel, 0, ns)
                var bestA = sel[0]; var bestB = sel[0]; var sa = sel[0]
                for (z in 1 until ns) {
                    if (sel[z] - sel[z - 1] > 18 * px) sa = sel[z]
                    if (sel[z] - sa > bestB - bestA) { bestA = sa; bestB = sel[z] }
                }
                if (bestB - bestA < 0.3f * L) continue
                val p0x = cx + ct * rho0; val p0y = cy + st * rho0
                val ea = extend(m, w, h, p0x, p0y, ux, uy, bestA, -1f, px)
                val eb = extend(m, w, h, p0x, p0y, ux, uy, bestB, 1f, px)
                out.add(Seg(p0x + ux * ea, p0y + uy * ea, p0x + ux * eb, p0y + uy * eb, min(eb - ea, 1.2f * L) / L))
            }
        }
        return out
    }

    /** 从线段端点沿方向用低阈值继续走（杆身在亮背景上对比度低），允许 20px 缝。 */
    private fun extend(m: IntArray, w: Int, h: Int, p0x: Float, p0y: Float, ux: Float, uy: Float,
                       sEnd: Float, sign: Float, px: Float): Float {
        var s = sEnd; var last = sEnd; var gap = 0f
        val maxGap = 20 * px
        while (gap < maxGap) {
            s += sign
            val x = (p0x + ux * s).roundToInt(); val y = (p0y + uy * s).roundToInt()
            if (x < 1 || y < 1 || x >= w - 1 || y >= h - 1) break
            var mx = 0
            for (dy in -1..1) for (dx in -1..1) mx = max(mx, m[(y + dy) * w + x + dx])
            if (mx > 5) { last = s; gap = 0f } else gap += 1f
        }
        return last
    }
}
