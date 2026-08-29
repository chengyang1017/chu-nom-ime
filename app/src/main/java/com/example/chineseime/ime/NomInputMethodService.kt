package com.example.chineseime.ime

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.TextView
import com.example.chineseime.data.local.NomDatabase
import com.example.chineseime.data.repository.SQLiteNomRepository
import com.example.chineseime.engine.sentence.LatestQueryCoordinator
import com.example.chineseime.engine.sentence.SentenceNomEngine
import com.example.chineseime.ui.font.NomTypefaceProvider
import java.util.concurrent.Executors

class NomInputMethodService : InputMethodService(), KeyboardController.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor { task ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            task.run()
        }, "NomImeEngine")
    }
    private lateinit var database: NomDatabase
    private lateinit var engine: SentenceNomEngine
    private lateinit var t9Predictor: T9Predictor
    private lateinit var state: SentenceCompositionState
    private lateinit var keyboard: KeyboardController
    private lateinit var input: InputConnectionController
    private lateinit var root: LinearLayout
    private lateinit var composition: TextView
    private lateinit var t9Scroller: HorizontalScrollView
    private lateinit var t9Candidates: LinearLayout
    private lateinit var t9Strip: T9PredictionStrip
    private lateinit var candidateScroller: HorizontalScrollView
    private lateinit var candidates: LinearLayout
    private lateinit var candidateStrip: ImeCandidateStrip
    private lateinit var typefaceProvider: NomTypefaceProvider
    private var nomMode = true
    private var directInputMode = false
    private var t9Digits = ""
    private var t9Predictions: List<String> = emptyList()
    private var selectedT9PredictionIndex = -1
    @Volatile private var databaseReady = false
    private var sourceRows = 0
    private var searchRows = 0
    private var lastQuery = ""
    private var lastError = ""
    private var pendingQuery: Runnable? = null
    private val queryCoordinator = LatestQueryCoordinator()

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "NomInputMethodService.onCreate")
        database = NomDatabase(this)
        val repository = SQLiteNomRepository(database)
        engine = SentenceNomEngine(repository)
        t9Predictor = T9Predictor(repository)
        state = SentenceCompositionState()
        input = InputConnectionController { currentInputConnection }
        keyboard = KeyboardController(this, this)
        typefaceProvider = NomTypefaceProvider.get(this)
        initializeDatabase()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        if (::database.isInitialized) database.close()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        Log.i(TAG, "NomInputMethodService.onCreateInputView")
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
            setPadding(0, dp(4), 0, 0)
        }

        composition = TextView(this).apply {
            setTextColor(ACCENT)
            textSize = 14f
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(12), 0, dp(12), 0)
            maxLines = 1
            background = roundedBackground(SURFACE, dp(14), BORDER)
            visibility = View.GONE
        }
        root.addView(composition, LinearLayout.LayoutParams(-1, dp(34)).apply {
            setMargins(dp(8), dp(2), dp(8), dp(4))
        })

        t9Candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = dp(42)
        }
        t9Strip = T9PredictionStrip(
            context = this,
            host = t9Candidates,
            onSelect = ::selectT9Prediction
        )
        t9Scroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(t9Candidates)
            visibility = View.GONE
        }
        root.addView(t9Scroller, LinearLayout.LayoutParams(-1, dp(44)))

        candidates = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(8), 0, dp(8), 0)
            minimumHeight = dp(70)
        }
        candidateStrip = ImeCandidateStrip(
            context = this,
            host = candidates,
            typeface = typefaceProvider.resolve("碎", 0).typeface,
            onSelect = ::select
        )
        candidateScroller = HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
            addView(candidates)
            visibility = View.GONE
        }
        root.addView(candidateScroller, LinearLayout.LayoutParams(-1, dp(74)))
        root.addView(keyboard.build())

        updateUi()
        return root
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info, restarting)
        state.reset()
        resetT9Prediction()
        input.finishComposing()
        cancelPendingQueries()
        val inputClass = info?.inputType?.and(InputType.TYPE_MASK_CLASS)
        val mode = if (
            inputClass in setOf(
                InputType.TYPE_CLASS_NUMBER,
                InputType.TYPE_CLASS_PHONE,
                InputType.TYPE_CLASS_DATETIME
            )
        ) KeyboardMode.NUMBERS else KeyboardMode.LETTERS
        val variation = info?.inputType?.and(InputType.TYPE_MASK_VARIATION) ?: 0
        val protected = variation in setOf(
            InputType.TYPE_TEXT_VARIATION_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,
            InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,
            InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS
        )
        directInputMode = protected || mode != KeyboardMode.LETTERS
        nomMode = if (directInputMode) {
            false
        } else {
            getSharedPreferences(PREFS, MODE_PRIVATE).getBoolean(PREF_NOM_MODE, true)
        }
        keyboard.configure(
            mode = mode,
            isNomMode = nomMode,
            imeOptions = info?.imeOptions ?: EditorInfo.IME_ACTION_NONE,
            allowNineKey = !directInputMode
        )
        if (::composition.isInitialized) updateUi()
        Log.i(TAG, "onStartInput mode=$mode nomMode=$nomMode directInput=$directInputMode")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        keyboard.showMode(keyboard.currentMode)
        keyboard.setNomMode(nomMode)
        updateUi()
    }

    override fun onFinishInput() {
        state.reset()
        resetT9Prediction()
        cancelPendingQueries()
        input.finishComposing()
        super.onFinishInput()
    }

    override fun onLetter(value: Char) {
        resetT9Prediction()
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "key=$value mode=${keyboard.currentMode} nomMode=$nomMode rawLength=${state.rawSentence.length}")
        }
        if (directInputMode) {
            input.commit(value.toString())
            return
        }
        state.append(value.toString())
        showComposedImmediately()
        if (nomMode) enqueueSentenceQuery(keyPressedAt)
    }

    override fun onNineKeyDigit(value: Char) {
        if (directInputMode || value !in '2'..'9') return
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        t9Digits += value
        applyT9Prediction(keyPressedAt)
    }

    override fun onReplaceLastLetter(value: Char) {
        resetT9Prediction()
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        if (directInputMode) {
            input.delete()
            input.commit(value.toString())
            return
        }
        if (state.rawSentence.isEmpty()) {
            onLetter(value)
            return
        }
        state.replaceLastCodePoint(value.toString())
        showComposedImmediately()
        if (nomMode) enqueueSentenceQuery(keyPressedAt)
    }

    override fun onReplaceCommittedSymbol(value: String) {
        input.delete()
        input.commit(value)
    }

    override fun onSpace() {
        resetT9Prediction()
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "key=SPACE nomMode=$nomMode rawLength=${state.rawSentence.length}")
        }
        if (directInputMode) {
            input.commit(" ")
            return
        }
        if (!nomMode) {
            state.appendSpace()
            showComposedImmediately()
            return
        }
        val chooseOnSpace = getSharedPreferences(PREFS, MODE_PRIVATE)
            .getBoolean(PREF_SPACE_SELECT, false)
        if (chooseOnSpace && state.sentenceCandidates.isNotEmpty()) {
            select(0)
        } else {
            state.appendSpace()
            showComposedImmediately()
            enqueueSentenceQuery(keyPressedAt)
        }
    }

    override fun onDelete() {
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "key=DELETE nomMode=$nomMode rawLength=${state.rawSentence.length} t9Length=${t9Digits.length}")
        }

        if (keyboard.currentMode == KeyboardMode.NINE_KEY && t9Digits.isNotEmpty()) {
            t9Digits = t9Digits.dropLast(1)
            if (t9Digits.isNotEmpty()) {
                applyT9Prediction(keyPressedAt)
            } else {
                t9Predictions = emptyList()
                selectedT9PredictionIndex = -1
                state.replaceCurrentToken("")
                if (state.rawSentence.isEmpty()) {
                    input.finishComposing()
                    updateUi()
                } else {
                    showComposedImmediately()
                }
                if (nomMode && state.rawSentence.isNotBlank()) {
                    enqueueSentenceQuery(keyPressedAt)
                } else {
                    cancelPendingQueries()
                }
            }
            return
        }

        if (state.rawSentence.isEmpty()) {
            input.delete()
            return
        }
        state.deleteCodePoint()
        if (state.rawSentence.isEmpty()) input.finishComposing() else showComposedImmediately()
        updateUi()
        if (nomMode) enqueueSentenceQuery(keyPressedAt) else cancelPendingQueries()
    }

    override fun onSymbol(value: String) {
        resetT9Prediction()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "key=$value nomMode=$nomMode rawLength=${state.rawSentence.length}")
        }
        if (!directInputMode && state.rawSentence.isNotBlank()) {
            commitCurrentComposition()
        }
        input.commit(value)
    }

    override fun onEnter() {
        resetT9Prediction()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(TAG, "key=ENTER nomMode=$nomMode rawLength=${state.rawSentence.length}")
        }
        commitCurrentComposition()
        input.enter(keyboard.enterAction)
    }

    override fun onLanguage() {
        if (directInputMode) return
        resetT9Prediction()
        commitCurrentComposition()
        nomMode = !nomMode
        getSharedPreferences(PREFS, MODE_PRIVATE)
            .edit()
            .putBoolean(PREF_NOM_MODE, nomMode)
            .apply()
        keyboard.setNomMode(nomMode)
        updateUi()
        Log.i(TAG, "script mode changed to ${if (nomMode) "NOM" else "QUOC_NGU"}")
    }

    override fun onMode(mode: KeyboardMode) {
        resetT9Prediction()
        keyboard.showMode(mode)
        updateUi()
    }

    override fun onShift() {
        keyboard.toggleShift()
    }

    private fun applyT9Prediction(keyPressedAt: Long = SystemClock.elapsedRealtimeNanos()) {
        if (t9Digits.isEmpty()) return
        t9Predictions = if (databaseReady) {
            t9Predictor.predict(t9Digits, T9_PREDICTION_LIMIT)
        } else {
            emptyList()
        }
        selectedT9PredictionIndex = if (t9Predictions.isEmpty()) -1 else 0
        val prediction = t9Predictions.firstOrNull()
        state.replaceCurrentToken(prediction ?: t9Digits)
        showComposedImmediately()
        if (nomMode && databaseReady && prediction != null) {
            enqueueSentenceQuery(keyPressedAt)
        } else {
            cancelPendingQueries()
        }
    }

    private fun selectT9Prediction(index: Int) {
        if (keyboard.currentMode != KeyboardMode.NINE_KEY || t9Digits.isEmpty()) return
        val prediction = t9Predictions.getOrNull(index) ?: return
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        selectedT9PredictionIndex = index
        state.replaceCurrentToken(prediction)
        showComposedImmediately()
        if (nomMode && databaseReady) {
            enqueueSentenceQuery(keyPressedAt)
        } else {
            cancelPendingQueries()
        }
    }

    private fun resetT9Prediction() {
        t9Digits = ""
        t9Predictions = emptyList()
        selectedT9PredictionIndex = -1
    }

    private fun enqueueSentenceQuery(keyPressedAt: Long = SystemClock.elapsedRealtimeNanos()) {
        if (!nomMode || directInputMode) return
        val snapshot = state.rawSentence
        val generation = state.queryGeneration
        queryCoordinator.activate(generation)
        val scheduledAt = SystemClock.elapsedRealtimeNanos()
        lastQuery = snapshot
        pendingQuery?.let { mainHandler.removeCallbacks(it) }
        if (snapshot.isBlank() || !databaseReady) return
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "query scheduled generation=$generation keyToScheduleMs=${millis(scheduledAt - keyPressedAt)} rawLength=${snapshot.codePointCount(0, snapshot.length)}"
            )
        }
        val task = Runnable {
            val debounceFinishedAt = SystemClock.elapsedRealtimeNanos()
            executor.execute {
                if (queryCoordinator.isStale(generation)) {
                    if (Log.isLoggable(TAG, Log.DEBUG)) {
                        Log.d(
                            TAG,
                            "query coalesced before start generation=$generation latest=${queryCoordinator.latestGeneration()}"
                        )
                    }
                    return@execute
                }
                val backgroundStarted = SystemClock.elapsedRealtimeNanos()
                val context = queryCoordinator.context(generation)
                try {
                    val result = engine.query(snapshot, MAX_CANDIDATES, context)
                    if (context.isCancelled()) {
                        if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(
                                TAG,
                                "query cancelled during engine generation=$generation engineMs=${millis(context.metrics.totalEngineNanos)}"
                            )
                        }
                        return@execute
                    }
                    val backgroundFinished = SystemClock.elapsedRealtimeNanos()
                    mainHandler.post {
                        val applyStarted = SystemClock.elapsedRealtimeNanos()
                        if (nomMode && !directInputMode && state.applyCandidates(generation, result)) {
                            lastError = ""
                            input.setComposing(state.displaySentence)
                            updateUi()
                            val parsed = com.example.chineseime.engine.sentence.IncrementalSentenceInput.parse(snapshot)
                            if (Log.isLoggable(TAG, Log.DEBUG)) {
                                Log.d(
                                    TAG,
                                    "query timing generation=$generation debounceMs=${millis(debounceFinishedAt - scheduledAt)} queueMs=${millis(backgroundStarted - debounceFinishedAt)} segmentMs=${context.metrics.milliseconds(context.metrics.segmentationNanos)} databaseMs=${context.metrics.milliseconds(context.metrics.dictionaryLookupNanos)} beamMs=${context.metrics.milliseconds(context.metrics.beamGenerationNanos)} rankingMs=${context.metrics.milliseconds(context.metrics.candidateRankingNanos)} engineMs=${context.metrics.milliseconds(context.metrics.totalEngineNanos)} backgroundToMainMs=${millis(applyStarted - backgroundFinished)} applyMs=${millis(SystemClock.elapsedRealtimeNanos() - applyStarted)} keyToCandidateMs=${millis(SystemClock.elapsedRealtimeNanos() - keyPressedAt)} lookups=${context.metrics.dictionaryLookupCount} sqliteCalls=0 fastPath=${context.metrics.fastPath} rawLength=${snapshot.length} candidateCount=${result.size} endsWithSpace=${parsed.endsWithSpace} barVisible=${candidateScroller.visibility == View.VISIBLE}"
                                )
                            }
                        } else if (Log.isLoggable(TAG, Log.DEBUG)) {
                            Log.d(
                                TAG,
                                "stale or inactive sentence query ignored generation=$generation current=${state.queryGeneration} nomMode=$nomMode"
                            )
                        }
                    }
                } catch (error: Throwable) {
                    Log.e(TAG, "sentence query failed rawSentence=$snapshot", error)
                    mainHandler.post {
                        if (generation == state.queryGeneration && nomMode && !directInputMode) {
                            lastError = error.stackTraceToString()
                            updateUi()
                        }
                    }
                }
            }
        }
        pendingQuery = task
        mainHandler.postDelayed(task, QUERY_DEBOUNCE_MS)
    }

    private fun select(index: Int) {
        if (!nomMode || directInputMode) return
        resetT9Prediction()
        val raw = state.rawSentence.trim()
        val candidate = state.choose(index) ?: return
        Log.i(
            TAG,
            "sentence selected index=$index rawSentence=$raw restored=${candidate.restoredVietnamese} nomText=${candidate.nomText} sourceEntryIds=${candidate.sourceEntryIds}"
        )
        input.setComposing(candidate.nomText)
        input.finishComposing()
        executor.execute {
            try {
                engine.learn(raw, candidate)
            } catch (error: Throwable) {
                Log.e(TAG, "sentence learning failed", error)
            }
        }
        state.reset()
        cancelPendingQueries()
        updateUi()
    }

    private fun commitCurrentComposition() {
        resetT9Prediction()
        if (state.rawSentence.isBlank()) return

        if (!nomMode || directInputMode) {
            input.setComposing(state.displaySentence.ifBlank { state.rawSentence })
            input.finishComposing()
            state.reset()
            cancelPendingQueries()
            updateUi()
            return
        }

        val candidate = state.sentenceCandidates.firstOrNull()
        if (candidate != null) {
            val raw = state.rawSentence.trim()
            input.setComposing(candidate.nomText)
            input.finishComposing()
            executor.execute {
                try {
                    engine.learn(raw, candidate)
                } catch (error: Throwable) {
                    Log.e(TAG, "fallback learning failed", error)
                }
            }
        } else {
            input.setComposing(state.displaySentence.ifBlank { state.rawSentence.trim() })
            input.finishComposing()
        }
        state.reset()
        cancelPendingQueries()
        updateUi()
    }

    private fun showComposedImmediately() {
        input.setComposing(state.displaySentence)
        updateUi()
        if (Log.isLoggable(TAG, Log.DEBUG)) {
            Log.d(
                TAG,
                "setComposingText rawLength=${state.rawSentence.length} displayLength=${state.displaySentence.length} restoredLength=${state.restoredSentence.length} nomMode=$nomMode"
            )
        }
    }

    private fun initializeDatabase() {
        executor.execute {
            try {
                val status = database.initialize()
                sourceRows = status.sourceRows
                searchRows = status.searchRows
                databaseReady = true
                mainHandler.post {
                    Log.i(
                        TAG,
                        "device database initialized sourceRows=$sourceRows searchRows=$searchRows"
                    )
                    updateUi()
                    when {
                        keyboard.currentMode == KeyboardMode.NINE_KEY && t9Digits.isNotEmpty() -> applyT9Prediction()
                        nomMode && !directInputMode && state.rawSentence.isNotBlank() -> enqueueSentenceQuery()
                    }
                }
            } catch (error: Throwable) {
                Log.e(TAG, "database initialization failed", error)
                mainHandler.post {
                    lastError = error.stackTraceToString()
                    updateUi()
                }
            }
        }
    }

    private fun updateUi() {
        if (!::composition.isInitialized) return
        val display = state.displaySentence
        if (composition.text.toString() != display) composition.text = display
        composition.visibility = View.GONE

        val t9SurfaceActive =
            keyboard.currentMode == KeyboardMode.NINE_KEY &&
                t9Digits.isNotEmpty() &&
                t9Predictions.isNotEmpty()
        t9Scroller.visibility = if (t9SurfaceActive) View.VISIBLE else View.GONE
        if (t9SurfaceActive) {
            t9Strip.render(t9Predictions, selectedT9PredictionIndex)
        } else {
            t9Strip.clear()
        }

        val candidateSurfaceActive = nomMode && !directInputMode && state.rawSentence.isNotBlank()
        candidateScroller.visibility = if (candidateSurfaceActive) View.VISIBLE else View.GONE

        when {
            !candidateSurfaceActive -> candidateStrip.clear()
            state.sentenceCandidates.isNotEmpty() -> candidateStrip.render(state.sentenceCandidates)
            else -> Unit
        }
    }

    private fun roundedBackground(color: Int, radius: Int, stroke: Int): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius.toFloat()
            setColor(color)
            setStroke(dp(1), stroke)
        }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun millis(nanos: Long) = nanos / 1_000_000.0

    private fun cancelPendingQueries() {
        queryCoordinator.activate(state.queryGeneration)
        pendingQuery?.let(mainHandler::removeCallbacks)
        pendingQuery = null
    }

    companion object {
        const val TAG = "NOM_IME"
        const val PREFS = "nom_settings"
        const val PREF_SPACE_SELECT = "space_select_first"
        const val PREF_NOM_MODE = "nom_mode_enabled"
        const val QUERY_DEBOUNCE_MS = 20L
        const val MAX_CANDIDATES = 8
        const val T9_PREDICTION_LIMIT = 8
        val PUNCTUATION = setOf(",", ".", "?", "!")

        private val BACKGROUND = Color.rgb(10, 13, 18)
        private val SURFACE = Color.rgb(18, 24, 32)
        private val BORDER = Color.rgb(38, 50, 65)
        private val ACCENT = Color.rgb(111, 199, 255)
    }
}
