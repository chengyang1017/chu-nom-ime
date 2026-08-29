package com.example.chineseime.data.corpus

import com.example.chineseime.data.model.VerifiedNomPhrase
import org.json.JSONArray
import org.json.JSONObject

data class VerifiedPhraseCorpus(
    val schemaVersion: Int,
    val revision: Long,
    val phrases: List<VerifiedPhraseCorpusEntry>
)

data class VerifiedPhraseCorpusEntry(
    val phraseRaw: String,
    val tokens: List<VerifiedPhraseCorpusToken>
)

data class VerifiedPhraseCorpusToken(
    val inputToken: String,
    val readingRaw: String,
    val nomRaw: String,
    val exampleRaw: String,
    val noteRaw: String,
    val sourceRow: Int
)

object VerifiedPhraseCorpusCodec {
    const val SCHEMA_VERSION = 1

    fun decode(text: String): VerifiedPhraseCorpus {
        val root = JSONObject(text)
        val schemaVersion = root.getInt("schemaVersion")
        require(schemaVersion == SCHEMA_VERSION) {
            "Unsupported verified phrase corpus schemaVersion=$schemaVersion"
        }
        val revision = root.getLong("revision")
        require(revision >= 0L) { "Verified phrase corpus revision must be non-negative" }
        val phraseArray = root.getJSONArray("phrases")
        val phrases = List(phraseArray.length()) { phraseIndex ->
            val phraseObject = phraseArray.getJSONObject(phraseIndex)
            val phraseRaw = phraseObject.getString("phraseRaw").trim()
            require(phraseRaw.isNotEmpty()) { "Verified phrase corpus contains a blank phrase" }
            val tokenArray = phraseObject.getJSONArray("tokens")
            require(tokenArray.length() > 0) { "Verified phrase '$phraseRaw' has no tokens" }
            VerifiedPhraseCorpusEntry(
                phraseRaw = phraseRaw,
                tokens = List(tokenArray.length()) { tokenIndex ->
                    val token = tokenArray.getJSONObject(tokenIndex)
                    VerifiedPhraseCorpusToken(
                        inputToken = token.getString("inputToken"),
                        readingRaw = token.getString("readingRaw"),
                        nomRaw = token.getString("nomRaw"),
                        exampleRaw = token.optString("exampleRaw", ""),
                        noteRaw = token.optString("noteRaw", ""),
                        sourceRow = token.getInt("sourceRow")
                    )
                }
            )
        }
        return VerifiedPhraseCorpus(schemaVersion, revision, phrases)
    }

    fun encode(phrases: List<VerifiedNomPhrase>, revision: Long): String {
        require(revision >= 0L) { "Verified phrase corpus revision must be non-negative" }
        val phraseArray = JSONArray()
        phrases.forEach { phrase ->
            val tokenArray = JSONArray()
            phrase.tokens.forEach { token ->
                tokenArray.put(JSONObject().apply {
                    put("inputToken", token.inputToken)
                    put("readingRaw", token.readingRaw)
                    put("nomRaw", token.nomRaw)
                    put("exampleRaw", token.exampleRaw)
                    put("noteRaw", token.noteRaw)
                    put("sourceRow", token.sourceRow)
                })
            }
            phraseArray.put(JSONObject().apply {
                put("phraseRaw", phrase.phraseRaw)
                put("tokens", tokenArray)
            })
        }
        return JSONObject().apply {
            put("schemaVersion", SCHEMA_VERSION)
            put("revision", revision)
            put("phrases", phraseArray)
        }.toString(2)
    }
}
