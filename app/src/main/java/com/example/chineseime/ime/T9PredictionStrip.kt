package com.example.chineseime.ime

import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import androidx.appcompat.widget.AppCompatTextView

internal class T9PredictionStrip(
    private val context: Context,
    private val host: LinearLayout,
    private val onSelect: (Int) -> Unit
) {
    private data class Slot(
        val root: LinearLayout,
        val label: AppCompatTextView,
        val indicator: View
    )

    private val slots = List(MAX_PREDICTIONS) { index -> createSlot(index) }
    private var signature = ""

    init {
        slots.forEach { host.addView(it.root) }
    }

    fun render(values: List<String>, selectedIndex: Int) {
        val visible = values.take(MAX_PREDICTIONS)
        val nextSignature = visible.joinToString(SIGNATURE_SEPARATOR) + "#$selectedIndex"
        if (nextSignature == signature) return
        signature = nextSignature

        slots.forEachIndexed { index, slot ->
            val value = visible.getOrNull(index)
            if (value == null) {
                slot.root.visibility = View.GONE
                return@forEachIndexed
            }

            if (slot.label.text.toString() != value) slot.label.text = value
            val selected = index == selectedIndex
            slot.label.setTextColor(if (selected) ACCENT else TEXT)
            slot.indicator.setBackgroundColor(if (selected) ACCENT else Color.TRANSPARENT)
            slot.root.visibility = View.VISIBLE
        }
    }

    fun clear() {
        if (signature.isEmpty() && slots.all { it.root.visibility == View.GONE }) return
        signature = ""
        slots.forEach { it.root.visibility = View.GONE }
    }

    private fun createSlot(index: Int): Slot {
        val label = AppCompatTextView(context).apply {
            gravity = Gravity.CENTER
            textSize = 12.5f
            setTextColor(TEXT)
            includeFontPadding = false
            maxLines = 1
            setPadding(dp(2), 0, dp(2), 0)
        }

        val indicator = View(context).apply {
            setBackgroundColor(Color.TRANSPARENT)
        }

        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            visibility = View.GONE
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(38)
            ).apply {
                bottomMargin = dp(2)
            }
            addView(
                label,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    0,
                    1f
                )
            )
            addView(
                indicator,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(2)
                )
            )
            setOnClickListener { onSelect(index) }
        }

        return Slot(root, label, indicator)
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_PREDICTIONS = 8
        const val SIGNATURE_SEPARATOR = "\u0001"
        val TEXT = Color.rgb(245, 247, 250)
        val ACCENT = Color.rgb(111, 199, 255)
    }
}
