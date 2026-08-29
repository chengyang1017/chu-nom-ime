package com.example.chineseime.engine.sentence

import com.example.chineseime.data.model.NomSentenceCandidate

object SentenceCandidateRanker {
    fun rank(values: List<NomSentenceCandidate>, limit: Int): List<NomSentenceCandidate> =
        values.sortedWith(compareByDescending<NomSentenceCandidate> { it.score }
            .thenBy { it.unconvertedSegments.size }
            .thenByDescending { it.sourceEntryIds.size }).take(limit)
}
