package com.peach.timeguard

import android.content.Context
import android.content.Intent
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.MessageDigest

object Apps {
    /** แอปที่เปิดได้จากหน้าจอหลัก: package -> ชื่อ */
    fun list(c: Context): Map<String, String> {
        val pm = c.packageManager
        val out = LinkedHashMap<String, String>()
        for (cat in listOf(Intent.CATEGORY_LEANBACK_LAUNCHER, Intent.CATEGORY_LAUNCHER)) {
            val i = Intent(Intent.ACTION_MAIN).addCategory(cat)
            for (ri in pm.queryIntentActivities(i, 0)) {
                val p = ri.activityInfo.packageName
                if (p != c.packageName && p !in out) out[p] = ri.loadLabel(pm).toString()
            }
        }
        return out
    }

    fun label(c: Context, pkg: String): String = try {
        c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: Exception) { pkg }
}

/** เว็บเซิร์ฟเวอร์เล็กๆ บนทีวี ให้มือถือในวง Wi-Fi เดียวกันเปิดควบคุมได้ ทุกคำขอต้องมี token (?t=...) */
class WebServer(private val ctx: Context, private val port: Int = 8765) {
    private var ss: ServerSocket? = null
    @Volatile private var running = false
    @Volatile private var badUntil = 0L
    @Volatile private var bad = 0

    fun start() {
        if (running) return
        running = true
        Thread {
            try {
                val s = ServerSocket()
                s.reuseAddress = true
                s.bind(InetSocketAddress(port))
                ss = s
                while (running) {
                    val c = s.accept()
                    Thread { handle(c) }.start()
                }
            } catch (e: Exception) { }
            running = false
        }.start()
    }

    fun stop() {
        running = false
        try { ss?.close() } catch (_: Exception) {}
    }

    private fun authOk(t: String?): Boolean {
        val ok = t != null && MessageDigest.isEqual(t.toByteArray(), Store.token(ctx).toByteArray())
        if (ok) bad = 0 else {
            bad++
            if (bad >= 10) { badUntil = System.currentTimeMillis() + 60_000; bad = 0 }
        }
        return ok
    }

    private fun send(o: OutputStream, code: Int, type: String, body: ByteArray) {
        val text = when (code) { 200 -> "OK"; 400 -> "Bad Request"; 401 -> "Unauthorized"; 404 -> "Not Found"; else -> "Too Many Requests" }
        val head = "HTTP/1.1 $code $text\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nCache-Control: no-store\r\nConnection: close\r\n\r\n"
        o.write(head.toByteArray()); o.write(body); o.flush()
    }

    private fun json(o: OutputStream, j: JSONObject) = send(o, 200, "application/json; charset=utf-8", j.toString().toByteArray())

    private fun handle(s: Socket) {
        try {
            s.soTimeout = 5000
            val inp = BufferedReader(InputStreamReader(s.getInputStream(), Charsets.ISO_8859_1))
            val out = s.getOutputStream()
            val first = inp.readLine() ?: return
            val parts = first.split(" ")
            if (parts.size < 2) return
            val method = parts[0]
            val target = parts[1]
            var len = 0
            while (true) {
                val l = inp.readLine() ?: break
                if (l.isEmpty()) break
                if (l.lowercase().startsWith("content-length:")) len = l.substring(15).trim().toIntOrNull() ?: 0
            }
            var body = ""
            if (len in 1..8192) {
                val b = CharArray(len)
                var n = 0
                while (n < len) { val r = inp.read(b, n, len - n); if (r < 0) break; n += r }
                body = String(b, 0, n)
            }
            val path = target.substringBefore("?")
            val q = target.substringAfter("?", "").split("&").filter { it.contains("=") }
                .associate { it.substringBefore("=") to URLDecoder.decode(it.substringAfter("="), "UTF-8") }

            if (System.currentTimeMillis() < badUntil) { send(out, 429, "text/plain", "too many attempts".toByteArray()); return }
            if (!authOk(q["t"])) { send(out, 401, "text/plain", "unauthorized".toByteArray()); return }

            val j = try { JSONObject(if (body.isEmpty()) "{}" else body) } catch (e: Exception) { JSONObject() }
            val pkg = j.optString("pkg", "")
            val ok = JSONObject().put("ok", true)
            when {
                path == "/" -> send(out, 200, "text/html; charset=utf-8", PAGE.toByteArray())
                path == "/api/state" -> json(out, state())
                path == "/api/apps" -> {
                    val arr = JSONArray()
                    for ((p, l) in Apps.list(ctx)) arr.put(JSONObject().put("pkg", p).put("label", l))
                    json(out, JSONObject().put("apps", arr))
                }
                method == "POST" && path == "/api/rule" -> {
                    val d = j.optJSONArray("days")
                    if (pkg.isEmpty() || d == null || d.length() != 7) send(out, 400, "text/plain", "bad".toByteArray())
                    else {
                        Store.setRule(ctx, pkg, IntArray(7) { d.optInt(it, 30).coerceIn(-1, 1440) })
                        json(out, ok)
                    }
                }
                method == "POST" && path == "/api/remove" && pkg.isNotEmpty() -> { Store.removeRule(ctx, pkg); json(out, ok) }
                method == "POST" && path == "/api/extra" && pkg.isNotEmpty() -> {
                    Store.addExtra(ctx, pkg, j.optInt("minutes", 15).coerceIn(1, 240)); json(out, ok)
                }
                method == "POST" && path == "/api/lock" && pkg.isNotEmpty() -> { Store.forceLock(ctx, pkg, true); json(out, ok) }
                method == "POST" && path == "/api/unlock" && pkg.isNotEmpty() -> { Store.forceLock(ctx, pkg, false); json(out, ok) }
                else -> send(out, 404, "text/plain", "not found".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { s.close() } catch (_: Exception) {}
        }
    }

    private fun state(): JSONObject {
        val arr = JSONArray()
        for (pkg in Store.managed(ctx)) {
            val r = Store.rule(ctx, pkg)
            val lim = Store.limitSec(ctx, pkg)
            arr.put(JSONObject()
                .put("pkg", pkg)
                .put("label", Apps.label(ctx, pkg))
                .put("rule", if (r == null) JSONObject.NULL else JSONArray(r.toList()))
                .put("used", Store.used(ctx, pkg) / 60)
                .put("limit", if (lim == null) -1 else lim / 60)
                .put("blocked", Store.isBlocked(ctx, pkg))
                .put("forced", Store.isForced(ctx, pkg)))
        }
        return JSONObject().put("apps", arr).put("day", Store.dayIndex()).put("pin", Store.hasPin(ctx))
    }
}

private const val PAGE = """<!doctype html><html lang="th"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="apple-mobile-web-app-capable" content="yes"><title>Peach TimeGuard</title>
<style>
body{font-family:-apple-system,sans-serif;margin:0;background:#fff7f4;color:#3a2a26}
h1{font-size:20px;margin:0;padding:16px;background:#ff7f6b;color:#fff}
.c{background:#fff;margin:12px;padding:14px;border-radius:14px;box-shadow:0 1px 4px #0002}
.n{font-weight:600;font-size:17px}.s{color:#7a6560;font-size:14px;margin:4px 0 8px}
.bar{height:8px;background:#f1dcd6;border-radius:4px;overflow:hidden}.bar i{display:block;height:100%;background:#ff7f6b}
button{font-size:15px;padding:9px 12px;margin:8px 6px 0 0;border:0;border-radius:10px;background:#ffe3dc;color:#a8402d}
button.p{background:#ff7f6b;color:#fff}
select{font-size:15px;padding:6px;width:55%}
.r{display:flex;justify-content:space-between;align-items:center;margin:6px 0}
.b{color:#c0392b;font-weight:600}
</style></head><body><h1>Peach TimeGuard</h1><div id="app">กำลังโหลด...</div>
<script>
var T=new URLSearchParams(location.search).get('t')||'';
var D=['จันทร์','อังคาร','พุธ','พฤหัสบดี','ศุกร์','เสาร์','อาทิตย์'];
var O=[-1,0,15,30,45,60,90,120,180];
var S=null,ED=null,el=document.getElementById('app');
function esc(s){return String(s).replace(/&/g,'&amp;').replace(/</g,'&lt;')}
function lab(v){return v<0?'ไม่จำกัด':v==0?'ห้ามใช้':v+' นาที'}
function api(p,b){var o=b?{method:'POST',body:JSON.stringify(b)}:{};
 return fetch(p+'?t='+encodeURIComponent(T),o).then(function(r){if(!r.ok)throw 0;return r.json()})}
function load(){api('/api/state').then(function(s){S=s;if(!ED)draw()}).catch(function(){
 if(!ED)el.innerHTML='<div class=c>เชื่อมต่อทีวีไม่ได้ หรือรหัสไม่ถูกต้อง</div>'})}
function draw(){var h='';
 S.apps.forEach(function(a,i){
  var pct=a.limit>0?Math.min(100,Math.round(a.used*100/a.limit)):(a.blocked?100:0);
  h+='<div class=c><div class=n>'+esc(a.label)+(a.blocked?' <span class=b>🔒 ล็อกอยู่</span>':'')+'</div>'
  +'<div class=s>วันนี้ใช้ '+a.used+' นาที / '+(a.limit<0?'ไม่จำกัด':a.limit+' นาที')+'</div>'
  +'<div class=bar><i style="width:'+pct+'%"></i></div>'
  +'<button onclick="ex('+i+',15)">+15 นาที</button><button onclick="ex('+i+',30)">+30 นาที</button>'
  +(a.forced?'<button class=p onclick="act(\'unlock\','+i+')">ปลดล็อก</button>'
           :'<button class=p onclick="act(\'lock\','+i+')">🔒 ล็อกเลย</button>')
  +(a.rule?'<button onclick="edit('+i+')">ตั้งเวลา</button>':'')+'</div>'});
 h+='<div class=c><button class=p onclick="addApp()">+ เพิ่มแอปที่จะจำกัดเวลา</button></div>';
 el.innerHTML=h}
function act(k,i){api('/api/'+k,{pkg:S.apps[i].pkg}).then(load)}
function ex(i,m){api('/api/extra',{pkg:S.apps[i].pkg,minutes:m}).then(load)}
function edit(i){var a=S.apps[i];ED={pkg:a.pkg,label:a.label,v:a.rule.slice()};form()}
function addApp(){api('/api/apps').then(function(r){ED={};var h='<div class=c><div class=n>เลือกแอป</div>';
 r.apps.forEach(function(a,i){h+='<button onclick="pick('+i+')">'+esc(a.label)+'</button>'});
 h+='<br><button onclick="ED=null;draw()">ยกเลิก</button></div>';ED.list=r.apps;el.innerHTML=h})}
function pick(i){var a=ED.list[i];ED={pkg:a.pkg,label:a.label,v:[30,30,30,30,30,30,30]};form()}
function form(){var h='<div class=c><div class=n>'+esc(ED.label)+'</div>';
 for(var i=0;i<7;i++){h+='<div class=r><span>'+D[i]+'</span><select onchange="ED.v['+i+']=+this.value">';
  O.forEach(function(o){h+='<option value='+o+(ED.v[i]==o?' selected':'')+'>'+lab(o)+'</option>'});h+='</select></div>'}
 h+='<button onclick="same()">ใช้ค่าวันจันทร์กับทุกวัน</button><br>'
  +'<button class=p onclick="save()">บันทึก</button><button onclick="rm()">ลบกฎ</button><button onclick="ED=null;load();draw()">ยกเลิก</button></div>';
 el.innerHTML=h}
function same(){for(var i=1;i<7;i++)ED.v[i]=ED.v[0];form()}
function save(){api('/api/rule',{pkg:ED.pkg,days:ED.v}).then(function(){ED=null;load()})}
function rm(){api('/api/remove',{pkg:ED.pkg}).then(function(){ED=null;load()})}
load();setInterval(load,10000);
</script></body></html>"""
