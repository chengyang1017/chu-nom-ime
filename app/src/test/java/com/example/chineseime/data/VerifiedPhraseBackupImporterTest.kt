package com.example.chineseime.data

import com.example.chineseime.data.corpus.VerifiedPhraseBackupImporter
import com.example.chineseime.data.corpus.VerifiedPhraseCorpus
import com.example.chineseime.data.corpus.VerifiedPhraseCorpusEntry
import com.example.chineseime.data.corpus.VerifiedPhraseCorpusToken
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.model.VerifiedNomToken
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.VietnameseInput
import com.example.chineseime.engine.VietnameseInputParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class VerifiedPhraseBackupImporterTest {
    @Test
    fun restoreRebindsCurrentSourceIdsAndSkipsExistingPhrases() {
        val currentToi = NomCandidate(
            sourceEntryId = 101L,
            sourceRow = 700,
            readingRaw = "tôi",
            nomRaw = "碎",
            exampleRaw = "current tôi example",
            noteRaw = "current tôi note"
        )
        val currentYeu = NomCandidate(
            sourceEntryId = 202L,
            sourceRow = 701,
            readingRaw = "yêu",
            nomRaw = "㤇",
            exampleRaw = "current yêu example",
            noteRaw = "current yêu note"
        )
        val existing = phraseFrom(currentToi)
        val repository = FakeRepository(
            candidates = listOf(currentToi, currentYeu),
            existing = mutableListOf(existing)
        )
        val importer = VerifiedPhraseBackupImporter(repository)

        val backupToi = phraseFrom(currentToi, sourceEntryId = 9_001L, example = "old example")
        val backupYeu = phraseFrom(currentYeu, sourceEntryId = 9_002L, example = "old example")
        val corpus = corpusOf(revision = 42L, backupToi, backupYeu)

        val plan = importer.plan(corpus)
        assertEquals(2, plan.totalCount)
        assertEquals(1, plan.newCount)
        assertEquals(1, plan.duplicateCount)

        val result = importer.apply(plan)
        assertEquals(1, result.importedCount)
        assertEquals(1, result.skippedCount)

        val restored = repository.saved.single()
        assertEquals(202L, restored.tokens.single().sourceEntryId)
        assertEquals(currentYeu.exampleRaw, restored.tokens.single().exampleRaw)
        assertEquals(currentYeu.noteRaw, restored.tokens.single().noteRaw)
    }

    @Test
    fun invalidDictionaryEvidenceIsRejectedBeforeAnythingIsSaved() {
        val current = NomCandidate(
            sourceEntryId = 101L,
            sourceRow = 700,
            readingRaw = "tôi",
            nomRaw = "碎",
            exampleRaw = "example",
            noteRaw = "note"
        )
        val repository = FakeRepository(listOf(current), mutableListOf())
        val importer = VerifiedPhraseBackupImporter(repository)
        val invalid = VerifiedNomPhrase.create(
            phraseRaw = "tôi",
            tokens = listOf(
                VerifiedNomToken(
                    inputToken = "tôi",
                    sourceEntryId = 9_001L,
                    readingRaw = "tôi",
                    nomRaw = "碎",
                    exampleRaw = "example",
                    noteRaw = "note",
                    sourceRow = 999_999
                )
            )
        )
        val corpus = corpusOf(revision = 43L, invalid)

        try {
            importer.plan(corpus)
            fail("Expected invalid backup evidence to be rejected")
        } catch (_: IllegalArgumentException) {
            // Expected: validation happens before apply() can write anything.
        }

        assertTrue(repository.saved.isEmpty())
    }

    private fun corpusOf(
        revision: Long,
        vararg phrases: VerifiedNomPhrase
    ): VerifiedPhraseCorpus = VerifiedPhraseCorpus(
        schemaVersion = 1,
        revision = revision,
        phrases = phrases.map { phrase ->
            VerifiedPhraseCorpusEntry(
                phraseRaw = phrase.phraseRaw,
                tokens = phrase.tokens.map { token ->
                    VerifiedPhraseCorpusToken(
                        inputToken = token.inputToken,
                        readingRaw = token.readingRaw,
                        nomRaw = token.nomRaw,
                        exampleRaw = token.exampleRaw,
                        noteRaw = token.noteRaw,
                        sourceRow = token.sourceRow
                    )
                }
            )
        }
    )

    private fun phraseFrom(
        candidate: NomCandidate,
        sourceEntryId: Long = candidate.sourceEntryId,
        example: String = candidate.exampleRaw
    ): VerifiedNomPhrase = VerifiedNomPhrase.create(
        phraseRaw = candidate.readingRaw,
        tokens = listOf(
            VerifiedNomToken(
                inputToken = candidate.readingRaw,
                sourceEntryId = sourceEntryId,
                readingRaw = candidate.readingRaw,
                nomRaw = candidate.nomRaw,
                exampleRaw = example,
                noteRaw = candidate.noteRaw,
                sourceRow = candidate.sourceRow
            )
        )
    )

    private class FakeRepository(
        private val candidates: List<NomCandidate>,
        private val existing: MutableList<VerifiedNomPhrase>
    ) : NomRepository {
        val saved = mutableListOf<VerifiedNomPhrase>()

        override fun search(input: VietnameseInput, limit: Int): List<NomCandidate> = emptyList()

        override fun searchExactReading(normalized: String, limit: Int): List<NomCandidate> =
            candidates.filter {
                VietnameseInputParser.normalize(it.readingRaw) == normalized
            }.take(limit)

        override fun listVerifiedPhrases(limit: Int): List<VerifiedNomPhrase> =
            (existing + saved).take(limit)

        override fun saveVerifiedPhrase(phrase: VerifiedNomPhrase): Long {
            saved += phrase
            return (existing.size + saved.size).toLong()
        }
    }
}
