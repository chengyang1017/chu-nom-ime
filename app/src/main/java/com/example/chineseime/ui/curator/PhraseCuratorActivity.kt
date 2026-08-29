package com.example.chineseime.ui.curator

import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout

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
            setTitleTextColor(TEXT)
            setBackgroundColor(BACKGROUND)
            navigationIcon = AppCompatResources.getDrawable(
                this@PhraseCuratorActivity,
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            navigationIcon?.setTint(TEXT)
            setNavigationOnClickListener { finish() }
            menu.add("Library").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add("Export").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBar = MaterialCardView(this).apply {
            setCardBackgroundColor(SURFACE)
            radius = dp(18).toFloat()
            cardElevation = dp(6).toFloat()
            strokeColor = BORDER
            strokeWidth = dp(1)
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(4), dp(12), dp(10))
        })

        curatorView = PhraseCuratorView(this).also { it.attach(content, bottomBar) }
        polishStudio(content, bottomBar)

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

    private fun polishStudio(content: View, bottomBar: MaterialCardView) {
        normalizeStudioViews(content)

        (bottomBar.getChildAt(0) as? LinearLayout)?.setPadding(
            dp(14),
            dp(8),
            dp(10),
            dp(8)
        )
        polishBottomBar(bottomBar)
    }

    private fun normalizeStudioViews(view: View) {
        when (view) {
            is TextInputLayout -> {
                view.hintEnabled = false
                view.hint = null
                view.placeholderText = null
                view.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                view.boxBackgroundColor = SURFACE_HIGH
                view.boxStrokeColor = ACCENT
                view.boxStrokeWidth = dp(1)
                view.boxStrokeWidthFocused = dp(2)
                view.setBoxCornerRadii(
                    dp(14).toFloat(),
                    dp(14).toFloat(),
                    dp(14).toFloat(),
                    dp(14).toFloat()
                )
                view.editText?.apply {
                    hint = "Type Vietnamese · e.g. tôi yêu em"
                    setHintTextColor(MUTED)
                    background = null
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
            }

            is LinearProgressIndicator -> {
                // Token chips and the explicit verified count communicate progress more
                // clearly on small phones. The stock indicator looked like a slider.
                view.visibility = View.GONE
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                normalizeStudioViews(view.getChildAt(index))
            }
        }
    }

    private fun polishBottomBar(view: View) {
        when (view) {
            is MaterialButton -> if (view.text?.toString() == "Save") {
                view.cornerRadius = dp(16)
                view.layoutParams = view.layoutParams.apply {
                    width = dp(96)
                    height = dp(48)
                }
            }

            is TextView -> when {
                view.text?.toString() == "CURRENT NÔM" -> view.textSize = 9f
                view.text?.toString() == "—" -> view.textSize = 24f
                view.text?.toString()?.contains("saved locally") == true -> view.textSize = 10f
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                polishBottomBar(view.getChildAt(index))
            }
        }
    }

    override fun onDestroy() {
        curatorView?.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(10, 13, 18)
        private val SURFACE = Color.rgb(18, 24, 32)
        private val SURFACE_HIGH = Color.rgb(23, 31, 41)
        private val BORDER = Color.rgb(38, 50, 65)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(151, 163, 179)
        private val ACCENT = Color.rgb(111, 199, 255)
    }
}
