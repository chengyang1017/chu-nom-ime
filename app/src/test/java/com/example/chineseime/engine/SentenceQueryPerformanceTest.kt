package com.example.chineseime.engine

import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.local.NomMemoryIndex
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.sentence.LatestQueryCoordinator
import com.example.chineseime.engine.sentence.SentenceNomEngine
import com.example.chineseime.engine.sentence.SentenceQueryContext
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SentenceQueryPerformanceTest {
    private val source = NomCsvLoader.load(File("src/main/assets/hannom_rcv_standard_nom.csv").readText(Charsets.UTF_8))
    private val index = NomMemoryIndex(source.map { entry ->
        NomMemoryIndex.IndexedNomCandidate(
            NomCandidate(entry.sourceRow.toLong(),entry.sourceRow,entry.readingRaw,entry.nomRaw,entry.exampleRaw,entry.noteRaw),
            VietnameseInputParser.normalize(entry.readingRaw),
            VietnameseInputParser.withoutTone(entry.readingRaw),
            TelexComposer().toTelex(entry.readingRaw)
        )
    })
    private val repository = object : NomRepository {
        override fun search(input: VietnameseInput, limit: Int) = index.search(input, limit)
        override fun searchExactReading(normalized: String, limit: Int) = index.exactNormalized(normalized, limit)
        override fun searchWithoutTone(withoutTone: String, limit: Int) = index.exactWithoutTone(withoutTone, limit)
        override fun searchReadingPrefix(normalizedPrefix: String, limit: Int) = index.prefixNormalized(normalizedPrefix, limit)
        override fun searchWithoutTonePrefix(withoutTonePrefix: String, limit: Int) = index.prefixWithoutTone(withoutTonePrefix, limit)
        override fun searchTelexExact(telexKey: String, limit: Int) = index.exactTelex(telexKey, limit)
        override fun searchTelexPrefix(telexPrefix: String, limit: Int) = index.prefixTelex(telexPrefix, limit)
        override fun canExtend(input: VietnameseInput) = index.canExtend(input)
        override fun corpusFrequency(reading: String) = index.corpusFrequency(reading)
    }

    @Test fun debugProfileColdAndWarmQueriesWithoutStrictTimingAssertions() {
        val engine = SentenceNomEngine(repository)
        listOf("banj","tooi","toi","toiyeu","toiyeuem","tooiyeeuem").forEach { raw ->
            val cold = SentenceQueryContext()
            val coldResult = engine.query(raw,8,cold)
            val warm = SentenceQueryContext()
            val warmResult = engine.query(raw,8,warm)
            assertTrue("no candidates for $raw", coldResult.isNotEmpty() && warmResult.isNotEmpty())
            println("NOM_PERF raw=$raw coldMs=${cold.metrics.milliseconds(cold.metrics.totalEngineNanos)} " +
                "warmMs=${warm.metrics.milliseconds(warm.metrics.totalEngineNanos)} lookups=${warm.metrics.dictionaryLookupCount} " +
                "databaseMs=${warm.metrics.milliseconds(warm.metrics.dictionaryLookupNanos)} fastPath=${warm.metrics.fastPath}")
        }
    }

    @Test fun staleQueryCancelsInsideSegmentationInsteadOfFinishingAllLookups() {
        val coordinator = LatestQueryCoordinator()
        coordinator.activate(1)
        var calls = 0
        val cancellingRepository = object : NomRepository {
            override fun search(input: VietnameseInput, limit: Int) = emptyList<NomCandidate>()
            override fun searchExactReading(normalized: String, limit: Int) = emptyList<NomCandidate>().also { calls++ }
            override fun searchWithoutTone(withoutTone: String, limit: Int) = emptyList<NomCandidate>().also { calls++ }
            override fun searchTelexExact(telexKey: String, limit: Int) = emptyList<NomCandidate>().also { calls++ }
            override fun searchReadingPrefix(normalizedPrefix: String, limit: Int) = emptyList<NomCandidate>().also { calls++ }
            override fun searchWithoutTonePrefix(withoutTonePrefix: String, limit: Int) = emptyList<NomCandidate>().also { calls++ }
            override fun searchTelexPrefix(telexPrefix: String, limit: Int) = emptyList<NomCandidate>().also { calls++ }
            override fun canExtend(input: VietnameseInput): Boolean {
                calls++
                coordinator.activate(2)
                return true
            }
        }
        val context = coordinator.context(1)
        val result = SentenceNomEngine(cancellingRepository).query("toiyeuem",8,context)
        assertTrue(result.isEmpty())
        assertTrue(context.metrics.cancelled)
        assertTrue("stale query performed $calls lookups", calls < 132)
        assertTrue(coordinator.context(2).isCancelled().not())
    }
}
