package com.example.chineseime.data

import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.local.Utf8CsvReader
import com.example.chineseime.engine.TelexComposer
import com.example.chineseime.engine.VietnameseInputParser
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.sql.DriverManager

class NomCsvIntegrityTest {
    private fun asset(name: String): File = listOf(File("src/main/assets/$name"), File("app/src/main/assets/$name")).first { it.exists() }
    private fun entries() = NomCsvLoader.load(asset("hannom_rcv_standard_nom.csv").readText(Charsets.UTF_8))

    @Test fun generatedCsvIsNonEmptyAndReloadable() {
        val first = entries(); val second = entries()
        assertTrue(first.isNotEmpty()); assertEquals(first, second)
    }

    @Test fun commasQuotesAndNewlinesParseCorrectly() {
        val parsed = Utf8CsvReader.read("a,b,c\n1,\"comma, quote \"\" and\nnewline\",3\n")
        assertEquals("comma, quote \" and\nnewline", parsed[1][1])
    }

    @Test fun extendedCodePointsSurrogatesAndVariationSelectorsSurvive() {
        val source = entries()
        val extended = source.first { it.nomRaw.codePoints().anyMatch { cp -> cp > 0xFFFF } }.nomRaw
        val variation = source.first { it.nomRaw.codePoints().anyMatch { cp -> cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF } }.nomRaw
        assertArrayEquals(extended.codePoints().toArray(), extended.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8).codePoints().toArray())
        assertArrayEquals(variation.codePoints().toArray(), variation.toByteArray(Charsets.UTF_8).toString(Charsets.UTF_8).codePoints().toArray())
        assertTrue(extended.length > extended.codePointCount(0, extended.length))
    }

    @Test fun sqliteImportsEverySourceAndCreatesTraceableIndex() {
        Class.forName("org.sqlite.JDBC")
        DriverManager.getConnection("jdbc:sqlite::memory:").use { db ->
            db.createStatement().use { statement ->
                statement.execute("CREATE TABLE source(id INTEGER PRIMARY KEY AUTOINCREMENT, sourceRow INTEGER, readingRaw TEXT, nomRaw TEXT, exampleRaw TEXT, noteRaw TEXT, sourceUrl TEXT)")
                statement.execute("CREATE TABLE search(id INTEGER PRIMARY KEY AUTOINCREMENT, sourceEntryId INTEGER NOT NULL, readingNormalized TEXT, readingWithoutTone TEXT, telexKey TEXT, FOREIGN KEY(sourceEntryId) REFERENCES source(id))")
            }
            val telex = TelexComposer()
            val sourceInsert = db.prepareStatement("INSERT INTO source(sourceRow,readingRaw,nomRaw,exampleRaw,noteRaw,sourceUrl) VALUES(?,?,?,?,?,?)", java.sql.Statement.RETURN_GENERATED_KEYS)
            val indexInsert = db.prepareStatement("INSERT INTO search(sourceEntryId,readingNormalized,readingWithoutTone,telexKey) VALUES(?,?,?,?)")
            entries().forEach { entry ->
                sourceInsert.setInt(1, entry.sourceRow); sourceInsert.setString(2, entry.readingRaw); sourceInsert.setString(3, entry.nomRaw)
                sourceInsert.setString(4, entry.exampleRaw); sourceInsert.setString(5, entry.noteRaw); sourceInsert.setString(6, entry.sourceUrl); sourceInsert.executeUpdate()
                val id = sourceInsert.generatedKeys.use { it.next(); it.getLong(1) }
                indexInsert.setLong(1, id); indexInsert.setString(2, VietnameseInputParser.normalize(entry.readingRaw))
                indexInsert.setString(3, VietnameseInputParser.withoutTone(entry.readingRaw)); indexInsert.setString(4, telex.toTelex(entry.readingRaw)); indexInsert.executeUpdate()
            }
            fun count(sql: String) = db.createStatement().use { st -> st.executeQuery(sql).use { rs -> rs.next(); rs.getInt(1) } }
            val expected = entries().size
            assertEquals(expected, count("SELECT COUNT(*) FROM source"))
            assertEquals(expected, count("SELECT COUNT(*) FROM search"))
            assertEquals(0, count("SELECT COUNT(*) FROM search i LEFT JOIN source s ON s.id=i.sourceEntryId WHERE s.id IS NULL"))
        }
    }
}