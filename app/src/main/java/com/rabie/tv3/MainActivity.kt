package com.rabie.tv3

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView

/** القوائم — محرك Ultimate الأصلي يجلب، الكاش يفتح فوراً، والتجديد بالخلفية */
class MainActivity : Activity() {

    private lateinit var rvChips: RecyclerView
    private lateinit var rvList: RecyclerView
    private lateinit var ovLoad: FrameLayout
    private lateinit var tvStage: TextView
    private lateinit var tvUser: TextView
    private val prefs by lazy { getSharedPreferences("tv3", MODE_PRIVATE) }
    private var curGroup = "الكل"
    private val shown = ArrayList<Ch3>()
    private var refreshed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        rvChips = findViewById(R.id.rvChips); rvList = findViewById(R.id.rvList)
        ovLoad = findViewById(R.id.ovLoad); tvStage = findViewById(R.id.tvStage)
        tvUser = findViewById(R.id.tvUser)
        tvUser.text = "@" + (prefs.getString("username", "") ?: "")
        findViewById<TextView>(R.id.btnExit).setOnClickListener {
            prefs.edit().remove("username").remove("password").remove("server").remove("token").apply()
            startActivity(Intent(this, LoginActivity::class.java)); finish()
        }
        rvList.layoutManager = LinearLayoutManager(this)
        rvChips.layoutManager = LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false)
        // السيرفر من الجلسة
        try { Engine.srv = Engine.fromJson(prefs.getString("server", "") ?: "") } catch (e: Exception) { Engine.srv = null }
        if (Engine.srv == null) { startActivity(Intent(this, LoginActivity::class.java)); finish(); return }
        // فتح فوري من الكاش
        if (Engine.loadCache(this)) render()
        // تجديد بالخلفية
        startFetch()
    }

    private fun startFetch() {
        ovLoad.visibility = if (Engine.all.isEmpty()) View.VISIBLE else View.GONE
        tvStage.text = "الاتصال بالسيرفر..."
        refreshed = true
        Engine.fetch(this,
            { st -> runOnUiThread { tvStage.text = st } },
            { ok, err ->
                runOnUiThread {
                    ovLoad.visibility = View.GONE
                    if (ok) { curGroup = "الكل"; render() }
                    else if (Engine.all.isEmpty()) {
                        tvStage.text = err
                        ovLoad.visibility = View.VISIBLE
                    } else { Toast2(err) }
                }
            })
    }

    private fun Toast2(m: String) {
        try { android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show() } catch (e: Exception) {}
    }

    private fun render() {
        shown.clear()
        synchronized(Engine.groups) {
            for (c in Engine.all) if (curGroup == "الكل" || c.group == curGroup) shown.add(c)
        }
        rvList.adapter = ListAdapter()
        val names = ArrayList<String>()
        names.add("الكل")
        synchronized(Engine.groups) { names.addAll(Engine.groups.keys) }
        rvChips.adapter = ChipsAdapter(names)
    }

    inner class ChipsAdapter(val names: List<String>) : RecyclerView.Adapter<ChipsAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) { val tv: TextView = v.findViewById(R.id.tvChip) }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH = VH(layoutInflater.inflate(R.layout.item_chip, p, false))
        override fun getItemCount(): Int = names.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val n = names[i]
            h.tv.text = n
            h.tv.setTextColor(resources.getColor(if (n == curGroup) R.color.gold else R.color.text))
            try { h.tv.setBackgroundResource(if (n == curGroup) R.drawable.card_focus else R.drawable.card) } catch (e: Exception) {}
            h.tv.setOnClickListener { curGroup = n; render() }
            h.tv.setOnFocusChangeListener { v, f ->
                try { v.animate().scaleX(if (f) 1.07f else 1f).scaleY(if (f) 1.07f else 1f).setDuration(110).start() } catch (e: Exception) {}
            }
        }
    }

    inner class ListAdapter : RecyclerView.Adapter<ListAdapter.VH>() {
        inner class VH(v: View) : RecyclerView.ViewHolder(v) {
            val card: LinearLayout = v.findViewById(R.id.card)
            val img: ImageView = v.findViewById(R.id.imgLogo)
            val n: TextView = v.findViewById(R.id.tvName)
            val g: TextView = v.findViewById(R.id.tvGroup)
        }
        override fun onCreateViewHolder(p: ViewGroup, t: Int): VH = VH(layoutInflater.inflate(R.layout.item_ch, p, false))
        override fun getItemCount(): Int = shown.size
        override fun onBindViewHolder(h: VH, i: Int) {
            val c = shown[i]
            h.n.text = c.name
            h.g.text = c.group
            try { Img.load(this@MainActivity, c.logo, h.img) } catch (e: Exception) {}
            h.card.setOnClickListener { openPlayer(c) }
            h.card.setOnFocusChangeListener { v, f ->
                try {
                    h.card.setBackgroundResource(if (f) R.drawable.card_focus else R.drawable.card)
                    v.animate().scaleX(if (f) 1.04f else 1f).scaleY(if (f) 1.04f else 1f).setDuration(110).start()
                    if (f) rvList.scrollToPosition(i)
                } catch (e: Exception) {}
            }
        }
    }

    private fun openPlayer(c: Ch3) {
        val it = Intent(this, PlayerActivity::class.java)
        it.putExtra("chId", c.id)
        startActivity(it)
    }
}
