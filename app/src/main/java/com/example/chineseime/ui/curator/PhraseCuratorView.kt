package com.example.chineseime.ui.curator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
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

    private val phraseInput = EditText(activity).apply {
        hint = "Vietnamese phrase"
        maxLines = 3
        setPadding(dp(14), dp(12), dp(14), dp(12))
    }
    private val status = TextView(activity).apply {
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(8), 0, dp(8))
    }
    private val tokenStrip = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val currentTokenHeader = TextView(activity).apply {
        textSize = 18f
        setTextColor(Color.WHITE)
        setPadding(0, dp(12), 0, dp(6))
    }
    private val candidateBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val summaryBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private val resultText = TextView(activity).apply {
        textSize = 30f
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(10), dp(12), dp(14))
    }
    private val previousButton = Button(activity).apply {
        text = "Previous"
        setOnClickListener { moveToken(-1) }
    }
    private val clearButton = Button(activity).apply {
        text = "Clear token"
        setOnClickListener { clearCurrentSelection() }
    }
    private val nextButton = Button(activity).apply {
        text = "Next"
        setOnClickListener { moveToken(1) }
    }
    private val savedHeader = TextView(activity).apply {
        textSize = 16f
        setTextColor(Color.WHITE)
        setPadding(dp(12), dp(14), dp(12), dp(14))
        background = roundedBackground(PANEL, 14)
        setOnClickListener { toggleSavedSection() }
    }
    private val savedContent = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        visibility = View.GONE
        setPadding(0, dp(8), 0, 0)
    }
    private val savedBox = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }

    private var tokens: List<String> = emptyList()
    private var candidateGroups: List<CandidateGroups> = emptyList()
    private var currentTokenIndex = 0
    private var savedExpanded = false
    private var savedCount = 0
    private val selections = linkedMapOf<Int, NomCandidate>()
    private val exactCollapsedTokens = mutableSetOf<Int>()
    private val toneLessExpandedTokens = mutableSetOf<Int>()
    private val evidenceExpanded = mutableSetOf<Long>()

    fun attach(parent: LinearLayout) {
        parent.addView(sectionTitle("Build a verified phrase"))
        parent.addView(TextView(activity).apply {
            text = "Enter Vietnamese, then verify one token at a time. Exact readings stay separate from broader tone-less matches."
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, 0, 0, dp(12))
        })
        parent.addView(phraseInput, LinearLayout.LayoutParams(-1, -2))
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(8), 0, 0)
            addView(Button(activity).apply {
                text = "Load candidates"
                setOnClickListener { loadCandidates() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(Button(activity).apply {
                text = "Save verified"
                setOnClickListener { savePhrase() }
            }, LinearLayout.LayoutParams(0, -2, 1f))
        })
        parent.addView(status)

        parent.addView(sectionTitle("Tokens"))
        parent.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(tokenStrip)
        }, LinearLayout.LayoutParams(-1, -2))

        parent.addView(currentTokenHeader)
        parent.addView(candidateBox)
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(10), 0, dp(14))
            addView(previousButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(clearButton, LinearLayout.LayoutParams(0, -2, 1f))
            addView(nextButton, LinearLayout.LayoutParams(0, -2, 1f))
        })

        parent.addView(sectionTitle("Selected phrase"))
        parent.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(8), dp(12), dp(6))
            background = roundedBackground(PANEL, 14)
            addView(summaryBox)
            addView(resultText)
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(16)) })

        parent.addView(savedHeader)
        savedContent.addView(Button(activity).apply {
            text = "Copy corpus JSON"
            setOnClickListener { copyCorpusJson() }
        })
        savedContent.addView(savedBox)
        parent.addView(savedContent)

        resetEditorUi()
        updateSavedHeader()
        status.text = "Initializing phrase curator…"
        executor.execute {
            runCatching { database.initialize() }
                .onSuccess {
                    activity.runOnUiThread {
                        status.text = "Phrase curator ready"
                        refreshSaved()
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread { status.text = "Initialization failed: ${error.message}" }
                }
        }
    }

    fun close() {
        executor.shutdownNow()
        database.close()
    }

    private fun loadCandidates() {
        val phrase = phraseInput.text.toString().trim()
        tokens = phrase.split(Regex("\\s+")).filter(String::isNotEmpty)
        candidateGroups = emptyList()
        selections.clear()
        exactCollapsedTokens.clear()
        toneLessExpandedTokens.clear()
        evidenceExpanded.clear()
        currentTokenIndex = 0
        if (tokens.isEmpty()) {
            status.text = "Enter a Vietnamese phrase first"
            resetEditorUi()
            return
        }

        status.text = "Loading ${tokens.size} token(s)…"
        renderTokenChips()
        renderSummary()
        currentTokenHeader.text = "Loading candidates…"
        candidateBox.removeAllViews()
        updateNavigation()

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
                    candidateGroups = groups
                    renderTokenChips()
                    renderCurrentToken()
                    renderSummary()
                    val total = groups.sumOf(CandidateGroups::total)
                    status.text = "${tokens.size} token(s) · $total candidate(s) · 0/${tokens.size} selected"
                }.onFailure { status.text = "Candidate query failed: ${it.message}" }
            }
        }
    }

    private fun renderTokenChips() {
        tokenStrip.removeAllViews()
        if (tokens.isEmpty()) {
            tokenStrip.addView(TextView(activity).apply {
                text = "Load a phrase to begin"
                textSize = 13f
                setTextColor(MUTED)
                setPadding(0, dp(6), 0, dp(6))
            })
            return
        }

        tokens.forEachIndexed { index, token ->
            val selected = selections[index]
            val isCurrent = index == currentTokenIndex
            tokenStrip.addView(TextView(activity).apply {
                text = if (selected == null) token else "$token · ${selected.nomRaw}"
                textSize = 14f
                setTextColor(Color.WHITE)
                gravity = Gravity.CENTER
                setPadding(dp(14), dp(9), dp(14), dp(9))
                background = roundedBackground(
                    color = when {
                        isCurrent -> ACCENT_DARK
                        selected != null -> SUCCESS_DARK
                        else -> PANEL
                    },
                    radiusDp = 18,
                    strokeColor = if (isCurrent) ACCENT else null
                )
                setOnClickListener {
                    currentTokenIndex = index
                    renderTokenChips()
                    renderCurrentToken()
                    updateNavigation()
                }
            }, LinearLayout.LayoutParams(-2, -2).apply { setMargins(0, 0, dp(8), 0) })
        }
    }

    private fun renderCurrentToken() {
        candidateBox.removeAllViews()
        if (tokens.isEmpty() || candidateGroups.size != tokens.size) {
            currentTokenHeader.text = "Candidates"
            updateNavigation()
            return
        }

        val groups = candidateGroups[currentTokenIndex]
        val selected = selections[currentTokenIndex]
        currentTokenHeader.text = buildString {
            append("${currentTokenIndex + 1}/${tokens.size}  ${tokens[currentTokenIndex]}")
            if (selected != null) append("  →  ${selected.nomRaw}")
            append("  ·  ${groups.total} candidates")
        }

        if (groups.exact.isNotEmpty()) {
            renderCandidateSection(
                title = "Exact reading",
                values = groups.exact,
                expanded = currentTokenIndex !in exactCollapsedTokens,
                onToggle = {
                    if (!exactCollapsedTokens.add(currentTokenIndex)) exactCollapsedTokens.remove(currentTokenIndex)
                    renderCurrentToken()
                }
            )
        }
        if (groups.toneLess.isNotEmpty()) {
            renderCandidateSection(
                title = "Same spelling without tone",
                values = groups.toneLess,
                expanded = currentTokenIndex in toneLessExpandedTokens,
                onToggle = {
                    if (!toneLessExpandedTokens.add(currentTokenIndex)) toneLessExpandedTokens.remove(currentTokenIndex)
                    renderCurrentToken()
                }
            )
        }
        if (groups.total == 0) {
            candidateBox.addView(TextView(activity).apply {
                text = "No dictionary candidate for this token"
                setTextColor(Color.RED)
                setPadding(dp(12), dp(18), dp(12), dp(18))
                background = roundedBackground(PANEL, 12)
            })
        }
        updateNavigation()
    }

    private fun renderCandidateSection(
        title: String,
        values: List<NomCandidate>,
        expanded: Boolean,
        onToggle: () -> Unit
    ) {
        candidateBox.addView(TextView(activity).apply {
            text = "${if (expanded) "▾" else "▸"}  $title (${values.size})"
            textSize = 14f
            setTextColor(if (title == "Exact reading") ACCENT else MUTED)
            setPadding(dp(12), dp(12), dp(12), dp(12))
            background = roundedBackground(PANEL, 12)
            setOnClickListener { onToggle() }
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(6)) })

        if (!expanded) return

        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        values.forEach { candidate -> row.addView(candidateCard(currentTokenIndex, candidate)) }
        candidateBox.addView(HorizontalScrollView(activity).apply {
            isHorizontalScrollBarEnabled = false
            addView(row)
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun candidateCard(tokenIndex: Int, candidate: NomCandidate): LinearLayout {
        val isSelected = selections[tokenIndex]?.sourceEntryId == candidate.sourceEntryId
        return LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            minimumWidth = dp(176)
            background = roundedBackground(
                color = if (isSelected) SELECTED_CARD else CARD,
                radiusDp = 14,
                strokeColor = if (isSelected) ACCENT else null
            )
            layoutParams = LinearLayout.LayoutParams(dp(196), -2).apply { setMargins(0, 0, dp(10), 0) }

            if (isSelected) {
                addView(TextView(activity).apply {
                    text = "SELECTED"
                    textSize = 10f
                    setTextColor(ACCENT)
                    gravity = Gravity.CENTER
                })
            }

            addView(TextView(activity).apply {
                text = candidate.nomRaw
                typeface = typefaces.resolve(candidate.nomRaw, candidate.sourceRow).typeface
                textSize = 34f
                includeFontPadding = true
                maxLines = 1
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
                setPadding(0, dp(4), 0, dp(4))
            })
            addView(TextView(activity).apply {
                text = candidate.readingRaw
                textSize = 14f
                gravity = Gravity.CENTER
                setTextColor(Color.WHITE)
            })
            addView(TextView(activity).apply {
                text = "row ${candidate.sourceRow}"
                textSize = 11f
                gravity = Gravity.CENTER
                setTextColor(MUTED)
                setPadding(0, dp(2), 0, dp(6))
            })

            val details = TextView(activity).apply {
                text = "Example\n${candidate.exampleRaw.ifBlank { "—" }}\n\nNote\n${candidate.noteRaw.ifBlank { "—" }}"
                textSize = 12f
                setTextColor(LIGHT_MUTED)
                visibility = if (candidate.sourceEntryId in evidenceExpanded) View.VISIBLE else View.GONE
                setPadding(0, dp(8), 0, dp(4))
            }
            addView(details, LinearLayout.LayoutParams(-1, -2))
            addView(TextView(activity).apply {
                text = if (details.visibility == View.VISIBLE) "Hide evidence" else "View evidence"
                textSize = 12f
                gravity = Gravity.CENTER
                setTextColor(ACCENT)
                setPadding(0, dp(8), 0, dp(4))
                setOnClickListener {
                    val nowExpanded = details.visibility != View.VISIBLE
                    details.visibility = if (nowExpanded) View.VISIBLE else View.GONE
                    text = if (nowExpanded) "Hide evidence" else "View evidence"
                    if (nowExpanded) evidenceExpanded.add(candidate.sourceEntryId)
                    else evidenceExpanded.remove(candidate.sourceEntryId)
                }
            })

            setOnClickListener { selectCandidate(tokenIndex, candidate) }
        }
    }

    private fun selectCandidate(tokenIndex: Int, candidate: NomCandidate) {
        selections[tokenIndex] = candidate
        val selectedCount = selections.size
        status.text = "$selectedCount/${tokens.size} selected · ${candidate.readingRaw} → ${candidate.nomRaw}"
        renderSummary()

        val nextUnselected = ((tokenIndex + 1) until tokens.size).firstOrNull { it !in selections }
            ?: (0 until tokenIndex).firstOrNull { it !in selections }
        if (nextUnselected != null) currentTokenIndex = nextUnselected

        renderTokenChips()
        renderCurrentToken()
    }

    private fun renderSummary() {
        summaryBox.removeAllViews()
        if (tokens.isEmpty()) {
            summaryBox.addView(TextView(activity).apply {
                text = "Nothing selected yet"
                textSize = 13f
                setTextColor(MUTED)
                setPadding(0, dp(8), 0, dp(4))
            })
            resultText.text = "—"
            return
        }

        tokens.forEachIndexed { index, token ->
            val selected = selections[index]
            summaryBox.addView(TextView(activity).apply {
                text = if (selected == null) "$token  →  —" else "$token  →  ${selected.nomRaw}"
                textSize = 14f
                setTextColor(if (selected == null) MUTED else Color.WHITE)
                setPadding(0, dp(4), 0, dp(4))
            })
        }
        resultText.text = tokens.indices.joinToString("") { index -> selections[index]?.nomRaw ?: "·" }
        if (selections.isNotEmpty()) {
            resultText.typeface = typefaces.resolve(resultText.text.toString(), selections.values.first().sourceRow).typeface
        }
    }

    private fun moveToken(delta: Int) {
        if (tokens.isEmpty()) return
        currentTokenIndex = (currentTokenIndex + delta).coerceIn(0, tokens.lastIndex)
        renderTokenChips()
        renderCurrentToken()
    }

    private fun clearCurrentSelection() {
        if (tokens.isEmpty()) return
        selections.remove(currentTokenIndex)
        status.text = "Cleared token ${currentTokenIndex + 1}"
        renderTokenChips()
        renderCurrentToken()
        renderSummary()
    }

    private fun updateNavigation() {
        val hasTokens = tokens.isNotEmpty()
        previousButton.isEnabled = hasTokens && currentTokenIndex > 0
        nextButton.isEnabled = hasTokens && currentTokenIndex < tokens.lastIndex
        clearButton.isEnabled = hasTokens && currentTokenIndex in selections
    }

    private fun resetEditorUi() {
        tokenStrip.removeAllViews()
        tokenStrip.addView(TextView(activity).apply {
            text = "Load a phrase to begin"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(0, dp(6), 0, dp(6))
        })
        currentTokenHeader.text = "Candidates"
        candidateBox.removeAllViews()
        renderSummary()
        updateNavigation()
    }

    private fun savePhrase() {
        if (tokens.isEmpty() || selections.size != tokens.size) {
            status.text = "Select one candidate for every token before saving"
            return
        }
        val phraseRaw = phraseInput.text.toString().trim()
        val verifiedTokens = tokens.mapIndexed { index, inputToken ->
            selections.getValue(index).let { candidate ->
                VerifiedNomToken(
                    inputToken = inputToken,
                    sourceEntryId = candidate.sourceEntryId,
                    readingRaw = candidate.readingRaw,
                    nomRaw = candidate.nomRaw,
                    exampleRaw = candidate.exampleRaw,
                    noteRaw = candidate.noteRaw,
                    sourceRow = candidate.sourceRow
                )
            }
        }
        val phrase = VerifiedNomPhrase.create(phraseRaw, verifiedTokens)
        executor.execute {
            val result = runCatching { repository.saveVerifiedPhrase(phrase) }
            activity.runOnUiThread {
                result.onSuccess {
                    status.text = "Saved verified phrase · ${phrase.phraseRaw} → ${phrase.nomText}"
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

    private fun toggleSavedSection() {
        savedExpanded = !savedExpanded
        savedContent.visibility = if (savedExpanded) View.VISIBLE else View.GONE
        updateSavedHeader()
    }

    private fun updateSavedHeader() {
        savedHeader.text = "${if (savedExpanded) "▾" else "▸"}  Saved verified phrases ($savedCount)"
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
        savedCount = values.size
        updateSavedHeader()
        savedBox.removeAllViews()
        values.forEach { phrase ->
            savedBox.addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(12), dp(10), dp(8), dp(10))
                background = roundedBackground(PANEL, 12)
                addView(TextView(activity).apply {
                    text = "${phrase.phraseRaw}\n${phrase.nomText}"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(Button(activity).apply {
                    text = "Delete"
                    setOnClickListener { deletePhrase(phrase.id) }
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }
        if (values.isEmpty()) {
            savedBox.addView(TextView(activity).apply {
                text = "No verified phrases saved"
                textSize = 13f
                setTextColor(MUTED)
                setPadding(dp(8), dp(12), dp(8), dp(12))
            })
        }
    }

    private fun deletePhrase(id: Long) {
        executor.execute {
            val result = runCatching { repository.deleteVerifiedPhrase(id) }
            activity.runOnUiThread {
                result.onSuccess { deleted ->
                    status.text = if (deleted) "Deleted verified phrase" else "Phrase not found"
                    refreshSaved()
                }.onFailure { status.text = "Delete failed: ${it.message}" }
            }
        }
    }

    private fun sectionTitle(text: String) = TextView(activity).apply {
        this.text = text
        textSize = 15f
        setTextColor(Color.WHITE)
        setPadding(0, dp(12), 0, dp(8))
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = dp(radiusDp).toFloat()
        setColor(color)
        if (strokeColor != null) setStroke(dp(2), strokeColor)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private val PANEL = Color.rgb(36, 39, 44)
        private val CARD = Color.rgb(45, 49, 55)
        private val SELECTED_CARD = Color.rgb(38, 52, 64)
        private val ACCENT = Color.rgb(111, 199, 255)
        private val ACCENT_DARK = Color.rgb(35, 74, 100)
        private val SUCCESS_DARK = Color.rgb(42, 75, 61)
        private val MUTED = Color.rgb(165, 171, 181)
        private val LIGHT_MUTED = Color.rgb(202, 207, 214)
    }
}
