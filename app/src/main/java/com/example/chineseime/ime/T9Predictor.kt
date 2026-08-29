package com.example.chineseime.ime

import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.VietnameseInputParser
import java.text.Normalizer

enum class T9Tone(
    val label: String,
    private val toneMark: Int?
) {
    AUTO("调", null),
    NGANG("—", NO_TONE_MARK),
    SAC("´", 0x0301),
    HUYEN("`", 0x0300),
    HOI("ˀ", 0x0309),
    NGA("~", 0x0303),
    NANG(".", 0x0323);

    fun next(): T9Tone {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }

    fun matches(reading: String): Boolean {
        if (this == AUTO) return true
        val actualTone = findToneMark(reading)
        return if (toneMark == NO_TONE_MARK) actualTone == null else actualTone == toneMark
    }

    companion object {
        private const val NO_TONE_MARK = -1
        private val VIETNAMESE_TONE_MARKS = setOf(0x0301, 0x0300, 0x0309, 0x0303, 0x0323)

        private fun findToneMark(reading: String): Int? {
            val decomposed = Normalizer.normalize(reading, Normalizer.Form.NFD)
            return decomposed.codePoints()
                .toArray()
                .firstOrNull { it in VIETNAMESE_TONE_MARKS }
                ?.takeIf { it in VIETNAMESE_TONE_MARKS }
        }
    }
}

/** Predicts tone-less Vietnamese readings from classic phone-key digit sequences. */
class T9Predictor(private val repository: NomRepository) {
    private data class RankedPrediction(
        val reading: String,
        val evidenceCount: Int,
        val firstSourceRow: Int
    )

    fun predict(digits: String, limit: Int = DEFAULT_LIMIT): List<String> {
        if (digits.isBlank() || limit <= 0) return emptyList()
        if (digits.any { it !in KEY_GROUPS }) return emptyList()

        var prefixes = listOf("")
        digits.forEach { digit ->
            val group = requireNotNull(KEY_GROUPS[digit])
            val next = ArrayList<String>()
            prefixes.forEach { prefix ->
                group.forEach { letter ->
                    val candidatePrefix = prefix + letter
                    if (repository.searchWithoutTonePrefix(candidatePrefix, 1).isNotEmpty()) {
                        next += candidatePrefix
                    }
                }
            }
            if (next.isEmpty()) return emptyList()
            prefixes = next.distinct().take(MAX_PREFIX_BEAM)
        }

        return prefixes.mapNotNull { reading ->
            val evidence = repository.searchWithoutTone(reading, EVIDENCE_LIMIT)
            if (evidence.isEmpty()) {
                null
            } else {
                RankedPrediction(
                    reading = reading,
                    evidenceCount = evidence.size,
                    firstSourceRow = evidence.minOf { it.sourceRow }
                )
            }
        }
            .sortedWith(
                compareByDescending<RankedPrediction> { it.evidenceCount }
                    .thenBy { it.firstSourceRow }
                    .thenBy { it.reading }
            )
            .map { it.reading }
            .take(limit)
    }

    /**
     * Returns real dictionary readings matching both the T9 digits and an explicit
     * Vietnamese tone. Vowel quality marks such as â/ê/ô/ơ/ư are preserved.
     */
    fun predictWithTone(
        digits: String,
        tone: T9Tone,
        limit: Int = DEFAULT_LIMIT
    ): List<String> {
        if (tone == T9Tone.AUTO) return predict(digits, limit)
        if (digits.isBlank() || limit <= 0) return emptyList()

        val ranked = LinkedHashMap<String, RankedPrediction>()
        predict(digits, TONE_BASE_LIMIT).forEach { toneLessReading ->
            repository.searchWithoutTone(toneLessReading, TONE_EVIDENCE_LIMIT)
                .forEach { candidate ->
                    val reading = VietnameseInputParser.normalize(candidate.readingRaw.trim())
                    if (reading.isBlank()) return@forEach
                    if (VietnameseInputParser.withoutTone(reading) != toneLessReading) return@forEach
                    if (!tone.matches(reading)) return@forEach

                    val existing = ranked[reading]
                    ranked[reading] = if (existing == null) {
                        RankedPrediction(
                            reading = reading,
                            evidenceCount = 1,
                            firstSourceRow = candidate.sourceRow
                        )
                    } else {
                        existing.copy(
                            evidenceCount = existing.evidenceCount + 1,
                            firstSourceRow = minOf(existing.firstSourceRow, candidate.sourceRow)
                        )
                    }
                }
        }

        return ranked.values
            .sortedWith(
                compareByDescending<RankedPrediction> { it.evidenceCount }
                    .thenBy { it.firstSourceRow }
                    .thenBy { it.reading }
            )
            .map { it.reading }
            .take(limit)
    }

    companion object {
        private const val DEFAULT_LIMIT = 8
        private const val MAX_PREFIX_BEAM = 96
        private const val EVIDENCE_LIMIT = 32
        private const val TONE_BASE_LIMIT = 32
        private const val TONE_EVIDENCE_LIMIT = 64

        private val KEY_GROUPS = mapOf(
            '2' to "abc",
            '3' to "def",
            '4' to "ghi",
            '5' to "jkl",
            '6' to "mno",
            '7' to "pqrs",
            '8' to "tuv",
            '9' to "wxyz"
        )

        fun toDigits(readingWithoutTone: String): String? {
            val output = StringBuilder(readingWithoutTone.length)
            readingWithoutTone.lowercase().forEach { letter ->
                val digit = KEY_GROUPS.entries.firstOrNull { letter in it.value }?.key ?: return null
                output.append(digit)
            }
            return output.toString()
        }
    }
}
