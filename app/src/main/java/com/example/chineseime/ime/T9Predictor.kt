package com.example.chineseime.ime

import com.example.chineseime.data.repository.NomRepository

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

    companion object {
        private const val DEFAULT_LIMIT = 8
        private const val MAX_PREFIX_BEAM = 96
        private const val EVIDENCE_LIMIT = 32

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
