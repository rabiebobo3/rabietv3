package com.rabie.tv3

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import org.json.JSONObject
import java.util.UUID

/** شاشة الدخول — حساب المشترك من لوحة RABIE TV */
class LoginActivity : Activity() {

    private lateinit var edUser: EditText
    private lateinit var edPass: EditText
    private lateinit var tvErr: TextView
    private lateinit var btnGo: Button
    private val prefs by lazy { getSharedPreferences("tv3", MODE_PRIVATE) }
    private var busy = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_login)
        edUser = findViewById(R.id.edUser); edPass = findViewById(R.id.edPass)
        tvErr = findViewById(R.id.tvErr); btnGo = findViewById(R.id.btnGo)
        findViewById<TextView>(R.id.tvVer).text = "v" + versionName() + " · RABIE TV"
        // جلسة محفوظة → تحقق صامت سريع
        val su = prefs.getString("username", "") ?: ""
        val sp = prefs.getString("password", "") ?: ""
        if (!su.isEmpty() && !sp.isEmpty()) {
            tvErr.text = "جاري التحقق من الحساب..."; tvErr.visibility = TextView.VISIBLE; tvErr.setTextColor(getColor(R.color.textDim))
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

    private fun showErr(m: String, isErr: Boolean = true) {
        tvErr.text = m
        tvErr.setTextColor(resources.getColor(if (isErr) R.color.err else R.color.textDim))
        tvErr.visibility = TextView.VISIBLE
    }

    private fun login(user: String, pass: String, silent: Boolean) {
        if (user.isEmpty() || pass.isEmpty()) { if (!silent) showErr("أدخل اسم المستخدم وكلمة المرور"); return }
        busy = true
        Thread {
            val body = Fs.get("/users/" + java.net.URLEncoder.encode(user, "UTF-8"))
            val o = Fs.obj(body)
            runOnUiThread {
                busy = false
                if (o == null) {
                    if (!silent) showErr("اسم المستخدم غير موجود أو تعذر الاتصال")
                    else showErr("تعذر التحقق — أدخل الحساب من جديد")
                    return@runOnUiThread
                }
                val dbPass = Fs.s(o, "password")
                val blocked = Fs.s(o, "blocked").toLowerCase()
                val expiry = Fs.s(o, "expiry")
                val serverId = Fs.s(o, "server_id")
                if (dbPass.isEmpty()) { showErr("كلمة المرور غير معرفة في اللوحة"); return@runOnUiThread }
                if (dbPass != pass) { if (!silent) showErr("كلمة المرور غير صحيحة"); else showErr("🔁 تم تغيير كلمة المرور — سجّل من جديد"); return@runOnUiThread }
                if (blocked == "true" || blocked == "1") { showErr("الحساب محظور — تواصل مع الإدارة"); return@runOnUiThread }
                if (!Fs.expiryOk(expiry)) { showErr("انتهى الاشتراك بتاريخ " + expiry + " — تواصل مع الإدارة"); return@runOnUiThread }
                // منع مشاركة الحساب: رمز جلسة جديد يبطل القديمة
                val token = UUID.randomUUID().toString()
                Thread { Fs.http("PUT", "/sessions/" + java.net.URLEncoder.encode(user, "UTF-8"), JSONObject().put("token", token).toString()) }.start()
                prefs.edit().putString("username", user).putString("password", pass)
                    .putString("expiry", expiry).putString("token", token).apply()
                // جلب السيرفر المرتبط بالحساب
                var srvJson = ""
                if (serverId.isNotEmpty()) {
                    val sb = Fs.get("/servers/" + java.net.URLEncoder.encode(serverId, "UTF-8"))
                    val so = Fs.obj(sb)
                    if (so != null) srvJson = buildServerJson(so, serverId)
                }
                prefs.edit().putString("server", srvJson).apply()
                startActivity(Intent(this, MainActivity::class.java))
                finish()
            }
        }.start()
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
