package com.example.chineseime.engine

import java.text.Normalizer
import java.util.Locale

data class VietnameseInput(
    val raw: String,
    val composed: String,
    val normalized: String,
    val withoutTone: String,
    val telexKey: String
)

class VietnameseInputParser(private val telex: TelexComposer = TelexComposer()) {
    fun parse(input: String): VietnameseInput {
        val composed = telex.compose(input)
        return VietnameseInput(
            raw = input,
            composed = composed,
            normalized = normalize(composed),
            withoutTone = withoutTone(composed),
            telexKey = input.lowercase(Locale.ROOT)
        )
    }

    companion object {
        fun normalize(value: String): String = Normalizer.normalize(value, Normalizer.Form.NFC).lowercase(Locale.ROOT)
        fun withoutTone(value: String): String {
            val decomposed = Normalizer.normalize(value, Normalizer.Form.NFD)
            val output = StringBuilder()
            decomposed.codePoints().forEach { codePoint ->
                val type = Character.getType(codePoint)
                if (type != Character.NON_SPACING_MARK.toInt() && type != Character.COMBINING_SPACING_MARK.toInt()) {
                    when (codePoint) {
                        0x0111 -> output.append('d')
                        0x0110 -> output.append('D')
                        else -> output.appendCodePoint(codePoint)
                    }
                }
            }
            return output.toString().lowercase(Locale.ROOT)
        }
    }
}