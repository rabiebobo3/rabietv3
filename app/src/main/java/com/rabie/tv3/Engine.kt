package com.rabie.tv3

import android.content.Context
import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** قناة */
class Ch3 {
    var id: String = ""
    var name: String = ""
    var logo: String = ""
    var group: String = ""
    var u: String = ""      // رابط مباشر (xtream/m3u/mac المفكوك)
    var cmd: String = ""    // أمر MAC البوابة (يُفكّ وقت التشغيل)
    var kind: String = "live"
}

/** سيرفر من لوحة RABIE TV */
class Srv3 {
    var id: String = ""
    var name: String = "سيرفر RABIE TV"
    var type: String = "m3u"   // xtream | mac | m3u
    var url: String = ""
    var portal: String = ""
    var user: String = ""
    var pass: String = ""
    var mac: String = ""
}

/**
 * محرك الجلب — نفس قلب تطبيقات IPTV الكبار (ExoPlayer + البروتوكولات العامة):
 * MAC Portal (handshake) + Xtream (player_api) + M3U القياسية — بكاش محلي يفتح فوراً
 */
object Engine {
    val all = ArrayList<Ch3>()
    val groups = LinkedHashMap<String, MutableList<Ch3>>()
    var srv: Srv3? = null

    private val MAC_PATHS = arrayOf("/portal.php", "/server/load.php", "/stalker_portal/server/load.php", "/c/server/load.php", "/stalker_portal/c/server/load.php")
    var macBase = ""
    var macCookie = ""
    var macToken = ""
    val macUA = "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/534.12 (KHTML, like Gecko) Safari/534.12"

    fun clear() { synchronized(all) { all.clear() }; synchronized(groups) { groups.clear() } }

    fun fromJson(s: String): Srv3? {
        val o = Fs.obj(s) ?: return null
        val v = Srv3()
        v.type = Fs.s(o, "type").toLowerCase()
        v.name = if (Fs.s(o, "name").isEmpty()) v.name else Fs.s(o, "name")
        v.url = Fs.s(o, "url"); v.user = Fs.s(o, "username"); v.pass = Fs.s(o, "password")
        v.portal = Fs.s(o, "portal"); v.mac = Fs.s(o, "mac")
        if (v.type == "mac" && v.portal.isEmpty()) v.portal = v.url
        if (v.type == "mac" && v.url.isNotEmpty() && v.portal.isEmpty()) v.portal = v.url
        if (v.url.isEmpty() && v.portal.isNotEmpty()) v.url = v.portal
        if (v.type != "xtream" && v.type != "mac") v.type = "m3u"
        return v
    }

    // ══════════ الكاش المحلي — فتح فوري ══════════
    fun cacheFile(ctx: Context): File {
        val sid = srv?.id?.replace(Regex("[^a-zA-Z0-9]"), "") ?: "s"
        return File(ctx.filesDir, "tv3_cache_$sid.json")
    }

    fun loadCache(ctx: Context): Boolean {
        try {
            val f = cacheFile(ctx)
            if (!f.exists()) return false
            val o = Fs.obj(f.readText()) ?: return false
            val arr = o.optJSONArray("chs") ?: return false
            val list = ArrayList<Ch3>()
            for (i in 0 until arr.length()) {
                val c = arr.getJSONObject(i)
                val ch = Ch3()
                ch.id = c.optString("id"); ch.name = c.optString("name"); ch.logo = c.optString("logo")
                ch.group = c.optString("group"); ch.u = c.optString("u"); ch.cmd = c.optString("cmd"); ch.kind = c.optString("kind", "live")
                if (ch.name.isNotEmpty() && (ch.u.isNotEmpty() || ch.cmd.isNotEmpty())) list.add(ch)
            }
            if (list.isEmpty()) return false
            rebuild(list)
            return true
        } catch (e: Exception) { return false }
    }

    fun saveCache(ctx: Context) {
        try {
            val arr = JSONArray()
            synchronized(all) { for (c in all) {
                val o = JSONObject()
                o.put("id", c.id); o.put("name", c.name); o.put("logo", c.logo)
                o.put("group", c.group); o.put("u", c.u); o.put("cmd", c.cmd); o.put("kind", c.kind)
                arr.put(o)
            } }
            val o = JSONObject(); o.put("ts", System.currentTimeMillis()); o.put("chs", arr)
            cacheFile(ctx).writeText(o.toString())
        } catch (e: Exception) {}
    }

    fun rebuild(list: List<Ch3>) {
        synchronized(all) { all.clear(); all.addAll(list) }
        synchronized(groups) {
            groups.clear()
            for (c in list) groups.getOrPut(if (c.group.isEmpty()) "قنوات" else c.group) { ArrayList() }.add(c)
        }
    }

    private fun httpGet(url: String, headers: Map<String, String>? = null): String? {
        try {
            var c: HttpURLConnection? = null
            try {
                c = URL(url).openConnection() as HttpURLConnection
                c.connectTimeout = 12000; c.readTimeout = 20000
                c.setRequestProperty("Accept", "*/*")
                if (headers != null) for (k in headers.keys) c.setRequestProperty(k, headers[k])
                val code = c.responseCode
                if (code != 200) return null
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally { try { c?.disconnect() } catch (e: Exception) {} }
        } catch (e: Exception) { return null }
    }

    private fun jsArray(body: String?): JSONArray? {
        val o = Fs.obj(body) ?: return null
        try { if (o.has("js")) { val js = o.get("js"); if (js is JSONArray) return js; if (js is JSONObject) { if (js.has("data")) return js.getJSONArray("data") } } } catch (e: Exception) {}
        try { if (o.has("data")) return o.getJSONArray("data") } catch (e: Exception) {}
        return null
    }

    // ══════════ الجلب — على خيط خلفي ══════════
    fun fetch(ctx: Context, onStage: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            var ok = false; var err = "تعذر الاتصال بالسيرفر"
            try {
                val s = srv
                if (s == null) err = "لا يوجد سيرفر مفعّل"
                else if (s.type == "xtream") { ok = fetchXtream(s, onStage); if (!ok) err = "تعذر الجلب من سيرفر Xtream — تحقق من الحساب" }
                else if (s.type == "mac") { ok = fetchMac(s, onStage); if (!ok) err = "تعذر الاتصال ببوابة MAC" }
                else { ok = fetchM3u(s, onStage); if (!ok) err = "تعذر جلب قائمة M3U" }
                if (ok) saveCache(ctx)
            } catch (e: Exception) { ok = false; err = "خطأ: " + e.javaClass.simpleName }
            onDone(ok, err)
        }.start()
    }

    private fun fetchXtream(s: Srv3, onStage: (String) -> Unit): Boolean {
        var base = s.url.trim()
        if (!base.startsWith("http")) base = "http://" + base
        base = base.trimEnd('/')
        val api = "$base/player_api.php?username=" + Uri.encode(s.user) + "&password=" + Uri.encode(s.pass)
        onStage("جاري تحميل القنوات...")
        val cats = HashMap<String, String>()
        val catBody = httpGet(api + "&action=get_live_categories")
        jsArray(catBody)?.let { arr ->
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                cats[o.optString("category_id")] = o.optString("category_name", "قنوات") }
        }
        val body = httpGet(api + "&action=get_live_streams") ?: return false
        val arr = jsArray(body) ?: return false
        val list = ArrayList<Ch3>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val id = o.optString("stream_id"); if (id.isEmpty()) continue
            val ch = Ch3()
            ch.id = "x" + id
            ch.name = o.optString("name", "قناة")
            ch.logo = o.optString("stream_icon", "")
            ch.group = cats.getOrDefault(o.optString("category_id"), "قنوات")
            ch.u = "$base/live/" + Uri.encode(s.user) + "/" + Uri.encode(s.pass) + "/" + id + ".m3u8"
            ch.kind = "live"
            list.add(ch)
        }
        if (list.isEmpty()) return false
        rebuild(list)
        return true
    }

    private fun normMac(raw: String): String {
        var m = raw
        try { if (m.contains("mac=")) { val q = Uri.parse(m).getQueryParameter("mac"); if (!q.isNullOrEmpty()) m = q } } catch (e: Exception) {}
        val hex = m.replace(Regex("[^0-9A-Fa-f]"), "")
        if (hex.length != 12) return ""
        return hex.toUpperCase().replace(Regex("(.{2})(?!$)"), "$1:")
    }

    private fun fetchMac(s: Srv3, onStage: (String) -> Unit): Boolean {
        onStage("الاتصال بالبوابة...")
        val mac = normMac(if (s.mac.isEmpty()) s.portal else s.mac)
        if (mac.isEmpty()) return false
        var portal = s.portal.trim()
        if (!portal.startsWith("http")) portal = "http://" + portal
        val uri = Uri.parse(portal)
        var host = uri.host ?: return false
        if (uri.port != -1) host += ":" + uri.port
        val scheme = if (portal.startsWith("https")) "https" else "http"
        val path = uri.path ?: ""
        var clean = path.trimEnd('/')
        if (clean.endsWith("/c")) clean = clean.substring(0, clean.length - 2)
        val base0 = "$scheme://$host$clean"
        macCookie = "mac=$mac; stb_lang=ar; timezone=Europe/London"
        // handshake على المسارات المعروفة
        var hsBody: String? = null
        for (p in MAC_PATHS) {
            hsBody = httpGet("$base0$p?type=stb&action=handshake&token=&JsHttpRequest=1-xml", mapOf(
                "Cookie" to macCookie, "User-Agent" to macUA, "X-User-Agent" to "Model: MAG250; Link: WiFi"))
            val h = Fs.obj(hsBody)
            if (h != null && h.has("js")) {
                val js = h.get("js")
                var token = ""
                try { if (js is JSONObject && js.has("token")) token = js.getString("token"); else if (js is String) token = js } catch (e: Exception) {}
                if (token.isNotEmpty()) {
                    macBase = base0 + p
                    macToken = token
                    break
                }
            }
            hsBody = null
        }
        if (macBase.isEmpty() || macToken.isEmpty()) return false
        val hd = mapOf("Cookie" to macCookie, "User-Agent" to macUA, "X-User-Agent" to "Model: MAG250; Link: WiFi",
            "Authorization" to ("Bearer " + macToken), "Referer" to (base0 + "/"))
        onStage("جاري تحميل القنوات...")
        val genres = HashMap<String, String>()
        jsArray(httpGet("$macBase?type=itv&action=get_genres&JsHttpRequest=1-xml", hd))?.let { arr ->
            for (i in 0 until arr.length()) { val o = arr.getJSONObject(i)
                val t = if (o.has("title")) o.optString("title") else o.optString("name", "قنوات")
                genres[o.optString("id")] = t }
        }
        val body = httpGet("$macBase?type=itv&action=get_all_channels&JsHttpRequest=1-xml", hd) ?: return false
        val arr = jsArray(body) ?: return false
        val list = ArrayList<Ch3>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            val name = o.optString("name", "قناة")
            var cmd = o.optString("cmd", "")
            if (cmd.isEmpty()) continue
            val ch = Ch3()
            ch.id = "m" + o.optString("id", i.toString())
            ch.name = name
            ch.logo = o.optString("logo", "")
            ch.group = genres.getOrDefault(o.optString("tv_genre_id", ""), "قنوات")
            ch.kind = "mac"; ch.cmd = cmd; ch.u = decodeMacCmd(cmd)
            list.add(ch)
        }
        if (list.isEmpty()) return false
        rebuild(list)
        return true
    }

    /** فك أمر MAC: base64 → رابط مباشر إن أمكن، وإلا يُفك وقت التشغيل عبر create_link */
    fun decodeMacCmd(cmd: String): String {
        try {
            var c = cmd.trim()
            if (c.startsWith("ffmpeg ")) c = c.substring(7).trim()
            if (c.startsWith("http")) return c
            var b = c.replace("-", "+").replace("_", "/")
            while (b.length % 4 != 0) b += "="
            val d = String(Base64.decode(b, Base64.DEFAULT)).trim()
            if (d.startsWith("http")) return d
        } catch (e: Exception) {}
        return ""
    }

    /** create_link وقت التشغيل — يعيد رابط البث الحقيقي */
    fun macCreateLink(cmd: String): String? {
        if (macBase.isEmpty()) return null
        val hd = mapOf("Cookie" to macCookie, "User-Agent" to macUA, "X-User-Agent" to "Model: MAG250; Link: WiFi",
            "Authorization" to ("Bearer " + macToken))
        val body = httpGet("$macBase?type=itv&action=create_link&cmd=" + Uri.encode(cmd) + "&JsHttpRequest=1-xml", hd) ?: return null
        val o = Fs.obj(body) ?: return null
        try {
            if (o.has("js")) {
                val js = o.get("js")
                var real = ""
                if (js is String) real = js
                else if (js is JSONObject) real = js.optString("cmd", "")
                if (real.startsWith("ffmpeg ")) real = real.substring(7).trim()
                if (real.startsWith("http")) return real
            }
        } catch (e: Exception) {}
        return null
    }

    private fun fetchM3u(s: Srv3, onStage: (String) -> Unit): Boolean {
        onStage("جاري تحميل القنوات...")
        var url = s.url.trim()
        if (!url.startsWith("http")) url = "http://" + url
        val body = httpGet(url) ?: return false
        val list = ArrayList<Ch3>()
        var name = ""; var logo = ""; var group = ""
        for (line in body.lines()) {
            val l = line.trim()
            if (l.startsWith("#EXTINF")) {
                name = ""; logo = ""; group = ""
                try {
                    val g = Regex("group-title=\"([^\"]*)\"").find(l); group = g?.groupValues?.get(1) ?: ""
                    val lg = Regex("tvg-logo=\"([^\"]*)\"").find(l); logo = lg?.groupValues?.get(1) ?: ""
                    var nm = l.substringAfterLast(",", "")
                    if (nm.isEmpty()) { val q = Regex("\"([^\"]*)\"").findAll(l).toList(); if (q.isNotEmpty()) nm = q.last().groupValues[1] }
                    name = nm.trim()
                } catch (e: Exception) {}
            } else if (l.isNotEmpty() && !l.startsWith("#") && name.isNotEmpty()) {
                val ch = Ch3()
                ch.id = "u" + l.hashCode()
                ch.name = name; ch.logo = logo; ch.group = if (group.isEmpty()) "قنوات" else group
                ch.u = l; ch.kind = "live"
                list.add(ch)
                name = ""
            }
        }
        if (list.isEmpty()) return false
        rebuild(list)
        return true
    }
}
