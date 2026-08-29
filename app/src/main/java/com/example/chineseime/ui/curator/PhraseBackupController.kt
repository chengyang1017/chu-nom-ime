package com.example.chineseime.ui.curator

import android.net.Uri
import androidx.appcompat.app.AppCompatActivity
import com.example.chineseime.data.corpus.VerifiedPhraseBackupImporter
import com.example.chineseime.data.corpus.VerifiedPhraseCorpusCodec
import com.example.chineseime.data.corpus.VerifiedPhraseImportPlan
import com.example.chineseime.data.corpus.VerifiedPhraseImportResult
import com.example.chineseime.data.local.NomDatabase
import com.example.chineseime.data.repository.SQLiteNomRepository
import java.util.concurrent.Executors

class PhraseBackupController(
    private val activity: AppCompatActivity
) {
    private val executor = Executors.newSingleThreadExecutor()
    private val database = NomDatabase(activity)
    private val repository = SQLiteNomRepository(database)
    private val importer = VerifiedPhraseBackupImporter(repository)
    @Volatile private var ready = false

    fun exportBackup(uri: Uri, onComplete: (Result<Int>) -> Unit) {
        executor.execute {
            val result = runCatching {
                ensureReady()
                val phrases = repository.listVerifiedPhrases(Int.MAX_VALUE)
                val json = VerifiedPhraseCorpusCodec.encode(
                    phrases = phrases,
                    revision = System.currentTimeMillis()
                )
                val output = requireNotNull(activity.contentResolver.openOutputStream(uri, "w")) {
                    "Unable to open the selected backup destination"
                }
                output.bufferedWriter(Charsets.UTF_8).use { writer -> writer.write(json) }
                phrases.size
            }
            deliver(result, onComplete)
        }
    }

    fun inspectImport(uri: Uri, onComplete: (Result<VerifiedPhraseImportPlan>) -> Unit) {
        executor.execute {
            val result = runCatching {
                ensureReady()
                val input = requireNotNull(activity.contentResolver.openInputStream(uri)) {
                    "Unable to open the selected backup file"
                }
                val text = input.bufferedReader(Charsets.UTF_8).use { reader -> reader.readText() }
                require(text.length <= MAX_BACKUP_CHARS) {
                    "Backup file is too large"
                }
                importer.plan(text)
            }
            deliver(result, onComplete)
        }
    }

    fun applyImport(
        plan: VerifiedPhraseImportPlan,
        onComplete: (Result<VerifiedPhraseImportResult>) -> Unit
    ) {
        executor.execute {
            val result = runCatching {
                ensureReady()
                importer.apply(plan)
            }
            deliver(result, onComplete)
        }
    }

    fun close() {
        executor.shutdownNow()
        database.close()
    }

    @Synchronized
    private fun ensureReady() {
        if (ready) return
        database.initialize()
        ready = true
    }

    private fun <T> deliver(result: Result<T>, callback: (Result<T>) -> Unit) {
        activity.runOnUiThread { callback(result) }
    }

    private companion object {
        const val MAX_BACKUP_CHARS = 10_000_000
    }
}
