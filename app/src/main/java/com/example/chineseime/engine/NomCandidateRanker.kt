package com.example.chineseime.engine
import com.example.chineseime.data.model.NomCandidate
object NomCandidateRanker {
    fun rank(items: List<NomCandidate>, input: VietnameseInput): List<NomCandidate> =
        items.sortedWith(compareBy<NomCandidate> {
            if (VietnameseInputParser.normalize(it.readingRaw) == input.normalized) 0 else 1
        }.thenBy { it.sourceRow })
}