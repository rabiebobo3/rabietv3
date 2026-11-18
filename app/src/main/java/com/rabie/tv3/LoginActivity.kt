package com.rabie.tv3

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import org.json.JSONObject
import java.util.UUID

/** شاشة الدخول — حساب المشترك من لوحة RABIE TV — ثابتة ما كتتحركش، ومن غير حلقة رجوع */
class LoginActivity : Activity() {

    private lateinit var edUser: EditText
    private lateinit var edPass: EditText
    private lateinit var tvErr: TextView
    private lateinit var btnGo: Button
    private val prefs by lazy { getSharedPreferences("tv3", MODE_PRIVATE) }
    private var busy = false
    /** منع إعادة المحاولة الصامتة داخل نفس الشاشة (لا حلقة) */
    private var silentTried = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        edUser = findViewById(R.id.edUser); edPass = findViewById(R.id.edPass)
        tvErr = findViewById(R.id.tvErr); btnGo = findViewById(R.id.btnGo)
        findViewById<TextView>(R.id.tvVer).text = "v" + versionName() + " · RABIE TV"
        edUser.requestFocus()
        // جلسة محفوظة → تحقق صامت واحد فقط
        val su = prefs.getString("username", "") ?: ""
        val sp = prefs.getString("password", "") ?: ""
        if (!su.isEmpty() && !sp.isEmpty() && !silentTried) {
            silentTried = true
            msg("جاري التحقق من الحساب...", false)
            login(su, sp, true)
        }
        btnGo.setOnClickListener { doLogin() }
    }

    private fun versionName(): String {
        return try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "" }
    }

    private fun doLogin() {
        if (busy) return
        login(edUser.text.toString().trim(), edPass.text.toString().trim(), false)
    }

    /** رسالة ثابتة في خانتها — بلا أي حركة في الشاشة */
    private fun msg(m: String, isErr: Boolean = true) {
        try {
            tvErr.text = m
            tvErr.setTextColor(resources.getColor(if (isErr) R.color.err else R.color.textDim))
        } catch (e: Exception) {}
    }

    private fun login(user: String, pass: String, silent: Boolean) {
        if (user.isEmpty() || pass.isEmpty()) { if (!silent) msg("أدخل اسم المستخدم وكلمة المرور"); return }
        busy = true
        Thread {
            val body = Fs.get("/users/" + Fs.pathEnc(user))
            val o = Fs.obj(body)
            runOnUiThread {
                busy = false
                if (o == null) {
                    if (!silent) msg("تعذر الاتصال باللوحة أو الحساب غير موجود — أعد المحاولة")
                    else msg("تعذر التحقق — سجل الحساب من جديد")
                    return@runOnUiThread
                }
                val dbPass = Fs.s(o, "password")
                val blocked = Fs.s(o, "blocked").toLowerCase()
                val expiry = Fs.s(o, "expiry")
                val serverId = Fs.s(o, "server_id")
                if (dbPass.isEmpty()) { msg("كلمة المرور غير معرفة في اللوحة"); return@runOnUiThread }
                if (dbPass != pass) { if (!silent) msg("كلمة المرور غير صحيحة"); else msg("🔁 تم تغيير كلمة المرور — سجّل من جديد"); return@runOnUiThread }
                if (blocked == "true" || blocked == "1") { msg("الحساب محظور — تواصل مع الإدارة"); return@runOnUiThread }
                if (!Fs.expiryOk(expiry)) { msg("انتهى الاشتراك بتاريخ " + expiry + " — تواصل مع الإدارة"); return@runOnUiThread }
                // جلب السيرفر المرتبط بالحساب — بنفس مرونة اللوحة (ids بأرقام، بـ server، وحتى بفراغ)
                val srvJson = resolveServer(serverId)
                if (srvJson.isEmpty()) {
                    msg("الحساب مربوط بسيرفر غير موجود في اللوحة — تواصل مع الإدارة")
                    return@runOnUiThread
                }
                // منع مشاركة الحساب: رمز جلسة جديد يبطل القديمة
                val token = UUID.randomUUID().toString()
                Thread { Fs.http("PUT", "/sessions/" + Fs.pathEnc(user), JSONObject().put("token", token).toString()) }.start()
                prefs.edit().putString("username", user).putString("password", pass)
                    .putString("expiry", expiry).putString("token", token)
                    .putString("server", srvJson).apply()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }.start()
    }

    /** servers/{sid} ثم servers/server{sid} ثم servers/server {sid} — فشل الكل = إبقاء المستخدم هنا بلا حلقة */
    private fun resolveServer(sid: String): String {
        if (sid.isEmpty()) return ""
        val s1 = Fs.obj(Fs.get("/servers/" + Fs.pathEnc(sid)))
        if (s1 != null) return buildServerJson(s1, sid)
        if (sid.matches(Regex("\\d+"))) {
            val s2 = Fs.obj(Fs.get("/servers/" + Fs.pathEnc("server" + sid)))
            if (s2 != null) return buildServerJson(s2, "server" + sid)
            val s3 = Fs.obj(Fs.get("/servers/" + Fs.pathEnc("server " + sid)))
            if (s3 != null) return buildServerJson(s3, "server " + sid)
        }
        return ""
    }

    /** servers/{id} → JSON سيرفر موحد (نفس بروتوكول اللوحة المفتوح) */
    private fun buildServerJson(s: JSONObject, sid: String): String {
        val o = JSONObject()
        val type = Fs.s(s, "type").toLowerCase()
        o.put("id", sid)
        o.put("name", if (Fs.s(s, "name").isEmpty()) "سيرفر RABIE TV" else Fs.s(s, "name"))
        if (type == "xtream") {
            o.put("type", "xtream"); o.put("url", Fs.s(s, "url"))
            o.put("username", Fs.s(s, "username")); o.put("password", Fs.s(s, "password"))
        } else if (type == "mac") {
            o.put("type", "mac"); o.put("portal", if (Fs.s(s, "portal").isEmpty()) Fs.s(s, "url") else Fs.s(s, "portal"))
            o.put("mac", Fs.s(s, "mac"))
        } else {
            o.put("type", "m3u"); o.put("url", Fs.s(s, "url"))
        }
        return o.toString()
    }
}
