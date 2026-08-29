package com.example.chineseime.engine

import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.sentence.SentenceNomEngine
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class SentenceNomEngineTest {
    private val entries = NomCsvLoader.load(File("src/main/assets/hannom_rcv_standard_nom.csv").readText(Charsets.UTF_8))
    private class CsvRepository(entries: List<com.example.chineseime.data.model.NomSourceEntry>) : NomRepository {
        private val values = entries.map { NomCandidate(it.sourceRow.toLong(),it.sourceRow,it.readingRaw,it.nomRaw,it.exampleRaw,it.noteRaw) }
        private val selected = mutableMapOf<Pair<String,List<Long>>,Int>()
        override fun search(input: VietnameseInput, limit: Int) = values.filter {
            VietnameseInputParser.normalize(it.readingRaw)==input.normalized || VietnameseInputParser.withoutTone(it.readingRaw)==input.withoutTone
        }.take(limit)
        override fun searchExactReading(normalized: String, limit: Int)=values.filter { VietnameseInputParser.normalize(it.readingRaw)==normalized }.take(limit)
        override fun searchWithoutTone(withoutTone: String, limit: Int)=values.filter { VietnameseInputParser.withoutTone(it.readingRaw)==withoutTone }.take(limit)
        override fun searchReadingPrefix(normalizedPrefix: String, limit: Int)=values.filter { VietnameseInputParser.normalize(it.readingRaw).startsWith(normalizedPrefix) }.take(limit)
        override fun searchWithoutTonePrefix(withoutTonePrefix: String, limit: Int)=values.filter { VietnameseInputParser.withoutTone(it.readingRaw).startsWith(withoutTonePrefix) }.take(limit)
        override fun exactReadingEntryCount(reading: String)=values.count { it.readingRaw.equals(reading,true) }
        override fun corpusFrequency(reading: String)=values.count { it.exampleRaw.contains(reading,true) }
        override fun sentenceHistoryScore(rawSentence: String, sourceEntryIds: List<Long>) = (selected[rawSentence to sourceEntryIds]?:0)*5.0
        override fun recordSelection(rawSentence: String, candidate: NomSentenceCandidate) { selected[rawSentence to candidate.sourceEntryIds]=(selected[rawSentence to candidate.sourceEntryIds]?:0)+1 }
    }
    private val repository=CsvRepository(entries)
    private val engine=SentenceNomEngine(repository)

    @Test fun toneLessSentenceProducesRestoredVietnameseAndTraceableNom() {
        val result=engine.query("toi yeu em",8)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { it.restoredVietnamese.split(" ").size == 3 })
        result.flatMap { it.segments }.filter { it.isConverted }.forEach { assertTrue(it.sourceEntryIds.isNotEmpty()); assertTrue(it.nomText.isNotEmpty()) }
    }
    @Test fun unknownTokenIsPreservedWithoutInventedMapping() {
        val result=engine.query("toi chatgpt yeu em",8)
        assertTrue(result.isNotEmpty())
        assertTrue(result.all { candidate -> candidate.segments.any { !it.isConverted && it.nomText=="chatgpt" && it.sourceEntryIds.isEmpty() } })
    }
    @Test fun requestedDynamicSentencesDoNotLoseTokens() {
        listOf("toi","nguoi viet nam","toi rat yeu tieng viet").forEach { raw ->
            val result=engine.query(raw,8); assertTrue(raw,result.isNotEmpty())
            assertEquals(raw.split(' ').size,result.first().segments.sumOf { it.rawTokens.size })
        }
    }
    @Test fun selectionHistoryRaisesChosenPath() {
        val initial=engine.query("toi",8); assertTrue(initial.size>1)
        val chosen=initial.last(); repeat(5){engine.learn("toi",chosen)}
        assertEquals(chosen.sourceEntryIds,engine.query("toi",8).first().sourceEntryIds)
    }
    @Test fun everyIncrementalStepKeepsCandidates() {
        listOf("t","to","toi","toi ","toi y","toi ye","toi yeu","toi yeu ","toi yeu e","toi yeu em").forEach { raw -> assertTrue("no candidates for $raw",engine.query(raw,8).isNotEmpty()) }
    }
    @Test fun mixedToneSentencesAllProduceCandidates() {
        listOf("tôi yeu em","toi yêu em","tôi yêu em").forEach { raw -> assertTrue(raw,engine.query(raw,8).isNotEmpty()) }
    }
    @Test fun accentedTokenExactReadingIsFirst() {
        listOf("tôi yeu","toi yêu","tôi yêu em").forEach { raw ->
            val result=engine.query(raw,8); val typed=raw.split(' ')
            typed.forEachIndexed { index, token -> if(VietnameseInputParser.normalize(token)!=VietnameseInputParser.withoutTone(token)) assertEquals(VietnameseInputParser.normalize(token),VietnameseInputParser.normalize(result.first().segments[index].restoredVietnamese)) }
        }
    }
}