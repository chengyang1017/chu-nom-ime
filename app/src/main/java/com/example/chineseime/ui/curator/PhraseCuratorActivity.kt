package com.example.chineseime.ui.curator

import android.os.Bundle
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity

class PhraseCuratorActivity : AppCompatActivity() {
    private var curatorView: PhraseCuratorView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = "Verified Nôm Phrase Curator"
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(32))
        }
        curatorView = PhraseCuratorView(this).also { it.attach(content) }

        setContentView(ScrollView(this).apply {
            isFillViewport = true
            addView(content)
        })
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    override fun onDestroy() {
        curatorView?.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
