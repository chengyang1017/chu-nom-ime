package com.example.chineseime.ime

import org.junit.Assert.*
import org.junit.Test

class SentenceCompositionStateTest {
    @Test fun spaceContinuesSentenceAndDoesNotReset() { val s=SentenceCompositionState(); "toi".forEach{s.append(it.toString())}; s.appendSpace(); "yeu".forEach{s.append(it.toString())}; assertEquals("toi yeu",s.rawSentence); assertTrue(s.isComposing) }
    @Test fun deleteUsesUnicodeCodePoint() { val s=SentenceCompositionState(); s.append("a"); s.append(String(Character.toChars(0x20000))); s.deleteCodePoint(); assertEquals("a",s.rawSentence) }
    @Test fun staleGenerationCannotOverwriteNewSentence() { val s=SentenceCompositionState(); s.append("a"); val old=s.queryGeneration; s.append("b"); assertFalse(s.applyCandidates(old,emptyList())) }
    @Test fun resetAllowsNextSentence() { val s=SentenceCompositionState(); s.append("toi"); s.reset(); s.append("em"); assertEquals("em",s.rawSentence); assertTrue(s.isComposing) }
    @Test fun tenSentencesRemainResponsive() { val s=SentenceCompositionState(); repeat(10){s.append("toi yeu em");s.reset()}; assertEquals("",s.rawSentence) }
    @Test fun trailingSpaceIsPreserved() { val s=SentenceCompositionState(); s.append("toi"); s.appendSpace(); assertEquals("toi ",s.rawSentence); val parsed=com.example.chineseime.engine.sentence.IncrementalSentenceInput.parse(s.rawSentence); assertEquals(listOf("toi"),parsed.completedTokens); assertEquals("",parsed.currentToken); assertTrue(parsed.endsWithSpace) }
}