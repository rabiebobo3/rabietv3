package com.rabie.tv3

import android.app.Application
import org.json.JSONObject

/** حارس أعطال RabieTV3 — يرفع الأثر للوحة تلقائياً (نجلب السبب الحقيقي ونعالجه) */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Thread.setDefaultUncaughtExceptionHandler { t, e ->
            try {
                val trace = android.util.Log.getStackTraceString(e)
                val vc = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (eV: Exception) { "" }
                val tr = if (trace.length > 60000) trace.substring(0, 60000) else trace
                Thread {
                    try {
                        val o = JSONObject(); o.put("trace", tr)
                        Fs.http("PUT", "/crash_reports_tv3/" + vc + "/" + System.currentTimeMillis(), o.toString())
                    } catch (eU: Exception) {}
                }.start()
                Thread.sleep(400)
            } catch (e2: Exception) {}
            android.os.Process.killProcess(android.os.Process.myPid())
        }
    }
}
