package com.example.chineseime.data.local

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.Log
import com.example.chineseime.data.corpus.VerifiedPhraseCorpusCodec
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.model.VerifiedNomToken
import com.example.chineseime.engine.TelexComposer
import com.example.chineseime.engine.VietnameseInput
import com.example.chineseime.engine.VietnameseInputParser
import org.json.JSONArray
import org.json.JSONObject

class NomDatabase(private val context: Context, databaseName: String = DATABASE_NAME) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
    data class InitializationStatus(
        val sourceRows: Int,
        val searchRows: Int,
        val csvSha256: String,
        val reimported: Boolean,
        val failedRows: Map<Int, String>
    )

    private val telex = TelexComposer()
    @Volatile private var initialized = false
    @Volatile private var memoryIndex: NomMemoryIndex? = null
    private val corpusFrequencyCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val sentenceSelectionCache = java.util.concurrent.ConcurrentHashMap<String, Int>()
    private val ngramCache = java.util.concurrent.ConcurrentHashMap<Pair<Long, Long>, Int>()
    @Volatile private var verifiedPhraseCache: List<VerifiedNomPhrase> = emptyList()
    @Volatile private var localVerifiedRevision: Long = -1

    override fun onConfigure(db: SQLiteDatabase) {
        super.onConfigure(db)
        db.setForeignKeyConstraintsEnabled(true)
    }

    override fun onCreate(db: SQLiteDatabase) = createSchema(db)

    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        Log.w(TAG, "database migration oldVersion=$oldVersion newVersion=$newVersion")
        // Migrations are additive: source dictionary and local learning data must survive upgrades.
        createSchema(db)
    }

    @Synchronized
    fun initialize(): InitializationStatus {
        try {
            val metadataText = context.assets.open(METADATA_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
            val metadata = JSONObject(metadataText)
            val expectedSha = metadata.getString("csvSha256")
            val expectedRows = metadata.getInt("extractedRowCount")
            val db = writableDatabase
            val existingSha = metadataValue(db, "csv_sha256")
            val importComplete = metadataValue(db, "import_complete") == "1"
            val before = counts(db)
            val mustImport = existingSha != expectedSha || !importComplete || before.first != expectedRows || before.second != expectedRows
            Log.i(TAG, "initialize csvSha=$expectedSha storedSha=$existingSha complete=$importComplete source=${before.first} search=${before.second} expected=$expectedRows reimport=$mustImport")
            val failures = linkedMapOf<Int, String>()
            if (mustImport) importCsv(db, expectedSha, expectedRows, failures)
            val after = counts(db)
            check(after.first == expectedRows && after.second == expectedRows) {
                "database count mismatch after import: source=${after.first} search=${after.second} expected=$expectedRows"
            }
            importBundledVerifiedCorpusIfNeeded(db)
            val indexStarted = System.nanoTime()
            memoryIndex = buildMemoryIndex(db)
            loadLearningCaches(db)
            verifiedPhraseCache = queryVerified("1=1",emptyArray(),Int.MAX_VALUE)
            localVerifiedRevision = verifiedRevision.get()
            initialized = true
            Log.i(TAG, "database ready source=${after.first} search=${after.second} csvSha=$expectedSha memoryIndexMs=${(System.nanoTime()-indexStarted)/1_000_000.0}")
            return InitializationStatus(after.first, after.second, expectedSha, mustImport, failures)
        } catch (error: Throwable) {
            initialized = false
            Log.e(TAG, "database initialization failed", error)
            throw error
        }
    }

    fun search(input: VietnameseInput, limit: Int): List<NomCandidate> {
        check(initialized) { "Nom database queried before initialization completed" }
        return requireNotNull(memoryIndex).search(input, limit)
    }

    fun searchExactReading(normalized: String, limit: Int): List<NomCandidate> = index().exactNormalized(normalized, limit)
    fun searchWithoutTone(withoutTone: String, limit: Int): List<NomCandidate> = index().exactWithoutTone(withoutTone, limit)
    fun searchReadingPrefix(normalizedPrefix: String, limit: Int): List<NomCandidate> = index().prefixNormalized(normalizedPrefix, limit)
    fun searchWithoutTonePrefix(withoutTonePrefix: String, limit: Int): List<NomCandidate> = index().prefixWithoutTone(withoutTonePrefix, limit)
    fun searchTelexExact(telexKey: String, limit: Int): List<NomCandidate> = index().exactTelex(telexKey, limit)
    fun searchTelexPrefix(telexPrefix: String, limit: Int): List<NomCandidate> = index().prefixTelex(telexPrefix, limit)
    fun canExtend(input: VietnameseInput): Boolean = index().canExtend(input)

    private fun index(): NomMemoryIndex {
        check(initialized) { "Nom database queried before initialization completed" }
        return requireNotNull(memoryIndex)
    }

    private fun searchColumn(where: String, argument: String, limit: Int, label: String): List<NomCandidate> {
        check(initialized) { "Nom database queried before initialization completed" }
        val sql = """SELECT s.id,s.sourceRow,s.readingRaw,s.nomRaw,s.exampleRaw,s.noteRaw
            FROM nom_search_index i JOIN nom_source_entries s ON s.id=i.sourceEntryId
            WHERE $where ORDER BY s.sourceRow LIMIT ?""".trimIndent()
        val result = mutableListOf<NomCandidate>()
        readableDatabase.rawQuery(sql, arrayOf(argument, limit.toString())).use { cursor ->
            while (cursor.moveToNext()) result += NomCandidate(cursor.getLong(0),cursor.getInt(1),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5))
        }
        Log.d(TAG, "SQL $label argument=$argument resultCount=${result.size}")
        return result
    }

    private fun escapeLike(value: String): String = value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")

    private fun buildMemoryIndex(db: SQLiteDatabase): NomMemoryIndex {
        val values = ArrayList<NomMemoryIndex.IndexedNomCandidate>()
        val sql = """SELECT s.id,s.sourceRow,s.readingRaw,s.nomRaw,s.exampleRaw,s.noteRaw,
            i.readingNormalized,i.readingWithoutTone,i.telexKey
            FROM nom_search_index i JOIN nom_source_entries s ON s.id=i.sourceEntryId
            ORDER BY s.sourceRow""".trimIndent()
        db.rawQuery(sql, null).use { cursor ->
            while (cursor.moveToNext()) {
                values += NomMemoryIndex.IndexedNomCandidate(
                    candidate = NomCandidate(cursor.getLong(0),cursor.getInt(1),cursor.getString(2),cursor.getString(3),cursor.getString(4),cursor.getString(5)),
                    readingNormalized = cursor.getString(6),
                    readingWithoutTone = cursor.getString(7),
                    telexKey = cursor.getString(8)
                )
            }
        }
        return NomMemoryIndex(values)
    }
    private fun createSchema(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE IF NOT EXISTS nom_source_entries(id INTEGER PRIMARY KEY AUTOINCREMENT, sourceRow INTEGER NOT NULL, readingRaw TEXT NOT NULL, nomRaw TEXT NOT NULL, exampleRaw TEXT NOT NULL, noteRaw TEXT NOT NULL, sourceUrl TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS nom_search_index(id INTEGER PRIMARY KEY AUTOINCREMENT, sourceEntryId INTEGER NOT NULL, readingNormalized TEXT NOT NULL, readingWithoutTone TEXT NOT NULL, telexKey TEXT NOT NULL, FOREIGN KEY(sourceEntryId) REFERENCES nom_source_entries(id) ON DELETE CASCADE)")
        db.execSQL("CREATE TABLE IF NOT EXISTS nom_metadata(key TEXT PRIMARY KEY, value TEXT NOT NULL)")
        db.execSQL("CREATE TABLE IF NOT EXISTS nom_user_selection(rawSentence TEXT NOT NULL, restoredSentence TEXT NOT NULL, selectedNomText TEXT NOT NULL, sourceEntryIds TEXT NOT NULL, selectedCount INTEGER NOT NULL, lastSelectedAt INTEGER NOT NULL, PRIMARY KEY(rawSentence, selectedNomText))")
        db.execSQL("CREATE TABLE IF NOT EXISTS nom_user_ngram(previousSourceEntryId INTEGER NOT NULL, currentSourceEntryId INTEGER NOT NULL, count INTEGER NOT NULL, PRIMARY KEY(previousSourceEntryId,currentSourceEntryId))")
        createVerifiedPhraseSchema(db)
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nom_normalized ON nom_search_index(readingNormalized)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nom_without_tone ON nom_search_index(readingWithoutTone)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_nom_telex ON nom_search_index(telexKey)")
    }

    private fun createVerifiedPhraseSchema(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE IF NOT EXISTS verified_nom_phrases(
            id INTEGER PRIMARY KEY AUTOINCREMENT,
            phraseRaw TEXT NOT NULL,
            phraseNormalized TEXT NOT NULL,
            phraseWithoutTone TEXT NOT NULL,
            phraseNormalizedCompact TEXT NOT NULL,
            phraseWithoutToneCompact TEXT NOT NULL,
            nomText TEXT NOT NULL,
            sourceEntryIdsJson TEXT NOT NULL,
            tokensJson TEXT NOT NULL,
            createdAt INTEGER NOT NULL,
            updatedAt INTEGER NOT NULL,
            UNIQUE(phraseNormalized,nomText)
        )""".trimIndent())
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_verified_exact ON verified_nom_phrases(phraseNormalized,phraseNormalizedCompact)")
        db.execSQL("CREATE INDEX IF NOT EXISTS idx_verified_without_tone ON verified_nom_phrases(phraseWithoutTone,phraseWithoutToneCompact)")
    }

    private fun importCsv(db: SQLiteDatabase, sha: String, expectedRows: Int, failures: MutableMap<Int, String>) {
        val csvText = context.assets.open(CSV_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val entries = NomCsvLoader.load(csvText)
        check(entries.size == expectedRows) { "CSV row count=${entries.size} metadata count=$expectedRows" }
        db.beginTransaction()
        try {
            db.delete("nom_search_index", null, null)
            db.delete("nom_source_entries", null, null)
            db.delete("nom_metadata", null, null)
            entries.forEach { entry ->
                try {
                    val sourceId = db.insertOrThrow("nom_source_entries", null, ContentValues().apply {
                        put("sourceRow", entry.sourceRow); put("readingRaw", entry.readingRaw); put("nomRaw", entry.nomRaw)
                        put("exampleRaw", entry.exampleRaw); put("noteRaw", entry.noteRaw); put("sourceUrl", entry.sourceUrl)
                    })
                    db.insertOrThrow("nom_search_index", null, ContentValues().apply {
                        put("sourceEntryId", sourceId)
                        put("readingNormalized", VietnameseInputParser.normalize(entry.readingRaw))
                        put("readingWithoutTone", VietnameseInputParser.withoutTone(entry.readingRaw))
                        put("telexKey", telex.toTelex(entry.readingRaw))
                    })
                } catch (error: Throwable) {
                    failures[entry.sourceRow] = error.stackTraceToString()
                    Log.e(TAG, "CSV import failed sourceRow=${entry.sourceRow}", error)
                }
            }
            check(failures.isEmpty()) { "CSV import failures sourceRows=${failures.keys}" }
            putMetadata(db, "csv_sha256", sha)
            putMetadata(db, "import_complete", "1")
            putMetadata(db, "row_count", expectedRows.toString())
            db.setTransactionSuccessful()
        } catch (error: Throwable) {
            Log.e(TAG, "CSV transaction failed", error)
            throw error
        } finally {
            db.endTransaction()
        }
    }

    private fun importBundledVerifiedCorpusIfNeeded(db: SQLiteDatabase) {
        val corpusText=context.assets.open(VERIFIED_CORPUS_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() }
        val corpus=VerifiedPhraseCorpusCodec.decode(corpusText)
        val installedRevision=metadataValue(db,VERIFIED_CORPUS_REVISION_METADATA)?.toLongOrNull() ?: -1L
        if(installedRevision>=corpus.revision) return
        var imported=0
        db.beginTransaction()
        try {
            corpus.phrases.forEach { corpusPhrase ->
                val tokens=corpusPhrase.tokens.map { token ->
                    VerifiedNomToken(
                        inputToken=token.inputToken,
                        sourceEntryId=resolveBundledSourceEntryId(db,token.sourceRow,token.readingRaw,token.nomRaw),
                        readingRaw=token.readingRaw,
                        nomRaw=token.nomRaw,
                        exampleRaw=token.exampleRaw,
                        noteRaw=token.noteRaw,
                        sourceRow=token.sourceRow
                    )
                }
                val phrase=VerifiedNomPhrase.create(corpusPhrase.phraseRaw,tokens)
                val values=ContentValues().apply {
                    put("phraseRaw",phrase.phraseRaw);put("phraseNormalized",phrase.phraseNormalized)
                    put("phraseWithoutTone",phrase.phraseWithoutTone);put("phraseNormalizedCompact",phrase.phraseNormalizedCompact)
                    put("phraseWithoutToneCompact",phrase.phraseWithoutToneCompact);put("nomText",phrase.nomText)
                    put("sourceEntryIdsJson",JSONArray(phrase.sourceEntryIds).toString());put("tokensJson",tokensToJson(phrase.tokens))
                    put("createdAt",phrase.createdAt);put("updatedAt",phrase.updatedAt)
                }
                if(db.insertWithOnConflict("verified_nom_phrases",null,values,SQLiteDatabase.CONFLICT_IGNORE)!=-1L) imported++
            }
            putMetadata(db,VERIFIED_CORPUS_REVISION_METADATA,corpus.revision.toString())
            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
        if(imported>0) verifiedRevision.incrementAndGet()
        Log.i(TAG,"verified corpus revision=${corpus.revision} previous=$installedRevision imported=$imported")
    }

    private fun resolveBundledSourceEntryId(db: SQLiteDatabase, sourceRow: Int, readingRaw: String, nomRaw: String): Long {
        return db.rawQuery(
            "SELECT id FROM nom_source_entries WHERE sourceRow=? AND readingRaw=? AND nomRaw=? LIMIT 1",
            arrayOf(sourceRow.toString(),readingRaw,nomRaw)
        ).use { cursor ->
            check(cursor.moveToFirst()) {
                "Bundled verified token no longer matches dictionary sourceRow=$sourceRow readingRaw=$readingRaw nomRaw=$nomRaw"
            }
            cursor.getLong(0)
        }
    }

    fun exactReadingEntryCount(reading: String): Int {
        check(initialized)
        return readableDatabase.rawQuery("SELECT COUNT(*) FROM nom_source_entries WHERE readingRaw=? COLLATE NOCASE", arrayOf(reading)).use { it.moveToFirst(); it.getInt(0) }
    }
    fun corpusFrequency(reading: String): Int {
        check(initialized)
        return corpusFrequencyCache.getOrPut(reading.lowercase()) { index().corpusFrequency(reading) }
    }
    fun sentenceHistoryScore(rawSentence: String, sourceEntryIds: List<Long>): Double {
        check(initialized)
        return (sentenceSelectionCache[selectionKey(rawSentence, sourceEntryIds)] ?: 0) * 5.0
    }

    fun ngramScore(previousSourceEntryId: Long, currentSourceEntryId: Long): Double {
        check(initialized)
        return kotlin.math.ln(1.0 + (ngramCache[previousSourceEntryId to currentSourceEntryId] ?: 0))
    }

    fun recordSelection(rawSentence: String, candidate: NomSentenceCandidate) {
        check(initialized)
        val db = writableDatabase
        db.beginTransaction()
        try {
            val ids = candidate.sourceEntryIds.joinToString(",")
            db.execSQL("INSERT INTO nom_user_selection(rawSentence,restoredSentence,selectedNomText,sourceEntryIds,selectedCount,lastSelectedAt) VALUES(?,?,?,?,1,?) ON CONFLICT(rawSentence,selectedNomText) DO UPDATE SET restoredSentence=excluded.restoredSentence,sourceEntryIds=excluded.sourceEntryIds,selectedCount=selectedCount+1,lastSelectedAt=excluded.lastSelectedAt", arrayOf(rawSentence, candidate.restoredVietnamese, candidate.nomText, ids, System.currentTimeMillis()))
            candidate.sourceEntryIds.zipWithNext().forEach { (previous, current) ->
                db.execSQL("INSERT INTO nom_user_ngram(previousSourceEntryId,currentSourceEntryId,count) VALUES(?,?,1) ON CONFLICT(previousSourceEntryId,currentSourceEntryId) DO UPDATE SET count=count+1", arrayOf(previous, current))
            }
            db.setTransactionSuccessful()
        } finally { db.endTransaction() }
        sentenceSelectionCache.merge(selectionKey(rawSentence, candidate.sourceEntryIds), 1, Int::plus)
        candidate.sourceEntryIds.zipWithNext().forEach { pair -> ngramCache.merge(pair, 1, Int::plus) }
    }

    @Synchronized fun saveVerifiedPhrase(phrase: VerifiedNomPhrase): Long {
        check(initialized)
        val db = writableDatabase
        val existing = db.rawQuery(
            "SELECT id,createdAt FROM verified_nom_phrases WHERE phraseNormalized=? AND nomText=?",
            arrayOf(phrase.phraseNormalized, phrase.nomText)
        ).use { cursor -> if (cursor.moveToFirst()) cursor.getLong(0) to cursor.getLong(1) else null }
        val values = ContentValues().apply {
            put("phraseRaw",phrase.phraseRaw); put("phraseNormalized",phrase.phraseNormalized)
            put("phraseWithoutTone",phrase.phraseWithoutTone); put("phraseNormalizedCompact",phrase.phraseNormalizedCompact)
            put("phraseWithoutToneCompact",phrase.phraseWithoutToneCompact); put("nomText",phrase.nomText)
            put("sourceEntryIdsJson",JSONArray(phrase.sourceEntryIds).toString()); put("tokensJson",tokensToJson(phrase.tokens))
            put("createdAt",existing?.second ?: phrase.createdAt); put("updatedAt",phrase.updatedAt)
        }
        val id = if (existing == null) db.insertOrThrow("verified_nom_phrases",null,values)
        else { db.update("verified_nom_phrases",values,"id=?",arrayOf(existing.first.toString())); existing.first }
        val saved = phrase.copy(id=id,createdAt=existing?.second ?: phrase.createdAt)
        verifiedPhraseCache = (verifiedPhraseCache.filterNot { it.id == id ||
            (it.phraseNormalized == saved.phraseNormalized && it.nomText == saved.nomText) } + saved)
            .sortedWith(compareByDescending<VerifiedNomPhrase> { it.updatedAt }.thenByDescending { it.id })
        localVerifiedRevision = verifiedRevision.incrementAndGet()
        return id
    }

    fun findVerifiedExact(normalized: String, limit: Int): List<VerifiedNomPhrase> {
        check(initialized)
        refreshVerifiedCacheIfNeeded()
        val compact = VerifiedNomPhrase.compact(normalized)
        return verifiedPhraseCache.filter { it.phraseNormalized == normalized || it.phraseNormalizedCompact == compact }.take(limit)
    }

    fun findVerifiedWithoutTone(withoutTone: String, limit: Int): List<VerifiedNomPhrase> {
        check(initialized)
        refreshVerifiedCacheIfNeeded()
        val compact = VerifiedNomPhrase.compact(withoutTone)
        return verifiedPhraseCache.filter { it.phraseWithoutTone == withoutTone || it.phraseWithoutToneCompact == compact }.take(limit)
    }

    fun listVerifiedPhrases(limit: Int): List<VerifiedNomPhrase> {
        check(initialized)
        refreshVerifiedCacheIfNeeded()
        return verifiedPhraseCache.take(limit)
    }

    @Synchronized fun deleteVerifiedPhrase(id: Long): Boolean {
        check(initialized)
        val deleted = writableDatabase.delete("verified_nom_phrases","id=?",arrayOf(id.toString())) > 0
        if (deleted) {
            verifiedPhraseCache = verifiedPhraseCache.filterNot { it.id == id }
            localVerifiedRevision = verifiedRevision.incrementAndGet()
        }
        return deleted
    }

    @Synchronized private fun refreshVerifiedCacheIfNeeded() {
        val revision = verifiedRevision.get()
        if (revision == localVerifiedRevision) return
        verifiedPhraseCache = queryVerified("1=1",emptyArray(),Int.MAX_VALUE)
        localVerifiedRevision = revision
    }

    private fun queryVerified(where: String, arguments: Array<String>, limit: Int): List<VerifiedNomPhrase> {
        val sql = """SELECT id,phraseRaw,phraseNormalized,phraseWithoutTone,phraseNormalizedCompact,
            phraseWithoutToneCompact,nomText,tokensJson,createdAt,updatedAt
            FROM verified_nom_phrases WHERE $where ORDER BY updatedAt DESC,id DESC LIMIT ?""".trimIndent()
        val result = ArrayList<VerifiedNomPhrase>()
        readableDatabase.rawQuery(sql,arguments + limit.toString()).use { cursor ->
            while (cursor.moveToNext()) result += VerifiedNomPhrase(
                id=cursor.getLong(0),phraseRaw=cursor.getString(1),phraseNormalized=cursor.getString(2),
                phraseWithoutTone=cursor.getString(3),phraseNormalizedCompact=cursor.getString(4),
                phraseWithoutToneCompact=cursor.getString(5),nomText=cursor.getString(6),tokens=tokensFromJson(cursor.getString(7)),
                createdAt=cursor.getLong(8),updatedAt=cursor.getLong(9)
            )
        }
        return result
    }

    private fun tokensToJson(tokens: List<VerifiedNomToken>): String = JSONArray().apply {
        tokens.forEach { token -> put(JSONObject().apply {
            put("inputToken",token.inputToken); put("sourceEntryId",token.sourceEntryId); put("readingRaw",token.readingRaw)
            put("nomRaw",token.nomRaw); put("exampleRaw",token.exampleRaw); put("noteRaw",token.noteRaw); put("sourceRow",token.sourceRow)
        }) }
    }.toString()

    private fun tokensFromJson(value: String): List<VerifiedNomToken> {
        val array = JSONArray(value)
        return List(array.length()) { index -> array.getJSONObject(index).let { token ->
            VerifiedNomToken(token.getString("inputToken"),token.getLong("sourceEntryId"),token.getString("readingRaw"),
                token.getString("nomRaw"),token.getString("exampleRaw"),token.getString("noteRaw"),token.getInt("sourceRow"))
        } }
    }

    private fun loadLearningCaches(db: SQLiteDatabase) {
        sentenceSelectionCache.clear()
        ngramCache.clear()
        db.rawQuery("SELECT rawSentence,sourceEntryIds,selectedCount FROM nom_user_selection", null).use { cursor ->
            while (cursor.moveToNext()) sentenceSelectionCache[cursor.getString(0) + KEY_SEPARATOR + cursor.getString(1)] = cursor.getInt(2)
        }
        db.rawQuery("SELECT previousSourceEntryId,currentSourceEntryId,count FROM nom_user_ngram", null).use { cursor ->
            while (cursor.moveToNext()) ngramCache[cursor.getLong(0) to cursor.getLong(1)] = cursor.getInt(2)
        }
    }

    private fun selectionKey(rawSentence: String, sourceEntryIds: List<Long>) =
        rawSentence + KEY_SEPARATOR + sourceEntryIds.joinToString(",")
    private fun counts(db: SQLiteDatabase): Pair<Int, Int> {
        fun count(table: String): Int = db.rawQuery("SELECT COUNT(*) FROM $table", null).use { cursor -> cursor.moveToFirst(); cursor.getInt(0) }
        return count("nom_source_entries") to count("nom_search_index")
    }

    private fun metadataValue(db: SQLiteDatabase, key: String): String? =
        db.rawQuery("SELECT value FROM nom_metadata WHERE key=?", arrayOf(key)).use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }

    private fun putMetadata(db: SQLiteDatabase, key: String, value: String) {
        db.insertWithOnConflict("nom_metadata", null, ContentValues().apply { put("key", key); put("value", value) }, SQLiteDatabase.CONFLICT_REPLACE)
    }

    companion object {
        const val TAG = "NOM_IME"
        const val DATABASE_NAME = "nom.db"
        const val DATABASE_VERSION = 6
        const val CSV_ASSET = "hannom_rcv_standard_nom.csv"
        const val METADATA_ASSET = "hannom_rcv_metadata.json"
        const val VERIFIED_CORPUS_ASSET = "verified_nom_phrases.json"
        private const val VERIFIED_CORPUS_REVISION_METADATA = "verified_corpus_revision"
        private const val KEY_SEPARATOR = "\u0000"
        private val verifiedRevision = java.util.concurrent.atomic.AtomicLong(0)
    }
}
