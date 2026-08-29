package com.example.chineseime.ime

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.os.SystemClock
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Space
import androidx.appcompat.widget.AppCompatTextView
import com.example.chineseime.R
import kotlin.math.roundToInt

enum class KeyboardMode { LETTERS, NINE_KEY, NUMBERS, SYMBOLS }

class KeyboardController(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onLetter(value: Char)
        fun onNineKeyDigit(value: Char)
        fun onReplaceLastLetter(value: Char)
        fun onReplaceCommittedSymbol(value: String)
        fun onDelete()
        fun onSpace()
        fun onEnter()
        fun onLanguage()
        fun onMode(mode: KeyboardMode)
        fun onShift()
        fun onSymbol(value: String)
    }

    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private var preferredTextMode =
        if (prefs.getBoolean(PREF_NINE_KEY_LAYOUT, false)) KeyboardMode.NINE_KEY
        else KeyboardMode.LETTERS

    var currentMode: KeyboardMode = preferredTextMode
        private set
    var shifted = false
        private set
    private var nomMode = true
    var enterAction = EditorInfo.IME_ACTION_NONE
        private set
    private var panel: KeyboardPanel? = null

    fun build(): View = panel ?: KeyboardPanel(context).also { panel = it }

    fun configure(
        mode: KeyboardMode,
        isNomMode: Boolean,
        imeOptions: Int,
        allowNineKey: Boolean = true
    ) {
        currentMode = if (mode == KeyboardMode.LETTERS && allowNineKey) preferredTextMode else mode
        nomMode = isNomMode
        enterAction = imeOptions
        panel?.updateDynamicKeys()
        panel?.showMode(currentMode)
    }

    fun refreshCurrentMode() {
        panel?.showMode(currentMode)
    }

    fun showMode(mode: KeyboardMode) {
        currentMode = mode
        if (mode == KeyboardMode.LETTERS || mode == KeyboardMode.NINE_KEY) {
            preferredTextMode = mode
            prefs.edit()
                .putBoolean(PREF_NINE_KEY_LAYOUT, mode == KeyboardMode.NINE_KEY)
                .apply()
        }
        panel?.showMode(mode)
    }

    fun setNomMode(value: Boolean) {
        nomMode = value
        panel?.updateDynamicKeys()
    }

    fun toggleShift() {
        shifted = !shifted
        panel?.resetNineKeyCycle()
        panel?.updateDynamicKeys()
    }

    private inner class KeyboardPanel(context: Context) : LinearLayout(context) {
        private val tag = "NOM_IME"
        private val horizontalPadding = dp(6)
        private val horizontalGap = dp(4)
        private val verticalGap = dp(5)
        private val keyHeight = dp(50)
        private val pageHost = FrameLayout(context)
        private var pagesCreated = false
        private var cachedMeasuredWidth = 0
        private lateinit var lettersView: View
        private lateinit var nineKeyView: View
        private lateinit var numbersView: View
        private lateinit var symbolsView: View
        private val letterKeys = mutableListOf<Pair<Char, AppCompatTextView>>()
        private val languageKeys = mutableListOf<AppCompatTextView>()
        private val enterKeys = mutableListOf<ImageButton>()

        private var lastNineKeyDigit: Char? = null
        private var lastNineKeyIndex = 0
        private var lastNineKeyAt = 0L

        init {
            orientation = VERTICAL
            setPadding(horizontalPadding, dp(6), horizontalPadding, dp(6))
            setBackgroundColor(BACKGROUND)
            minimumHeight = dp(232)
            addView(pageHost, LayoutParams(LayoutParams.MATCH_PARENT, dp(220)))
            post { createPagesAfterLayout() }
        }

        override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
            super.onSizeChanged(w, h, oldw, oldh)
            if (!pagesCreated && w > paddingLeft + paddingRight) post { createPagesAfterLayout() }
        }

        private fun createPagesAfterLayout() {
            if (pagesCreated) return
            val measured = pageHost.measuredWidth.takeIf { it > 0 }
                ?: (measuredWidth - paddingLeft - paddingRight).takeIf { it > 0 }
            if (measured == null) {
                Log.w(tag, "pages deferred: mode=$currentMode panel=$measuredWidth host=${pageHost.measuredWidth}")
                pageHost.post { createPagesAfterLayout() }
                return
            }
            cachedMeasuredWidth = measured
            try {
                val normalWidth = ((cachedMeasuredWidth - horizontalGap * 9) / 10f).roundToInt()
                val newLetters = buildLettersPage(normalWidth, cachedMeasuredWidth)
                val newNineKey = buildNineKeyPage(normalWidth, cachedMeasuredWidth)
                val newNumbers = buildGridPage(
                    listOf("1234567890", "-/:;()\u0024&@\"", ".,?!'#+=%"),
                    normalWidth,
                    cachedMeasuredWidth,
                    KeyboardMode.NUMBERS
                )
                val newSymbols = buildGridPage(
                    listOf("[]{}#%^*+=", "_\\|~<>+-*/", ".,?!'`:;="),
                    normalWidth,
                    cachedMeasuredWidth,
                    KeyboardMode.SYMBOLS
                )
                lettersView = newLetters
                nineKeyView = newNineKey
                numbersView = newNumbers
                symbolsView = newSymbols
                pageHost.addView(lettersView, frameParams())
                pageHost.addView(nineKeyView, frameParams())
                pageHost.addView(numbersView, frameParams())
                pageHost.addView(symbolsView, frameParams())
                pagesCreated = true
                updateDynamicKeys()
                showMode(currentMode)
                Log.d(tag, "pages created: mode=$currentMode width=$cachedMeasuredWidth key=$normalWidth")
            } catch (error: Throwable) {
                Log.e(tag, "page creation failed: mode=$currentMode width=$cachedMeasuredWidth", error)
                pageHost.post { createPagesAfterLayout() }
            }
        }

        fun showMode(mode: KeyboardMode) {
            resetNineKeyCycle()
            if (!pagesCreated) {
                Log.d(tag, "mode queued: mode=$mode panel=$measuredWidth host=${pageHost.measuredWidth}")
                post { createPagesAfterLayout() }
                return
            }
            lettersView.visibility = if (mode == KeyboardMode.LETTERS) View.VISIBLE else View.GONE
            nineKeyView.visibility = if (mode == KeyboardMode.NINE_KEY) View.VISIBLE else View.GONE
            numbersView.visibility = if (mode == KeyboardMode.NUMBERS) View.VISIBLE else View.GONE
            symbolsView.visibility = if (mode == KeyboardMode.SYMBOLS) View.VISIBLE else View.GONE
            Log.d(
                tag,
                "mode=$mode width=$cachedMeasuredWidth visibility=L${lettersView.visibility}/9${nineKeyView.visibility}/N${numbersView.visibility}/S${symbolsView.visibility}"
            )
        }

        fun updateDynamicKeys() {
            if (!pagesCreated) return
            letterKeys.forEach { (letter, key) ->
                key.text = (if (shifted) letter.uppercaseChar() else letter).toString()
            }
            languageKeys.forEach { key ->
                key.text = if (nomMode) "NÔM" else "QN"
                key.setTextColor(if (nomMode) ACCENT else MUTED)
            }
            enterKeys.forEach { it.setImageResource(enterIcon()) }
        }

        fun resetNineKeyCycle() {
            lastNineKeyDigit = null
            lastNineKeyIndex = 0
            lastNineKeyAt = 0L
        }

        private fun buildLettersPage(unit: Int, available: Int): View {
            val page = page()
            page.addView(characterRow("qwertyuiop", unit, available, letters = true))
            page.addView(characterRow("asdfghjkl", unit, available, letters = true))
            val special = (unit * 1.4f).roundToInt()
            val content = special * 2 + unit * 7 + horizontalGap * 8
            val row = newRow(((available - content) / 2).coerceAtLeast(0))
            row.addView(iconKey(R.drawable.ic_shift, special) {
                resetNineKeyCycle()
                listener.onShift()
            })
            "zxcvbnm".forEach { letter ->
                addGap(row)
                val key = textKey(letter.toString(), unit) {
                    resetNineKeyCycle()
                    listener.onLetter(if (shifted) letter.uppercaseChar() else letter)
                }
                letterKeys += letter to key
                row.addView(key)
            }
            addGap(row)
            row.addView(
                iconKey(
                    R.drawable.ic_backspace,
                    special,
                    repeatOnHold = true
                ) {
                    resetNineKeyCycle()
                    listener.onDelete()
                }
            )
            page.addView(row)
            page.addView(bottomRow(unit, available, KeyboardMode.LETTERS))
            return page
        }

        private fun buildNineKeyPage(unit: Int, available: Int): View {
            val page = page()
            val keyWidth = ((available - horizontalGap * 2 - dp(28)) / 3f).roundToInt()
            val rows = listOf(
                listOf('1' to ".,?!", '2' to "ABC", '3' to "DEF"),
                listOf('4' to "GHI", '5' to "JKL", '6' to "MNO"),
                listOf('7' to "PQRS", '8' to "TUV", '9' to "WXYZ")
            )
            rows.forEach { groups ->
                val content = keyWidth * 3 + horizontalGap * 2
                val row = newRow(((available - content) / 2).coerceAtLeast(0))
                groups.forEachIndexed { index, (digit, letters) ->
                    if (index > 0) addGap(row)
                    row.addView(nineKeyButton(digit, letters, keyWidth))
                }
                page.addView(row)
            }
            page.addView(bottomRow(unit, available, KeyboardMode.NINE_KEY))
            return page
        }

        private fun buildGridPage(
            rows: List<String>,
            unit: Int,
            available: Int,
            mode: KeyboardMode
        ): View = page().apply {
            rows.forEach { addView(characterRow(it, unit, available, letters = false)) }
            addView(bottomRow(unit, available, mode))
        }

        private fun characterRow(chars: String, unit: Int, available: Int, letters: Boolean): View {
            val content = unit * chars.length + horizontalGap * (chars.length - 1)
            val row = newRow(((available - content) / 2).coerceAtLeast(0))
            chars.forEachIndexed { index, char ->
                if (index > 0) addGap(row)
                val key = textKey(char.toString(), unit) {
                    resetNineKeyCycle()
                    if (letters) listener.onLetter(if (shifted) char.uppercaseChar() else char)
                    else listener.onSymbol(char.toString())
                }
                if (letters) letterKeys += char to key
                row.addView(key)
            }
            return row
        }

        private fun bottomRow(unit: Int, available: Int, mode: KeyboardMode): View {
            val small = (unit * 1.2f).roundToInt()
            val enterWidth = (unit * 1.4f).roundToInt()
            val widths = intArrayOf(small, small, small, unit * 4, unit, enterWidth)
            val content = widths.sum() + horizontalGap * 5
            val row = newRow(((available - content) / 2).coerceAtLeast(0), bottom = false)

            val firstLabel = when (mode) {
                KeyboardMode.LETTERS, KeyboardMode.NINE_KEY -> "123"
                KeyboardMode.NUMBERS, KeyboardMode.SYMBOLS -> "ABC"
            }
            row.addView(textKey(firstLabel, widths[0], function = true) {
                resetNineKeyCycle()
                val target = when (mode) {
                    KeyboardMode.LETTERS, KeyboardMode.NINE_KEY -> KeyboardMode.NUMBERS
                    KeyboardMode.NUMBERS, KeyboardMode.SYMBOLS -> preferredTextMode
                }
                listener.onMode(target)
            })
            addGap(row)

            val secondLabel = when (mode) {
                KeyboardMode.LETTERS -> "9K"
                KeyboardMode.NINE_KEY -> "ABC"
                KeyboardMode.NUMBERS -> "=\\<"
                KeyboardMode.SYMBOLS -> "123"
            }
            row.addView(textKey(secondLabel, widths[1], function = true) {
                resetNineKeyCycle()
                val target = when (mode) {
                    KeyboardMode.LETTERS -> KeyboardMode.NINE_KEY
                    KeyboardMode.NINE_KEY -> KeyboardMode.LETTERS
                    KeyboardMode.NUMBERS -> KeyboardMode.SYMBOLS
                    KeyboardMode.SYMBOLS -> KeyboardMode.NUMBERS
                }
                listener.onMode(target)
            })
            addGap(row)

            val language = textKey("", widths[2], function = true) {
                resetNineKeyCycle()
                listener.onLanguage()
            }
            languageKeys += language
            row.addView(language)
            addGap(row)

            row.addView(textKey("", widths[3]) {
                resetNineKeyCycle()
                listener.onSpace()
            })
            addGap(row)

            if (mode == KeyboardMode.NINE_KEY) {
                row.addView(
                    iconKey(
                        R.drawable.ic_backspace,
                        widths[4],
                        repeatOnHold = true
                    ) {
                        resetNineKeyCycle()
                        listener.onDelete()
                    }
                )
            } else {
                row.addView(textKey(".", widths[4]) {
                    resetNineKeyCycle()
                    listener.onSymbol(".")
                })
            }
            addGap(row)

            val enter = iconKey(enterIcon(), widths[5]) {
                resetNineKeyCycle()
                listener.onEnter()
            }
            enterKeys += enter
            row.addView(enter)
            return row
        }

        private fun nineKeyButton(
            digit: Char,
            letters: String,
            width: Int
        ) = AppCompatTextView(context).apply {
            text = "$digit\n$letters"
            gravity = Gravity.CENTER
            setTextColor(TEXT)
            textSize = 14.5f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 2
            includeFontPadding = false
            setLineSpacing(0f, 0.92f)
            background = context.getDrawable(R.drawable.key_background)
            layoutParams = LayoutParams(width, keyHeight)
            installImmediatePress(
                click = { handleNineKeyPress(digit, letters) }
            )
        }

        private fun handleNineKeyPress(digit: Char, group: String) {
            if (digit != '1') {
                resetNineKeyCycle()
                listener.onNineKeyDigit(digit)
                return
            }

            val now = SystemClock.uptimeMillis()
            val cycling =
                lastNineKeyDigit == digit &&
                    now - lastNineKeyAt <= NINE_KEY_CYCLE_TIMEOUT_MS
            val index = if (cycling) (lastNineKeyIndex + 1) % group.length else 0

            lastNineKeyDigit = digit
            lastNineKeyIndex = index
            lastNineKeyAt = now

            val selected = group[index].toString()
            if (cycling) {
                listener.onReplaceCommittedSymbol(selected)
            } else {
                listener.onSymbol(selected)
            }
        }

        private fun enterIcon(): Int = when (enterAction and EditorInfo.IME_MASK_ACTION) {
            EditorInfo.IME_ACTION_SEARCH -> R.drawable.ic_search
            EditorInfo.IME_ACTION_DONE,
            EditorInfo.IME_ACTION_GO,
            EditorInfo.IME_ACTION_SEND -> R.drawable.ic_done
            EditorInfo.IME_ACTION_NEXT,
            EditorInfo.IME_ACTION_PREVIOUS -> R.drawable.ic_next
            else -> R.drawable.ic_enter
        }

        private fun page() = LinearLayout(context).apply {
            orientation = VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        private fun frameParams() = FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT
        )

        private fun newRow(sidePadding: Int, bottom: Boolean = true) = LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(sidePadding, 0, sidePadding, 0)
            layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, keyHeight).apply {
                if (bottom) bottomMargin = verticalGap
            }
        }

        private fun textKey(
            label: String,
            width: Int,
            function: Boolean = false,
            click: () -> Unit
        ) = AppCompatTextView(context).apply {
            text = label
            gravity = Gravity.CENTER
            setTextColor(if (function) MUTED else TEXT)
            textSize = if (function) 13f else 18f
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            includeFontPadding = false
            background = context.getDrawable(
                if (function) R.drawable.key_function_background else R.drawable.key_background
            )
            layoutParams = LayoutParams(width, keyHeight)
            installImmediatePress(click)
        }

        private fun iconKey(
            icon: Int,
            width: Int,
            repeatOnHold: Boolean = false,
            click: () -> Unit
        ) = ImageButton(context).apply {
            setImageResource(icon)
            scaleType = ImageView.ScaleType.CENTER
            setPadding(dp(12), dp(12), dp(12), dp(12))
            contentDescription = null
            background = context.getDrawable(R.drawable.key_function_background)
            layoutParams = LayoutParams(width, keyHeight)
            installImmediatePress(click, repeatOnHold)
        }

        private fun View.installImmediatePress(
            click: () -> Unit,
            repeatOnHold: Boolean = false
        ) {
            isClickable = true
            isFocusable = true

            lateinit var repeatAction: Runnable
            repeatAction = Runnable {
                if (!repeatOnHold || !isPressed) return@Runnable
                click()
                postDelayed(repeatAction, DELETE_REPEAT_INTERVAL_MS)
            }

            setOnTouchListener { view, event ->
                when (event.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        view.removeCallbacks(repeatAction)
                        view.isPressed = true
                        click()
                        if (repeatOnHold) {
                            view.postDelayed(repeatAction, DELETE_REPEAT_START_DELAY_MS)
                        }
                        true
                    }

                    MotionEvent.ACTION_UP,
                    MotionEvent.ACTION_CANCEL -> {
                        view.isPressed = false
                        view.removeCallbacks(repeatAction)
                        true
                    }

                    else -> true
                }
            }
            setOnClickListener { click() }
        }

        private fun addGap(row: LinearLayout) =
            row.addView(Space(context), LayoutParams(horizontalGap, 1))

        private fun dp(value: Int) = (value * resources.displayMetrics.density).roundToInt()
    }

    companion object {
        private const val PREFS = "nom_settings"
        private const val PREF_NINE_KEY_LAYOUT = "nine_key_layout"
        private const val NINE_KEY_CYCLE_TIMEOUT_MS = 650L
        private const val DELETE_REPEAT_START_DELAY_MS = 320L
        private const val DELETE_REPEAT_INTERVAL_MS = 60L
        private val BACKGROUND = Color.rgb(10, 13, 18)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(151, 163, 179)
        private val ACCENT = Color.rgb(111, 199, 255)
    }
}
