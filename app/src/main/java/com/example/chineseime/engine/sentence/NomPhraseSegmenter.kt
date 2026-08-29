package com.example.chineseime.engine.sentence

class NomPhraseSegmenter(private val maxPhraseTokens: Int = 4) {
    fun spans(tokens: List<String>, start: Int): List<IntRange> {
        val maxEnd = minOf(tokens.size, start + maxPhraseTokens)
        return (maxEnd downTo start + 1).map { start until it }
    }
}
