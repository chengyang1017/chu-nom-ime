package com.example.chineseime.ime

import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.NomSentenceSegment
import org.junit.Assert.*
import org.junit.Test

class SentenceCompositionStateTest {
    @Test fun spaceContinuesSentenceAndDoesNotReset() { val s=SentenceCompositionState(); "toi".forEach{s.append(it.toString())}; s.appendSpace(); "yeu".forEach{s.append(it.toString())}; assertEquals("toi yeu",s.rawSentence); assertTrue(s.isComposing) }
    @Test fun deleteUsesUnicodeCodePoint() { val s=SentenceCompositionState(); s.append("a"); s.append(String(Character.toChars(0x20000))); s.deleteCodePoint(); assertEquals("a",s.rawSentence) }
    @Test fun staleGenerationCannotOverwriteNewSentence() { val s=SentenceCompositionState(); s.append("a"); val old=s.queryGeneration; s.append("b"); assertFalse(s.applyCandidates(old,emptyList())) }
    @Test fun resetAllowsNextSentence() { val s=SentenceCompositionState(); s.append("toi"); s.reset(); s.append("em"); assertEquals("em",s.rawSentence); assertTrue(s.isComposing) }
    @Test fun tenSentencesRemainResponsive() { val s=SentenceCompositionState(); repeat(10){s.append("toi yeu em");s.reset()}; assertEquals("",s.rawSentence) }
    @Test fun trailingSpaceIsPreserved() { val s=SentenceCompositionState(); s.append("toi"); s.appendSpace(); assertEquals("toi ",s.rawSentence); val parsed=com.example.chineseime.engine.sentence.IncrementalSentenceInput.parse(s.rawSentence); assertEquals(listOf("toi"),parsed.completedTokens); assertEquals("",parsed.currentToken); assertTrue(parsed.endsWithSpace) }
    @Test fun telexIsComposedSynchronouslyWithoutCandidates() {
        mapOf("banj" to "bạn", "tooi" to "tôi", "yeeu" to "yêu", "ddang" to "đang").forEach { (raw, expected) ->
            val state = SentenceCompositionState()
            state.append(raw)
            assertEquals(raw, state.rawSentence)
            assertEquals(expected, state.displaySentence)
        }
    }
    @Test fun inferredToneNeverOverridesExplicitDisplay() {
        val state = SentenceCompositionState()
        state.append("toi")
        val generation = state.queryGeneration
        assertTrue(state.applyCandidates(generation, listOf(restoredCandidate(listOf("toi"), "tối"))))
        assertEquals("toi", state.rawSentence)
        assertEquals("toi", state.displaySentence)
        assertEquals("tối", state.restoredSentence)
    }
    @Test fun segmentationAddsBoundariesWithoutInferringTonesInDisplay() {
        val state = SentenceCompositionState()
        state.append("toiyeuem")
        val generation = state.queryGeneration
        assertTrue(state.applyCandidates(generation, listOf(restoredCandidate(listOf("toi", "yeu", "em"), "tôi yêu em"))))
        assertEquals("toiyeuem", state.rawSentence)
        assertEquals("toi yeu em", state.displaySentence)
        assertEquals("tôi yêu em", state.restoredSentence)
    }
    @Test fun segmentedExplicitTelexControlsDisplay() {
        val state = SentenceCompositionState()
        state.append("tooiyeeuem")
        val generation = state.queryGeneration
        assertTrue(state.applyCandidates(generation, listOf(restoredCandidate(listOf("tooi", "yeeu", "em"), "tôi yêu em"))))
        assertEquals("tooiyeeuem", state.rawSentence)
        assertEquals("tôi yêu em", state.displaySentence)
        assertEquals("tôi yêu em", state.restoredSentence)
    }
    @Test fun deletingTelexKeyRecomposesFromRaw() {
        val state = SentenceCompositionState()
        state.append("banj")
        assertEquals("bạn", state.displaySentence)
        state.deleteCodePoint()
        assertEquals("ban", state.rawSentence)
        assertEquals("ban", state.displaySentence)
    }
    @Test fun staleSentenceRestorationCannotReplaceCurrentComposingText() {
        val state = SentenceCompositionState()
        state.append("toiyeuem")
        val staleGeneration = state.queryGeneration
        state.append("x")
        val currentDisplay = state.displaySentence
        assertFalse(state.applyCandidates(staleGeneration, listOf(restoredCandidate(listOf("toi", "yeu", "em"), "tôi yêu em"))))
        assertEquals("toiyeuemx", state.rawSentence)
        assertEquals(currentDisplay, state.displaySentence)
    }

    private fun restoredCandidate(rawSegments: List<String>, restored: String) = NomSentenceCandidate(
        nomText = "",
        restoredVietnamese = restored,
        sourceEntryIds = emptyList(),
        segments = rawSegments.mapIndexed { index, raw ->
            NomSentenceSegment(index, index + 1, listOf(raw), "", "", emptyList(), 0.0, true)
        },
        score = 0.0
    )
}
