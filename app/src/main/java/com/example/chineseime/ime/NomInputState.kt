package com.example.chineseime.ime

import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.engine.VietnameseInputParser

class NomInputState(private val parser: VietnameseInputParser = VietnameseInputParser()) {
    var buffer: String = ""
        private set
    var candidates: List<NomCandidate> = emptyList()
        private set
    val composed: String get() = parser.parse(buffer).composed

    fun type(value: String) { buffer += value; candidates = emptyList() }

    fun delete() {
        val count = buffer.codePointCount(0, buffer.length)
        if (count == 0) return
        val end = buffer.offsetByCodePoints(0, count - 1)
        buffer = buffer.substring(0, end)
        candidates = emptyList()
    }

    fun setCandidates(value: List<NomCandidate>) { candidates = value }

    fun choose(index: Int): String? {
        val value = candidates.getOrNull(index)?.nomRaw ?: return null
        clear()
        return value
    }

    fun commitFallback(): String? {
        if (buffer.isEmpty()) return null
        val value = composed
        clear()
        return value
    }

    fun clear() { buffer = ""; candidates = emptyList() }
}