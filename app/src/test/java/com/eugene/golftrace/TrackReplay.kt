package com.eugene.golftrace

import org.junit.Test
import java.io.File

/**
 * 离线回放跟踪：用 ffmpeg 导出的 gray rawvideo 跑 App 同一份跟踪代码。
 * 环境变量：GT_RAW=帧文件 GT_W GT_H GT_SEED="帧,x,y" GT_OUT=csv 输出 [GT_FPS=60]
 */
class TrackReplay {
    @Test
    fun replay() {
        val raw = System.getenv("GT_RAW") ?: return
        val w = requireNotNull(System.getenv("GT_W")) { "GT_W is required" }.toInt()
        val h = requireNotNull(System.getenv("GT_H")) { "GT_H is required" }.toInt()
        val bytes = File(raw).readBytes()
        val n = bytes.size / (w * h)
        val frames = (0 until n).map { bytes.copyOfRange(it * w * h, (it + 1) * w * h) }
        val fps = System.getenv("GT_FPS")?.toDoubleOrNull()?.takeIf { it > 0.0 } ?: 60.0
        val t = LongArray(n) { (it * 1_000_000.0 / fps).toLong() }
        val clip = Clip(w, h, frames, t, Decoder.ColorAccum(1, 1))
        val tr = Track(n)
        for (s in requireNotNull(System.getenv("GT_SEED")) { "GT_SEED is required" }.split(";")) {
            val (f, x, y) = s.split(",").map { it.trim().toFloat() }
            tr.x[f.toInt()] = x; tr.y[f.toInt()] = y; tr.anchors.add(f.toInt())
        }
        val grip = Track(n)
        System.getenv("GT_GRIP")?.let { val (f, x, y) = it.split(",").map { v -> v.trim().toFloat() }; grip.x[f.toInt()] = x; grip.y[f.toInt()] = y; grip.anchors.add(f.toInt()) }
        val t0 = System.currentTimeMillis()
        val hands = Track(n)
        val res = ShaftTracker.track(clip, tr, grip, hands)
        tr.impact = res?.impact ?: -1
        println("tracked in ${System.currentTimeMillis() - t0} ms, impact=${res?.impact} end=${res?.end}")
        File(requireNotNull(System.getenv("GT_OUT")) { "GT_OUT is required" }).printWriter().use { o ->
            for (i in 0 until n) o.println("$i,${tr.x[i]},${tr.y[i]},${hands.x[i]},${hands.y[i]},${if (tr.sure[i]) 1 else 0}")
        }
        val ph = Analysis.phases(tr, clip, tr.anchors.first())
        println(Analysis.report(clip, tr, grip, tr.anchors.first(), 1f))
        println("phases=${ph?.let { "${it.takeaway} ${it.pauseStart}-${it.pauseEnd} top ${it.top} imp ${it.impact}" }}")
    }
}
