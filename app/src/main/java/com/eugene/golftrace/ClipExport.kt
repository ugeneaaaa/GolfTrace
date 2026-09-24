package com.eugene.golftrace

import android.content.ContentValues
import android.content.Context
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.nio.ByteBuffer

/**
 * 从原视频里剪出一段挥杆，直接拷贝压缩数据（不重新编码，快且不掉画质）。
 *
 * slow > 1 时把时间戳拉长 slow 倍，得到慢放文件（去掉声音）。
 * 这样按“每秒抽 1 帧”看视频的 AI 也能看到更多挥杆中间的帧。
 */
object ClipExport {

    class Result(val uri: Uri, val startUs: Long, val endUs: Long, val frames: Int)

    fun export(ctx: Context, src: Uri, startUs: Long, endUs: Long, rotation: Int, slow: Int, name: String): Result {
        val cv = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/杆头轨迹/发送")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val cr = ctx.contentResolver
        val dst = cr.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, cv) ?: error("无法创建文件")
        try {
            val res = cr.openFileDescriptor(dst, "rw")!!.use { pfd ->
                val mux = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                mux.setOrientationHint(rotation)
                try { copy(ctx, src, mux, startUs, endUs, slow) } finally {
                    try { mux.release() } catch (_: Exception) {}
                }
            }
            cr.update(dst, ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }, null, null)
            return Result(dst, res.first, res.second, res.third)
        } catch (e: Exception) {
            try { cr.delete(dst, null, null) } catch (_: Exception) {}
            throw e
        }
    }

    /** 返回 (实际起点, 实际终点, 视频帧数)。起点会对齐到它之前最近的关键帧。 */
    private fun copy(ctx: Context, src: Uri, mux: MediaMuxer, startUs: Long, endUs: Long, slow: Int): Triple<Long, Long, Int> {
        val vx = MediaExtractor().apply { setDataSource(ctx, src, null) }
        val vt = (0 until vx.trackCount).first { mime(vx, it).startsWith("video/") }
        vx.selectTrack(vt)
        val vfmt = vx.getTrackFormat(vt)
        // 慢放文件里帧率标记也要跟着改，不然有的播放器按原帧率播
        if (slow > 1 && vfmt.containsKey(MediaFormat.KEY_FRAME_RATE)) {
            val fr = try { vfmt.getInteger(MediaFormat.KEY_FRAME_RATE).toFloat() }
                catch (_: Exception) { vfmt.getFloat(MediaFormat.KEY_FRAME_RATE) }
            vfmt.setInteger(MediaFormat.KEY_FRAME_RATE, maxOf(1, (fr / slow).toInt()))
        }
        val vOut = mux.addTrack(vfmt)

        // 声音只在原速时带上
        var ax: MediaExtractor? = null; var aOut = -1
        if (slow == 1) {
            val e = MediaExtractor().apply { setDataSource(ctx, src, null) }
            val at = (0 until e.trackCount).firstOrNull { mime(e, it).startsWith("audio/") }
            if (at != null) { e.selectTrack(at); aOut = mux.addTrack(e.getTrackFormat(at)); ax = e } else e.release()
        }
        mux.start()

        val buf = ByteBuffer.allocate(maxInput(vfmt, 8 shl 20))
        val info = android.media.MediaCodec.BufferInfo()

        vx.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
        val base = vx.sampleTime.coerceAtLeast(0)
        var last = base; var n = 0
        while (true) {
            val t = vx.sampleTime
            if (t < 0 || t > endUs) break
            info.size = vx.readSampleData(buf, 0)
            if (info.size < 0) break
            info.offset = 0
            info.presentationTimeUs = (t - base) * slow
            info.flags = if (vx.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0)
                android.media.MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
            mux.writeSampleData(vOut, buf, info)
            last = t; n++
            vx.advance()
        }
        vx.release()

        ax?.let { e ->
            val abuf = ByteBuffer.allocate(1 shl 20)
            e.seekTo(base, MediaExtractor.SEEK_TO_CLOSEST_SYNC)
            while (true) {
                val t = e.sampleTime
                if (t < 0 || t > last) break
                if (t >= base) {
                    info.size = e.readSampleData(abuf, 0)
                    if (info.size < 0) break
                    info.offset = 0; info.presentationTimeUs = t - base; info.flags = 0
                    mux.writeSampleData(aOut, abuf, info)
                }
                e.advance()
            }
            e.release()
        }
        mux.stop()
        return Triple(base, last, n)
    }

    private fun mime(e: MediaExtractor, i: Int) = e.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""

    private fun maxInput(f: MediaFormat, def: Int) =
        if (f.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) maxOf(def, f.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE)) else def
}
