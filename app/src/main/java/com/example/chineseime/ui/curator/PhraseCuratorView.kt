package com.example.chineseime.ui.curator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chineseime.data.corpus.VerifiedPhraseCorpusCodec
import com.example.chineseime.data.local.NomDatabase
import com.example.chineseime.data.model.NomCandidate
import com.example.chineseime.data.model.VerifiedNomPhrase
import com.example.chineseime.data.model.VerifiedNomToken
import com.example.chineseime.data.repository.SQLiteNomRepository
import com.example.chineseime.engine.VietnameseInputParser
import com.example.chineseime.ui.font.NomTypefaceProvider
import java.util.concurrent.Executors

class PhraseCuratorView(private val activity: AppCompatActivity) {
    private data class CandidateGroups(
        val exact: List<NomCandidate>,
        val toneLess: List<NomCandidate>
    ) {
        val total: Int get() = exact.size + toneLess.size
    }

    private val executor = Executors.newSingleThreadExecutor()
    private val database = NomDatabase(activity)
    private val repository = SQLiteNomRepository(database)
    private val parser = VietnameseInputParser()
    private val typefaces = NomTypefaceProvider.get(activity)
    private val phraseInput = EditText(activity).apply { hint = "Vietnamese phrase"; maxLines = 3 }
    private val candidateBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val savedBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val status = TextView(activity)
    private var tokens: List<String> = emptyList()
    private val selections = linkedMapOf<Int, NomCandidate>()
    private val candidateViews = linkedMapOf<Int, MutableList<View>>()

    fun attach(parent: LinearLayout) {
        parent.addView(phraseInput, LinearLayout.LayoutParams(-1, -2))
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(Button(activity).apply {
                text = "Load token candidates"
                setOnClickListener { loadCandidates() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(activity).apply {
                text = "Save verified phrase"
                setOnClickListener { savePhrase() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        parent.addView(status)
        parent.addView(candidateBox)
        parent.addView(TextView(activity).apply {
            text = "Saved verified phrases"
            textSize = 16f
            setPadding(0, 24, 0, 8)
        })
        parent.addView(Button(activity).apply {
            text = "Copy corpus JSON"
            setOnClickListener { copyCorpusJson() }
        })
        parent.addView(savedBox)
        status.text = "Initializing phrase curator…"
        executor.execute {
            runCatching { database.initialize() }
                .onSuccess { activity.runOnUiThread { status.text = "Phrase curator ready"; refreshSaved() } }
                .onFailure { error -> activity.runOnUiThread { status.text = "Initialization failed: ${error.message}" } }
        }
    }

    fun close() {
        executor.shutdownNow()
        database.close()
    }

    private fun loadCandidates() {
        val phrase = phraseInput.text.toString().trim()
        tokens = phrase.split(Regex("\\s+")).filter(String::isNotEmpty)
        selections.clear()
        candidateViews.clear()
        candidateBox.removeAllViews()
        if (tokens.isEmpty()) {
            status.text = "Enter a Vietnamese phrase first"
            return
        }
        status.text = "Loading ${tokens.size} token(s)…"
        executor.execute {
            val values = runCatching {
                tokens.map { token ->
                    val parsed = parser.parse(token)
                    val exact = repository.searchExactReading(parsed.normalized, Int.MAX_VALUE)
                        .distinctBy(NomCandidate::sourceEntryId)
                    val exactIds = exact.asSequence().map(NomCandidate::sourceEntryId).toHashSet()
                    val toneLess = repository.searchWithoutTone(parsed.withoutTone, Int.MAX_VALUE)
                        .asSequence()
                        .filterNot { it.sourceEntryId in exactIds }
                        .distinctBy(NomCandidate::sourceEntryId)
                        .toList()
                    CandidateGroups(exact = exact, toneLess = toneLess)
                }
            }
            activity.runOnUiThread {
                values.onSuccess { groups ->
                    renderCandidateGroups(groups)
                    val total = groups.sumOf(CandidateGroups::total)
                    status.text = "Loaded $total candidate(s). Exact readings are shown first."
                }.onFailure { status.text = "Candidate query failed: ${it.message}" }
            }
        }
    }

    private fun renderCandidateGroups(groups: List<CandidateGroups>) {
        candidateBox.removeAllViews()
        candidateViews.clear()
        groups.forEachIndexed { tokenIndex, values ->
            candidateViews[tokenIndex] = mutableListOf()
            candidateBox.addView(TextView(activity).apply {
                text = "Token ${tokenIndex + 1}: ${tokens[tokenIndex]}  (${values.total} candidate(s))"
                textSize = 16f
                setPadding(0, 18, 0, 6)
            })

            if (values.exact.isNotEmpty()) {
                renderCandidateSection(
                    tokenIndex = tokenIndex,
                    title = "Exact reading (${values.exact.size})",
                    values = values.exact
                )
            }
            if (values.toneLess.isNotEmpty()) {
                renderCandidateSection(
                    tokenIndex = tokenIndex,
                    title = "Same spelling without tone (${values.toneLess.size})",
                    values = values.toneLess
                )
            }
            if (values.total == 0) {
                candidateBox.addView(TextView(activity).apply {
                    text = "No dictionary candidate"
                    setTextColor(Color.RED)
                })
            }
        }
    }

    private fun renderCandidateSection(tokenIndex: Int, title: String, values: List<NomCandidate>) {
        candidateBox.addView(TextView(activity).apply {
            text = title
            textSize = 13f
            setPadding(0, 6, 0, 4)
            setTextColor(Color.LTGRAY)
        })
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { candidate ->
            val card = candidateCard(tokenIndex, candidate)
            candidateViews.getValue(tokenIndex).add(card)
            row.addView(card)
        }
        candidateBox.addView(
            HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = true
                addView(row)
            },
            LinearLayout.LayoutParams(-1, -2)
        )
    }

    private fun candidateCard(tokenIndex: Int, candidate: NomCandidate) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(18, 12, 18, 12)
        minimumWidth = 280
        setBackgroundColor(Color.rgb(42, 45, 49))
        layoutParams = LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, 8, 0) }
        addView(TextView(activity).apply {
            text = candidate.nomRaw
            typeface = typefaces.resolve(candidate.nomRaw, candidate.sourceRow).typeface
            textSize = 28f
            includeFontPadding = true
            maxLines = 1
            gravity = Gravity.CENTER_HORIZONTAL
            setTextColor(Color.WHITE)
        })
        addView(TextView(activity).apply {
            text = "readingRaw: ${candidate.readingRaw}\nsourceRow: ${candidate.sourceRow}\nexampleRaw: ${candidate.exampleRaw}\nnoteRaw: ${candidate.noteRaw}"
            textSize = 12f
            setTextColor(Color.LTGRAY)
        })
        setOnClickListener {
            selections[tokenIndex] = candidate
            candidateViews[tokenIndex].orEmpty().forEach { view -> view.alpha = 0.62f }
            alpha = 1f
            status.text = "Selected ${selections.size}/${tokens.size} token(s): ${candidate.readingRaw} → ${candidate.nomRaw}"
        }
    }

    private fun savePhrase() {
        if (tokens.isEmpty() || selections.size != tokens.size) {
            status.text = "Select one candidate for every token"
            return
        }
        val phraseRaw = phraseInput.text.toString().trim()
        val verifiedTokens = tokens.mapIndexed { index, inputToken ->
            selections.getValue(index).let { candidate ->
                VerifiedNomToken(
                    inputToken,
                    candidate.sourceEntryId,
                    candidate.readingRaw,
                    candidate.nomRaw,
                    candidate.exampleRaw,
                    candidate.noteRaw,
                    candidate.sourceRow
                )
            }
        }
        val phrase = VerifiedNomPhrase.create(phraseRaw, verifiedTokens)
        executor.execute {
            val result = runCatching { repository.saveVerifiedPhrase(phrase) }
            activity.runOnUiThread {
                result.onSuccess {
                    status.text = "Saved verified phrase id=$it"
                    refreshSaved()
                }.onFailure { status.text = "Save failed: ${it.message}" }
            }
        }
    }

    private fun copyCorpusJson() {
        status.text = "Exporting verified corpus…"
        executor.execute {
            val result = runCatching {
                val phrases = repository.listVerifiedPhrases(Int.MAX_VALUE)
                phrases to VerifiedPhraseCorpusCodec.encode(phrases, System.currentTimeMillis())
            }
            activity.runOnUiThread {
                result.onSuccess { (phrases, json) ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("verified_nom_phrases.json", json))
                    status.text = "Copied ${phrases.size} verified phrase(s) as corpus JSON"
                }.onFailure { status.text = "Export failed: ${it.message}" }
            }
        }
    }

    private fun refreshSaved() {
        executor.execute {
            val result = runCatching { repository.listVerifiedPhrases(100) }
            activity.runOnUiThread {
                result.onSuccess(::renderSaved).onFailure { status.text = "List failed: ${it.message}" }
            }
        }
    }

    private fun renderSaved(values: List<VerifiedNomPhrase>) {
        savedBox.removeAllViews()
        values.forEach { phrase ->
            savedBox.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = "${phrase.phraseRaw}  →  ${phrase.nomText}\nrows=${phrase.tokens.joinToString { it.sourceRow.toString() }}"
                    textSize = 14f
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(activity).apply {
                    text = "Delete"
                    setOnClickListener { deletePhrase(phrase.id) }
                })
            })
        }
        if (values.isEmpty()) savedBox.addView(TextView(activity).apply { text = "No verified phrases saved" })
    }

    private fun deletePhrase(id: Long) {
        executor.execute {
            val result = runCatching { repository.deleteVerifiedPhrase(id) }
            activity.runOnUiThread {
                result.onSuccess { deleted ->
                    status.text = if (deleted) "Deleted id=$id" else "Phrase not found"
                    refreshSaved()
                }.onFailure { status.text = "Delete failed: ${it.message}" }
            }
        }
    }
}
