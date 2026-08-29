package com.example.chineseime.engine.sentence

import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.NomSentenceCandidateOrigin
import com.example.chineseime.data.model.NomSentenceSegment
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.VietnameseInputParser

class SentenceNomEngine(private val repository: NomRepository) {
    private val generator = SentenceCandidateGenerator(repository)
    private val parser = VietnameseInputParser()
    fun query(rawSentence: String, limit: Int = 8, context: SentenceQueryContext = SentenceQueryContext()): List<NomSentenceCandidate> {
        val totalStarted = System.nanoTime()
        val parsed = parser.parse(rawSentence)
        val exact = context.dictionaryLookup { repository.findVerifiedExact(parsed.normalized,limit) }
        if (exact.isNotEmpty()) return finishVerified(exact,NomSentenceCandidateOrigin.VERIFIED_EXACT,context,totalStarted,limit)
        if (context.isCancelled()) return emptyList()
        val rawToneLess = VietnameseInputParser.withoutTone(VietnameseInputParser.normalize(rawSentence))
        val toneLess = context.dictionaryLookup {
            (repository.findVerifiedWithoutTone(parsed.withoutTone,limit) +
                repository.findVerifiedWithoutTone(rawToneLess,limit))
                .distinctBy { it.id }.take(limit)
        }
        if (toneLess.isNotEmpty()) return finishVerified(toneLess,NomSentenceCandidateOrigin.VERIFIED_TONELESS,context,totalStarted,limit)
        if (context.isCancelled()) return emptyList()
        val generated = generator.generate(rawSentence, limit, context)
        if (context.isCancelled()) {
            context.metrics.totalEngineNanos = System.nanoTime() - totalStarted
            return emptyList()
        }
        val rankingStarted = System.nanoTime()
        val ranked = SentenceCandidateRanker.rank(generated, limit)
        context.metrics.candidateRankingNanos += System.nanoTime() - rankingStarted
        context.metrics.totalEngineNanos = System.nanoTime() - totalStarted
        return ranked
    }

    private fun finishVerified(
        phrases: List<VerifiedNomPhrase>,
        origin: NomSentenceCandidateOrigin,
        context: SentenceQueryContext,
        totalStarted: Long,
        limit: Int
    ): List<NomSentenceCandidate> {
        val result = phrases.take(limit).map { phrase ->
            NomSentenceCandidate(
                nomText=phrase.nomText,
                restoredVietnamese=phrase.phraseRaw,
                sourceEntryIds=phrase.sourceEntryIds,
                segments=phrase.tokens.mapIndexed { index, token -> NomSentenceSegment(
                    inputStart=index,inputEnd=index+1,rawTokens=listOf(token.inputToken),restoredVietnamese=token.readingRaw,
                    nomText=token.nomRaw,sourceEntryIds=listOf(token.sourceEntryId),score=VERIFIED_SCORE,isConverted=true,evidenceText=token.exampleRaw
                ) },
                score=VERIFIED_SCORE,
                origin=origin
            )
        }
        context.metrics.totalEngineNanos=System.nanoTime()-totalStarted
        context.metrics.fastPath=true
        return result
    }
    fun learn(rawSentence: String, candidate: NomSentenceCandidate) = repository.recordSelection(rawSentence, candidate)

    companion object { private const val VERIFIED_SCORE = 1_000_000.0 }
}
