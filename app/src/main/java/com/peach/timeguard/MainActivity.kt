package com.peach.timeguard

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

class MainActivity : Activity() {
    private data class App(val pkg: String, val label: String)

    private var apps = listOf<App>()
    private var built = false
    private lateinit var status: TextView
    private lateinit var list: ListView
    private lateinit var grantBtn: Button
    private lateinit var urlView: TextView

    private val dayShort = arrayOf("จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.", "อา.")
    private val dayNames = arrayOf("จันทร์", "อังคาร", "พุธ", "พฤหัสบดี", "ศุกร์", "เสาร์", "อาทิตย์")
    private val opts = intArrayOf(-1, 0, 15, 30, 45, 60, 90, 120, 180)
    private val grants = intArrayOf(5, 10, 15, 30, 60)

    private fun dp(i: Int) = (i * resources.displayMetrics.density).toInt()
    private fun fmtLong(v: Int) = when (v) { -1 -> "ไม่จำกัด"; 0 -> "ห้ามใช้"; else -> "$v นาที" }
    private fun fmtShort(v: Int) = when (v) { -1 -> "∞"; 0 -> "✕"; else -> "$v" }

    override fun onCreate(b: Bundle?) {
        super.onCreate(b)
        if (Store.hasPin(this)) startActivityForResult(Intent(this, PinActivity::class.java), 1) else buildUi()
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(r: Int, res: Int, d: Intent?) {
        if (res == RESULT_OK) buildUi() else finish()
    }

    override fun onResume() { super.onResume(); if (built) reload() }

    private fun svcOn(): Boolean {
        val s = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        return s.contains("$packageName/")
    }

    private fun buildUi() {
        built = true
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(32), dp(24), dp(32), dp(16))
        }
        root.addView(TextView(this).apply { text = "Peach TimeGuard — จำกัดเวลาใช้แอป"; textSize = 26f })
        status = TextView(this).apply { textSize = 16f }
        root.addView(status)
        urlView = TextView(this).apply { textSize = 16f; setTextIsSelectable(true) }
        root.addView(urlView)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(Button(this).apply {
            text = "เปิดบริการ (Accessibility)"
            setOnClickListener { startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
        })
        row.addView(Button(this).apply { text = "ตั้ง / เปลี่ยน PIN"; setOnClickListener { pinDialog() } })
        grantBtn = Button(this).apply {
            setOnClickListener {
                val i = (grants.indexOf(Store.grantMin(this@MainActivity)) + 1) % grants.size
                Store.setGrantMin(this@MainActivity, grants[i]); reload()
            }
        }
        row.addView(grantBtn)
        row.addView(Button(this).apply {
            text = "เปลี่ยนรหัสมือถือ"
            setOnClickListener { Store.renewToken(this@MainActivity); reload() }
        })
        row.addView(Button(this).apply {
            text = "ดูบันทึกการทำงาน"
            setOnClickListener {
                AlertDialog.Builder(this@MainActivity).setTitle("บันทึกการทำงานของบริการ")
                    .setMessage(Diag.text(this@MainActivity))
                    .setPositiveButton("ปิด", null)
                    .setNeutralButton("ล้าง") { _, _ -> Diag.clear(this@MainActivity) }
                    .show()
            }
        })
        root.addView(row)

        list = ListView(this)
        list.setOnItemClickListener { _, _, pos, _ -> editRule(apps[pos]) }
        root.addView(list, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
        setContentView(root)
        reload()
    }

    private fun localIp(): String {
        try {
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp || ni.isLoopback) continue
                for (a in Collections.list(ni.inetAddresses)) {
                    if (a is Inet4Address && !a.isLoopbackAddress) return a.hostAddress ?: "?"
                }
            }
        } catch (e: Exception) { }
        return "?"
    }

    private fun summary(a: App): String {
        val r = Store.rule(this, a.pkg) ?: return a.label
        val lim = r.indices.joinToString(" ") { "${dayShort[it]}${fmtShort(r[it])}" }
        return "${a.label}\n$lim  |  วันนี้ใช้ ${Store.used(this, a.pkg) / 60} นาที"
    }

    private fun reload() {
        status.text = if (svcOn()) "✅ บริการทำงานอยู่" else "⚠️ ยังไม่ได้เปิดบริการ Accessibility (กดปุ่มแรก แล้วเปิด Peach TimeGuard)"
        grantBtn.text = "เวลาเพิ่มเมื่อใส่ PIN: ${Store.grantMin(this)} นาที"
        urlView.text = "เปิดจากมือถือ (Wi-Fi เดียวกัน):  http://${localIp()}:8765/?t=${Store.token(this)}"
        val pm = packageManager
        val out = LinkedHashMap<String, App>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val p = ri.activityInfo.packageName
                if (p != packageName && p !in out) out[p] = App(p, ri.loadLabel(pm).toString())
            }
        }
        apps = out.values.sortedWith(compareBy({ Store.rule(this, it.pkg) == null }, { it.label.lowercase() }))
        list.adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, apps.map { summary(it) })
    }

    private fun pinDialog() {
        val et = EditText(this)
        et.inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        et.hint = "PIN 4-8 หลัก"
        AlertDialog.Builder(this).setTitle("ตั้ง PIN").setView(et)
            .setPositiveButton("บันทึก") { _, _ ->
                val p = et.text.toString()
                if (p.length in 4..8) { Store.setPin(this, p); Toast.makeText(this, "บันทึก PIN แล้ว", Toast.LENGTH_SHORT).show() }
                else Toast.makeText(this, "PIN ต้องมี 4-8 หลัก", Toast.LENGTH_LONG).show()
            }
            .setNegativeButton("ยกเลิก", null).show()
    }

    private fun editRule(a: App) {
        if (!Store.hasPin(this)) {
            Toast.makeText(this, "ตั้ง PIN ก่อน", Toast.LENGTH_LONG).show(); pinDialog(); return
        }
        val v = Store.rule(this, a.pkg)?.copyOf() ?: IntArray(7) { 30 }
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(16), dp(8), dp(16), dp(8)) }
        val btns = ArrayList<Button>()
        fun paint() { for (i in 0..6) btns[i].text = "${dayNames[i]}: ${fmtLong(v[i])}" }
        for (i in 0..6) {
            val bt = Button(this)
            bt.setOnClickListener { v[i] = opts[(opts.indexOf(v[i]) + 1) % opts.size]; paint() }
            btns.add(bt); box.addView(bt)
        }
        val all = Button(this)
        all.text = "ใช้ค่าของวันจันทร์กับทุกวัน"
        all.setOnClickListener { for (i in 1..6) v[i] = v[0]; paint() }
        box.addView(all)
        paint()
        val sv = ScrollView(this); sv.addView(box)
        AlertDialog.Builder(this).setTitle("${a.label} (กดปุ่มวันเพื่อเปลี่ยนเวลา)").setView(sv)
            .setPositiveButton("บันทึก") { _, _ -> Store.setRule(this, a.pkg, v); reload() }
            .setNeutralButton("ลบกฎ") { _, _ -> Store.removeRule(this, a.pkg); reload() }
            .setNegativeButton("ยกเลิก", null).show()
    }
}
