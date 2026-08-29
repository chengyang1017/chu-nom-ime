package com.example.chineseime.ime

import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.inputmethodservice.InputMethodService
import android.os.Handler
import android.os.Looper
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
import com.example.chineseime.data.model.NomSentenceCandidate
import com.example.chineseime.data.repository.SQLiteNomRepository
import com.example.chineseime.engine.sentence.SentenceNomEngine
import com.example.chineseime.engine.sentence.LatestQueryCoordinator
import com.example.chineseime.ui.font.NomTypefaceProvider
import java.util.concurrent.Executors

class NomInputMethodService : InputMethodService(), KeyboardController.Listener {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var database: NomDatabase
    private lateinit var engine: SentenceNomEngine
    private lateinit var state: SentenceCompositionState
    private lateinit var keyboard: KeyboardController
    private lateinit var input: InputConnectionController
    private lateinit var root: LinearLayout
    private lateinit var composition: TextView
    private lateinit var candidates: LinearLayout
    private lateinit var diagnostics: TextView
    private lateinit var typefaceProvider: NomTypefaceProvider
    private var nomMode = true
    @Volatile private var databaseReady = false
    private var sourceRows = 0
    private var searchRows = 0
    private var lastQuery = ""
    private var lastError = ""
    private var pendingQuery: Runnable? = null
    private val queryCoordinator = LatestQueryCoordinator()

    override fun onCreate() {
        super.onCreate(); Log.i(TAG, "NomInputMethodService.onCreate")
        database = NomDatabase(this)
        val repository = SQLiteNomRepository(database)
        engine = SentenceNomEngine(repository)
        state = SentenceCompositionState()
        input = InputConnectionController { currentInputConnection }
        keyboard = KeyboardController(this, this)
        typefaceProvider = NomTypefaceProvider.get(this)
        initializeDatabase()
    }

    override fun onDestroy() { executor.shutdownNow(); if (::database.isInitialized) database.close(); super.onDestroy() }

    override fun onCreateInputView(): View {
        Log.i(TAG, "NomInputMethodService.onCreateInputView")
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(Color.rgb(23,25,28)) }
        composition = TextView(this).apply {
            setTextColor(Color.rgb(187,134,252)); textSize=16f; gravity=Gravity.CENTER_VERTICAL
            setPadding(dp(10),0,dp(10),0); maxLines=1
            layoutParams=LinearLayout.LayoutParams(-1,dp(30))
        }
        candidates = LinearLayout(this).apply { orientation=LinearLayout.HORIZONTAL; gravity=Gravity.CENTER_VERTICAL; setPadding(dp(5),0,dp(5),0); minimumHeight=dp(72) }
        root.addView(composition)
        root.addView(HorizontalScrollView(this).apply {
            isHorizontalScrollBarEnabled=false; addView(candidates)
            layoutParams=LinearLayout.LayoutParams(-1,dp(76))
        })
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            diagnostics=TextView(this).apply { setTextColor(Color.rgb(184,188,194)); textSize=10f; maxLines=8; setPadding(dp(6),dp(2),dp(6),dp(2)) }
            root.addView(diagnostics,LinearLayout.LayoutParams(-1,-2))
        }
        root.addView(keyboard.build()); updateUi(); return root
    }

    override fun onStartInput(info: EditorInfo?, restarting: Boolean) {
        super.onStartInput(info,restarting); state.reset(); input.finishComposing()
        cancelPendingQueries()
        val inputClass=info?.inputType?.and(InputType.TYPE_MASK_CLASS)
        val mode=if(inputClass in setOf(InputType.TYPE_CLASS_NUMBER,InputType.TYPE_CLASS_PHONE,InputType.TYPE_CLASS_DATETIME)) KeyboardMode.NUMBERS else KeyboardMode.LETTERS
        val variation=info?.inputType?.and(InputType.TYPE_MASK_VARIATION)?:0
        val protected=variation in setOf(InputType.TYPE_TEXT_VARIATION_PASSWORD,InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD,InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD,InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS,InputType.TYPE_TEXT_VARIATION_WEB_EMAIL_ADDRESS)
        nomMode=!protected && mode==KeyboardMode.LETTERS
        keyboard.configure(mode,nomMode,info?.imeOptions?:EditorInfo.IME_ACTION_NONE)
        if(::composition.isInitialized) updateUi()
        Log.i(TAG,"onStartInput mode=$mode nomMode=$nomMode")
    }

    override fun onStartInputView(info: EditorInfo?, restarting: Boolean) { super.onStartInputView(info,restarting); keyboard.showMode(keyboard.currentMode); keyboard.setNomMode(nomMode); updateUi() }
    override fun onFinishInput() { state.reset(); cancelPendingQueries(); input.finishComposing(); super.onFinishInput() }

    override fun onLetter(value: Char) {
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        Log.d(TAG,"key=$value mode=${keyboard.currentMode} rawSentence=${state.rawSentence}")
        if(!nomMode){ input.commit(value.toString()); return }
        state.append(value.toString()); showComposedImmediately(); enqueueSentenceQuery(keyPressedAt)
    }

    override fun onSpace() {
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        Log.d(TAG,"key=SPACE rawSentence=${state.rawSentence}")
        if(!nomMode){ input.commit(" "); return }
        val chooseOnSpace=getSharedPreferences(PREFS,MODE_PRIVATE).getBoolean(PREF_SPACE_SELECT,false)
        if(chooseOnSpace && state.sentenceCandidates.isNotEmpty()) select(0) else {
            state.appendSpace(); showComposedImmediately(); enqueueSentenceQuery(keyPressedAt)
        }
    }

    override fun onDelete() {
        val keyPressedAt = SystemClock.elapsedRealtimeNanos()
        Log.d(TAG,"key=DELETE rawSentence=${state.rawSentence}")
        if(state.rawSentence.isEmpty()){ input.delete(); return }
        state.deleteCodePoint(); if(state.rawSentence.isEmpty()) input.finishComposing() else showComposedImmediately(); enqueueSentenceQuery(keyPressedAt)
    }

    override fun onSymbol(value: String) {
        Log.d(TAG,"key=$value rawSentence=${state.rawSentence}")
        if(nomMode && value in PUNCTUATION) { commitSentenceOrFallback(); input.commit(value) }
        else input.commit(value)
    }

    override fun onEnter() { Log.d(TAG,"key=ENTER rawSentence=${state.rawSentence}"); commitSentenceOrFallback(); input.enter(keyboard.enterAction) }
    override fun onLanguage() { commitSentenceOrFallback(); nomMode=!nomMode; keyboard.setNomMode(nomMode); updateUi() }
    override fun onMode(mode: KeyboardMode) { keyboard.showMode(mode) }
    override fun onShift() { keyboard.toggleShift() }

    private fun enqueueSentenceQuery(keyPressedAt: Long = SystemClock.elapsedRealtimeNanos()) {
        val snapshot=state.rawSentence
        val generation=state.queryGeneration
        queryCoordinator.activate(generation)
        val scheduledAt=SystemClock.elapsedRealtimeNanos()
        lastQuery=snapshot
        updateUi()
        pendingQuery?.let { mainHandler.removeCallbacks(it) }
        if(snapshot.isBlank() || !databaseReady) return
        if(Log.isLoggable(TAG,Log.DEBUG)) Log.d(TAG,"query scheduled generation=$generation keyToScheduleMs=${millis(scheduledAt-keyPressedAt)} rawLength=${snapshot.codePointCount(0,snapshot.length)}")
        val task=Runnable {
            val debounceFinishedAt=SystemClock.elapsedRealtimeNanos()
            executor.execute {
                if(queryCoordinator.isStale(generation)) {
                    if(Log.isLoggable(TAG,Log.DEBUG)) Log.d(TAG,"query coalesced before start generation=$generation latest=${queryCoordinator.latestGeneration()}")
                    return@execute
                }
                val backgroundStarted=SystemClock.elapsedRealtimeNanos()
                val context=queryCoordinator.context(generation)
                try {
                    val result=engine.query(snapshot,8,context)
                    if(context.isCancelled()) {
                        if(Log.isLoggable(TAG,Log.DEBUG)) Log.d(TAG,"query cancelled during engine generation=$generation engineMs=${millis(context.metrics.totalEngineNanos)}")
                        return@execute
                    }
                    val backgroundFinished=SystemClock.elapsedRealtimeNanos()
                    mainHandler.post {
                        val applyStarted=SystemClock.elapsedRealtimeNanos()
                        if(state.applyCandidates(generation,result)) {
                            lastError=""
                            input.setComposing(state.displaySentence)
                            updateUi()
                            val parsed=com.example.chineseime.engine.sentence.IncrementalSentenceInput.parse(snapshot)
                            if(Log.isLoggable(TAG,Log.DEBUG)) Log.d(TAG,"query timing generation=$generation debounceMs=${millis(debounceFinishedAt-scheduledAt)} queueMs=${millis(backgroundStarted-debounceFinishedAt)} segmentMs=${context.metrics.milliseconds(context.metrics.segmentationNanos)} databaseMs=${context.metrics.milliseconds(context.metrics.dictionaryLookupNanos)} beamMs=${context.metrics.milliseconds(context.metrics.beamGenerationNanos)} rankingMs=${context.metrics.milliseconds(context.metrics.candidateRankingNanos)} engineMs=${context.metrics.milliseconds(context.metrics.totalEngineNanos)} backgroundToMainMs=${millis(applyStarted-backgroundFinished)} applyMs=${millis(SystemClock.elapsedRealtimeNanos()-applyStarted)} keyToCandidateMs=${millis(SystemClock.elapsedRealtimeNanos()-keyPressedAt)} lookups=${context.metrics.dictionaryLookupCount} sqliteCalls=0 fastPath=${context.metrics.fastPath} rawLength=${snapshot.length} candidateCount=${result.size} endsWithSpace=${parsed.endsWithSpace} barVisible=${candidates.visibility==View.VISIBLE}")
                        } else Log.d(TAG,"stale sentence query ignored generation=$generation current=${state.queryGeneration}")
                    }
                } catch(error:Throwable) {
                    Log.e(TAG,"sentence query failed rawSentence=$snapshot",error)
                    mainHandler.post { if(generation==state.queryGeneration){lastError=error.stackTraceToString();updateUi()} }
                }
            }
        }
        pendingQuery=task
        mainHandler.postDelayed(task,QUERY_DEBOUNCE_MS)
    }
    private fun select(index:Int) {
        val raw=state.rawSentence.trim(); val candidate=state.choose(index)?:return
        Log.i(TAG,"sentence selected index=$index rawSentence=$raw restored=${candidate.restoredVietnamese} nomText=${candidate.nomText} sourceEntryIds=${candidate.sourceEntryIds}")
        input.setComposing(candidate.nomText); input.finishComposing()
        executor.execute { try { engine.learn(raw,candidate) } catch(error:Throwable){ Log.e(TAG,"sentence learning failed",error) } }
        state.reset(); cancelPendingQueries(); updateUi()
    }

    private fun commitSentenceOrFallback() {
        if(state.rawSentence.isBlank()) return
        val candidate=state.sentenceCandidates.firstOrNull()
        if(candidate!=null){
            val raw=state.rawSentence.trim(); input.setComposing(candidate.nomText); input.finishComposing()
            executor.execute { try{engine.learn(raw,candidate)}catch(error:Throwable){Log.e(TAG,"fallback learning failed",error)} }
        } else { input.setComposing(state.displaySentence.ifBlank { state.rawSentence.trim() }); input.finishComposing() }
        state.reset(); cancelPendingQueries(); updateUi()
    }

    private fun showComposedImmediately(){
        input.setComposing(state.displaySentence)
        updateUi()
        Log.d(TAG,"setComposingText rawSentence=${state.rawSentence} displaySentence=${state.displaySentence} restoredSentence=${state.restoredSentence}")
    }

    private fun initializeDatabase(){ executor.execute { try { val status=database.initialize(); sourceRows=status.sourceRows; searchRows=status.searchRows; databaseReady=true; mainHandler.post{Log.i(TAG,"device database initialized sourceRows=$sourceRows searchRows=$searchRows");updateUi();if(state.rawSentence.isNotBlank())enqueueSentenceQuery()} }catch(error:Throwable){Log.e(TAG,"database initialization failed",error);mainHandler.post{lastError=error.stackTraceToString();updateUi()}} } }

    private fun updateUi(){
        if(!::composition.isInitialized)return
        composition.text=state.displaySentence
        candidates.removeAllViews()
        state.sentenceCandidates.forEachIndexed{index,candidate->candidates.addView(candidateCard(candidate,index))}
        updateDiagnostics()
    }

    private fun candidateCard(candidate:NomSentenceCandidate,index:Int)=LinearLayout(this).apply{
        orientation=LinearLayout.VERTICAL; gravity=Gravity.CENTER; setPadding(dp(14),dp(4),dp(14),dp(4)); minimumWidth=dp(96); minimumHeight=dp(72)
        setBackgroundColor(Color.rgb(32,35,40)); layoutParams=LinearLayout.LayoutParams(-2,dp(72)).apply{rightMargin=dp(4)}
        val resolved=typefaceProvider.resolve(candidate.nomText,0)
        addView(TextView(this@NomInputMethodService).apply{text=candidate.nomText;typeface=resolved.typeface;textSize=24f;setTextColor(Color.rgb(241,241,241));gravity=Gravity.CENTER;maxLines=1;includeFontPadding=true},LinearLayout.LayoutParams(-2,dp(42)))
        addView(TextView(this@NomInputMethodService).apply{text=candidate.restoredVietnamese;textSize=12f;setTextColor(Color.rgb(184,188,194));gravity=Gravity.CENTER;maxLines=1},LinearLayout.LayoutParams(-2,dp(24)))
        setOnClickListener{select(index)}
    }

    private fun updateDiagnostics(){if(::diagnostics.isInitialized)diagnostics.text="Source rows: $sourceRows\nSearch rows: $searchRows\nRaw: ${state.rawSentence}\nDisplay: ${state.displaySentence}\nRestored: ${state.restoredSentence}\nLast query: $lastQuery\nCandidates: ${state.sentenceCandidates.size}\nGeneration: ${state.queryGeneration}\nLast error: ${lastError.lineSequence().firstOrNull().orEmpty()}"}
    private fun dp(v:Int)=(v*resources.displayMetrics.density).toInt()
    private fun millis(nanos:Long)=nanos/1_000_000.0
    private fun cancelPendingQueries(){
        queryCoordinator.activate(state.queryGeneration)
        pendingQuery?.let(mainHandler::removeCallbacks)
        pendingQuery=null
    }
    companion object{const val TAG="NOM_IME";const val PREFS="nom_settings";const val PREF_SPACE_SELECT="space_select_first";const val QUERY_DEBOUNCE_MS=12L;val PUNCTUATION=setOf(",",".","?","!")}
}
