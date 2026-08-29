package com.example.chineseime.ime

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView
import com.example.chineseime.R

internal class T9PredictionStrip(
    private val context: Context,
    private val host: LinearLayout,
    private val onSelect: (Int) -> Unit
) {
    private val slots = List(MAX_PREDICTIONS) { index -> createSlot(index) }
    private var signature = ""

    init {
        slots.forEach(host::addView)
    }

    fun render(values: List<String>, selectedIndex: Int) {
        val visible = values.take(MAX_PREDICTIONS)
        val nextSignature = visible.joinToString(SIGNATURE_SEPARATOR) + "#$selectedIndex"
        if (nextSignature == signature) return
        signature = nextSignature

        slots.forEachIndexed { index, slot ->
            val value = visible.getOrNull(index)
            if (value == null) {
                slot.visibility = View.GONE
                return@forEachIndexed
            }

            if (slot.text.toString() != value) slot.text = value
            val selected = index == selectedIndex
            slot.setTextColor(if (selected) ON_ACCENT else TEXT)
            slot.backgroundTintList = ColorStateList.valueOf(if (selected) ACCENT else SURFACE_HIGH)
            slot.visibility = View.VISIBLE
        }
    }

    fun clear() {
        if (signature.isEmpty() && slots.all { it.visibility == View.GONE }) return
        signature = ""
        slots.forEach { it.visibility = View.GONE }
    }

    private fun createSlot(index: Int) = AppCompatTextView(context).apply {
        gravity = Gravity.CENTER
        textSize = 14f
        setTextColor(TEXT)
        minWidth = dp(72)
        minHeight = dp(38)
        setPadding(dp(14), 0, dp(14), 0)
        background = context.getDrawable(R.drawable.key_function_background)
        visibility = View.GONE
        layoutParams = LinearLayout.LayoutParams(-2, dp(38)).apply {
            rightMargin = dp(6)
        }
        setOnClickListener { onSelect(index) }
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_PREDICTIONS = 8
        const val SIGNATURE_SEPARATOR = "\u0001"
        val SURFACE_HIGH = Color.rgb(24, 33, 43)
        val TEXT = Color.rgb(245, 247, 250)
        val ACCENT = Color.rgb(111, 199, 255)
        val ON_ACCENT = Color.rgb(4, 17, 26)
    }
}
