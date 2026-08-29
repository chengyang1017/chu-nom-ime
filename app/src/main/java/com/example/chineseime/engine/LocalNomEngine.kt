package com.example.chineseime.engine
import com.example.chineseime.data.repository.NomRepository
class LocalNomEngine(private val repository: NomRepository, private val parser: VietnameseInputParser = VietnameseInputParser()) : NomEngine {
    override fun query(input: String, limit: Int) = parser.parse(input).let { parsed ->
        NomCandidateRanker.rank(repository.search(parsed, limit), parsed).take(limit)
    }
}