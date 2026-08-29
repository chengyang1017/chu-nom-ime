package com.example.chineseime.ui.curator

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.widget.LinearLayout
import android.widget.ScrollView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.card.MaterialCardView

class PhraseCuratorActivity : AppCompatActivity() {
    private var curatorView: PhraseCuratorView? = null

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)

        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "Phrase Studio"
            subtitle = "Verify Nôm readings with source evidence"
            setTitleTextColor(TEXT)
            setSubtitleTextColor(MUTED)
            setBackgroundColor(BACKGROUND)
            navigationIcon = AppCompatResources.getDrawable(
                this@PhraseCuratorActivity,
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            navigationIcon?.setTint(TEXT)
            setNavigationOnClickListener { finish() }
            menu.add("Library").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
            menu.add("Export").setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(10), dp(18), dp(30))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBar = MaterialCardView(this).apply {
            setCardBackgroundColor(SURFACE)
            radius = dp(22).toFloat()
            cardElevation = dp(8).toFloat()
            strokeColor = BORDER
            strokeWidth = dp(1)
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(4), dp(12), dp(12))
        })

        curatorView = PhraseCuratorView(this).also { it.attach(content, bottomBar) }

        toolbar.setOnMenuItemClickListener { item ->
            when (item.title?.toString()) {
                "Library" -> {
                    curatorView?.showSavedPhrases()
                    true
                }
                "Export" -> {
                    curatorView?.copyCorpusJson()
                    true
                }
                else -> false
            }
        }

        setContentView(root)
    }

    override fun onDestroy() {
        curatorView?.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(10, 13, 18)
        private val SURFACE = Color.rgb(18, 24, 32)
        private val BORDER = Color.rgb(38, 50, 65)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(151, 163, 179)
    }
}
