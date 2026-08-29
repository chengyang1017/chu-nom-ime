package com.example.chineseime.data.model

data class NomCandidate(
    val sourceEntryId: Long,
    val sourceRow: Int,
    val readingRaw: String,
    val nomRaw: String,
    val exampleRaw: String,
    val noteRaw: String
)