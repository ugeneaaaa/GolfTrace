package com.eugene.golftrace

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.net.Uri

/** 视频基本信息。rotation 是播放时需要顺时针转的角度。 */
data class VideoInfo(val srcW: Int, val srcH: Int, val rotation: Int, val durationUs: Long, val fps: Float)

/**
 * 用 MediaCodec 硬解，只取 Y（亮度）平面并降采样成小灰度图；可选同时累积彩色多重曝光。
 * 不依赖任何第三方库。
 */
object Decoder {

    fun info(ctx: Context, uri: Uri): VideoInfo {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(ctx, uri)
            val w = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toInt() ?: 0
            val h = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toInt() ?: 0
            val rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toInt() ?: 0
            val dur = (r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLong() ?: 0L) * 1000
            val cnt = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_FRAME_COUNT)?.toLong() ?: 0L
            val fps = if (dur > 0 && cnt > 0) cnt * 1_000_000f / dur else 30f
            return VideoInfo(w, h, ((rot % 360) + 360) % 360, dur, fps)
        } finally {
            r.release()
        }
    }

    /** 输出尺寸（已旋转），长边 = longSide。 */
    fun outSize(info: VideoInfo, longSide: Int): Pair<Int, Int> {
        val sw = info.srcW.toFloat(); val sh = info.srcH.toFloat()
        val s = longSide / maxOf(sw, sh)
        var uw = maxOf(2, (sw * s).toInt()); var uh = maxOf(2, (sh * s).toInt())
        return if (info.rotation == 90 || info.rotation == 270) Pair(uh, uw) else Pair(uw, uh)
    }

    class ColorAccum(val w: Int, val h: Int) {
        val first = IntArray(w * h)
        val dark = IntArray(w * h)
        val bright = IntArray(w * h)
        val darkKey = IntArray(w * h) { 256 }
        val brightKey = IntArray(w * h) { -1 }
        var hasFirst = false
    }

    /**
     * 解码 [startUs, endUs] 内每一帧。onFrame(tUs, gray) 返回 false 可提前停止。
     * gray 为 outW*outH 字节，已按 rotation 转正。
     */
    fun decode(
        ctx: Context, uri: Uri, info: VideoInfo, longSide: Int,
        startUs: Long, endUs: Long, color: ColorAccum?,
        onFrame: (Long, ByteArray) -> Boolean
    ) {
        val (outW, outH) = outSize(info, longSide)
        val ex = MediaExtractor()
        ex.setDataSource(ctx, uri, null)
        var track = -1
        for (i in 0 until ex.trackCount) {
            val m = ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (m.startsWith("video/")) { track = i; break }
        }
        require(track >= 0) { "没有视频轨" }
        ex.selectTrack(track)
        val fmt = ex.getTrackFormat(track)
        fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
        val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
        codec.configure(fmt, null, null, 0)
        codec.start()
        if (startUs > 0) ex.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)

        val bi = MediaCodec.BufferInfo()
        var inputDone = false
        var stop = false
        var map: SampleMap? = null
        try {
            while (!stop) {
                if (!inputDone) {
                    val ii = codec.dequeueInputBuffer(5000)
                    if (ii >= 0) {
                        val buf = codec.getInputBuffer(ii)!!
                        val n = ex.readSampleData(buf, 0)
                        if (n < 0 || ex.sampleTime > endUs && ex.sampleTime - endUs > 200_000) {
                            codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0)
                            ex.advance()
                        }
                    }
                }
                val oi = codec.dequeueOutputBuffer(bi, 5000)
                if (oi >= 0) {
                    val eos = bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    val t = bi.presentationTimeUs
                    if (bi.size > 0 && t >= startUs && t <= endUs) {
                        val img = codec.getOutputImage(oi)
                        if (img != null) {
                            if (map == null || !map.matches(img)) map = SampleMap(img, info.rotation, outW, outH)
                            val gray = map.gray(img)
                            color?.let { map.accumulate(img, it) }
                            img.close()
                            if (!onFrame(t, gray)) stop = true
                        }
                    }
                    codec.releaseOutputBuffer(oi, false)
                    if (eos || t > endUs) stop = true
                }
            }
        } finally {
            try { codec.stop() } catch (_: Exception) {}
            codec.release()
            ex.release()
        }
    }

    /** 预先算好每个输出像素对应源图里的字节偏移，逐帧只做查表。 */
    private class SampleMap(img: Image, rotation: Int, val outW: Int, val outH: Int) {
        val fmt = img.format
        val w = img.width; val h = img.height
        val yRow = img.planes[0].rowStride; val yPix = img.planes[0].pixelStride
        val uRow = img.planes[1].rowStride; val uPix = img.planes[1].pixelStride
        val vRow = img.planes[2].rowStride; val vPix = img.planes[2].pixelStride
        // P010 等 16 位格式：取高字节
        val hi = if (fmt == ImageFormat.YCBCR_P010 || yPix == 2 && uPix == 4) 1 else 0
        val yOff = IntArray(outW * outH)
        val uOff = IntArray(outW * outH)
        val vOff = IntArray(outW * outH)

        init {
            val crop = img.cropRect
            val rot = rotation
            // 未旋转的降采样尺寸
            val uw = if (rot == 90 || rot == 270) outH else outW
            val uh = if (rot == 90 || rot == 270) outW else outH
            for (oy in 0 until outH) for (ox in 0 until outW) {
                val ux: Int; val uy: Int
                when (rot) {
                    90 -> { ux = oy; uy = uh - 1 - ox }
                    180 -> { ux = uw - 1 - ox; uy = uh - 1 - oy }
                    270 -> { ux = uw - 1 - oy; uy = ox }
                    else -> { ux = ox; uy = oy }
                }
                val sx = crop.left + ((ux + 0.5f) * crop.width() / uw).toInt().coerceIn(0, crop.width() - 1)
                val sy = crop.top + ((uy + 0.5f) * crop.height() / uh).toInt().coerceIn(0, crop.height() - 1)
                val i = oy * outW + ox
                yOff[i] = sy * yRow + sx * yPix + hi
                uOff[i] = (sy / 2) * uRow + (sx / 2) * uPix + hi
                vOff[i] = (sy / 2) * vRow + (sx / 2) * vPix + hi
            }
        }

        fun matches(img: Image) = img.width == w && img.height == h && img.format == fmt &&
                img.planes[0].rowStride == yRow && img.planes[1].rowStride == uRow

        fun gray(img: Image): ByteArray {
            val yb = img.planes[0].buffer
            val out = ByteArray(outW * outH)
            // 小输出直接随机访问 ByteBuffer 即可
            for (i in out.indices) out[i] = yb.get(yOff[i])
            return out
        }

        fun accumulate(img: Image, c: ColorAccum) {
            val yb = img.planes[0].buffer; val ub = img.planes[1].buffer; val vb = img.planes[2].buffer
            for (i in 0 until outW * outH) {
                val y = yb.get(yOff[i]).toInt() and 0xff
                val needDark = y < c.darkKey[i]
                val needBright = y > c.brightKey[i]
                if (!c.hasFirst || needDark || needBright) {
                    val u = (ub.get(uOff[i]).toInt() and 0xff) - 128
                    val v = (vb.get(vOff[i]).toInt() and 0xff) - 128
                    val r = (y + 1.402f * v).toInt().coerceIn(0, 255)
                    val g = (y - 0.344f * u - 0.714f * v).toInt().coerceIn(0, 255)
                    val b = (y + 1.772f * u).toInt().coerceIn(0, 255)
                    val argb = (0xff shl 24) or (r shl 16) or (g shl 8) or b
                    if (!c.hasFirst) c.first[i] = argb
                    if (needDark) { c.darkKey[i] = y; c.dark[i] = argb }
                    if (needBright) { c.brightKey[i] = y; c.bright[i] = argb }
                }
            }
            c.hasFirst = true
        }
    }

    /**
     * 击球声：5ms 能量相对前 100ms 中位数突增 8 倍以上、且处在整段最响的 0.5% 里。
     * 返回时间（µs）；没有音轨返回空。BMD 的文件音画可能不同步（9/18 素材晚 ~1s），只当候选用。
     */
    fun audioOnsets(ctx: Context, uri: Uri): List<Long> {
        val ex = MediaExtractor()
        try {
            ex.setDataSource(ctx, uri, null)
            var track = -1
            for (i in 0 until ex.trackCount) if ((ex.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) { track = i; break }
            if (track < 0) return emptyList()
            ex.selectTrack(track)
            val fmt = ex.getTrackFormat(track)
            val codec = MediaCodec.createDecoderByType(fmt.getString(MediaFormat.KEY_MIME)!!)
            codec.configure(fmt, null, null, 0); codec.start()
            val envT = ArrayList<Long>(); val env = ArrayList<Float>()
            val bi = MediaCodec.BufferInfo()
            var inDone = false; var outDone = false
            var sr = fmt.getInteger(MediaFormat.KEY_SAMPLE_RATE); var ch = fmt.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            try {
                while (!outDone) {
                    if (!inDone) {
                        val ii = codec.dequeueInputBuffer(5000)
                        if (ii >= 0) {
                            val n = ex.readSampleData(codec.getInputBuffer(ii)!!, 0)
                            if (n < 0) { codec.queueInputBuffer(ii, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inDone = true }
                            else { codec.queueInputBuffer(ii, 0, n, ex.sampleTime, 0); ex.advance() }
                        }
                    }
                    val oi = codec.dequeueOutputBuffer(bi, 5000)
                    if (oi == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        sr = codec.outputFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE); ch = codec.outputFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    } else if (oi >= 0) {
                        val buf = codec.getOutputBuffer(oi)!!.order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer()
                        val win = sr / 200 * ch   // 5ms
                        var pos = 0; val total = bi.size / 2
                        while (pos + win <= total) {
                            var s = 0.0
                            for (k in 0 until win) { val v = buf.get(pos + k).toDouble(); s += v * v }
                            env.add(kotlin.math.sqrt(s / win).toFloat() + 1f)
                            envT.add(bi.presentationTimeUs + pos.toLong() / ch * 1_000_000 / sr)
                            pos += win
                        }
                        codec.releaseOutputBuffer(oi, false)
                        if (bi.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outDone = true
                    }
                }
            } finally { codec.stop(); codec.release() }
            if (env.size < 100) return emptyList()
            val sorted = env.toFloatArray().also { it.sort() }
            val loud = sorted[(sorted.size * 0.995).toInt()]
            val out = ArrayList<Long>()
            var last = Long.MIN_VALUE / 2
            val hist = ArrayDeque<Float>()
            for (i in env.indices) {
                if (hist.size >= 20) {
                    val med = hist.sorted()[10]
                    if (env[i] >= loud && env[i] / med > 8f && envT[i] - last > 3_000_000) { out.add(envT[i]); last = envT[i] }
                    hist.removeFirst()
                }
                hist.addLast(env[i])
            }
            return out
        } catch (e: Exception) {
            return emptyList()
        } finally { ex.release() }
    }
}
