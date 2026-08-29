package com.example.chineseime.engine

import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.repository.NomRepository
import org.junit.Assert.*
import org.junit.Test
import java.io.File

class LocalNomEngineTest {
    private val source by lazy { NomCsvLoader.load(listOf(File("src/main/assets/hannom_rcv_standard_nom.csv"), File("app/src/main/assets/hannom_rcv_standard_nom.csv")).first { it.exists() }.readText(Charsets.UTF_8)) }
    private val repository = object : NomRepository {
        override fun search(input: VietnameseInput, limit: Int) = source.filter {
            VietnameseInputParser.normalize(it.readingRaw) == input.normalized ||
                VietnameseInputParser.withoutTone(it.readingRaw) == input.withoutTone ||
                TelexComposer().toTelex(it.readingRaw) == input.telexKey
        }.take(limit).map { NomCandidate(it.sourceRow.toLong(), it.sourceRow, it.readingRaw, it.nomRaw, it.exampleRaw, it.noteRaw) }
    }
    private val engine = LocalNomEngine(repository)

    @Test fun dynamicCsvCandidateIsReturnedForDirectToneAndTelex() {
        val entry = source.first { it.readingRaw.any { ch -> ch.code > 127 } && !it.readingRaw.contains(' ') }
        assertTrue(engine.query(entry.readingRaw, 40).any { it.sourceRow == entry.sourceRow })
        assertTrue(engine.query(TelexComposer().toTelex(entry.readingRaw), 40).any { it.sourceRow == entry.sourceRow })
    }

    @Test fun homophonesAreNotMerged() {
        val group = source.groupBy { VietnameseInputParser.normalize(it.readingRaw) }.values.first { it.size > 1 }
        val result = engine.query(group.first().readingRaw, group.size + 10)
        assertTrue(result.map { it.sourceRow }.containsAll(group.map { it.sourceRow }))
        assertEquals(result.size, result.map { it.sourceEntryId }.distinct().size)
    }
}