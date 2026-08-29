package com.example.chineseime.engine.sentence

import android.util.Log
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.NomSentenceSegment
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.VietnameseInputParser

enum class SentenceMatchType(val baseScore: Double) {
    EXACT_TYPED(34.0), EXACT_WITHOUT_TONE(24.0), PREFIX_TYPED(15.0),
    PREFIX_WITHOUT_TONE(10.0), UNKNOWN_RAW(-18.0)
}

data class IncrementalSentenceInput(
    val rawSentence: String,
    val completedTokens: List<String>,
    val currentToken: String,
    val endsWithSpace: Boolean
) {
    val tokens: List<String> get() = completedTokens + listOfNotNull(currentToken.takeIf(String::isNotEmpty))

    companion object {
        fun parse(raw: String): IncrementalSentenceInput {
            val endsWithSpace = raw.endsWith(' ')
            val parts = if (raw.isEmpty()) emptyList() else raw.split(' ')
            return if (endsWithSpace) {
                IncrementalSentenceInput(raw, parts.dropLast(1).filter(String::isNotEmpty), "", true)
            } else {
                IncrementalSentenceInput(raw, parts.dropLast(1).filter(String::isNotEmpty), parts.lastOrNull().orEmpty(), false)
            }
        }
    }
}

class SentenceCandidateGenerator(
    private val repository: NomRepository,
    private val parser: VietnameseInputParser = VietnameseInputParser(),
    private val beamWidth: Int = 30,
    private val maxSegmentCodePoints: Int = 12
) {
    private data class Path(val segments: List<NomSentenceSegment>, val score: Double)
    private data class TypedCandidate(val candidate: NomCandidate, val type: SentenceMatchType)

    fun generate(rawSentence: String, limit: Int = 8): List<NomSentenceCandidate> {
        val input = IncrementalSentenceInput.parse(rawSentence)
        Log.d(TAG, "rawSentence=${input.rawSentence} endsWithSpace=${input.endsWithSpace} " +
            "completedTokens=${input.completedTokens} currentToken=${input.currentToken}")
        if (input.tokens.isEmpty()) return emptyList()

        var sentenceBeam = listOf(Path(emptyList(), 0.0))
        val hasExplicitBoundaries = rawSentence.contains(' ')
        input.tokens.forEachIndexed { tokenIndex, rawToken ->
            val isCurrent = !input.endsWithSpace && tokenIndex == input.tokens.lastIndex
            val tokenPaths = if (hasExplicitBoundaries) {
                atomicTokenPaths(rawToken, isCurrent)
            } else {
                continuousTokenPaths(rawToken, isCurrent)
            }
            sentenceBeam = sentenceBeam.flatMap { prefix ->
                tokenPaths.map { suffix ->
                    val adjacency = adjacencyBonus(prefix.segments.lastOrNull(), suffix.segments.firstOrNull())
                    Path(prefix.segments + suffix.segments, prefix.score + suffix.score + adjacency)
                }
            }.sortedByDescending(Path::score).take(beamWidth)
        }

        val candidates = sentenceBeam.ifEmpty {
            val rawLength = rawSentence.codePointCount(0, rawSentence.length)
            listOf(Path(listOf(unknownSegment(0, rawLength, rawSentence)), SentenceMatchType.UNKNOWN_RAW.baseScore))
        }.map { path ->
            val ids = path.segments.flatMap(NomSentenceSegment::sourceEntryIds)
            NomSentenceCandidate(
                nomText = joinNom(path.segments),
                restoredVietnamese = path.segments.joinToString(" ", transform = NomSentenceSegment::restoredVietnamese),
                sourceEntryIds = ids,
                segments = path.segments,
                score = path.score + repository.sentenceHistoryScore(rawSentence, ids)
            )
        }.distinctBy { Triple(it.nomText, it.restoredVietnamese, it.sourceEntryIds) }
            .sortedByDescending(NomSentenceCandidate::score)
            .take(limit)

        Log.d(TAG, "rawSentence=$rawSentence beamPaths=${sentenceBeam.size} finalCandidates=${candidates.size}")
        return candidates
    }

    /** Explicit spaces are hard boundaries. An unmatched explicit token stays intact. */
    private fun atomicTokenPaths(raw: String, allowPrefix: Boolean): List<Path> {
        val matches = queryCandidates(raw, allowPrefix)
        if (matches.isEmpty()) {
            val rawLength = raw.codePointCount(0, raw.length)
            return listOf(Path(listOf(unknownSegment(0, rawLength, raw)), SentenceMatchType.UNKNOWN_RAW.baseScore))
        }
        return matches.take(beamWidth).map { item ->
            val segment = convertedSegment(0, raw, item)
            Path(listOf(segment), segment.score)
        }
    }

    /** DP/beam segmentation inside a no-space keystroke run. */
    private fun continuousTokenPaths(raw: String, allowPrefix: Boolean): List<Path> {
        val offsets = codePointOffsets(raw)
        val count = offsets.lastIndex
        val beams = Array(count + 1) { mutableListOf<Path>() }
        beams[0] += Path(emptyList(), 0.0)
        val queryCache = HashMap<Pair<String, Boolean>, List<TypedCandidate>>()

        for (start in 0 until count) {
            val active = beams[start].sortedByDescending(Path::score).take(beamWidth)
            if (active.isEmpty()) continue
            val maxEnd = minOf(count, start + maxSegmentCodePoints)
            for (end in start + 1..maxEnd) {
                val rawSegment = raw.substring(offsets[start], offsets[end])
                val prefix = allowPrefix && end == count
                val matches = queryCache.getOrPut(rawSegment to prefix) { queryCandidates(rawSegment, prefix) }
                for (path in active) {
                    matches.forEach { item ->
                        val segment = convertedSegment(start, rawSegment, item, end)
                        beams[end] += Path(path.segments + segment, path.score + segment.score + adjacencyBonus(path.segments.lastOrNull(), segment))
                    }
                }
            }

            val unknownText = raw.substring(offsets[start], offsets[start + 1])
            active.forEach { path ->
                val appended = appendUnknown(path.segments, start, start + 1, unknownText)
                val penalty = if (path.segments.lastOrNull()?.isConverted == false) -1.0 else SentenceMatchType.UNKNOWN_RAW.baseScore
                beams[start + 1] += Path(appended, path.score + penalty)
            }
            for (end in start + 1..maxEnd) {
                if (beams[end].size > beamWidth * 4) {
                    val pruned = beams[end].sortedByDescending(Path::score).take(beamWidth)
                    beams[end].clear(); beams[end].addAll(pruned)
                }
            }
        }
        return beams[count].sortedByDescending(Path::score).take(beamWidth)
    }

    private fun queryCandidates(rawSegment: String, prefix: Boolean): List<TypedCandidate> {
        val parsed = parser.parse(rawSegment)
        val hasVietnameseMarks = parsed.normalized != parsed.withoutTone
        val exactNormalized = repository.searchExactReading(parsed.normalized, QUERY_LIMIT)
        val exactWithoutTone = repository.searchWithoutTone(parsed.withoutTone, QUERY_LIMIT)
        val exactTelex = repository.searchTelexExact(parsed.telexKey, QUERY_LIMIT)
        val prefixNormalized = if (prefix) repository.searchReadingPrefix(parsed.normalized, QUERY_LIMIT) else emptyList()
        val prefixWithoutTone = if (prefix) repository.searchWithoutTonePrefix(parsed.withoutTone, QUERY_LIMIT) else emptyList()
        val prefixTelex = if (prefix) repository.searchTelexPrefix(parsed.telexKey, QUERY_LIMIT) else emptyList()

        val exactTypedType = if (hasVietnameseMarks) SentenceMatchType.EXACT_TYPED else SentenceMatchType.EXACT_WITHOUT_TONE
        val prefixTypedType = if (hasVietnameseMarks) SentenceMatchType.PREFIX_TYPED else SentenceMatchType.PREFIX_WITHOUT_TONE
        val typed = buildList {
            addAll(exactNormalized.map { TypedCandidate(it, exactTypedType) })
            addAll(exactWithoutTone.map { TypedCandidate(it, SentenceMatchType.EXACT_WITHOUT_TONE) })
            addAll(exactTelex.map { TypedCandidate(it, exactTypedType) })
            addAll(prefixNormalized.map { TypedCandidate(it, prefixTypedType) })
            addAll(prefixWithoutTone.map { TypedCandidate(it, SentenceMatchType.PREFIX_WITHOUT_TONE) })
            addAll(prefixTelex.map { TypedCandidate(it, prefixTypedType) })
        }.groupBy { it.candidate.sourceEntryId }.map { (_, variants) ->
            variants.maxBy { variant ->
                variant.type.baseScore + if (hasVietnameseMarks && variant.type == SentenceMatchType.EXACT_TYPED) 14.0 else 0.0
            }
        }.sortedByDescending { it.type.baseScore }.take(QUERY_LIMIT)

        Log.d(TAG, "segment=$rawSegment composed=${parsed.composed} hasVietnameseMarks=$hasVietnameseMarks " +
            "prefix=$prefix normalized=${exactNormalized.size} noTone=${exactWithoutTone.size} telex=${exactTelex.size} " +
            "prefixCount=${prefixNormalized.size + prefixWithoutTone.size + prefixTelex.size}")
        return typed
    }

    private fun convertedSegment(start: Int, raw: String, item: TypedCandidate, end: Int = start + raw.codePointCount(0, raw.length)): NomSentenceSegment {
        val entry = item.candidate
        val parsed = parser.parse(raw)
        val hasMarks = parsed.normalized != parsed.withoutTone
        val codePointLength = end - start
        // Quadratic length reward prevents a valid word from losing to many one-letter entries.
        var score = item.type.baseScore + codePointLength * codePointLength * 5.0 - 40.0
        score += kotlin.math.ln(1.0 + repository.corpusFrequency(entry.readingRaw)) * 2.0
        if (hasMarks && VietnameseInputParser.normalize(entry.readingRaw) == parsed.normalized) score += 14.0
        if (entry.noteRaw.contains("[異]") || entry.noteRaw.contains("[俗]") || entry.noteRaw.contains("[翻]")) score -= 2.5
        if (entry.readingRaw != entry.readingRaw.lowercase()) score -= 2.0
        return NomSentenceSegment(
            inputStart = start,
            inputEnd = end,
            rawTokens = listOf(raw),
            restoredVietnamese = entry.readingRaw.lowercase(),
            nomText = entry.nomRaw,
            sourceEntryIds = listOf(entry.sourceEntryId),
            score = score,
            isConverted = true,
            evidenceText = entry.exampleRaw
        )
    }

    private fun appendUnknown(segments: List<NomSentenceSegment>, start: Int, end: Int, text: String): List<NomSentenceSegment> {
        val last = segments.lastOrNull()
        if (last == null || last.isConverted) return segments + unknownSegment(start, end, text)
        val merged = last.copy(
            inputEnd = end,
            rawTokens = listOf(last.rawTokens.joinToString("") + text),
            restoredVietnamese = last.restoredVietnamese + text,
            nomText = last.nomText + text
        )
        return segments.dropLast(1) + merged
    }

    private fun unknownSegment(start: Int, end: Int, text: String) = NomSentenceSegment(
        start, end, listOf(text), text, text, emptyList(), SentenceMatchType.UNKNOWN_RAW.baseScore, false
    )

    private fun adjacencyBonus(previous: NomSentenceSegment?, current: NomSentenceSegment?): Double {
        if (previous == null || current == null || !previous.isConverted || !current.isConverted) return 0.0
        val evidence = if (current.evidenceText.contains(previous.restoredVietnamese, true) ||
            previous.evidenceText.contains(current.restoredVietnamese, true)) 3.0 else 0.0
        return evidence + repository.ngramScore(previous.sourceEntryIds.last(), current.sourceEntryIds.first())
    }

    private fun codePointOffsets(value: String): IntArray {
        val count = value.codePointCount(0, value.length)
        return IntArray(count + 1) { value.offsetByCodePoints(0, it) }
    }

    private fun joinNom(segments: List<NomSentenceSegment>) = buildString {
        segments.forEachIndexed { index, segment ->
            if (!segment.isConverted && index > 0) append(' ')
            append(segment.nomText)
            if (!segment.isConverted && index < segments.lastIndex) append(' ')
        }
    }

    companion object {
        private const val TAG = "NOM_IME"
        private const val QUERY_LIMIT = 40
    }
}
