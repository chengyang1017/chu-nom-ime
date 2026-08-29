package com.example.chineseime.ime

import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.NomSentenceCandidateOrigin
import com.example.chineseime.engine.VietnameseInputParser

class SentenceCompositionState(
    private val parser: VietnameseInputParser = VietnameseInputParser()
) {
    var rawSentence: String = ""; private set
    var displaySentence: String = ""; private set
    val explicitComposedSentence: String get() = displaySentence
    var restoredSentence: String = ""; private set
    var currentTokens: List<String> = emptyList(); private set
    var sentenceCandidates: List<NomSentenceCandidate> = emptyList(); private set
    var queryGeneration: Long = 0; private set
    var isComposing: Boolean = false; private set
    var selectedCandidateIndex: Int = -1; private set

    fun append(value: String) { rawSentence += value; changed() }

    fun appendSpace() {
        if (rawSentence.isNotEmpty() && !rawSentence.endsWith(" ")) {
            rawSentence += " "
            changed()
        }
    }

    fun replaceLastCodePoint(value: String) {
        if (rawSentence.isEmpty()) {
            append(value)
            return
        }
        val count = rawSentence.codePointCount(0, rawSentence.length)
        rawSentence = rawSentence.substring(0, rawSentence.offsetByCodePoints(0, count - 1)) + value
        changed()
    }

    fun replaceCurrentToken(value: String) {
        val separator = rawSentence.lastIndexOf(' ')
        rawSentence = if (separator >= 0) {
            rawSentence.substring(0, separator + 1) + value
        } else {
            value
        }
        changed()
    }

    fun deleteCodePoint() {
        if (rawSentence.isEmpty()) return
        val count = rawSentence.codePointCount(0, rawSentence.length)
        rawSentence = rawSentence.substring(0, rawSentence.offsetByCodePoints(0, count - 1))
        changed()
    }

    fun applyCandidates(generation: Long, candidates: List<NomSentenceCandidate>): Boolean {
        if (generation != queryGeneration) return false
        sentenceCandidates = candidates
        val best = candidates.firstOrNull()
        restoredSentence = best?.restoredVietnamese.orEmpty()
        displaySentence = best?.let(::composeUsingSegmentation) ?: composeRaw()
        return true
    }

    fun choose(index: Int): NomSentenceCandidate? {
        val candidate = sentenceCandidates.getOrNull(index) ?: return null
        selectedCandidateIndex = index
        return candidate
    }

    fun reset() {
        rawSentence = ""
        displaySentence = ""
        restoredSentence = ""
        currentTokens = emptyList()
        sentenceCandidates = emptyList()
        selectedCandidateIndex = -1
        isComposing = false
        queryGeneration++
    }

    private fun changed() {
        currentTokens = rawSentence.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
        displaySentence = composeRaw()
        restoredSentence = ""
        sentenceCandidates = emptyList()
        selectedCandidateIndex = -1
        isComposing = rawSentence.isNotEmpty()
        queryGeneration++
    }

    private fun composeRaw(): String = parser.parse(rawSentence).composed

    private fun composeUsingSegmentation(candidate: NomSentenceCandidate): String {
        if (candidate.origin != NomSentenceCandidateOrigin.GENERATED) return composeRaw()
        if (candidate.segments.isEmpty()) return composeRaw()
        val segmented = candidate.segments.joinToString(" ") { segment ->
            parser.parse(segment.rawTokens.joinToString(" ")).composed
        }
        return if (rawSentence.endsWith(' ')) "$segmented " else segmented
    }
}
