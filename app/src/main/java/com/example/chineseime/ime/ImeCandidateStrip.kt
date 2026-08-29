package com.example.chineseime.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chineseime.data.model.NomSentenceCandidate

internal class ImeCandidateStrip(
    private val context: Context,
    private val host: LinearLayout,
    typeface: Typeface,
    private val onSelect: (Int) -> Unit
) {
    private data class Slot(
        val root: LinearLayout,
        val nom: TextView,
        val reading: TextView
    )

    private val slots = List(MAX_CANDIDATES) { index -> createSlot(index, typeface) }
    private var signature = ""

    init {
        slots.forEach { host.addView(it.root) }
    }

    fun render(values: List<NomSentenceCandidate>) {
        val visible = values.take(MAX_CANDIDATES)
        val nextSignature = visible.joinToString(SIGNATURE_SEPARATOR) {
            it.nomText + VALUE_SEPARATOR + it.restoredVietnamese
        }
        if (nextSignature == signature) return
        signature = nextSignature

        slots.forEachIndexed { index, slot ->
            val candidate = visible.getOrNull(index)
            if (candidate == null) {
                slot.root.visibility = View.GONE
                return@forEachIndexed
            }
            if (slot.nom.text.toString() != candidate.nomText) slot.nom.text = candidate.nomText
            if (slot.reading.text.toString() != candidate.restoredVietnamese) {
                slot.reading.text = candidate.restoredVietnamese
            }
            slot.root.visibility = View.VISIBLE
        }
    }

    fun clear() {
        if (signature.isEmpty() && slots.all { it.root.visibility == View.GONE }) return
        signature = ""
        slots.forEach { it.root.visibility = View.GONE }
    }

    private fun createSlot(index: Int, typeface: Typeface): Slot {
        val nom = TextView(context).apply {
            this.typeface = typeface
            textSize = 25f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = true
        }
        val reading = TextView(context).apply {
            textSize = 11.5f
            setTextColor(if (index == 0) ACCENT else MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(14), dp(6), dp(14), dp(6))
            minimumWidth = dp(104)
            minimumHeight = dp(66)
            background = roundedBackground(
                color = if (index == 0) ACCENT_DARK else SURFACE,
                stroke = if (index == 0) ACCENT else BORDER
            )
            layoutParams = LinearLayout.LayoutParams(-2, dp(66)).apply {
                rightMargin = dp(7)
            }
            visibility = View.GONE
            addView(nom, LinearLayout.LayoutParams(-2, dp(38)))
            addView(reading, LinearLayout.LayoutParams(-2, dp(20)))
            setOnClickListener {
                performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                onSelect(index)
            }
        }
        return Slot(root, nom, reading)
    }

    private fun roundedBackground(color: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = dp(15).toFloat()
            setColor(color)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_CANDIDATES = 8
        const val SIGNATURE_SEPARATOR = "\u0001"
        const val VALUE_SEPARATOR = "\u0000"
        val SURFACE = Color.rgb(18, 24, 32)
        val BORDER = Color.rgb(38, 50, 65)
        val TEXT = Color.rgb(245, 247, 250)
        val MUTED = Color.rgb(151, 163, 179)
        val ACCENT = Color.rgb(111, 199, 255)
        val ACCENT_DARK = Color.rgb(35, 74, 100)
    }
}
