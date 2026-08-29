package com.example.chineseime.engine.sentence

import java.util.concurrent.atomic.AtomicLong

/** Shares latest-generation state between the IME thread and the query worker. */
class LatestQueryCoordinator {
    private val latest = AtomicLong(-1L)

    fun activate(generation: Long) {
        latest.set(generation)
    }

    fun isStale(generation: Long): Boolean = latest.get() != generation

    fun latestGeneration(): Long = latest.get()

    fun context(generation: Long, metrics: SentenceQueryMetrics = SentenceQueryMetrics()) =
        SentenceQueryContext(cancellationCheck = { isStale(generation) }, metrics = metrics)
}
