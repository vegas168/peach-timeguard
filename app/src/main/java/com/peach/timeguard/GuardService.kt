package com.peach.timeguard

import android.accessibilityservice.AccessibilityService
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Toast

class GuardService : AccessibilityService() {
    private val h = Handler(Looper.getMainLooper())
    private var cur: String? = null
    private var screenOn = true
    private var lastBlock = 0L

    private val tick = object : Runnable {
        override fun run() { onTick(); h.postDelayed(this, 1000) }
    }
    private val rcv = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { screenOn = i.action == Intent.ACTION_SCREEN_ON }
    }

    override fun onServiceConnected() {
        registerReceiver(rcv, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
        screenOn = getSystemService(PowerManager::class.java).isInteractive
        h.removeCallbacks(tick); h.post(tick)
    }

    private fun ignored(p: String) =
        p == "android" || p == "com.android.systemui" || p.contains("inputmethod") || p.contains("keyboard")

    override fun onAccessibilityEvent(e: AccessibilityEvent?) {
        if (e == null || e.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val p = e.packageName?.toString() ?: return
        if (p == packageName) { cur = null; return }   // หน้าของเราเอง (PIN) ไม่นับเวลา
        if (ignored(p)) return
        cur = p
        if (Store.isBlocked(this, p)) block(p)
    }

    private fun onTick() {
        val p = cur ?: return
        if (!screenOn || Store.rule(this, p) == null) return
        Store.addUsed(this, p, 1)
        val lim = Store.limitSec(this, p) ?: return
        val left = lim - Store.used(this, p)
        if (left == 60 && Store.hasPin(this))
            Toast.makeText(this, "เหลืออีก 1 นาที", Toast.LENGTH_LONG).show()
        if (left <= 0) block(p)
    }

    private fun block(p: String) {
        if (!Store.hasPin(this)) return
        val now = System.currentTimeMillis()
        if (now - lastBlock < 2000) return
        lastBlock = now
        cur = null
        performGlobalAction(GLOBAL_ACTION_HOME)
        h.postDelayed({
            startActivity(Intent(this, PinActivity::class.java)
                .putExtra("pkg", p)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }, 300)
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        h.removeCallbacks(tick)
        try { unregisterReceiver(rcv) } catch (_: Exception) {}
        super.onDestroy()
    }
}
