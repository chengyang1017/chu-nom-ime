package com.example.chineseime.ime

import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.VietnameseInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class T9PredictorTest {
    @Test
    fun predictsToiFrom864UsingDictionaryEvidence() {
        val predictor = T9Predictor(
            FakeRepository(
                mapOf(
                    "toi" to 4,
                    "voi" to 2,
                    "ung" to 1
                )
            )
        )

        val predictions = predictor.predict("864", 3)

        assertEquals("toi", predictions.first())
        assertTrue("voi" in predictions)
        assertTrue("ung" in predictions)
    }

    @Test
    fun keepsAllUsefulReadingsForAmbiguous2438() {
        val predictor = T9Predictor(
            FakeRepository(
                mapOf(
                    "bieu" to 5,
                    "biet" to 4,
                    "ciet" to 1
                )
            )
        )

        val predictions = predictor.predict("2438", 8)

        assertEquals("bieu", predictions.first())
        assertTrue("biet" in predictions)
    }

    @Test
    fun convertsToneLessVietnameseLettersToPhoneDigits() {
        assertEquals("864", T9Predictor.toDigits("toi"))
        assertEquals("938", T9Predictor.toDigits("yeu"))
        assertEquals("36", T9Predictor.toDigits("em"))
    }

    @Test
    fun rejectsUnsupportedDigits() {
        val predictor = T9Predictor(FakeRepository(mapOf("toi" to 1)))
        assertTrue(predictor.predict("106").isEmpty())
    }

    private class FakeRepository(
        private val evidenceCounts: Map<String, Int>
    ) : NomRepository {
        override fun search(input: VietnameseInput, limit: Int): List<NomCandidate> = emptyList()

        override fun searchWithoutTonePrefix(
            withoutTonePrefix: String,
            limit: Int
        ): List<NomCandidate> = evidenceCounts.keys
            .asSequence()
            .filter { it.startsWith(withoutTonePrefix) }
            .take(limit)
            .mapIndexed { index, reading -> candidate(reading, index + 1) }
            .toList()

        override fun searchWithoutTone(
            withoutTone: String,
            limit: Int
        ): List<NomCandidate> {
            val count = (evidenceCounts[withoutTone] ?: 0).coerceAtMost(limit)
            return List(count) { index -> candidate(withoutTone, index + 1) }
        }

        private fun candidate(reading: String, row: Int) = NomCandidate(
            sourceEntryId = row.toLong(),
            sourceRow = row,
            readingRaw = reading,
            nomRaw = "字$row",
            exampleRaw = "",
            noteRaw = ""
        )
    }
}
