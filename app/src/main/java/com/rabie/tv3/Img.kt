package com.rabie.tv3

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.widget.ImageView
import java.net.HttpURLConnection
import java.net.URL

/** تحميل لوقو القنوات — خفيف بلا مكتبات ثقيلة، بكاش داخل الذاكرة */
object Img {
    private val cache = HashMap<String, Bitmap>()

    fun load(act: Activity, url: String, iv: ImageView) {
        try {
            iv.tag = url
            if (url.isEmpty()) { iv.setImageResource(R.drawable.ic_app); return }
            val b = cache[url]
            if (b != null) { iv.setImageBitmap(b); return }
            iv.setImageResource(R.drawable.ic_app)
            Thread {
                var bmp: Bitmap? = null
                try {
                    var c: HttpURLConnection? = null
                    try {
                        c = URL(url).openConnection() as HttpURLConnection
                        c.connectTimeout = 8000; c.readTimeout = 8000
                        if (c.responseCode == 200) bmp = BitmapFactory.decodeStream(c.inputStream)
                    } finally { try { c?.disconnect() } catch (e: Exception) {} }
                } catch (e: Exception) {}
                if (bmp != null) {
                    try { if (cache.size > 120) cache.clear(); cache[url] = bmp!! } catch (e: Exception) {}
                }
                act.runOnUiThread {
                    try { if (bmp != null && iv.tag == url) iv.setImageBitmap(bmp) } catch (e: Exception) {}
                }
            }.start()
        } catch (e: Exception) {}
    }
}
