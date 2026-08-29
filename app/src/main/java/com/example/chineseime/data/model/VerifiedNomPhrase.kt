package com.example.chineseime.data.model

import com.example.chineseime.engine.VietnameseInputParser

data class VerifiedNomToken(
    val inputToken: String,
    val sourceEntryId: Long,
    val readingRaw: String,
    val nomRaw: String,
    val exampleRaw: String,
    val noteRaw: String,
    val sourceRow: Int
)

data class VerifiedNomPhrase(
    val id: Long = 0,
    val phraseRaw: String,
    val phraseNormalized: String,
    val phraseWithoutTone: String,
    val phraseNormalizedCompact: String,
    val phraseWithoutToneCompact: String,
    val nomText: String,
    val tokens: List<VerifiedNomToken>,
    val createdAt: Long,
    val updatedAt: Long
) {
    val sourceEntryIds: List<Long> get() = tokens.map(VerifiedNomToken::sourceEntryId)

    companion object {
        fun create(phraseRaw: String, tokens: List<VerifiedNomToken>, now: Long = System.currentTimeMillis()): VerifiedNomPhrase {
            require(phraseRaw.isNotBlank()) { "Verified phrase cannot be blank" }
            require(tokens.isNotEmpty()) { "Verified phrase must contain at least one token" }
            val normalized = VietnameseInputParser.normalize(phraseRaw.trim())
            val withoutTone = VietnameseInputParser.withoutTone(normalized)
            return VerifiedNomPhrase(
                phraseRaw = phraseRaw.trim(),
                phraseNormalized = normalized,
                phraseWithoutTone = withoutTone,
                phraseNormalizedCompact = compact(normalized),
                phraseWithoutToneCompact = compact(withoutTone),
                nomText = tokens.joinToString("") { it.nomRaw },
                tokens = tokens,
                createdAt = now,
                updatedAt = now
            )
        }

        fun compact(value: String): String {
            val output = StringBuilder()
            value.codePoints().forEach { codePoint -> if (!Character.isWhitespace(codePoint)) output.appendCodePoint(codePoint) }
            return output.toString()
        }
    }
}
