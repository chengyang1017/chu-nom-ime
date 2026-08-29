package com.example.chineseime.data.local

import com.example.chineseime.data.model.NomSourceEntry

object NomCsvLoader {
    val headers = listOf("source_row", "reading_raw", "nom_raw", "example_raw", "note_raw", "source_url")

    fun load(text: String): List<NomSourceEntry> {
        val rows = Utf8CsvReader.read(text)
        require(rows.isNotEmpty()) { "Nom CSV is empty" }
        require(rows.first() == headers) { "Unexpected Nom CSV header: ${rows.first()}" }
        return rows.drop(1).mapIndexed { index, row ->
            require(row.size == headers.size) { "CSV row ${index + 2} has ${row.size} columns" }
            NomSourceEntry(
                sourceRow = row[0].toInt(), readingRaw = row[1], nomRaw = row[2],
                exampleRaw = row[3], noteRaw = row[4], sourceUrl = row[5]
            )
        }
    }
}