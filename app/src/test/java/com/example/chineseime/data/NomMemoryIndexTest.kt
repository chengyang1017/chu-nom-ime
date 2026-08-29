package com.example.chineseime.data

import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.local.NomMemoryIndex
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.engine.TelexComposer
import com.example.chineseime.engine.VietnameseInputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class NomMemoryIndexTest {
    private val source = NomCsvLoader.load(File("src/main/assets/hannom_rcv_standard_nom.csv").readText(Charsets.UTF_8))
    private val telex = TelexComposer()
    private val index = NomMemoryIndex(source.map { entry ->
        NomMemoryIndex.IndexedNomCandidate(
            NomCandidate(entry.sourceRow.toLong(),entry.sourceRow,entry.readingRaw,entry.nomRaw,entry.exampleRaw,entry.noteRaw),
            VietnameseInputParser.normalize(entry.readingRaw),
            VietnameseInputParser.withoutTone(entry.readingRaw),
            telex.toTelex(entry.readingRaw)
        )
    })

    @Test fun exactAndPrefixIndexesReturnStableSourceOrderedResults() {
        val exact = index.exactWithoutTone("toi", 40)
        assertTrue(exact.isNotEmpty())
        assertEquals(exact.sortedBy(NomCandidate::sourceRow), exact)
        assertEquals(exact, index.exactWithoutTone("toi", 40))
        val prefix = index.prefixWithoutTone("to", 40)
        assertTrue(prefix.isNotEmpty())
        assertTrue(prefix.all { VietnameseInputParser.withoutTone(it.readingRaw).startsWith("to") })
        assertTrue(index.canExtend(VietnameseInputParser().parse("toi")))
    }
}
