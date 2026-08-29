package com.example.chineseime.engine

import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chineseime.data.local.NomDatabase
import com.example.chineseime.data.repository.SQLiteNomRepository
import com.example.chineseime.engine.sentence.SentenceNomEngine
import com.example.chineseime.engine.sentence.SentenceQueryContext
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SentenceDevicePerformanceTest {
    @Test fun profileColdAndWarmEngineQueries() {
        val database = NomDatabase(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            database.initialize()
            val engine = SentenceNomEngine(SQLiteNomRepository(database))
            listOf("banj","tooi","toi","toiyeu","toiyeuem","tooiyeeuem").forEach { raw ->
                val cold = SentenceQueryContext()
                val coldCandidates = engine.query(raw,8,cold)
                val warm = SentenceQueryContext()
                val warmCandidates = engine.query(raw,8,warm)
                assertTrue("no candidates for $raw",coldCandidates.isNotEmpty() && warmCandidates.isNotEmpty())
                Log.i(TAG,"raw=$raw coldMs=${cold.metrics.milliseconds(cold.metrics.totalEngineNanos)} " +
                    "warmMs=${warm.metrics.milliseconds(warm.metrics.totalEngineNanos)} " +
                    "lookups=${warm.metrics.dictionaryLookupCount} dictionaryMs=${warm.metrics.milliseconds(warm.metrics.dictionaryLookupNanos)} " +
                    "beamMs=${warm.metrics.milliseconds(warm.metrics.beamGenerationNanos)} rankingMs=${warm.metrics.milliseconds(warm.metrics.candidateRankingNanos)} fastPath=${warm.metrics.fastPath}")
            }
        } finally {
            database.close()
        }
    }

    companion object { private const val TAG = "NOM_PERF_DEVICE" }
}
