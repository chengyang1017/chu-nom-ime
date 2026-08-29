package com.example.chineseime.data.repository

import com.example.chineseime.data.local.NomDatabase
import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.engine.VietnameseInput

class SQLiteNomRepository(private val database: NomDatabase) : NomRepository {
    override fun search(input: VietnameseInput, limit: Int) = database.search(input, limit)
    override fun searchExactReading(normalized: String, limit: Int) = database.searchExactReading(normalized, limit)
    override fun searchWithoutTone(withoutTone: String, limit: Int) = database.searchWithoutTone(withoutTone, limit)
    override fun searchReadingPrefix(normalizedPrefix: String, limit: Int) = database.searchReadingPrefix(normalizedPrefix, limit)
    override fun searchWithoutTonePrefix(withoutTonePrefix: String, limit: Int) = database.searchWithoutTonePrefix(withoutTonePrefix, limit)
    override fun searchTelexExact(telexKey: String, limit: Int) = database.searchTelexExact(telexKey, limit)
    override fun searchTelexPrefix(telexPrefix: String, limit: Int) = database.searchTelexPrefix(telexPrefix, limit)
    override fun canExtend(input: VietnameseInput) = database.canExtend(input)
    override fun exactReadingEntryCount(reading: String) = database.exactReadingEntryCount(reading)
    override fun corpusFrequency(reading: String) = database.corpusFrequency(reading)
    override fun sentenceHistoryScore(rawSentence: String, sourceEntryIds: List<Long>) = database.sentenceHistoryScore(rawSentence, sourceEntryIds)
    override fun ngramScore(previousSourceEntryId: Long, currentSourceEntryId: Long) = database.ngramScore(previousSourceEntryId, currentSourceEntryId)
    override fun recordSelection(rawSentence: String, candidate: NomSentenceCandidate) = database.recordSelection(rawSentence, candidate)
    override fun saveVerifiedPhrase(phrase: VerifiedNomPhrase) = database.saveVerifiedPhrase(phrase)
    override fun findVerifiedExact(normalized: String, limit: Int) = database.findVerifiedExact(normalized, limit)
    override fun findVerifiedWithoutTone(withoutTone: String, limit: Int) = database.findVerifiedWithoutTone(withoutTone, limit)
    override fun listVerifiedPhrases(limit: Int) = database.listVerifiedPhrases(limit)
    override fun deleteVerifiedPhrase(id: Long) = database.deleteVerifiedPhrase(id)
}
