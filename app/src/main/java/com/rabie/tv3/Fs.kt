package com.rabie.tv3

import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** الاتصال بقاعدة بيانات لوحة RABIE TV (Firebase REST) + أدوات مساعدة */
object Fs {
    const val DB = "https://rabiepro-bb7e9-default-rtdb.firebaseio.com"

    fun http(method: String, path: String, body: String?): String? {
        try {
            var c: HttpURLConnection? = null
            try {
                c = URL(DB + path + ".json").openConnection() as HttpURLConnection
                c.requestMethod = method
                c.connectTimeout = 9000
                c.readTimeout = 9000
                if (body != null) {
                    c.doOutput = true
                    c.setRequestProperty("Content-Type", "application/json")
                    c.outputStream.write(body.toByteArray())
                }
                val code = c.responseCode
                if (code != 200) return null
                return c.inputStream.bufferedReader().use { it.readText() }
            } finally {
                try { c?.disconnect() } catch (e: Exception) {}
            }
        } catch (e: Exception) { return null }
    }

    fun get(path: String): String? = http("GET", path, null)

    fun obj(t: String?): JSONObject? {
        try { if (t == null) return null; val s = t.trim(); if (s.isEmpty() || s == "null") return null; return JSONObject(s) } catch (e: Exception) { return null }
    }

    /** قيمة نصية آمنة من JSONObject */
    fun s(o: JSONObject?, k: String): String {
        try { if (o == null || !o.has(k) || o.isNull(k)) return ""; return o.getString(k).trim() } catch (e: Exception) { return "" }
    }

    /** التحقق من تاريخ انتهاء الاشتراك — يدعم 2026-12-31 و 15-09-2026 و 15/09/2026 و 15.09.2026 */
    fun expiryOk(expiry: String): Boolean {
        try {
            val s = expiry.trim().replace(".", "-").replace("/", "-").replace(" ", "")
            if (s.isEmpty()) return true
            val p = s.split("-")
            if (p.size != 3) return true
            val a = p[0].toInt(); val b = p[1].toInt(); val c = p[2].toInt()
            var year: Int; var month: Int; var day: Int
            if (p[0].length == 4 || a > 31) { year = a; month = b; day = c } else { day = a; month = b; year = c }
            if (month < 1 || month > 12 || day < 1 || day > 31 || year < 2000 || year > 2100) return true
            val cal = java.util.Calendar.getInstance()
            val now = java.util.Calendar.getInstance()
            cal.set(year, month - 1, day, 23, 59, 59)
            return now.before(cal)
        } catch (e: Exception) { return true }
    }
}
