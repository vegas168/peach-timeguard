package com.peach.timeguard

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** บันทึกเหตุการณ์ของบริการ เก็บไว้ดูในแอป (ใช้หาสาเหตุเมื่อเปิด ADB ไม่ได้) */
object Diag {
    private fun sp(c: Context) = c.getSharedPreferences("tg_diag", Context.MODE_PRIVATE)

    fun log(c: Context, msg: String) {
        try {
            val t = SimpleDateFormat("HH:mm:ss", Locale.US).format(Date())
            val old = sp(c).getString("log", "") ?: ""
            val lines = (old.lines().filter { it.isNotBlank() } + "$t $msg").takeLast(15)
            sp(c).edit().putString("log", lines.joinToString("\n")).commit()
        } catch (_: Exception) {}
    }

    fun text(c: Context): String = sp(c).getString("log", "")?.ifBlank { null } ?: "(ยังไม่มีบันทึก)"
    fun clear(c: Context) { sp(c).edit().clear().commit() }
}
