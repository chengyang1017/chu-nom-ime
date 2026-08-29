package com.example.chineseime.ime

import com.example.chineseime.data.model.NomSentenceCandidate

class SentenceCompositionState {
    var rawSentence: String = ""; private set
    var restoredSentence: String = ""; private set
    var currentTokens: List<String> = emptyList(); private set
    var sentenceCandidates: List<NomSentenceCandidate> = emptyList(); private set
    var queryGeneration: Long = 0; private set
    var isComposing: Boolean = false; private set
    var selectedCandidateIndex: Int = -1; private set

    fun append(value: String) { rawSentence += value; changed() }
    fun appendSpace() { if (rawSentence.isNotEmpty() && !rawSentence.endsWith(" ")) { rawSentence += " "; changed() } }
    fun deleteCodePoint() {
        if (rawSentence.isEmpty()) return
        val count = rawSentence.codePointCount(0, rawSentence.length)
        rawSentence = rawSentence.substring(0, rawSentence.offsetByCodePoints(0, count - 1)); changed()
    }
    fun applyCandidates(generation: Long, candidates: List<NomSentenceCandidate>): Boolean {
        if (generation != queryGeneration) return false
        sentenceCandidates = candidates
        restoredSentence = candidates.firstOrNull()?.restoredVietnamese ?: rawSentence.trimEnd()
        return true
    }
    fun choose(index: Int): NomSentenceCandidate? {
        val candidate = sentenceCandidates.getOrNull(index) ?: return null
        selectedCandidateIndex = index
        return candidate
    }
    fun reset() {
        rawSentence = ""; restoredSentence = ""; currentTokens = emptyList(); sentenceCandidates = emptyList()
        selectedCandidateIndex = -1; isComposing = false; queryGeneration++
    }
    private fun changed() {
        currentTokens = rawSentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        restoredSentence = rawSentence.trimEnd(); sentenceCandidates = emptyList(); selectedCandidateIndex = -1
        isComposing = rawSentence.isNotEmpty(); queryGeneration++
    }
}
