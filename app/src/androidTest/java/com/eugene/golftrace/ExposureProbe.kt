package com.eugene.golftrace

import android.app.Instrumentation
import android.content.Intent
import android.graphics.Bitmap
import android.hardware.camera2.CameraCharacteristics
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.view.TextureView
import java.io.File

/** Opt-in physical-device A/B probe. Outputs stay in app-private files. */
class ExposureProbe : Instrumentation() {
    private lateinit var args: Bundle
    private lateinit var activity: CameraActivity
    override fun onCreate(arguments: Bundle?) {
        args = arguments ?: Bundle()
        super.onCreate(arguments)
        start()
    }

    private fun get(name: String): Any? = CameraActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.get(activity)
    private fun set(name: String, value: Any) = CameraActivity::class.java.getDeclaredField(name)
        .apply { isAccessible = true }.set(activity, value)
    private fun call(name: String) = CameraActivity::class.java.getDeclaredMethod(name)
        .apply { isAccessible = true }.invoke(activity)
    private fun settle() {
        val until = SystemClock.elapsedRealtime() + 12_000
        while ((get("actualExposureNs") as Long) <= 0 && SystemClock.elapsedRealtime() < until)
            SystemClock.sleep(100)
        check((get("actualExposureNs") as Long) > 0) { "No camera result" }
        SystemClock.sleep(2000)
    }

    override fun onStart() {
        val flag = File(targetContext.filesDir, "exposure-probe.flag")
        val out = File(targetContext.filesDir, "exposure-probe").apply { mkdirs() }
        val report = StringBuilder()
        try {
            flag.writeText("enabled")
            activity = startActivitySync(Intent(targetContext, CameraActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as CameraActivity
            settle()
            val chars = get("chars") as CameraCharacteristics
            report.appendLine("fpsRanges=${chars.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)?.contentToString()}")
            report.appendLine("sessionKeys=${chars.availableSessionKeys?.joinToString { it.name }}")
            report.appendLine("requestKeys=${chars.availableCaptureRequestKeys.joinToString { it.name }}")
            val modes = get("modes") as List<*>
            for (fps in (args.getString("fps") ?: "60,120").split(',').map { it.toInt() }) {
                val idx = modes.indexOfFirst { mode ->
                    mode!!::class.java.getDeclaredMethod("getFps").invoke(mode) == fps &&
                        (mode::class.java.getDeclaredMethod("getSize").invoke(mode) as android.util.Size).width == 1920
                }
                check(idx >= 0) { "Missing 1080p mode $fps" }
                runOnMainSync { set("modeIdx", idx); set("iso", 6400); call("reopen") }
                settle()
                val means = ArrayList<Double>()
                for (shutter in listOf(500, 4000)) {
                    runOnMainSync {
                        set("shutterIdx", if (shutter == 500) 1 else 4)
                        set("actualExposureNs", 0L)
                        CameraActivity::class.java.getDeclaredMethod("applySettings", android.view.Surface::class.java)
                            .apply { isAccessible = true }.invoke(activity, null)
                        call("refreshButtons")
                    }
                    settle()
                    val label = "${fps}fps_1-$shutter"
                    runOnMainSync {
                        val bitmap = (get("texture") as TextureView).getBitmap(270, 480)!!
                        File(out, "$label.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                        val pixels = IntArray(bitmap.width * bitmap.height)
                        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                        val mean = pixels.map { p -> ((p shr 16 and 255)*0.2126 + (p shr 8 and 255)*0.7152 + (p and 255)*0.0722) }.average()
                        report.appendLine("$label previewMean=$mean reportedExp=${get("actualExposureNs")} iso=${get("actualIso")}")
                        means.add(mean)
                        bitmap.recycle()
                    }
                    if (args.getString("record") == "true") {
                        runOnMainSync { call("startRecording") }
                        SystemClock.sleep(4500)
                        runOnMainSync { call("stopRecording") }
                        val uri = get("lastUri") as? Uri ?: error("No recorded URI")
                        targetContext.contentResolver.openInputStream(uri)!!.use { src ->
                            File(out, "$label.mp4").outputStream().use { dst -> src.copyTo(dst) }
                        }
                        // Only the exact probe recording, after copying it successfully.
                        targetContext.contentResolver.delete(uri, null, null)
                        settle()
                    }
                }
                if (args.getString("assertExposure") == "true") {
                    check(means[0] > means[1] * 1.5 && means[0] - means[1] > 15) {
                        "$fps fps: shutter changed 8x but preview did not respond: $means. Keep scene and light fixed."
                    }
                }
            }
            File(out, "report.txt").writeText(report.toString())
            finish(0, Bundle().apply { putString("stream", report.toString()) })
        } catch (e: Throwable) {
            File(out, "report.txt").writeText(report.toString() + e.stackTraceToString())
            finish(1, Bundle().apply { putString("stream", e.stackTraceToString()) })
        } finally {
            flag.delete()
            if (::activity.isInitialized) runOnMainSync { activity.finish() }
        }
    }
}
