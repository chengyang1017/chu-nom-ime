package com.example.chineseime.ui.curator

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
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
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.chip.Chip
import com.google.android.material.chip.ChipGroup
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import java.util.concurrent.Executors

class PhraseCuratorView(private val activity: AppCompatActivity) {
    private data class CandidateGroups(
        val exact: List<NomCandidate>,
        val toneLess: List<NomCandidate>
    ) {
        val total: Int get() = exact.size + toneLess.size
    }

    private enum class CandidateMode { EXACT, TONELESS }

    private val executor = Executors.newSingleThreadExecutor()
    private val database = NomDatabase(activity)
    private val repository = SQLiteNomRepository(database)
    private val parser = VietnameseInputParser()
    private val typefaces = NomTypefaceProvider.get(activity)

    private val phraseInput = TextInputEditText(activity).apply {
        textSize = 18f
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        hint = "e.g. tôi yêu em"
        minLines = 1
        maxLines = 3
        inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
        setPadding(dp(2), dp(6), dp(2), dp(6))
    }
    private val phraseField = TextInputLayout(activity).apply {
        hint = "Vietnamese phrase"
        boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
        boxBackgroundColor = SURFACE_HIGH
        boxStrokeColor = ACCENT
        setDefaultHintTextColor(ColorStateList.valueOf(MUTED))
        setBoxCornerRadii(
            dp(16).toFloat(),
            dp(16).toFloat(),
            dp(16).toFloat(),
            dp(16).toFloat()
        )
        addView(phraseInput)
    }
    private val loadButton = primaryButton("Find candidates") { loadCandidates() }.apply {
        isEnabled = false
        alpha = 0.45f
    }
    private val status = TextView(activity).apply {
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(10), 0, 0)
    }

    private val progressLabel = TextView(activity).apply {
        textSize = 13f
        setTextColor(MUTED)
    }
    private val progressBar = LinearProgressIndicator(activity).apply {
        max = 1
        progress = 0
        trackColor = BORDER
        setIndicatorColor(ACCENT)
    }
    private val tokenChipGroup = ChipGroup(activity).apply {
        isSingleLine = true
        chipSpacingHorizontal = dp(8)
    }

    private val workspaceTitle = TextView(activity).apply {
        textSize = 24f
        setTextColor(TEXT)
    }
    private val workspaceMeta = TextView(activity).apply {
        textSize = 12f
        setTextColor(MUTED)
        setPadding(0, dp(2), 0, dp(12))
    }
    private val modeRow = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }
    private val candidateHost = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
    }

    private val previousButton = secondaryButton("Previous") { moveToken(-1) }
    private val clearButton = secondaryButton("Clear") { clearCurrentSelection() }.apply {
        setTextColor(DANGER)
    }
    private val nextButton = secondaryButton("Next") { moveToken(1) }

    private val bottomResult = TextView(activity).apply {
        textSize = 28f
        setTextColor(TEXT)
        maxLines = 1
    }
    private val bottomMeta = TextView(activity).apply {
        textSize = 11f
        setTextColor(MUTED)
        setPadding(0, dp(2), 0, 0)
    }
    private val saveButton = primaryButton("Save") { savePhrase() }.apply {
        isEnabled = false
        alpha = 0.45f
    }

    private var databaseReady = false
    private var tokens: List<String> = emptyList()
    private var candidateGroups: List<CandidateGroups> = emptyList()
    private var currentTokenIndex = 0
    private var currentMode = CandidateMode.EXACT
    private var savedCount = 0
    private val selections = linkedMapOf<Int, NomCandidate>()
    private val visibleCounts = mutableMapOf<Pair<Int, CandidateMode>, Int>()

    fun attach(content: LinearLayout, bottomBar: MaterialCardView) {
        content.addView(TextView(activity).apply {
            text = "Verify a phrase"
            textSize = 30f
            setTextColor(TEXT)
        })
        content.addView(TextView(activity).apply {
            text = "Choose each Nôm character from dictionary evidence. Your edits stay on this device until you export them."
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(18))
        })

        content.addView(buildInputCard())
        content.addView(space(dp(14)))
        content.addView(buildProgressCard())
        content.addView(space(dp(14)))
        content.addView(buildWorkspaceCard())
        content.addView(space(dp(10)))
        content.addView(buildNavigationRow())
        content.addView(space(dp(18)))
        content.addView(buildHintCard())

        attachBottomBar(bottomBar)
        resetEditorUi()
        setStatus("Preparing dictionary…")

        executor.execute {
            runCatching { database.initialize() }
                .onSuccess {
                    databaseReady = true
                    activity.runOnUiThread {
                        loadButton.isEnabled = true
                        loadButton.alpha = 1f
                        setStatus("Ready · local editing mode")
                        refreshSavedCount()
                    }
                }
                .onFailure { error ->
                    activity.runOnUiThread {
                        setStatus("Initialization failed: ${error.message}", DANGER)
                    }
                }
        }
    }

    fun close() {
        executor.shutdownNow()
        database.close()
    }

    fun copyCorpusJson() {
        if (!databaseReady) {
            setStatus("Dictionary is still preparing")
            return
        }
        setStatus("Exporting local phrase library…")
        executor.execute {
            val result = runCatching {
                val phrases = repository.listVerifiedPhrases(Int.MAX_VALUE)
                phrases to VerifiedPhraseCorpusCodec.encode(phrases, System.currentTimeMillis())
            }
            activity.runOnUiThread {
                result.onSuccess { (phrases, json) ->
                    val clipboard = activity.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    clipboard.setPrimaryClip(ClipData.newPlainText("verified_nom_phrases.json", json))
                    setStatus("Copied ${phrases.size} local phrase(s) as JSON", SUCCESS)
                }.onFailure { setStatus("Export failed: ${it.message}", DANGER) }
            }
        }
    }

    fun showSavedPhrases() {
        if (!databaseReady) {
            setStatus("Dictionary is still preparing")
            return
        }

        val listBox = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(6), dp(4), dp(6), dp(8))
        }
        val scroll = ScrollView(activity).apply { addView(listBox) }
        val dialog = MaterialAlertDialogBuilder(activity)
            .setTitle("Verified phrase library")
            .setView(scroll)
            .setNegativeButton("Close", null)
            .create()
        dialog.setOnShowListener { loadSavedIntoDialog(listBox) }
        dialog.show()
    }

    private fun buildInputCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(stepLabel("01  PHRASE"))
            addView(TextView(activity).apply {
                text = "What do you want to verify?"
                textSize = 19f
                setTextColor(TEXT)
                setPadding(0, dp(4), 0, dp(12))
            })
            addView(phraseField, LinearLayout.LayoutParams(-1, -2))
            addView(loadButton, LinearLayout.LayoutParams(-1, dp(52)).apply {
                setMargins(0, dp(12), 0, 0)
            })
            addView(status)
        })
    }

    private fun buildProgressCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(stepLabel("02  REVIEW"))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(activity).apply {
                    text = "Token progress"
                    textSize = 18f
                    setTextColor(TEXT)
                }, LinearLayout.LayoutParams(0, -2, 1f))
                addView(progressLabel)
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, dp(10)) })
            addView(progressBar, LinearLayout.LayoutParams(-1, dp(6)))
            addView(HorizontalScrollView(activity).apply {
                isHorizontalScrollBarEnabled = false
                addView(tokenChipGroup)
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(14), 0, 0) })
        })
    }

    private fun buildWorkspaceCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(stepLabel("03  CANDIDATES"))
            addView(workspaceTitle, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(4), 0, 0) })
            addView(workspaceMeta)
            addView(modeRow)
            addView(candidateHost)
        })
    }

    private fun buildNavigationRow(): LinearLayout = LinearLayout(activity).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        addView(previousButton, LinearLayout.LayoutParams(0, dp(46), 1f))
        addView(clearButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            setMargins(dp(8), 0, dp(8), 0)
        })
        addView(nextButton, LinearLayout.LayoutParams(0, dp(46), 1f))
    }

    private fun buildHintCard(): MaterialCardView = MaterialCardView(activity).apply {
        setCardBackgroundColor(SURFACE_HIGH)
        radius = dp(16).toFloat()
        strokeColor = BORDER
        strokeWidth = dp(1)
        addView(TextView(activity).apply {
            text = "Tip · Exact reading is the safest place to start. Tone-less matches are broader and should be checked against the source example before selection."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(dp(14), dp(13), dp(14), dp(13))
        })
    }

    private fun attachBottomBar(bottomBar: MaterialCardView) {
        bottomBar.removeAllViews()
        bottomBar.addView(LinearLayout(activity).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(12), dp(12), dp(12))
            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                addView(TextView(activity).apply {
                    text = "CURRENT NÔM"
                    textSize = 10f
                    setTextColor(MUTED)
                })
                addView(bottomResult)
                addView(bottomMeta)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(saveButton, LinearLayout.LayoutParams(dp(104), dp(52)))
        })
    }

    private fun loadCandidates() {
        if (!databaseReady) {
            setStatus("Dictionary is still preparing")
            return
        }

        val phrase = phraseInput.text?.toString()?.trim().orEmpty()
        tokens = phrase.split(Regex("\\s+")).filter(String::isNotEmpty)
        candidateGroups = emptyList()
        selections.clear()
        visibleCounts.clear()
        currentTokenIndex = 0
        currentMode = CandidateMode.EXACT

        if (tokens.isEmpty()) {
            setStatus("Enter a Vietnamese phrase first", DANGER)
            resetEditorUi()
            return
        }

        loadButton.isEnabled = false
        loadButton.text = "Loading…"
        setStatus("Searching dictionary for ${tokens.size} token(s)…")
        renderProgress()
        workspaceTitle.text = "Loading candidates"
        workspaceMeta.text = "Please wait"
        modeRow.removeAllViews()
        candidateHost.removeAllViews()
        renderFooter()

        executor.execute {
            val result = runCatching {
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
                loadButton.isEnabled = true
                loadButton.text = "Find candidates"
                result.onSuccess { groups ->
                    candidateGroups = groups
                    chooseBestModeForCurrentToken()
                    renderProgress()
                    renderWorkspace()
                    renderFooter()
                    val total = groups.sumOf(CandidateGroups::total)
                    setStatus("Loaded $total candidate(s) across ${tokens.size} token(s)", SUCCESS)
                }.onFailure {
                    setStatus("Candidate search failed: ${it.message}", DANGER)
                    resetEditorUi()
                }
            }
        }
    }

    private fun renderProgress() {
        tokenChipGroup.removeAllViews()
        if (tokens.isEmpty()) {
            progressBar.max = 1
            progressBar.progress = 0
            progressLabel.text = "Not started"
            tokenChipGroup.addView(Chip(activity).apply {
                text = "Load a phrase to begin"
                isCheckable = false
                setTextColor(MUTED)
                chipBackgroundColor = ColorStateList.valueOf(SURFACE_HIGH)
            })
            return
        }

        progressBar.max = tokens.size
        progressBar.setProgressCompat(selections.size, true)
        progressLabel.text = "${selections.size} / ${tokens.size} verified"

        tokens.forEachIndexed { index, token ->
            val selected = selections[index]
            val current = index == currentTokenIndex
            tokenChipGroup.addView(Chip(activity).apply {
                text = when {
                    selected != null -> "✓ $token · ${selected.nomRaw}"
                    else -> "${index + 1}. $token"
                }
                isCheckable = false
                setTextColor(TEXT)
                chipBackgroundColor = ColorStateList.valueOf(
                    when {
                        current -> ACCENT_DARK
                        selected != null -> SUCCESS_DARK
                        else -> SURFACE_HIGH
                    }
                )
                chipStrokeColor = ColorStateList.valueOf(if (current) ACCENT else BORDER)
                chipStrokeWidth = dp(if (current) 2 else 1).toFloat()
                setOnClickListener { setCurrentToken(index) }
            })
        }
    }

    private fun renderWorkspace() {
        modeRow.removeAllViews()
        candidateHost.removeAllViews()

        if (tokens.isEmpty() || candidateGroups.size != tokens.size) {
            workspaceTitle.text = "Candidates"
            workspaceMeta.text = "Load a phrase to start reviewing"
            renderEmptyState("No phrase loaded")
            updateNavigation()
            return
        }

        val token = tokens[currentTokenIndex]
        val groups = candidateGroups[currentTokenIndex]
        val selected = selections[currentTokenIndex]
        workspaceTitle.text = token
        workspaceMeta.text = buildString {
            append("Token ${currentTokenIndex + 1} of ${tokens.size}")
            append(" · ${groups.total} candidate(s)")
            if (selected != null) append(" · selected ${selected.nomRaw}")
        }

        if (currentMode == CandidateMode.EXACT && groups.exact.isEmpty() && groups.toneLess.isNotEmpty()) {
            currentMode = CandidateMode.TONELESS
        }
        if (currentMode == CandidateMode.TONELESS && groups.toneLess.isEmpty() && groups.exact.isNotEmpty()) {
            currentMode = CandidateMode.EXACT
        }

        val exactButton = modeButton(
            title = "Exact  ${groups.exact.size}",
            selected = currentMode == CandidateMode.EXACT,
            enabled = groups.exact.isNotEmpty()
        ) {
            currentMode = CandidateMode.EXACT
            renderWorkspace()
        }
        val toneLessButton = modeButton(
            title = "Tone-less  ${groups.toneLess.size}",
            selected = currentMode == CandidateMode.TONELESS,
            enabled = groups.toneLess.isNotEmpty()
        ) {
            currentMode = CandidateMode.TONELESS
            renderWorkspace()
        }
        modeRow.addView(exactButton, LinearLayout.LayoutParams(0, dp(44), 1f))
        modeRow.addView(toneLessButton, LinearLayout.LayoutParams(0, dp(44), 1f).apply {
            setMargins(dp(8), 0, 0, 0)
        })

        renderCandidateGrid()
        updateNavigation()
    }

    private fun renderCandidateGrid() {
        candidateHost.removeAllViews()
        if (tokens.isEmpty() || candidateGroups.size != tokens.size) return

        val list = when (currentMode) {
            CandidateMode.EXACT -> candidateGroups[currentTokenIndex].exact
            CandidateMode.TONELESS -> candidateGroups[currentTokenIndex].toneLess
        }
        if (list.isEmpty()) {
            renderEmptyState(
                if (currentMode == CandidateMode.EXACT) "No exact-reading candidates" else "No tone-less candidates"
            )
            return
        }

        val key = currentTokenIndex to currentMode
        val visibleCount = visibleCounts.getOrPut(key) { PAGE_SIZE }.coerceAtMost(list.size)
        val grid = GridLayout(activity).apply {
            columnCount = 2
            alignmentMode = GridLayout.ALIGN_BOUNDS
        }
        val cardWidth = candidateCardWidth()
        list.take(visibleCount).forEachIndexed { index, candidate ->
            grid.addView(candidateCard(candidate), GridLayout.LayoutParams().apply {
                width = cardWidth
                height = -2
                rowSpec = GridLayout.spec(index / 2)
                columnSpec = GridLayout.spec(index % 2)
                setMargins(
                    0,
                    0,
                    if (index % 2 == 0) dp(8) else 0,
                    dp(8)
                )
            })
        }
        candidateHost.addView(grid, LinearLayout.LayoutParams(-1, -2))

        val remaining = list.size - visibleCount
        if (remaining > 0) {
            candidateHost.addView(secondaryButton("Show more · $remaining remaining") {
                visibleCounts[key] = (visibleCount + PAGE_SIZE).coerceAtMost(list.size)
                renderCandidateGrid()
            }, LinearLayout.LayoutParams(-1, dp(46)).apply { setMargins(0, dp(2), 0, 0) })
        }
    }

    private fun candidateCard(candidate: NomCandidate): MaterialCardView {
        val selected = selections[currentTokenIndex]?.sourceEntryId == candidate.sourceEntryId
        return MaterialCardView(activity).apply {
            setCardBackgroundColor(if (selected) SELECTED_CARD else SURFACE_HIGH)
            radius = dp(16).toFloat()
            strokeColor = if (selected) ACCENT else BORDER
            strokeWidth = dp(if (selected) 2 else 1)
            cardElevation = 0f
            isClickable = true
            isFocusable = true

            addView(LinearLayout(activity).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setPadding(dp(10), dp(12), dp(10), dp(10))

                addView(TextView(activity).apply {
                    text = if (selected) "SELECTED" else "SOURCE ${candidate.sourceRow}"
                    textSize = 9f
                    setTextColor(if (selected) ACCENT else MUTED)
                    gravity = Gravity.CENTER
                })
                addView(TextView(activity).apply {
                    text = candidate.nomRaw
                    typeface = typefaces.resolve(candidate.nomRaw, candidate.sourceRow).typeface
                    textSize = 42f
                    setTextColor(TEXT)
                    gravity = Gravity.CENTER
                    includeFontPadding = true
                    setPadding(0, dp(3), 0, dp(2))
                })
                addView(TextView(activity).apply {
                    text = candidate.readingRaw
                    textSize = 14f
                    setTextColor(TEXT)
                    gravity = Gravity.CENTER
                    maxLines = 1
                })
                addView(MaterialButton(activity).apply {
                    text = "Evidence"
                    textSize = 11f
                    setTextColor(ACCENT)
                    backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                    setOnClickListener { showEvidence(candidate) }
                }, LinearLayout.LayoutParams(-1, dp(40)).apply { setMargins(0, dp(5), 0, 0) })
            })

            setOnClickListener { selectCandidate(candidate) }
        }
    }

    private fun showEvidence(candidate: NomCandidate) {
        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(4), dp(22), dp(4))
            addView(TextView(activity).apply {
                text = candidate.nomRaw
                typeface = typefaces.resolve(candidate.nomRaw, candidate.sourceRow).typeface
                textSize = 52f
                gravity = Gravity.CENTER
                setTextColor(TEXT)
                setPadding(0, 0, 0, dp(8))
            })
            addView(evidenceLine("Reading", candidate.readingRaw))
            addView(evidenceLine("Source row", candidate.sourceRow.toString()))
            addView(evidenceBlock("Example", candidate.exampleRaw.ifBlank { "—" }))
            addView(evidenceBlock("Note", candidate.noteRaw.ifBlank { "—" }))
        }
        MaterialAlertDialogBuilder(activity)
            .setTitle("Dictionary evidence")
            .setView(box)
            .setNegativeButton("Close", null)
            .setPositiveButton("Select") { _, _ -> selectCandidate(candidate) }
            .show()
    }

    private fun selectCandidate(candidate: NomCandidate) {
        if (tokens.isEmpty()) return
        selections[currentTokenIndex] = candidate
        setStatus("Selected ${candidate.nomRaw} for ${tokens[currentTokenIndex]}", SUCCESS)

        val selectedIndex = currentTokenIndex
        val nextUnselected = ((selectedIndex + 1) until tokens.size).firstOrNull { it !in selections }
            ?: (0 until selectedIndex).firstOrNull { it !in selections }
        if (nextUnselected != null) {
            currentTokenIndex = nextUnselected
            chooseBestModeForCurrentToken()
        }

        renderProgress()
        renderWorkspace()
        renderFooter()
    }

    private fun setCurrentToken(index: Int) {
        if (index !in tokens.indices) return
        currentTokenIndex = index
        chooseBestModeForCurrentToken()
        renderProgress()
        renderWorkspace()
        renderFooter()
    }

    private fun chooseBestModeForCurrentToken() {
        if (candidateGroups.size != tokens.size || currentTokenIndex !in candidateGroups.indices) return
        currentMode = if (candidateGroups[currentTokenIndex].exact.isNotEmpty()) {
            CandidateMode.EXACT
        } else {
            CandidateMode.TONELESS
        }
    }

    private fun moveToken(delta: Int) {
        if (tokens.isEmpty()) return
        val next = (currentTokenIndex + delta).coerceIn(0, tokens.lastIndex)
        if (next == currentTokenIndex) return
        setCurrentToken(next)
    }

    private fun clearCurrentSelection() {
        if (tokens.isEmpty()) return
        val token = tokens[currentTokenIndex]
        selections.remove(currentTokenIndex)
        setStatus("Cleared selection for $token")
        renderProgress()
        renderWorkspace()
        renderFooter()
    }

    private fun updateNavigation() {
        val active = tokens.isNotEmpty()
        previousButton.isEnabled = active && currentTokenIndex > 0
        nextButton.isEnabled = active && currentTokenIndex < tokens.lastIndex
        clearButton.isEnabled = active && currentTokenIndex in selections
        previousButton.alpha = if (previousButton.isEnabled) 1f else 0.4f
        nextButton.alpha = if (nextButton.isEnabled) 1f else 0.4f
        clearButton.alpha = if (clearButton.isEnabled) 1f else 0.4f
    }

    private fun renderFooter() {
        if (tokens.isEmpty()) {
            bottomResult.text = "—"
            bottomMeta.text = "$savedCount saved locally"
            saveButton.isEnabled = false
            saveButton.alpha = 0.45f
            return
        }

        val result = tokens.indices.joinToString("") { index -> selections[index]?.nomRaw ?: "·" }
        bottomResult.text = result
        selections.values.firstOrNull()?.let { candidate ->
            bottomResult.typeface = typefaces.resolve(result, candidate.sourceRow).typeface
        }
        bottomMeta.text = "${selections.size}/${tokens.size} verified · $savedCount saved locally"
        val ready = selections.size == tokens.size
        saveButton.isEnabled = ready
        saveButton.alpha = if (ready) 1f else 0.45f
    }

    private fun savePhrase() {
        if (!databaseReady) return
        if (tokens.isEmpty() || selections.size != tokens.size) {
            setStatus("Verify every token before saving", DANGER)
            return
        }

        val phraseRaw = phraseInput.text?.toString()?.trim().orEmpty()
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
        saveButton.isEnabled = false
        saveButton.text = "Saving…"
        executor.execute {
            val result = runCatching { repository.saveVerifiedPhrase(phrase) }
            activity.runOnUiThread {
                saveButton.text = "Save"
                result.onSuccess {
                    setStatus("Saved locally · ${phrase.phraseRaw} → ${phrase.nomText}", SUCCESS)
                    refreshSavedCount()
                }.onFailure {
                    saveButton.isEnabled = true
                    saveButton.alpha = 1f
                    setStatus("Save failed: ${it.message}", DANGER)
                }
            }
        }
    }

    private fun refreshSavedCount() {
        if (!databaseReady) return
        executor.execute {
            val result = runCatching { repository.listVerifiedPhrases(Int.MAX_VALUE).size }
            activity.runOnUiThread {
                result.onSuccess {
                    savedCount = it
                    renderFooter()
                }
            }
        }
    }

    private fun loadSavedIntoDialog(container: LinearLayout) {
        container.removeAllViews()
        container.addView(TextView(activity).apply {
            text = "Loading local phrases…"
            textSize = 13f
            setTextColor(MUTED)
            setPadding(dp(8), dp(14), dp(8), dp(14))
        })
        executor.execute {
            val result = runCatching { repository.listVerifiedPhrases(100) }
            activity.runOnUiThread {
                result.onSuccess { renderSavedDialog(it, container) }
                    .onFailure {
                        container.removeAllViews()
                        container.addView(TextView(activity).apply {
                            text = "Could not load phrases: ${it.message}"
                            setTextColor(DANGER)
                        })
                    }
            }
        }
    }

    private fun renderSavedDialog(values: List<VerifiedNomPhrase>, container: LinearLayout) {
        container.removeAllViews()
        container.addView(secondaryButton("Copy all as JSON") { copyCorpusJson() }, LinearLayout.LayoutParams(-1, dp(46)).apply {
            setMargins(0, 0, 0, dp(10))
        })

        if (values.isEmpty()) {
            container.addView(TextView(activity).apply {
                text = "No verified phrases saved on this device yet."
                textSize = 13f
                setTextColor(MUTED)
                setPadding(dp(10), dp(18), dp(10), dp(18))
            })
            return
        }

        values.forEach { phrase ->
            container.addView(MaterialCardView(activity).apply {
                setCardBackgroundColor(SURFACE_HIGH)
                radius = dp(14).toFloat()
                strokeColor = BORDER
                strokeWidth = dp(1)
                addView(LinearLayout(activity).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(14), dp(12), dp(8), dp(12))
                    addView(LinearLayout(activity).apply {
                        orientation = LinearLayout.VERTICAL
                        addView(TextView(activity).apply {
                            text = phrase.phraseRaw
                            textSize = 14f
                            setTextColor(TEXT)
                        })
                        addView(TextView(activity).apply {
                            text = phrase.nomText
                            textSize = 24f
                            setTextColor(TEXT)
                            phrase.tokens.firstOrNull()?.let { token ->
                                typeface = typefaces.resolve(phrase.nomText, token.sourceRow).typeface
                            }
                        })
                        addView(TextView(activity).apply {
                            text = "rows ${phrase.tokens.joinToString { it.sourceRow.toString() }}"
                            textSize = 10f
                            setTextColor(MUTED)
                        })
                    }, LinearLayout.LayoutParams(0, -2, 1f))
                    addView(MaterialButton(activity).apply {
                        text = "Delete"
                        setTextColor(DANGER)
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                        setOnClickListener { deletePhraseFromDialog(phrase.id, container) }
                    }, LinearLayout.LayoutParams(-2, dp(42)))
                })
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
        }
    }

    private fun deletePhraseFromDialog(id: Long, container: LinearLayout) {
        executor.execute {
            val result = runCatching { repository.deleteVerifiedPhrase(id) }
            activity.runOnUiThread {
                result.onSuccess { deleted ->
                    if (deleted) {
                        setStatus("Deleted local verified phrase")
                        refreshSavedCount()
                        loadSavedIntoDialog(container)
                    }
                }.onFailure { setStatus("Delete failed: ${it.message}", DANGER) }
            }
        }
    }

    private fun resetEditorUi() {
        tokens = emptyList()
        candidateGroups = emptyList()
        selections.clear()
        visibleCounts.clear()
        currentTokenIndex = 0
        currentMode = CandidateMode.EXACT
        renderProgress()
        renderWorkspace()
        renderFooter()
    }

    private fun renderEmptyState(message: String) {
        candidateHost.removeAllViews()
        candidateHost.addView(MaterialCardView(activity).apply {
            setCardBackgroundColor(BACKGROUND)
            radius = dp(14).toFloat()
            strokeColor = BORDER
            strokeWidth = dp(1)
            addView(TextView(activity).apply {
                text = message
                textSize = 13f
                setTextColor(MUTED)
                gravity = Gravity.CENTER
                setPadding(dp(16), dp(28), dp(16), dp(28))
            })
        }, LinearLayout.LayoutParams(-1, -2))
    }

    private fun evidenceLine(label: String, value: String) = TextView(activity).apply {
        text = "$label  ·  $value"
        textSize = 13f
        setTextColor(TEXT)
        setPadding(0, dp(5), 0, dp(5))
    }

    private fun evidenceBlock(label: String, value: String) = LinearLayout(activity).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(12), 0, 0)
        addView(TextView(activity).apply {
            text = label.uppercase()
            textSize = 10f
            setTextColor(MUTED)
        })
        addView(TextView(activity).apply {
            text = value
            textSize = 14f
            setTextColor(TEXT)
            setPadding(0, dp(4), 0, 0)
        })
    }

    private fun stepLabel(value: String) = TextView(activity).apply {
        text = value
        textSize = 10f
        setTextColor(ACCENT)
        letterSpacing = 0.08f
    }

    private fun surfaceCard() = MaterialCardView(activity).apply {
        setCardBackgroundColor(SURFACE)
        radius = dp(20).toFloat()
        strokeColor = BORDER
        strokeWidth = dp(1)
        cardElevation = 0f
    }

    private fun primaryButton(label: String, action: () -> Unit) = MaterialButton(activity).apply {
        text = label
        textSize = 14f
        setTextColor(Color.rgb(5, 18, 28))
        backgroundTintList = ColorStateList.valueOf(ACCENT)
        cornerRadius = dp(14)
        setOnClickListener { action() }
    }

    private fun secondaryButton(label: String, action: () -> Unit) = MaterialButton(activity).apply {
        text = label
        textSize = 13f
        setTextColor(TEXT)
        backgroundTintList = ColorStateList.valueOf(SURFACE_HIGH)
        strokeColor = ColorStateList.valueOf(BORDER)
        strokeWidth = dp(1)
        cornerRadius = dp(13)
        setOnClickListener { action() }
    }

    private fun modeButton(
        title: String,
        selected: Boolean,
        enabled: Boolean,
        action: () -> Unit
    ) = MaterialButton(activity).apply {
        text = title
        textSize = 12f
        isEnabled = enabled
        alpha = if (enabled) 1f else 0.35f
        setTextColor(if (selected) TEXT else MUTED)
        backgroundTintList = ColorStateList.valueOf(if (selected) ACCENT_DARK else SURFACE_HIGH)
        strokeColor = ColorStateList.valueOf(if (selected) ACCENT else BORDER)
        strokeWidth = dp(if (selected) 2 else 1)
        cornerRadius = dp(12)
        setOnClickListener { if (enabled) action() }
    }

    private fun setStatus(message: String, color: Int = MUTED) {
        status.text = message
        status.setTextColor(color)
    }

    private fun candidateCardWidth(): Int {
        val available = activity.resources.displayMetrics.widthPixels - dp(18 * 2 + 16 * 2 + 8)
        return (available / 2).coerceAtLeast(dp(136))
    }

    private fun space(height: Int) = View(activity).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun dp(value: Int): Int = (value * activity.resources.displayMetrics.density).toInt()

    companion object {
        private const val PAGE_SIZE = 12
        private val BACKGROUND = Color.rgb(10, 13, 18)
        private val SURFACE = Color.rgb(18, 24, 32)
        private val SURFACE_HIGH = Color.rgb(24, 33, 43)
        private val BORDER = Color.rgb(38, 50, 65)
        private val SELECTED_CARD = Color.rgb(24, 49, 69)
        private val ACCENT = Color.rgb(117, 198, 255)
        private val ACCENT_DARK = Color.rgb(28, 67, 94)
        private val SUCCESS = Color.rgb(113, 214, 162)
        private val SUCCESS_DARK = Color.rgb(31, 70, 54)
        private val DANGER = Color.rgb(255, 126, 138)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(151, 163, 179)
    }
}
