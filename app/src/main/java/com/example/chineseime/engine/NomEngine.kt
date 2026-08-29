package com.example.chineseime.engine
import com.example.chineseime.data.model.NomCandidate
fun interface NomEngine { fun query(input: String, limit: Int): List<NomCandidate> }