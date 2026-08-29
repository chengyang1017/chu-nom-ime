package com.example.chineseime.engine.sentence

data class SentenceQueryMetrics(
    var segmentationNanos: Long = 0,
    var dictionaryLookupNanos: Long = 0,
    var beamGenerationNanos: Long = 0,
    var candidateRankingNanos: Long = 0,
    var totalEngineNanos: Long = 0,
    var dictionaryLookupCount: Int = 0,
    var cancelled: Boolean = false,
    var fastPath: Boolean = false
) {
    fun milliseconds(nanos: Long): Double = nanos / 1_000_000.0
}

class SentenceQueryContext(
    private val cancellationCheck: () -> Boolean = { false },
    val metrics: SentenceQueryMetrics = SentenceQueryMetrics()
) {
    private val adjacencyScores = HashMap<Pair<Long, Long>, Double>()
    fun isCancelled(): Boolean {
        val value = cancellationCheck()
        if (value) metrics.cancelled = true
        return value
    }

    inline fun <T> dictionaryLookup(block: () -> T): T {
        val started = System.nanoTime()
        return try {
            block()
        } finally {
            metrics.dictionaryLookupNanos += System.nanoTime() - started
            metrics.dictionaryLookupCount++
        }
    }

    inline fun <T> scoringLookup(block: () -> T): T {
        val started = System.nanoTime()
        return try {
            block()
        } finally {
            metrics.dictionaryLookupNanos += System.nanoTime() - started
        }
    }

    fun adjacencyScore(previous: Long, current: Long, lookup: () -> Double): Double =
        adjacencyScores.getOrPut(previous to current) { scoringLookup(lookup) }
}
