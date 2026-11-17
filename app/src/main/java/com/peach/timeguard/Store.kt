package com.peach.timeguard

import android.content.Context
import java.security.MessageDigest
import java.security.SecureRandom
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

object Store {
    private fun sp(c: Context) = c.getSharedPreferences("tg", Context.MODE_PRIVATE)
    private fun today() = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    /** 0 = จันทร์ ... 6 = อาทิตย์ */
    fun dayIndex() = (Calendar.getInstance().get(Calendar.DAY_OF_WEEK) + 5) % 7

    /** เริ่มวันใหม่: ล้างเวลาที่ใช้และเวลาพิเศษของเมื่อวาน */
    fun rollover(c: Context) {
        val s = sp(c)
        if (s.getString("day", "") == today()) return
        val e = s.edit()
        s.all.keys.filter { it.startsWith("used_") || it.startsWith("extra_") || it.startsWith("force_") }.forEach { e.remove(it) }
        e.putString("day", today()).apply()
    }

    // กฎต่อแอป: 7 ค่า (จ-อา) หน่วยนาที, -1 = ไม่จำกัด, 0 = ห้ามใช้
    fun rule(c: Context, pkg: String): IntArray? =
        sp(c).getString("rule_$pkg", null)?.split(",")?.map { it.toInt() }?.toIntArray()?.takeIf { it.size == 7 }

    fun setRule(c: Context, pkg: String, v: IntArray) =
        sp(c).edit().putString("rule_$pkg", v.joinToString(",")).apply()

    fun removeRule(c: Context, pkg: String) =
        sp(c).edit().remove("rule_$pkg").remove("used_$pkg").remove("extra_$pkg").remove("force_$pkg").apply()

    fun used(c: Context, pkg: String) = sp(c).getInt("used_$pkg", 0)

    fun addUsed(c: Context, pkg: String, sec: Int) {
        rollover(c)
        sp(c).edit().putInt("used_$pkg", used(c, pkg) + sec).apply()
    }

    /** เพดานเวลาวันนี้ (วินาที) หรือ null ถ้าไม่จำกัด */
    fun limitSec(c: Context, pkg: String): Int? {
        val v = rule(c, pkg)?.get(dayIndex()) ?: return null
        if (v < 0) return null
        return v * 60 + sp(c).getInt("extra_$pkg", 0)
    }

    fun isBlocked(c: Context, pkg: String): Boolean {
        if (!hasPin(c)) return false // ยังไม่ตั้ง PIN จะไม่ล็อก กันล็อกแล้วเข้าไม่ได้
        rollover(c)
        if (isForced(c, pkg)) return true
        val lim = limitSec(c, pkg) ?: return false
        return used(c, pkg) >= lim
    }

    fun grantMin(c: Context) = sp(c).getInt("grant", 15)
    fun setGrantMin(c: Context, m: Int) = sp(c).edit().putInt("grant", m).apply()

    /** ใส่ PIN ถูก: ให้เวลาเพิ่ม grantMin นาที นับจากตอนนี้ */
    fun grant(c: Context, pkg: String) {
        forceLock(c, pkg, false)
        val v = rule(c, pkg)?.get(dayIndex()) ?: return
        val lim = limitSec(c, pkg) ?: return
        if (used(c, pkg) >= lim)
            sp(c).edit().putInt("extra_$pkg", used(c, pkg) + grantMin(c) * 60 - maxOf(v, 0) * 60).apply()
    }

    /** ผู้ปกครองสั่งล็อก/ปลดล็อกจากมือถือ (ล็อกถึงเที่ยงคืน) */
    fun forceLock(c: Context, pkg: String, on: Boolean) {
        val e = sp(c).edit()
        if (on) e.putBoolean("force_$pkg", true) else e.remove("force_$pkg")
        e.apply()
    }

    fun isForced(c: Context, pkg: String) = sp(c).getBoolean("force_$pkg", false)

    /** เพิ่มเวลาให้แอปวันนี้ (นาที) และปลดล็อกที่สั่งไว้ */
    fun addExtra(c: Context, pkg: String, min: Int) {
        forceLock(c, pkg, false)
        val s = sp(c)
        s.edit().putInt("extra_$pkg", s.getInt("extra_$pkg", 0) + min * 60).apply()
    }

    /** แอปที่มีกฎหรือถูกสั่งล็อก */
    fun managed(c: Context): List<String> =
        sp(c).all.keys.filter { it.startsWith("rule_") || it.startsWith("force_") }
            .map { it.substringAfter("_") }.distinct().sorted()

    // รหัส (token) สำหรับให้มือถือเรียกทีวี
    private fun newToken() = ByteArray(6).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }

    fun token(c: Context): String {
        val s = sp(c)
        return s.getString("web_token", null) ?: newToken().also { s.edit().putString("web_token", it).apply() }
    }

    fun renewToken(c: Context) = sp(c).edit().putString("web_token", newToken()).apply()

    fun hasPin(c: Context) = sp(c).contains("pin_hash")

    private fun hash(salt: String, pin: String) =
        MessageDigest.getInstance("SHA-256").digest((salt + pin).toByteArray()).joinToString("") { "%02x".format(it) }

    fun setPin(c: Context, pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }.joinToString("") { "%02x".format(it) }
        sp(c).edit().putString("pin_salt", salt).putString("pin_hash", hash(salt, pin)).apply()
    }

    fun checkPin(c: Context, pin: String): Boolean {
        val s = sp(c)
        return hash(s.getString("pin_salt", "") ?: "", pin) == s.getString("pin_hash", "")
    }

    fun lockLeft(c: Context): Int =
        maxOf(0, ((sp(c).getLong("lock_until", 0) - System.currentTimeMillis()) / 1000).toInt())

    fun fail(c: Context) {
        val s = sp(c)
        val n = s.getInt("fails", 0) + 1
        val e = s.edit().putInt("fails", n)
        if (n >= 5) e.putLong("lock_until", System.currentTimeMillis() + 30_000L * (n - 4))
        e.apply()
    }

    fun resetFails(c: Context) = sp(c).edit().putInt("fails", 0).putLong("lock_until", 0).apply()
}
