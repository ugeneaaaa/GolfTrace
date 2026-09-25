package com.eugene.golftrace

import android.app.Activity
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/** 关于：版本、能力摘要、与 README 对齐的说明。 */
class AboutActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        val d = resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()

        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }

        val top = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, 0, 0, dp(20))
        }
        top.addView(TextView(this).apply {
            text = "‹ 返回"; textSize = 15f; setTextColor(Color.WHITE)
            setPadding(dp(14), dp(9), dp(14), dp(9))
            background = GradientDrawable().apply {
                cornerRadius = dp(20).toFloat(); setColor(0xFF2A2A2A.toInt())
            }
            setOnClickListener { finish() }
        })
        col.addView(top)

        col.addView(TextView(this).apply {
            text = "杆头轨迹"; textSize = 28f; setTextColor(Color.WHITE)
        })
        col.addView(TextView(this).apply {
            text = "GolfTrace  ·  v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
            textSize = 14f; setTextColor(0xFF9A9A9A.toInt())
            setPadding(0, dp(6), 0, dp(20))
        })

        fun section(title: String, body: String) {
            col.addView(TextView(this).apply {
                text = title; textSize = 16f; setTextColor(C_YELLOW)
                setPadding(0, dp(14), 0, dp(6))
            })
            col.addView(TextView(this).apply {
                text = body; textSize = 14f; setTextColor(0xFFCCCCCC.toInt())
                setLineSpacing(0f, 1.25f)
            })
        }

        section(
            "这是什么",
            "手机上录挥杆、慢放回看、再做杆头轨迹分析。Kotlin 写成，零第三方依赖。"
        )
        section(
            "三个入口",
            "录制：120fps 与普通档都可调快门 / ISO，走相机手动曝光。取景页显示相机回报与实际曝光。支持镜头与分辨率×帧率、水平仪、DTL / 正面预设。\n" +
                "回看：逐帧与显示 fps 慢放、截图 / 连截（每换一帧存一张）、双指放大；与分析互不进入。\n" +
                "杆头分析：自动找挥杆、标点跟踪、轨迹与节奏；结果进相册 Pictures/杆头轨迹。"
        )
        section(
            "相册目录",
            "回看截图 → Pictures/杆头回看\n分析导出 → Pictures/杆头轨迹"
        )
        section(
            "仓库",
            "github.com/ugeneaaaa/GolfTrace"
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            addView(col)
            setOnApplyWindowInsetsListener { v, ins ->
                val sb = ins.getInsets(android.view.WindowInsets.Type.systemBars())
                v.setPadding(0, maxOf(v.paddingTop, sb.top), 0, sb.bottom); ins
            }
        }
        setContentView(scroll)
    }
}
