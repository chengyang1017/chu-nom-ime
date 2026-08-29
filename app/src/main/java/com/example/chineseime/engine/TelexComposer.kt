package com.example.chineseime.engine

import java.text.Normalizer
import java.util.Locale

class TelexComposer {
    private val toneMarks = mapOf('s' to 0x0301, 'f' to 0x0300, 'r' to 0x0309, 'x' to 0x0303, 'j' to 0x0323)

    fun compose(input: String): String = input.split(" ").joinToString(" ") { composeWord(it) }

    fun toTelex(input: String): String = input.split(" ").joinToString(" ") { word ->
        var tone = ""
        val output = StringBuilder()
        val nfd = Normalizer.normalize(word, Normalizer.Form.NFD).lowercase(Locale.ROOT)
        val points = nfd.codePoints().toArray()
        var index = 0
        while (index < points.size) {
            val cp = points[index]
            if (cp == 0x0111) {
                output.append("dd"); index++; continue
            }
            if (cp in 'a'.code..'z'.code) {
                val base = cp
                var modifier = 0
                index++
                while (index < points.size && Character.getType(points[index]) == Character.NON_SPACING_MARK.toInt()) {
                    when (points[index]) {
                        0x0306, 0x0302, 0x031B -> modifier = points[index]
                        0x0301 -> tone = "s"
                        0x0300 -> tone = "f"
                        0x0309 -> tone = "r"
                        0x0303 -> tone = "x"
                        0x0323 -> tone = "j"
                    }
                    index++
                }
                output.appendCodePoint(base)
                output.append(when {
                    base == 'a'.code && modifier == 0x0306 -> "w"
                    base == 'a'.code && modifier == 0x0302 -> "a"
                    base == 'e'.code && modifier == 0x0302 -> "e"
                    base == 'o'.code && modifier == 0x0302 -> "o"
                    base == 'o'.code && modifier == 0x031B -> "w"
                    base == 'u'.code && modifier == 0x031B -> "w"
                    else -> ""
                })
            } else {
                output.appendCodePoint(cp); index++
            }
        }
        output.append(tone).toString()
    }

    private fun composeWord(raw: String): String {
        if (raw.isEmpty()) return raw
        var word = raw.lowercase(Locale.ROOT)
            .replace("dd", "đ").replace("aw", "ă").replace("aa", "â")
            .replace("ee", "ê").replace("oo", "ô").replace("ow", "ơ").replace("uw", "ư")
        val toneKey = word.lastOrNull()?.takeIf { it in toneMarks.keys }
        if (toneKey != null) word = word.dropLast(1)
        val tone = toneKey?.let { toneMarks[it] } ?: return word
        val points = word.codePoints().toArray()
        val vowelIndices = points.indices.filter { isVowel(points[it]) }
        if (vowelIndices.isEmpty()) return word + toneKey
        val preferred = vowelIndices.firstOrNull { points[it] in intArrayOf('ă'.code, 'â'.code, 'ê'.code, 'ô'.code, 'ơ'.code, 'ư'.code) }
        val target = preferred ?: if (vowelIndices.size == 1) vowelIndices[0] else vowelIndices[vowelIndices.size - 2]
        val output = StringBuilder()
        points.forEachIndexed { index, cp ->
            output.appendCodePoint(cp)
            if (index == target) output.appendCodePoint(tone)
        }
        return Normalizer.normalize(output.toString(), Normalizer.Form.NFC)
    }

    private fun isVowel(cp: Int) = cp in intArrayOf(
        'a'.code, 'ă'.code, 'â'.code, 'e'.code, 'ê'.code, 'i'.code,
        'o'.code, 'ô'.code, 'ơ'.code, 'u'.code, 'ư'.code, 'y'.code
    )
}