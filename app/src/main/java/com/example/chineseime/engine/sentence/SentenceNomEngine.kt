package com.example.chineseime.engine.sentence

import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.repository.NomRepository

class SentenceNomEngine(private val repository: NomRepository) {
    private val generator = SentenceCandidateGenerator(repository)
    fun query(rawSentence: String, limit: Int = 8, context: SentenceQueryContext = SentenceQueryContext()): List<NomSentenceCandidate> {
        val totalStarted = System.nanoTime()
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
    fun learn(rawSentence: String, candidate: NomSentenceCandidate) = repository.recordSelection(rawSentence, candidate)
}
