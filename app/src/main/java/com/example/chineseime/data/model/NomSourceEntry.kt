package com.example.chineseime.data.model

data class NomSourceEntry(
    val id: Long = 0,
    val sourceRow: Int,
    val readingRaw: String,
    val nomRaw: String,
    val exampleRaw: String,
    val noteRaw: String,
    val sourceUrl: String
)