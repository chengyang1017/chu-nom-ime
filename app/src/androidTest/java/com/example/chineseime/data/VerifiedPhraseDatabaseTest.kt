package com.example.chineseime.data

import android.content.ContentValues
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.local.NomDatabase
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.model.VerifiedNomToken
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class VerifiedPhraseDatabaseTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext

    @Test fun versionFiveMigrationPreservesExistingUserData() {
        val name="verified_migration_${System.nanoTime()}.db"
        try {
            context.openOrCreateDatabase(name,0,null).use { db ->
                db.execSQL("CREATE TABLE nom_user_selection(rawSentence TEXT NOT NULL,restoredSentence TEXT NOT NULL,selectedNomText TEXT NOT NULL,sourceEntryIds TEXT NOT NULL,selectedCount INTEGER NOT NULL,lastSelectedAt INTEGER NOT NULL,PRIMARY KEY(rawSentence,selectedNomText))")
                db.insertOrThrow("nom_user_selection",null,ContentValues().apply { put("rawSentence","sentinel");put("restoredSentence","sentinel");put("selectedNomText","sentinel");put("sourceEntryIds","");put("selectedCount",1);put("lastSelectedAt",1) })
                db.version=5
            }
            NomDatabase(context,name).use { helper ->
                val db=helper.writableDatabase
                val preserved=db.rawQuery("SELECT COUNT(*) FROM nom_user_selection WHERE rawSentence='sentinel'",null).use { it.moveToFirst();it.getInt(0) }
                val verifiedTable=db.rawQuery("SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='verified_nom_phrases'",null).use { it.moveToFirst();it.getInt(0) }
                assertEquals(1,preserved);assertEquals(1,verifiedTable)
            }
        } finally { context.deleteDatabase(name) }
    }

    @Test fun sqliteSaveToneLessContinuousUnicodeAndDeleteRoundTrip() {
        val name="verified_crud_${System.nanoTime()}.db"
        try {
            NomDatabase(context,name).use { database ->
                database.initialize()
                val entries=NomCsvLoader.load(context.assets.open(NomDatabase.CSV_ASSET).bufferedReader(Charsets.UTF_8).use { it.readText() })
                val selected=entries.filter { it.readingRaw.isNotBlank() && !it.readingRaw.contains(Regex("\\s")) }
                    .let { values -> listOf(values.first { it.nomRaw.codePoints().anyMatch { cp -> cp>0xFFFF } },values.last()) }
                val phrase=VerifiedNomPhrase.create(selected.joinToString(" ") { it.readingRaw },selected.map { entry ->
                    VerifiedNomToken(entry.readingRaw,entry.sourceRow.toLong(),entry.readingRaw,entry.nomRaw,entry.exampleRaw,entry.noteRaw,entry.sourceRow)
                })
                val id=database.saveVerifiedPhrase(phrase)
                val found=database.findVerifiedWithoutTone(phrase.phraseWithoutToneCompact,8).single { it.id==id }
                assertEquals(phrase.nomText,found.nomText)
                assertArrayEquals(phrase.nomText.codePoints().toArray(),found.nomText.codePoints().toArray())
                assertTrue(database.deleteVerifiedPhrase(id));assertTrue(database.findVerifiedExact(phrase.phraseNormalized,8).isEmpty())
            }
        } finally { context.deleteDatabase(name) }
    }
}
