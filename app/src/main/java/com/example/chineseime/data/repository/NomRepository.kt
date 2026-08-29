package com.example.chineseime.data.repository

import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.engine.VietnameseInput

interface NomRepository {
    fun search(input: VietnameseInput, limit: Int): List<NomCandidate>
    fun searchExactReading(normalized: String, limit: Int): List<NomCandidate> = emptyList()
    fun searchWithoutTone(withoutTone: String, limit: Int): List<NomCandidate> = search(VietnameseInput(withoutTone,withoutTone,withoutTone,withoutTone,withoutTone),limit)
    fun searchReadingPrefix(normalizedPrefix: String, limit: Int): List<NomCandidate> = emptyList()
    fun searchTelexExact(telexKey: String, limit: Int): List<NomCandidate> = emptyList()
    fun searchTelexPrefix(telexPrefix: String, limit: Int): List<NomCandidate> = emptyList()
    fun searchWithoutTonePrefix(withoutTonePrefix: String, limit: Int): List<NomCandidate> = emptyList()
    fun canExtend(input: VietnameseInput): Boolean = true
    fun exactReadingEntryCount(reading: String): Int = 0
    fun corpusFrequency(reading: String): Int = 0
    fun sentenceHistoryScore(rawSentence: String, sourceEntryIds: List<Long>): Double = 0.0
    fun ngramScore(previousSourceEntryId: Long, currentSourceEntryId: Long): Double = 0.0
    fun recordSelection(rawSentence: String, candidate: NomSentenceCandidate) {}
    fun saveVerifiedPhrase(phrase: VerifiedNomPhrase): Long = 0
    fun findVerifiedExact(normalized: String, limit: Int): List<VerifiedNomPhrase> = emptyList()
    fun findVerifiedWithoutTone(withoutTone: String, limit: Int): List<VerifiedNomPhrase> = emptyList()
    fun listVerifiedPhrases(limit: Int): List<VerifiedNomPhrase> = emptyList()
    fun deleteVerifiedPhrase(id: Long): Boolean = false
}
