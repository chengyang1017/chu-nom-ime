package com.example.chineseime.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SentenceCompositionStateNineKeyTest {
    @Test
    fun replaceLastCodePointUpdatesRawSentenceInOneGeneration() {
        val state = SentenceCompositionState()
        state.append("p")
        val before = state.queryGeneration

        state.replaceLastCodePoint("q")

        assertEquals("q", state.rawSentence)
        assertEquals(before + 1, state.queryGeneration)
        assertTrue(state.isComposing)
    }

    @Test
    fun replaceLastCodePointRecomposesTelexTone() {
        val state = SentenceCompositionState()
        state.append("banj")
        assertEquals("bạn", state.displaySentence)

        state.replaceLastCodePoint("s")

        assertEquals("bans", state.rawSentence)
        assertEquals("bán", state.displaySentence)
    }

    @Test
    fun replaceCurrentTokenKeepsPreviousWords() {
        val state = SentenceCompositionState()
        state.append("xin")
        state.appendSpace()
        state.append("864")

        state.replaceCurrentToken("toi")

        assertEquals("xin toi", state.rawSentence)
        assertEquals("xin toi", state.displaySentence)
    }

    @Test
    fun replaceCurrentTokenCanRemoveActiveT9Word() {
        val state = SentenceCompositionState()
        state.append("xin")
        state.appendSpace()
        state.append("toi")

        state.replaceCurrentToken("")

        assertEquals("xin ", state.rawSentence)
        assertEquals("xin ", state.displaySentence)
    }
}
