package com.example.chineseime.engine

import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.local.NomMemoryIndex
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.NomSentenceCandidateOrigin
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.model.VerifiedNomToken
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.sentence.SentenceNomEngine
import com.example.chineseime.ime.SentenceCompositionState
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class VerifiedNomPhraseTest {
    private val source=NomCsvLoader.load(File("src/main/assets/hannom_rcv_standard_nom.csv").readText(Charsets.UTF_8))
    private val sourceTokens=source.filter { it.readingRaw.isNotBlank() && !it.readingRaw.contains(Regex("\\s")) }
    private val indexed=sourceTokens.map { entry -> NomMemoryIndex.IndexedNomCandidate(
        NomCandidate(entry.sourceRow.toLong(),entry.sourceRow,entry.readingRaw,entry.nomRaw,entry.exampleRaw,entry.noteRaw),
        VietnameseInputParser.normalize(entry.readingRaw),VietnameseInputParser.withoutTone(entry.readingRaw),TelexComposer().toTelex(entry.readingRaw)
    ) }
    private val repository=MemoryVerifiedRepository(NomMemoryIndex(indexed))
    private val engine=SentenceNomEngine(repository)

    @Test fun saveQueryAndDeleteVerifiedPhrase() {
        val phrase=dynamicPhrase()
        val id=repository.saveVerifiedPhrase(phrase)
        assertTrue(id>0)
        assertEquals(id,repository.findVerifiedExact(phrase.phraseNormalized,8).single().id)
        assertTrue(repository.deleteVerifiedPhrase(id))
        assertTrue(repository.findVerifiedExact(phrase.phraseNormalized,8).isEmpty())
    }

    @Test fun exactThenToneLessThenContinuousNoSpaceAreVerified() {
        val phrase=dynamicPhrase()
        repository.saveVerifiedPhrase(phrase)
        val exact=engine.query(phrase.phraseRaw,8)
        assertEquals(NomSentenceCandidateOrigin.VERIFIED_EXACT,exact.first().origin)
        assertEquals(phrase.nomText,exact.first().nomText)
        val toneLess=engine.query(phrase.phraseWithoutTone,8)
        assertEquals(NomSentenceCandidateOrigin.VERIFIED_TONELESS,toneLess.first().origin)
        val continuous=engine.query(phrase.phraseWithoutToneCompact,8)
        assertEquals(NomSentenceCandidateOrigin.VERIFIED_TONELESS,continuous.first().origin)
        assertEquals(phrase.sourceEntryIds,continuous.first().sourceEntryIds)
    }

    @Test fun exactVerifiedPhraseWinsOverToneLessAlternatives() {
        val alternatives = sourceTokens
            .groupBy { VietnameseInputParser.withoutTone(it.readingRaw) }
            .values
            .first { group -> group.map { VietnameseInputParser.normalize(it.readingRaw) }.distinct().size >= 2 }
            .distinctBy { VietnameseInputParser.normalize(it.readingRaw) }
            .take(2)
        val first = alternatives[0]
        val second = alternatives[1]
        val exactPhrase = VerifiedNomPhrase.create(
            first.readingRaw,
            listOf(first.toVerifiedToken(first.readingRaw))
        )
        val competingPhrase = VerifiedNomPhrase.create(
            second.readingRaw,
            listOf(second.toVerifiedToken(second.readingRaw))
        )
        repository.saveVerifiedPhrase(competingPhrase)
        repository.saveVerifiedPhrase(exactPhrase)

        val result = engine.query(first.readingRaw, 8)

        assertEquals(NomSentenceCandidateOrigin.VERIFIED_EXACT, result.first().origin)
        assertEquals(exactPhrase.nomText, result.first().nomText)
        assertTrue(result.none { it.nomText == competingPhrase.nomText })
    }

    @Test fun missingVerifiedPhraseFallsBackToExistingGenerator() {
        val entry=sourceTokens.last()
        val result=engine.query(entry.readingRaw,8)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.origin==NomSentenceCandidateOrigin.GENERATED })
    }

    @Test fun verifiedUnicodeStringsRoundTripWithoutCharTruncation() {
        val entry=sourceTokens.first { it.nomRaw.codePoints().anyMatch { codePoint -> codePoint>0xFFFF } }
        val phrase=VerifiedNomPhrase.create(entry.readingRaw,listOf(entry.toVerifiedToken(entry.readingRaw)))
        repository.saveVerifiedPhrase(phrase)
        val candidate=engine.query(phrase.phraseRaw,8).first()
        assertEquals(entry.nomRaw,candidate.nomText)
        assertArrayEquals(entry.nomRaw.codePoints().toArray(),candidate.nomText.codePoints().toArray())
    }

    @Test fun verifiedCandidateCannotChangeRawOrDisplaySentence() {
        val phrase=dynamicPhrase()
        repository.saveVerifiedPhrase(phrase)
        val raw=phrase.phraseWithoutToneCompact
        val state=SentenceCompositionState();state.append(raw)
        val displayBefore=state.displaySentence
        assertTrue(state.applyCandidates(state.queryGeneration,engine.query(raw,8)))
        assertEquals(raw,state.rawSentence)
        assertEquals(displayBefore,state.displaySentence)
        assertEquals(phrase.phraseRaw,state.restoredSentence)
    }

    private fun dynamicPhrase():VerifiedNomPhrase {
        val accented=sourceTokens.filter { VietnameseInputParser.normalize(it.readingRaw)!=VietnameseInputParser.withoutTone(it.readingRaw) }
            .distinctBy { VietnameseInputParser.withoutTone(it.readingRaw) }.take(3)
        val raw=accented.joinToString(" ") { it.readingRaw }
        return VerifiedNomPhrase.create(raw,accented.map { it.toVerifiedToken(it.readingRaw) })
    }

    private fun com.example.chineseime.data.model.NomSourceEntry.toVerifiedToken(input:String)=VerifiedNomToken(
        input,id.takeIf { it!=0L } ?: sourceRow.toLong(),readingRaw,nomRaw,exampleRaw,noteRaw,sourceRow
    )

    private class MemoryVerifiedRepository(private val index:NomMemoryIndex):NomRepository {
        private var nextId=1L
        private val verified=mutableListOf<VerifiedNomPhrase>()
        override fun search(input:VietnameseInput,limit:Int)=index.search(input,limit)
        override fun searchExactReading(normalized:String,limit:Int)=index.exactNormalized(normalized,limit)
        override fun searchWithoutTone(withoutTone:String,limit:Int)=index.exactWithoutTone(withoutTone,limit)
        override fun searchReadingPrefix(normalizedPrefix:String,limit:Int)=index.prefixNormalized(normalizedPrefix,limit)
        override fun searchWithoutTonePrefix(withoutTonePrefix:String,limit:Int)=index.prefixWithoutTone(withoutTonePrefix,limit)
        override fun searchTelexExact(telexKey:String,limit:Int)=index.exactTelex(telexKey,limit)
        override fun searchTelexPrefix(telexPrefix:String,limit:Int)=index.prefixTelex(telexPrefix,limit)
        override fun canExtend(input:VietnameseInput)=index.canExtend(input)
        override fun corpusFrequency(reading:String)=index.corpusFrequency(reading)
        override fun saveVerifiedPhrase(phrase:VerifiedNomPhrase):Long {
            val id=phrase.id.takeIf { it>0 } ?: nextId++
            verified.removeAll { it.id==id || (it.phraseNormalized==phrase.phraseNormalized && it.nomText==phrase.nomText) }
            verified+=phrase.copy(id=id);return id
        }
        override fun findVerifiedExact(normalized:String,limit:Int):List<VerifiedNomPhrase> { val compact=VerifiedNomPhrase.compact(normalized);return verified.filter { it.phraseNormalized==normalized || it.phraseNormalizedCompact==compact }.take(limit) }
        override fun findVerifiedWithoutTone(withoutTone:String,limit:Int):List<VerifiedNomPhrase> { val compact=VerifiedNomPhrase.compact(withoutTone);return verified.filter { it.phraseWithoutTone==withoutTone || it.phraseWithoutToneCompact==compact }.take(limit) }
        override fun listVerifiedPhrases(limit:Int)=verified.take(limit)
        override fun deleteVerifiedPhrase(id:Long)=verified.removeAll { it.id==id }
    }
}
