package com.rabie.tv3

import android.app.Activity
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.ui.PlayerView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/**
 * المشغل — مشغل واحد مثبت على السطح من البداية ولا يُفصل أبداً:
 * القائمة الجانبية تطلع فوق الفيديو، التبديل يصير خلفها، وبطاقة اللوقو تغطي منطقة الفيديو حتى يأتي البث — صفر شاشة سوداء.
 */
class PlayerActivity : Activity() {

    private lateinit var pv: PlayerView
    private lateinit var logoCard: View
    private lateinit var imgCardLogo: ImageView
    private lateinit var tvCardName: TextView
    private lateinit var sidePanel: LinearLayout
    private lateinit var rvSide: RecyclerView
    private lateinit var tvMsg: TextView
    private var player: ExoPlayer? = null

    private var cur: Ch3? = null
    private var rescueUrls = ArrayList<String>()
    private var rescueIdx = 0
    private var retried = 0
    private val tried = ArrayList<String>()
    private val prefs by lazy { getSharedPreferences("tv3", MODE_PRIVATE) }
    private var dsFactory: DefaultHttpDataSource.Factory? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_player)
        pv = findViewById(R.id.pv); logoCard = findViewById(R.id.logoCard)
        imgCardLogo = findViewById(R.id.imgCardLogo); tvCardName = findViewById(R.id.tvCardName)
        sidePanel = findViewById(R.id.sidePanel); rvSide = findViewById(R.id.rvSide)
        tvMsg = findViewById(R.id.tvMsg)
        rvSide.layoutManager = LinearLayoutManager(this)

        val ds = DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(12000).setReadTimeoutMs(20000)
            .setUserAgent(Engine.macUA)
        dsFactory = ds
        player = ExoPlayer.Builder(this)
            .setMediaSourceFactory(DefaultMediaSourceFactory(ds))
            .build()
        pv.player = player
        player!!.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(s: Int) {
                if (s == Player.STATE_READY) { logoCard.visibility = View.GONE; tvMsg.visibility = View.GONE }
            }
            override fun onPlayerError(error: PlaybackException) { onError3(error) }
        })
        val id = intent.getStringExtra("chId") ?: ""
        var first: Ch3? = null
        synchronized(Engine.all) { for (c in Engine.all) if (c.id == id) { first = c; break } }
        if (first != null) playCh(first!!)
        else finish()
    }

    // ═══ التبديل داخل المشغل — نفس المشغل، بلا فصل سطح، البطاقة قبل كل شيء ═══
    private fun playCh(c: Ch3) {
        cur = c
        rescueUrls = Rescuer.urlsFor(c.name); rescueIdx = 0; retried = 0; tried.clear()
        showCard(c)
        if (c.kind == "mac" && c.u.isEmpty()) resolveMac(c) else startUrl(c.u)
    }

    private fun showCard(c: Ch3) {
        try {
            tvCardName.text = c.name
            Img.load(this, c.logo, imgCardLogo)
            logoCard.visibility = View.VISIBLE
        } catch (e: Exception) {}
    }

    private fun resolveMac(c: Ch3) {
        Thread {
            val url = Engine.macCreateLink(c.cmd)
            runOnUiThread {
                if (url != null && !isFinishing) startUrl(url)
                else if (!isFinishing) rescueNext("تعذر طلب الرابط من البوابة")
            }
        }.start()
    }

    private fun startUrl(url: String) {
        if (isFinishing || player == null) return
        tried.add(url)
        // ترويسات سيرفرات MAC (كوكيز + توكن + Referer) — وللمصادر العادية بلا ترويسات
        try {
            if (cur?.kind == "mac") {
                val hm = HashMap<String, String>()
                hm.put("Cookie", Engine.macCookie)
                if (Engine.macBase.isNotEmpty()) {
                    var b = Engine.macBase.substringBefore("/portal.php").substringBefore("/server/load.php")
                    if (b.isNotEmpty()) hm.put("Referer", b + "/")
                }
                if (Engine.macToken.isNotEmpty()) hm.put("Authorization", "Bearer " + Engine.macToken)
                dsFactory?.setDefaultRequestProperties(hm)
            } else dsFactory?.setDefaultRequestProperties(java.util.HashMap())
        } catch (e: Exception) {}
        player!!.setMediaItem(MediaItem.fromUri(url))
        player!!.prepare()
        player!!.playWhenReady = true
    }

    private fun onError3(error: PlaybackException) {
        if (isFinishing || cur == null) return
        // المنقذ الصامت: روابط بديلة من لوحة RABIE TV بالترتيب
        if (rescueIdx < rescueUrls.size) {
            val u = rescueUrls[rescueIdx]; rescueIdx++
            showCard(cur!!)
            startUrl(u); return
        }
        rescueNext("القناة غير متوفرة — اختر قناة أخرى من القائمة (زر OK)")
    }

    /** سلسلة المحاولات الأخيرة: إعادة حل MAC ثم إعادة الرابط الأخير ثم رسالة */
    private fun rescueNext(msg: String) {
        if (retried < 1 && cur?.kind == "mac") {
            retried++; showCard(cur!!); resolveMac(cur!!); return
        }
        if (retried < 2 && tried.isNotEmpty()) {
            retried++; showCard(cur!!)
            startUrl(tried.last()); return
        }
        logoCard.visibility = View.GONE
        try {
            tvMsg.text = msg
            tvMsg.visibility = View.VISIBLE
        } catch (e: Exception) {}
    }

    // ═══ القائمة الجانبية داخل المشغل ═══
    private fun panelOpen(): Boolean = sidePanel.visibility == View.VISIBLE

    private fun togglePanel() {
        if (panelOpen()) { sidePanel.visibility = View.GONE; try { pv.requestFocus() } catch (e: Exception) {} }
        else {
            sidePanel.visibility = View.VISIBLE
            rvSide.adapter = SideAdapter()
            val idx = curIdx()
            (rvSide.layoutManager as LinearLayoutManager).scrollToPosition(idx)
            rvSide.post { try { rvSide.findViewHolderForAdapterPosition(idx)?.itemView?.requestFocus() } catch (e: Exception) {} }
        }
    }

    private fun curIdx(): Int {
        synchronized(Engine.all) { for (i in 0 until Engine.all.size) if (Engine.all[i].id == cur?.id) return i }
        return 0
    }

    inner class SideAdapter : RecyclerView.Adapter<SideAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: LinearLayout = v.findViewById(R.id.card)
            val img: ImageView = v.findViewById(R.id.imgLogo)
            val n: TextView = v.findViewById(R.id.tvName)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH = VH(layoutInflater.inflate(R.layout.item_side, p, false))
        override fun getItemCount(): Int = synchronized(Engine.all) { Engine.all.size }
        override fun onBindViewHolder(h: VH, i: Int) {
            val c = synchronized(Engine.all) { Engine.all[i] }
            h.n.text = c.name
            h.n.setTextColor(resources.getColor(if (c.id == cur?.id) R.color.gold else R.color.text))
            try { Img.load(this@PlayerActivity, c.logo, h.img) } catch (e: Exception) {}
            h.card.setOnClickListener { v ->
                playCh(c)
                sidePanel.visibility = View.GONE
                try { pv.requestFocus() } catch (e: Exception) {}
            }
            h.card.setOnFocusChangeListener { v, f ->
                try {
                    h.card.setBackgroundResource(if (f) R.drawable.card_focus else R.drawable.card)
                    v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(110).start()
                    if (f) rvSide.scrollToPosition(i)
                } catch (e: Exception) {}
            }
        }
    }

    // ═══ الريموت: OK يفتح/يقفل القائمة، الرجوع يخرج ═══
    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        if (panelOpen()) {
            if (keyCode == KeyEvent.KEYCODE_BACK) { togglePanel(); return true }
            return super.onKeyDown(keyCode, event) // التصفح داخل القائمة
        }
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_MENU, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN -> {
                togglePanel(); return true
            }
            KeyEvent.KEYCODE_BACK -> { finish(); return true }
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onDestroy() {
        try { player?.stop(); player?.release() } catch (e: Exception) {}
        player = null
        super.onDestroy()
    }
}

/** سرفر المنقذ — روابط بديلة من لوحة RABIE TV (backup_links) — صامت تماماً */
object Rescuer {
    private val cache = ArrayList<Section>()
    private var loadedAt = 0L
    class Section(val keys: ArrayList<String>, val urls: ArrayList<String>)

    private fun norm(s: String): String {
        return s.toLowerCase().replace(" ", "").replace("-", "").replace("_", "").replace(".", "")
            .replace("|", "").replace("hd", "").replace("sd", "").replace("fhd", "").replace("h265", "").trim()
    }

    private fun ensure() {
        try {
            if (System.currentTimeMillis() - loadedAt < 120000 && cache.isNotEmpty()) return
            val body = Fs.get("/backup_links") ?: return
            val root = Fs.obj(body) ?: return
            cache.clear()
            val secKeys = root.keys()
            while (secKeys.hasNext()) {
                val sk = secKeys.next()
                val sec = Fs.obj(root.getJSONObject(sk).toString()) ?: continue
                val chKeys = sec.keys()
                while (chKeys.hasNext()) {
                    val ck = chKeys.next()
                    val cv = Fs.obj(sec.getJSONObject(ck).toString()) ?: continue
                    val keys = ArrayList<String>()
                    keys.add(norm(ck))
                    val nm = Fs.s(cv, "name"); if (nm.isNotEmpty()) keys.add(norm(nm))
                    val alt = Fs.s(cv, "alt")
                    if (alt.isNotEmpty()) for (p in alt.split("|", ",", "،")) if (p.trim().isNotEmpty()) keys.add(norm(p))
                    val urls = ArrayList<String>()
                    if (cv.has("links")) {
                        val lk = cv.get("links")
                        try {
                            if (lk is org.json.JSONArray) { for (i in 0 until lk.length()) {
                                val e = lk.get(i)
                                if (e is org.json.JSONObject) { val u = Fs.s(e, "url"); if (u.startsWith("http")) urls.add(u) }
                                else { val u = e.toString(); if (u.startsWith("http")) urls.add(u) }
                            } }
                            else if (lk is org.json.JSONObject) { val ks = lk.keys(); while (ks.hasNext()) { val u = Fs.s(lk, ks.next()); if (u.startsWith("http")) urls.add(u) } }
                        } catch (e: Exception) {}
                    }
                    if (urls.isNotEmpty()) cache.add(Section(keys, urls))
                }
            }
            loadedAt = System.currentTimeMillis()
        } catch (e: Exception) {}
    }

    fun urlsFor(name: String): ArrayList<String> {
        ensure()
        val out = ArrayList<String>()
        if (name.isEmpty()) return out
        val n = norm(name)
        if (n.isEmpty()) return out
        for (s in cache) {
            var hit = false
            for (k in s.keys) if (k == n || (k.length > 3 && n.contains(k)) || (n.length > 3 && k.contains(n))) { hit = true; break }
            if (hit) for (u in s.urls) if (!out.contains(u)) out.add(u)
        }
        return out
    }
}
