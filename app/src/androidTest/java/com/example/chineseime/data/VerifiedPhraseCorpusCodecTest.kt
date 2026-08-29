package com.example.chineseime.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chineseime.data.corpus.VerifiedPhraseCorpusCodec
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.model.VerifiedNomToken
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerifiedPhraseCorpusCodecTest {
    @Test fun exportRoundTripUsesStableSourceRowsInsteadOfDatabaseIds() {
        val supplementaryNom=String(Character.toChars(0x20000))
        val phrase=VerifiedNomPhrase.create(
            phraseRaw="fixture",
            tokens=listOf(
                VerifiedNomToken(
                    inputToken="fixture",
                    sourceEntryId=987654L,
                    readingRaw="fixture",
                    nomRaw=supplementaryNom,
                    exampleRaw="example",
                    noteRaw="note",
                    sourceRow=321
                )
            )
        )
        val json=VerifiedPhraseCorpusCodec.encode(listOf(phrase),revision=123L)
        assertFalse(json.contains("sourceEntryId"))
        val decoded=VerifiedPhraseCorpusCodec.decode(json)
        assertEquals(123L,decoded.revision)
        assertEquals("fixture",decoded.phrases.single().phraseRaw)
        assertEquals(321,decoded.phrases.single().tokens.single().sourceRow)
        assertEquals(supplementaryNom,decoded.phrases.single().tokens.single().nomRaw)
    }
}
