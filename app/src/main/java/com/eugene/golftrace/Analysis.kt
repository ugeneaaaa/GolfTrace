package com.eugene.golftrace

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** 一次挥杆片段的解码结果。 */
class Clip(
    val w: Int, val h: Int,
    val frames: List<ByteArray>,
    val tUs: LongArray,
    val color: Decoder.ColorAccum,
) {
    val n get() = frames.size
}

/** 一条轨迹：每帧一个点（可能为空），anchors 为手动点过的帧。 */
class Track(n: Int) {
    val x = FloatArray(n) { Float.NaN }
    val y = FloatArray(n) { Float.NaN }
    val anchors = sortedSetOf<Int>()
    var lostAt = -1
    var impact = -1   // 自动找到的击球帧（ShaftTracker）
    val sure = BooleanArray(n) { true }   // 这帧的杆头是否有把握（下杆糊掉的帧为 false）
    fun has(i: Int) = !x[i].isNaN()
    fun clear() { x.fill(Float.NaN); y.fill(Float.NaN); anchors.clear(); impact = -1; lostAt = -1 }
}

object Analysis {

    // ---------- 第一遍：运动能量，找挥杆 ----------

    fun motionEnergy(prev: ByteArray?, cur: ByteArray): Float {
        if (prev == null) return 0f
        var s = 0L
        for (i in cur.indices) s += abs((cur[i].toInt() and 0xff) - (prev[i].toInt() and 0xff))
        return s.toFloat() / cur.size
    }

    /**
     * 找挥杆（画面）：整幅画面里挥杆很小，按“能量最大”找会找到走动/换球。
     * 改为认挥杆的节奏：静止 ≥1s → 小段运动 0.3–1.5s（上杆）→ 静止 0.3–2s（顶点）→ 再运动（下杆）。
     * 返回估计的击球时间（µs）。
     */
    fun findSwingPeaks(e: FloatArray, t: LongArray): List<Long> {
        if (e.size < 60 || t.last() <= 0) return emptyList()
        // 0.1s 一格
        val nb = (t.last() / 100_000).toInt() + 1
        val sum = FloatArray(nb); val cnt = IntArray(nb)
        for (i in e.indices) { val k = (t[i] / 100_000).toInt().coerceIn(0, nb - 1); sum[k] += e[i]; cnt[k]++ }
        val b = FloatArray(nb) { if (cnt[it] > 0) sum[it] / cnt[it] else 0f }
        // 局部安静水平：前后 10s 的 20 分位
        val q = FloatArray(nb)
        for (i in 0 until nb) {
            val w = b.copyOfRange(max(0, i - 100), min(nb, i + 100)); w.sort()
            q[i] = w[(w.size * 0.2).toInt()]
        }
        val act = BooleanArray(nb) { b[it] > q[it] * 1.7f + 0.02f }
        class Run(val a: Boolean, val s: Int, val en: Int) { val len: Int get() = en - s + 1 }
        val runs = ArrayList<Run>()
        var i = 0
        while (i < nb) { var j = i; while (j + 1 < nb && act[j + 1] == act[i]) j++; runs.add(Run(act[i], i, j)); i = j + 1 }
        val out = ArrayList<Long>()
        for (k in 0 until runs.size - 3) {
            val r0 = runs[k]; val r1 = runs[k + 1]; val r2 = runs[k + 2]; val r3 = runs[k + 3]
            if (r0.a || !r1.a || r2.a || !r3.a) continue
            if (r0.len < 10 || r1.len !in 3..15 || r2.len !in 3..20) continue
            var m = 0f; for (z in r1.s..r1.en) m += b[z]; m /= r1.len
            if (m > q[r1.s] * 8 + 0.3f) continue   // 太大 = 走动
            out.add((r3.s + 2.5f).toLong() * 100_000)
        }
        return out
    }

    /** 两组候选合并，相距 < gap 的只留一个（优先留 a）。 */
    fun mergeTimes(a: List<Long>, b: List<Long>, gapUs: Long = 2_500_000): List<Long> {
        val out = ArrayList(a)
        for (x in b) if (out.none { abs(it - x) < gapUs }) out.add(x)
        return out.sorted()
    }

    fun smooth(a: FloatArray, r: Int): FloatArray {
        val o = FloatArray(a.size)
        for (i in a.indices) {
            var s = 0f; var c = 0
            for (k in max(0, i - r)..min(a.size - 1, i + r)) { s += a[k]; c++ }
            o[i] = s / c
        }
        return o
    }

    // ---------- 跟踪 ----------

    class TrackParams(
        var brightTarget: Boolean = false, // 杆头贴了反光胶带：偏好亮点
    )

    /**
     * 从 track 在 from 帧的已知点出发，向前跟踪到 to 帧（不含已有 anchor 的帧会被覆盖）。
     * 打分 = 模板相似度 + 帧差运动量 + (可选)亮度 − 偏离预测的惩罚。
     * 速度越快模板越不可信（运动模糊），权重自动转向帧差。
     */
    fun trackForward(clip: Clip, tr: Track, from: Int, to: Int, p: TrackParams) {
        if (!tr.has(from)) return
        val w = clip.w; val h = clip.h
        val scale = max(w, h) / 480f
        val half = max(4, (7 * scale).roundToInt())
        val tpl = patch(clip.frames[from], w, h, tr.x[from], tr.y[from], half)
        var vx = 0f; var vy = 0f
        if (from > 0 && tr.has(from - 1)) { vx = tr.x[from] - tr.x[from - 1]; vy = tr.y[from] - tr.y[from - 1] }
        var px = tr.x[from]; var py = tr.y[from]
        val last = min(to, clip.n - 1)
        for (i in from + 1..last) {
            if (i in tr.anchors) break
            val cur = clip.frames[i]; val prev = clip.frames[i - 1]
            val speed = hypot(vx, vy)
            val qx = px + vx; val qy = py + vy
            val R = (10 * scale + 1.6f * speed).coerceAtMost(max(w, h) * 0.3f)
            val step = if (R > 40 * scale) 2 else 1
            val blur = (speed / (12 * scale)).coerceIn(0f, 1f) // 0=静止 1=很快
            val wT = 1.0f - 0.7f * blur
            val wM = 0.3f + 1.2f * blur
            val wB = if (p.brightTarget) 1.0f else 0f
            var best = -1e9f; var bx = qx; var by = qy; var bSad = 0f; var bMot = 0f
            val x0 = max(half, (qx - R).toInt()); val x1 = min(w - 1 - half, (qx + R).toInt())
            val y0 = max(half, (qy - R).toInt()); val y1 = min(h - 1 - half, (qy + R).toInt())
            var cy = y0
            while (cy <= y1) {
                var cx = x0
                while (cx <= x1) {
                    val d2 = ((cx - qx) * (cx - qx) + (cy - qy) * (cy - qy)) / (R * R)
                    if (d2 <= 1f) {
                        var sad = 0; var mot = 0; var lum = 0; var cnt = 0
                        var k = 0
                        var yy = -half
                        while (yy <= half) {
                            var xx = -half
                            val row = (cy + yy) * w
                            while (xx <= half) {
                                val a = cur[row + cx + xx].toInt() and 0xff
                                val b = prev[row + cx + xx].toInt() and 0xff
                                sad += abs(a - (tpl[k].toInt() and 0xff))
                                mot += abs(a - b)
                                lum += a
                                k++; cnt++
                                xx++
                            }
                            yy++
                        }
                        val s = -wT * sad / (cnt * 64f) + wM * mot / (cnt * 32f) +
                                wB * lum / (cnt * 255f) - 0.6f * d2
                        if (s > best) { best = s; bx = cx.toFloat(); by = cy.toFloat(); bSad = sad / (cnt * 255f); bMot = mot / (cnt * 255f) }
                    }
                    cx += step
                }
                cy += step
            }
            // 跟丢判定：慢时外观不像、快时没有运动、或贴到画面边缘（杆头出画面）→ 停下，留空给手动补
            val edge = bx <= half + 1 || by <= half + 1 || bx >= w - half - 2 || by >= h - half - 2
            val lost = edge || (blur < 0.5f && bSad > 0.30f) || (blur >= 0.5f && bMot < 0.03f)
            if (lost) { tr.lostAt = i; return }
            val nvx = bx - px; val nvy = by - py
            vx = 0.6f * nvx + 0.4f * vx; vy = 0.6f * nvy + 0.4f * vy
            px = bx; py = by
            tr.x[i] = bx; tr.y[i] = by
            // 慢的时候更新模板，快的时候保留原模板（模糊帧会污染模板）
            // 只在“很像”时慢慢更新模板：更新太勤会沿杆身漂到手上，完全不更新又跟不上杆面转动
            if (blur < 0.5f && bSad < 0.10f) {
                val np = patch(cur, w, h, bx, by, half)
                for (j in tpl.indices) tpl[j] = (((tpl[j].toInt() and 0xff) * 7 + (np[j].toInt() and 0xff)) / 8).toByte()
            }
        }
    }

    /** 从每个手动点往后跟踪，直到下一个手动点。 */
    fun trackAll(clip: Clip, tr: Track, p: TrackParams) {
        val a = tr.anchors.toList()
        if (a.isEmpty()) return
        tr.lostAt = -1
        for (k in a.indices) {
            val end = if (k + 1 < a.size) a[k + 1] - 1 else clip.n - 1
            for (i in a[k] + 1..end) { tr.x[i] = Float.NaN; tr.y[i] = Float.NaN }
            trackForward(clip, tr, a[k], end, p)
        }
    }

    /** 补空档：用所有已知点（手动点 + 自动跟住的段）做 Catmull-Rom 插值，只填缺失帧。 */
    fun interpolate(tr: Track, n: Int) {
        val known = (0 until n).filter { tr.has(it) }
        if (known.size < 2) return
        for (k in 0 until known.size - 1) {
            val i1 = known[k]; val i2 = known[k + 1]
            if (i2 - i1 < 2) continue
            val i0 = known[max(0, k - 1)]; val i3 = known[min(known.size - 1, k + 2)]
            for (f in i1 + 1 until i2) {
                val s = (f - i1).toFloat() / (i2 - i1)
                tr.x[f] = cr(tr.x[i0], tr.x[i1], tr.x[i2], tr.x[i3], s)
                tr.y[f] = cr(tr.y[i0], tr.y[i1], tr.y[i2], tr.y[i3], s)
            }
        }
    }

    private fun cr(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
        val t2 = t * t; val t3 = t2 * t
        return 0.5f * ((2 * p1) + (-p0 + p2) * t + (2 * p0 - 5 * p1 + 4 * p2 - p3) * t2 + (-p0 + 3 * p1 - 3 * p2 + p3) * t3)
    }

    private fun patch(f: ByteArray, w: Int, h: Int, x: Float, y: Float, half: Int): ByteArray {
        val cx = x.roundToInt().coerceIn(half, w - 1 - half)
        val cy = y.roundToInt().coerceIn(half, h - 1 - half)
        val o = ByteArray((2 * half + 1) * (2 * half + 1))
        var k = 0
        for (yy in -half..half) for (xx in -half..half) o[k++] = f[(cy + yy) * w + cx + xx]
        return o
    }

    // ---------- 分段与数据 ----------

    class Phases(
        val takeaway: Int, val pauseStart: Int, val pauseEnd: Int,
        val top: Int, val impact: Int,
    )

    /**
     * 只用杆头轨迹分段：
     * 起杆 = 第一次离开地址点超过阈值；顶点 = 离地址点最远；击球 = 顶点之后回到离地址点最近；
     * 停顿 = 起杆到击球之间最长的一段低速帧。
     */
    fun phases(tr: Track, clip: Clip, addr: Int): Phases? {
        val n = clip.n
        if (!tr.has(addr)) return null
        val ax = tr.x[addr]; val ay = tr.y[addr]
        val span = max(clip.w, clip.h)
        val dist = FloatArray(n) { if (tr.has(it)) hypot(tr.x[it] - ax, tr.y[it] - ay) else Float.NaN }
        var takeaway = -1
        for (i in addr until n) if (!dist[i].isNaN() && dist[i] > span * 0.03f) { takeaway = i; break }
        if (takeaway < 0) return null
        // 往回找到真正开始动的那帧
        while (takeaway > addr + 1 && !dist[takeaway - 1].isNaN() && dist[takeaway - 1] > span * 0.008f) takeaway--
        var top = takeaway
        // 顶点：离地址最远，但要在“回到地址附近”之前
        var i = takeaway
        var farthest = 0f
        while (i < n) {
            if (!dist[i].isNaN()) {
                if (dist[i] > farthest) { farthest = dist[i]; top = i }
                if (farthest > span * 0.15f && dist[i] < farthest * 0.25f) break
            }
            i++
        }
        var impact = top
        var best = Float.MAX_VALUE
        var j = top
        while (j < n) {
            if (!dist[j].isNaN() && dist[j] < best) { best = dist[j]; impact = j }
            if (best < farthest * 0.25f && !dist[j].isNaN() && dist[j] > best + span * 0.1f) break
            j++
        }
        // 速度（像素/帧，按帧间隔归一到 60fps）
        val sp = FloatArray(n)
        for (k in 1 until n) {
            if (tr.has(k) && tr.has(k - 1)) {
                val dt = (clip.tUs[k] - clip.tUs[k - 1]).coerceAtLeast(1) / 16_667f
                sp[k] = hypot(tr.x[k] - tr.x[k - 1], tr.y[k] - tr.y[k - 1]) / dt
            }
        }
        val sps = smooth(sp, 3)
        // 顶点附近跟踪有 ~1% 画面的抖动，低于这个速度算静止
        val slow = span * 0.012f
        var ps = top; var pe = top; var bestLen = 0
        var k = takeaway
        while (k < impact) {
            if (sps[k] < slow) {
                var e = k
                while (e + 1 < impact && sps[e + 1] < slow) e++
                if (e - k + 1 > bestLen) { bestLen = e - k + 1; ps = k; pe = e }
                k = e + 1
            } else k++
        }
        return Phases(takeaway, ps, pe, top, impact)
    }

    fun angleDeg(x0: Float, y0: Float, x1: Float, y1: Float): Float {
        // 图像坐标 y 向下，这里转成常规：与水平线夹角 0..180
        var a = Math.toDegrees(atan2((y0 - y1).toDouble(), (x1 - x0).toDouble())).toFloat()
        if (a < 0) a += 180f
        return a
    }

    fun frameAtTime(clip: Clip, tUs: Long): Int {
        var best = 0; var bd = Long.MAX_VALUE
        for (i in 0 until clip.n) { val d = abs(clip.tUs[i] - tUs); if (d < bd) { bd = d; best = i } }
        return best
    }

    /**
     * 节奏分段（按杆头轨迹）。停顿时 ShaftTracker 的“保持”状态让位置完全不动，比画面能量可靠
     * （能量法在手机硬解 HEVC 上会被每秒一次的关键帧跳动干扰）。
     * top = 上杆/下杆分界：停顿之后杆头若还在远离地址位（二次上杆），分界往后推到最远那帧。
     */
    class Tempo(val takeaway: Int, val stillStart: Int, val stillEnd: Int, val top: Int, val impact: Int, val extraUp: Float)

    fun tempo(clip: Clip, head: Track, addr: Int): Tempo? {
        val impact = head.impact
        if (impact <= addr + 2 || !head.has(addr)) return null
        val span = max(clip.w, clip.h).toFloat()
        val n = impact + 1
        val d = FloatArray(n) { if (head.has(it)) hypot(head.x[it] - head.x[addr], head.y[it] - head.y[addr]) else Float.NaN }
        val sp = FloatArray(n)
        for (i in addr + 1 until n) if (head.has(i) && head.has(i - 1)) {
            val dt = (clip.tUs[i] - clip.tUs[i - 1]).coerceAtLeast(1) / 16_667f
            sp[i] = hypot(head.x[i] - head.x[i - 1], head.y[i] - head.y[i - 1]) / dt
        }
        // 5 帧中值：去掉停顿期间偶发的跟踪跳动
        val med = FloatArray(n) { i -> val w = FloatArray(5) { k -> sp[(i - 2 + k).coerceIn(0, n - 1)] }; w.sort(); w[2] }
        val slow = span * 0.01f
        var takeaway = addr
        while (takeaway < impact && (d[takeaway].isNaN() || d[takeaway] < span * 0.03f)) takeaway++
        while (takeaway > addr + 1 && sp[takeaway - 1] > 0.5f) takeaway--
        var ss = -1; var se = -1; var i = takeaway + 1
        while (i < impact) {
            if (med[i] < slow) {
                var j = i; while (j + 1 < impact && med[j + 1] < slow) j++
                if (j - i > se - ss) { ss = i; se = j }
                i = j + 1
            } else i++
        }
        if (se - ss < 3) { ss = -1; se = -1 }
        // 分界：停顿结束后到击球前，离地址最远的那帧（比停顿结束时多 2% 以上才算“继续上行”）
        var top = if (se >= 0) se else -1
        var extra = 0f
        val from = if (se >= 0) se else takeaway
        var far = from
        for (k in from until impact) if (!d[k].isNaN() && d[k] > d[far]) far = k
        if (se >= 0) {
            if (d[far] - d[se] > span * 0.02f) { top = far; extra = (d[far] - d[se]) / max(1f, d[far]) }
        } else top = far
        return Tempo(takeaway, ss, se, top, impact, extra)
    }

    /** 生成数据文本（只列量出来的数）；timeScale = 视频时间戳 → 真实时间的系数（慢动作用）。 */
    fun report(clip: Clip, head: Track, grip: Track, addr: Int, timeScale: Float): String {
        val tp = tempo(clip, head, addr) ?: return "还没有击球帧：先点“自动跟踪”"
        fun s(a: Int, b: Int) = (clip.tUs[b] - clip.tUs[a]) * timeScale / 1e6f
        val sb = StringBuilder()
        if (tp.stillStart >= 0) {
            sb.append("起杆→顶点静止  %.2f s\n".format(s(tp.takeaway, tp.stillStart)))
            sb.append("顶点静止        %.2f s\n".format(s(tp.stillStart, tp.stillEnd)))
            if (tp.top > tp.stillEnd) sb.append("停顿后继续上行  %.2f s（+%.0f%% 行程）\n".format(s(tp.stillEnd, tp.top), tp.extraUp * 100))
            else sb.append("停顿后继续上行  无\n")
            sb.append("下杆→击球      %.2f s\n".format(s(tp.top, tp.impact)))
        } else {
            sb.append("上杆            %.2f s（无静止段）\n".format(s(tp.takeaway, tp.top)))
            sb.append("下杆→击球      %.2f s\n".format(s(tp.top, tp.impact)))
        }
        if (grip.has(addr) && head.has(addr)) {
            val a0 = angleDeg(grip.x[addr], grip.y[addr], head.x[addr], head.y[addr])
            sb.append("地址杆身角      %.1f°\n".format(a0))
            val f = frameAtTime(clip, clip.tUs[tp.impact] - (100_000 / timeScale).toLong())
            if (grip.has(f) && head.has(f)) {
                val a1 = angleDeg(grip.x[f], grip.y[f], head.x[f], head.y[f])
                sb.append("击球前0.10s杆身 %.1f°（差 %+.1f°）\n".format(a1, a1 - a0))
            }
        }
        sb.append("帧: 起杆 ${tp.takeaway} 静止 ${tp.stillStart}-${tp.stillEnd} 分界 ${tp.top} 击球 ${tp.impact}")
        return sb.toString()
    }

    fun rms(a: FloatArray): Float = sqrt(a.fold(0f) { s, v -> s + v * v } / max(1, a.size))
}
