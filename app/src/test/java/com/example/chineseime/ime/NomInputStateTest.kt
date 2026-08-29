package com.example.chineseime.ime
import org.junit.Assert.*
import org.junit.Test
class NomInputStateTest {
 @Test fun noCandidateKeepsVietnameseInput(){val state=NomInputState();state.type("tieengs");assertEquals("tiếng",state.commitFallback())}
 @Test fun deleteUsesCodePointBoundary(){val state=NomInputState();state.type("a");state.type(String(Character.toChars(0x20000)));state.delete();assertEquals("a",state.buffer)}
}