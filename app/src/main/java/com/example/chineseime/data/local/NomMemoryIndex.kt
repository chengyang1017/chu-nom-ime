package com.example.chineseime.data.local

import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.engine.VietnameseInput
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Immutable hot-path index built from SQLite after database initialization. */
class NomMemoryIndex(entries: List<IndexedNomCandidate>) {
    data class IndexedNomCandidate(
        val candidate: NomCandidate,
        val readingNormalized: String,
        val readingWithoutTone: String,
        val telexKey: String
    )

    private class PrefixNode {
        val children = HashMap<Int, PrefixNode>()
        val candidates = ArrayList<NomCandidate>()
    }

    private class PrefixIndex {
        private val root = PrefixNode()

        fun add(key: String, candidate: NomCandidate) {
            var node = root
            key.codePoints().forEach { codePoint ->
                node = node.children.getOrPut(codePoint) { PrefixNode() }
                node.candidates += candidate
            }
        }

        fun search(prefix: String, limit: Int): List<NomCandidate> {
            var node = root
            val points = prefix.codePoints().toArray()
            for (codePoint in points) node = node.children[codePoint] ?: return emptyList()
            return node.candidates.take(limit)
        }

        fun containsPrefix(prefix: String): Boolean {
            var node = root
            val points = prefix.codePoints().toArray()
            for (codePoint in points) node = node.children[codePoint] ?: return false
            return true
        }
    }

    private val normalizedExact = LinkedHashMap<String, MutableList<NomCandidate>>()
    private val withoutToneExact = LinkedHashMap<String, MutableList<NomCandidate>>()
    private val telexExact = LinkedHashMap<String, MutableList<NomCandidate>>()
    private val normalizedPrefix = PrefixIndex()
    private val withoutTonePrefix = PrefixIndex()
    private val telexPrefix = PrefixIndex()
    private val examples = entries.map { it.candidate.exampleRaw.lowercase(Locale.ROOT) }
    private val corpusCache = ConcurrentHashMap<String, Int>()

    init {
        entries.sortedBy { it.candidate.sourceRow }.forEach { entry ->
            add(normalizedExact, entry.readingNormalized, entry.candidate)
            add(withoutToneExact, entry.readingWithoutTone, entry.candidate)
            add(telexExact, entry.telexKey, entry.candidate)
            normalizedPrefix.add(entry.readingNormalized, entry.candidate)
            withoutTonePrefix.add(entry.readingWithoutTone, entry.candidate)
            telexPrefix.add(entry.telexKey, entry.candidate)
        }
    }

    fun search(input: VietnameseInput, limit: Int): List<NomCandidate> =
        (exact(normalizedExact, input.normalized, limit) +
            exact(withoutToneExact, input.withoutTone, limit) +
            exact(telexExact, input.telexKey, limit))
            .distinctBy(NomCandidate::sourceEntryId).sortedBy(NomCandidate::sourceRow).take(limit)

    fun exactNormalized(key: String, limit: Int) = exact(normalizedExact, key, limit)
    fun exactWithoutTone(key: String, limit: Int) = exact(withoutToneExact, key, limit)
    fun exactTelex(key: String, limit: Int) = exact(telexExact, key, limit)
    fun prefixNormalized(key: String, limit: Int) = normalizedPrefix.search(key, limit)
    fun prefixWithoutTone(key: String, limit: Int) = withoutTonePrefix.search(key, limit)
    fun prefixTelex(key: String, limit: Int) = telexPrefix.search(key, limit)

    fun canExtend(input: VietnameseInput): Boolean =
        normalizedPrefix.containsPrefix(input.normalized) ||
            withoutTonePrefix.containsPrefix(input.withoutTone) ||
            telexPrefix.containsPrefix(input.telexKey)

    fun corpusFrequency(reading: String): Int {
        val key = reading.lowercase(Locale.ROOT)
        return corpusCache.getOrPut(key) { examples.count { it.contains(key) } }
    }

    private fun add(index: MutableMap<String, MutableList<NomCandidate>>, key: String, candidate: NomCandidate) {
        index.getOrPut(key) { ArrayList() } += candidate
    }

    private fun exact(index: Map<String, List<NomCandidate>>, key: String, limit: Int): List<NomCandidate> =
        index[key]?.take(limit).orEmpty()
}
