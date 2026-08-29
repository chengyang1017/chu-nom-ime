package com.example.chineseime.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chineseime.data.model.NomSentenceCandidate

internal class ImeCandidateStrip(
    private val context: Context,
    private val host: LinearLayout,
    typeface: Typeface,
    private val showReading: () -> Boolean,
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
        val readingVisible = showReading()
        val nextSignature = buildString {
            append(if (readingVisible) '1' else '0')
            visible.forEach {
                append(SIGNATURE_SEPARATOR)
                append(it.nomText)
                append(VALUE_SEPARATOR)
                append(it.restoredVietnamese)
            }
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
            slot.reading.visibility = if (readingVisible) View.VISIBLE else View.GONE
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
            textSize = 27f
            setTextColor(TEXT)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = true
            setPadding(0, dp(1), 0, dp(1))
        }
        val reading = TextView(context).apply {
            textSize = 10.5f
            setTextColor(MUTED)
            gravity = Gravity.CENTER
            maxLines = 1
            includeFontPadding = true
            visibility = View.GONE
        }
        val root = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(6), 0, dp(6), 0)
            minimumWidth = dp(54)
            minimumHeight = dp(50)
            clipChildren = false
            clipToPadding = false
            background = roundedBackground(
                color = if (index == 0) SURFACE_HIGH else Color.TRANSPARENT,
                stroke = if (index == 0) BORDER else Color.TRANSPARENT
            )
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                rightMargin = dp(1)
            }
            visibility = View.GONE
            addView(
                nom,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
            addView(
                reading,
                LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
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
            cornerRadius = dp(9).toFloat()
            setColor(color)
            if (stroke != Color.TRANSPARENT) setStroke(dp(1), stroke)
        }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    private companion object {
        const val MAX_CANDIDATES = 8
        const val SIGNATURE_SEPARATOR = '\u0001'
        const val VALUE_SEPARATOR = '\u0000'
        val SURFACE_HIGH = Color.rgb(24, 33, 43)
        val BORDER = Color.rgb(38, 50, 65)
        val TEXT = Color.rgb(245, 247, 250)
        val MUTED = Color.rgb(151, 163, 179)
    }
}
