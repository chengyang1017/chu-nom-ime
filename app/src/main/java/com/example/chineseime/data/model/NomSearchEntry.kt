package com.example.chineseime.data.model

data class NomSearchEntry(
    val id: Long = 0,
    val sourceEntryId: Long,
    val readingNormalized: String,
    val readingWithoutTone: String,
    val telexKey: String
)