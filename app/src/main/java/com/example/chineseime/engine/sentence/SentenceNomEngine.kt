package com.example.chineseime.engine.sentence

import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.repository.NomRepository

class SentenceNomEngine(private val repository: NomRepository) {
    private val generator = SentenceCandidateGenerator(repository)
    fun query(rawSentence: String, limit: Int = 8): List<NomSentenceCandidate> =
        SentenceCandidateRanker.rank(generator.generate(rawSentence, limit), limit)
    fun learn(rawSentence: String, candidate: NomSentenceCandidate) = repository.recordSelection(rawSentence, candidate)
}
