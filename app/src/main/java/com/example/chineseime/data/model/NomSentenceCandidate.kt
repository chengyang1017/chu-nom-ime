package com.example.chineseime.data.model

data class NomSentenceSegment(
    val inputStart: Int,
    val inputEnd: Int,
    val rawTokens: List<String>,
    val restoredVietnamese: String,
    val nomText: String,
    val sourceEntryIds: List<Long>,
    val score: Double,
    val isConverted: Boolean,
    val evidenceText: String = ""
)

data class NomSentenceCandidate(
    val nomText: String,
    val restoredVietnamese: String,
    val sourceEntryIds: List<Long>,
    val segments: List<NomSentenceSegment>,
    val score: Double
) {
    val unconvertedSegments: List<NomSentenceSegment> get() = segments.filterNot { it.isConverted }
}
