package com.peach.timeguard

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.KeyEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class PinActivity : Activity() {
    private var entered = ""
    private var pkg: String? = null
    private lateinit var dots: TextView
    private lateinit var msg: TextView

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun label(p: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(p, 0)).toString()
    } catch (e: Exception) { p }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        pkg = intent.getStringExtra("pkg")
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(0xFF1B1210.toInt())
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        val title = TextView(this).apply {
            textSize = 24f; setTextColor(Color.WHITE); gravity = Gravity.CENTER
            text = pkg?.let { "หมดเวลาใช้ ${label(it)} แล้ว\nใส่ PIN เพื่อใช้ต่ออีก ${Store.grantMin(this@PinActivity)} นาที" } ?: "ใส่ PIN"
        }
        dots = TextView(this).apply { textSize = 32f; setTextColor(Color.WHITE); gravity = Gravity.CENTER; text = " " }
        msg = TextView(this).apply { textSize = 16f; setTextColor(0xFFFF9E8F.toInt()); gravity = Gravity.CENTER; text = " " }
        root.addView(title); root.addView(dots); root.addView(msg)

        var five: Button? = null
        val keys = listOf(listOf("1", "2", "3"), listOf("4", "5", "6"), listOf("7", "8", "9"), listOf("⌫", "0", "OK"))
        for (row in keys) {
            val r = LinearLayout(this).apply { gravity = Gravity.CENTER }
            for (k in row) {
                val bt = Button(this).apply { text = k; textSize = 22f; setOnClickListener { press(k) } }
                if (k == "5") five = bt
                r.addView(bt, LinearLayout.LayoutParams(dp(96), dp(64)))
            }
            root.addView(r)
        }
        setContentView(root)
        five?.requestFocus()
    }

    override fun onKeyDown(k: Int, e: KeyEvent): Boolean {
        if (k in KeyEvent.KEYCODE_0..KeyEvent.KEYCODE_9) { press((k - KeyEvent.KEYCODE_0).toString()); return true }
        if (k == KeyEvent.KEYCODE_DEL) { press("⌫"); return true }
        return super.onKeyDown(k, e)
    }

    private fun press(k: String) {
        when (k) {
            "⌫" -> entered = entered.dropLast(1)
            "OK" -> { submit(); return }
            else -> if (entered.length < 8) entered += k
        }
        dots.text = "●".repeat(entered.length).ifEmpty { " " }
    }

    private fun submit() {
        val wait = Store.lockLeft(this)
        if (wait > 0) { msg.text = "ใส่ผิดหลายครั้ง ลองใหม่ใน $wait วินาที"; entered = ""; dots.text = " "; return }
        if (!Store.hasPin(this) || Store.checkPin(this, entered)) {
            Store.resetFails(this)
            val p = pkg
            if (p != null) {
                Store.grant(this, p)
                val i = packageManager.getLeanbackLaunchIntentForPackage(p) ?: packageManager.getLaunchIntentForPackage(p)
                i?.let { startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            } else setResult(RESULT_OK)
            finish()
        } else {
            Store.fail(this)
            entered = ""; dots.text = " "; msg.text = "PIN ไม่ถูกต้อง"
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (pkg != null) startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        super.onBackPressed()
    }
}
