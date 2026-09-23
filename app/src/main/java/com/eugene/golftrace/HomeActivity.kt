package com.eugene.golftrace

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView

/** 首页：三个入口，录制 / 回看 / 杆头分析。 */
class HomeActivity : Activity() {
    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        window.statusBarColor = Color.BLACK
        val d = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.BLACK)
            setPadding((20 * d).toInt(), (48 * d).toInt(), (20 * d).toInt(), (20 * d).toInt())
        }
        root.addView(TextView(this).apply {
            text = "杆头轨迹"; textSize = 28f; setTextColor(Color.WHITE); setPadding(0, 0, 0, (28 * d).toInt())
        })
        fun card(title: String, sub: String, accent: Int, go: () -> Unit) {
            val c = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding((20 * d).toInt(), (22 * d).toInt(), (20 * d).toInt(), (22 * d).toInt())
                background = GradientDrawable().apply { cornerRadius = 18 * d; setColor(0xFF1C1C1E.toInt()) }
                setOnClickListener { go() }
            }
            c.addView(TextView(this).apply { text = title; textSize = 22f; setTextColor(accent) })
            c.addView(TextView(this).apply { text = sub; textSize = 14f; setTextColor(0xFF9A9A9A.toInt()); setPadding(0, (6 * d).toInt(), 0, 0) })
            root.addView(c, LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = (14 * d).toInt() })
        }
        card("● 录制", "快门 / ISO / 镜头 / 水平仪 / DTL·正面预设", 0xFFFF5A5A.toInt()) {
            startActivity(Intent(this, CameraActivity::class.java))
        }
        card("▶ 回看", "逐帧 · fps 慢放 · 截图连截 · 双指放大", C_YELLOW) {
            startActivity(Intent(this, PlayerActivity::class.java))
        }
        card("◎ 杆头分析", "自动找挥杆 · 上杆杆头打点 · 节奏", 0xFF6FB6FF.toInt()) {
            startActivity(Intent(this, MainActivity::class.java))
        }
        root.gravity = Gravity.TOP
        val content = root
        content.setOnApplyWindowInsetsListener { v, ins ->
            val sb = ins.getInsets(android.view.WindowInsets.Type.systemBars())
            v.setPadding(v.paddingLeft, maxOf(v.paddingTop, sb.top), v.paddingRight, sb.bottom); ins
        }
        setContentView(content)
    }
}
