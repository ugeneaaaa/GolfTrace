package com.eugene.golftrace

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.util.Log
import android.util.Size
import android.view.Surface

/**
 * 高速档（120/240fps）的录制器。
 *
 * MediaRecorder 的录制面进不了高速会话，录制时再重配会话又会把相机 HAL 卡死，
 * 所以这里用一个常驻的编码器：相机会话从一开始就往它的输入面送帧，编码器一直在跑，
 * 按录制键只是把输出接到 MediaMuxer 上，相机会话全程不动。
 */
class HighSpeedRecorder(
    size: Size, fps: Int, bitRate: Int, hevc: Boolean,
) {
    /** 相机会话用的输入面，整个高速档期间不变。 */
    val surface: Surface

    private val codec: MediaCodec
    private var muxer: MediaMuxer? = null
    private var outFormat: MediaFormat? = null
    private var track = -1
    private var frames = 0
    private var firstPts = -1L
    @Volatile private var writing = false
    private val lock = Object()

    init {
        val mime = if (hevc) MediaFormat.MIMETYPE_VIDEO_HEVC else MediaFormat.MIMETYPE_VIDEO_AVC
        val fmt = MediaFormat.createVideoFormat(mime, size.width, size.height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, bitRate)
            setInteger(MediaFormat.KEY_FRAME_RATE, fps)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
            // 高帧率下让编码器全速跑，别按普通视频调度
            setInteger(MediaFormat.KEY_OPERATING_RATE, fps)
            setInteger(MediaFormat.KEY_PRIORITY, 0)
        }
        codec = MediaCodec.createEncoderByType(mime)
        Log.i("GT", "hs codec=${codec.name} mime=$mime size=${size.width}x${size.height} fps=$fps")
        codec.setCallback(object : MediaCodec.Callback() {
            override fun onInputBufferAvailable(c: MediaCodec, i: Int) {}
            override fun onOutputBufferAvailable(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
                try { drain(c, i, info) } catch (e: Exception) { Log.e("GT", "mux write", e) }
            }
            override fun onOutputFormatChanged(c: MediaCodec, f: MediaFormat) {
                synchronized(lock) {
                    outFormat = f
                    if (writing && track < 0) startTrack()
                }
            }
            override fun onError(c: MediaCodec, e: MediaCodec.CodecException) { Log.e("GT", "encoder", e) }
        })
        codec.configure(fmt, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        surface = codec.createInputSurface()
        codec.start()
    }

    @Volatile private var seen = 0
    /** 相机有没有真的往编码器送过帧。 */
    val sawOutput get() = seen > 0

    private fun drain(c: MediaCodec, i: Int, info: MediaCodec.BufferInfo) {
        synchronized(lock) {
            val cfg = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
            val key = info.flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0
            if (!cfg && info.size > 0) {
                seen++
                if (seen == 1 || seen % 60 == 0) {
                    Log.i("GT", "enc out #$seen size=${info.size} flags=${info.flags} pts=${info.presentationTimeUs}")
                }
            }
            // 不在录的时候，帧直接丢掉；录的时候从第一个关键帧开始写
            if (writing && !cfg && info.size > 0 && (track >= 0 || key)) {
                if (track < 0) startTrack()
                if (track >= 0 && (frames > 0 || key)) {
                    val buf = c.getOutputBuffer(i)
                    if (buf != null) {
                        if (firstPts < 0) firstPts = info.presentationTimeUs
                        buf.position(info.offset); buf.limit(info.offset + info.size)
                        info.presentationTimeUs -= firstPts
                        muxer?.writeSampleData(track, buf, info)
                        frames++
                    }
                }
            }
        }
        c.releaseOutputBuffer(i, false)
    }

    private fun startTrack() {
        val f = outFormat ?: return
        val mx = muxer ?: return
        track = mx.addTrack(f)
        mx.start()
        Log.i("GT", "muxer started track=$track fmt=$f")
    }

    /** 开始写文件；相机会话不动。 */
    fun start(out: ParcelFileDescriptor, orientationHint: Int) {
        synchronized(lock) {
            muxer = MediaMuxer(out.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                .apply { setOrientationHint(orientationHint) }
            track = -1; frames = 0; firstPts = -1
            writing = true
            Log.i("GT", "hs start writing, outFormat=${outFormat != null}")
            if (outFormat != null) startTrack()
        }
        // 让编码器马上出一个关键帧，不用等下一个 GOP
        try { codec.setParameters(Bundle().apply { putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0) }) }
        catch (_: Exception) {}
    }

    /** 停止写文件，编码器继续跑着等下一次；返回写进去的帧数。 */
    fun stop(): Int {
        val n: Int
        synchronized(lock) {
            if (!writing) return 0
            writing = false
            n = frames
            try { if (track >= 0) muxer?.stop() } catch (e: Exception) { Log.e("GT", "muxer stop", e) }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null; track = -1
        }
        return n
    }

    /** 离开高速档时调用。 */
    fun release() {
        synchronized(lock) {
            writing = false
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
        }
        try { codec.stop() } catch (_: Exception) {}
        try { codec.release() } catch (_: Exception) {}
        try { surface.release() } catch (_: Exception) {}
    }
}
