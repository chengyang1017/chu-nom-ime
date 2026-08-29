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
}
