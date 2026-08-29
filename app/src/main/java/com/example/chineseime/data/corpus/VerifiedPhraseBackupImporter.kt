package com.example.chineseime.data.corpus

import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.model.VerifiedNomToken
import com.example.chineseime.data.repository.NomRepository
import com.example.chineseime.engine.VietnameseInputParser

data class VerifiedPhraseImportPlan(
    val revision: Long,
    val totalCount: Int,
    val newCount: Int,
    val duplicateCount: Int,
    internal val validatedPhrases: List<VerifiedNomPhrase>
)

data class VerifiedPhraseImportResult(
    val totalCount: Int,
    val importedCount: Int,
    val skippedCount: Int
)

class VerifiedPhraseBackupImporter(
    private val repository: NomRepository
) {
    fun plan(text: String): VerifiedPhraseImportPlan =
        plan(VerifiedPhraseCorpusCodec.decode(text))

    fun plan(corpus: VerifiedPhraseCorpus): VerifiedPhraseImportPlan {
        val validated = corpus.phrases.map { entry ->
            val phraseTokens = entry.phraseRaw
                .trim()
                .split(Regex("\\s+"))
                .filter(String::isNotEmpty)

            require(phraseTokens.size == entry.tokens.size) {
                "Backup phrase '${entry.phraseRaw}' has ${entry.tokens.size} token record(s) but ${phraseTokens.size} phrase token(s)"
            }

            val tokens = entry.tokens.mapIndexed { index, token ->
                require(token.inputToken == phraseTokens[index]) {
                    "Backup token mismatch in '${entry.phraseRaw}' at position ${index + 1}"
                }

                val normalizedReading = VietnameseInputParser.normalize(token.readingRaw)
                val source = repository.searchExactReading(normalizedReading, Int.MAX_VALUE)
                    .firstOrNull { candidate ->
                        candidate.sourceRow == token.sourceRow &&
                            candidate.readingRaw == token.readingRaw &&
                            candidate.nomRaw == token.nomRaw
                    }

                requireNotNull(source) {
                    "Backup token no longer matches the dictionary: row=${token.sourceRow}, reading=${token.readingRaw}, Nôm=${token.nomRaw}"
                }

                VerifiedNomToken(
                    inputToken = token.inputToken,
                    sourceEntryId = source.sourceEntryId,
                    readingRaw = source.readingRaw,
                    nomRaw = source.nomRaw,
                    exampleRaw = source.exampleRaw,
                    noteRaw = source.noteRaw,
                    sourceRow = source.sourceRow
                )
            }

            VerifiedNomPhrase.create(entry.phraseRaw, tokens)
        }

        val unique = linkedMapOf<String, VerifiedNomPhrase>()
        validated.forEach { phrase -> unique.putIfAbsent(keyOf(phrase), phrase) }

        val existingKeys = repository.listVerifiedPhrases(Int.MAX_VALUE)
            .asSequence()
            .map(::keyOf)
            .toHashSet()
        val newCount = unique.count { (key, _) -> key !in existingKeys }

        return VerifiedPhraseImportPlan(
            revision = corpus.revision,
            totalCount = validated.size,
            newCount = newCount,
            duplicateCount = validated.size - newCount,
            validatedPhrases = unique.values.toList()
        )
    }

    fun apply(plan: VerifiedPhraseImportPlan): VerifiedPhraseImportResult {
        val existingKeys = repository.listVerifiedPhrases(Int.MAX_VALUE)
            .asSequence()
            .map(::keyOf)
            .toMutableSet()
        var imported = 0

        plan.validatedPhrases.forEach { phrase ->
            val key = keyOf(phrase)
            if (key !in existingKeys) {
                repository.saveVerifiedPhrase(phrase)
                existingKeys += key
                imported += 1
            }
        }

        return VerifiedPhraseImportResult(
            totalCount = plan.totalCount,
            importedCount = imported,
            skippedCount = plan.totalCount - imported
        )
    }

    private fun keyOf(phrase: VerifiedNomPhrase): String =
        phrase.phraseNormalized + KEY_SEPARATOR + phrase.nomText

    private companion object {
        const val KEY_SEPARATOR = "\u0000"
    }
}
