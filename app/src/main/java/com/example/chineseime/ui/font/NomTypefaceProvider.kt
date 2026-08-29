package com.example.chineseime.ui.font

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.util.Log

class NomTypefaceProvider private constructor(context: Context) {
    data class ResolvedTypeface(
        val typeface: Typeface,
        val fontLabel: String,
        val hasGlyph: Boolean
    )

    data class LoadStatus(
        val primaryLoaded: Boolean,
        val fallbackLoaded: Boolean,
        val customFallbackLoaded: Boolean
    )

    private val assets = context.applicationContext.assets
    private val systemTypeface = Typeface.create("sans-serif", Typeface.NORMAL)
    private val primaryTypeface: Typeface = loadLegacyTypeface(PRIMARY_PATH, "Minh Nguyen") ?: systemTypeface
    private val fallbackTypeface: Typeface = loadLegacyTypeface(FALLBACK_PATH, "Plangothic P1") ?: systemTypeface
    private val customFallbackTypeface: Typeface? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) buildApi29Fallback() else null
    val status = LoadStatus(primaryTypeface !== systemTypeface, fallbackTypeface !== systemTypeface, customFallbackTypeface != null)

    fun resolve(nomText: String, sourceRow: Int): ResolvedTypeface {
        return try {
            val containsVs = containsVariationSelector(nomText)
            val primaryHasGlyph = Paint().apply { typeface = primaryTypeface }.hasGlyph(nomText)
            val fallbackHasGlyph = Paint().apply { typeface = fallbackTypeface }.hasGlyph(nomText)
            val selectedLabel = when {
                sourceRow == PLANGOTHIC_REQUIRED_SOURCE_ROW && status.fallbackLoaded -> "Plangothic P1"
                status.primaryLoaded -> "Minh Nguyen"
                status.fallbackLoaded -> "Plangothic P1 (primary unavailable)"
                else -> "System fallback"
            }
            val selectedTypeface = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && customFallbackTypeface != null -> customFallbackTypeface
                sourceRow == PLANGOTHIC_REQUIRED_SOURCE_ROW && status.fallbackLoaded -> fallbackTypeface
                status.primaryLoaded -> primaryTypeface
                status.fallbackLoaded -> fallbackTypeface
                else -> systemTypeface
            }
            ResolvedTypeface(selectedTypeface, selectedLabel, Paint().apply { typeface = selectedTypeface }.hasGlyph(nomText))
        } catch (error: Throwable) {
            Log.e(TAG, "Typeface resolution failed sourceRow=$sourceRow textCodePoints=${codePoints(nomText)}", error)
            ResolvedTypeface(systemTypeface, "System fallback after error", Paint().apply { typeface = systemTypeface }.hasGlyph(nomText))
        }
    }

    fun loadStatus(): LoadStatus = status

    private fun loadLegacyTypeface(path: String, label: String): Typeface? = try {
        Typeface.createFromAsset(assets, path).also { Log.i(TAG, "Loaded $label from assets/$path") }
    } catch (error: Throwable) {
        Log.e(TAG, "Failed to load $label from assets/$path", error)
        null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildApi29Fallback(): Typeface? = try {
        val primaryFont = Font.Builder(assets, PRIMARY_PATH).build()
        val fallbackFont = Font.Builder(assets, FALLBACK_PATH).build()
        val primaryFamily = FontFamily.Builder(primaryFont).build()
        val fallbackFamily = FontFamily.Builder(fallbackFont).build()
        Typeface.CustomFallbackBuilder(primaryFamily)
            .addCustomFallback(fallbackFamily)
            .setSystemFallback("sans-serif")
            .build()
            .also { Log.i(TAG, "Built API 29+ fallback chain: Minh Nguyen -> Plangothic P1 -> sans-serif") }
    } catch (error: Throwable) {
        Log.e(TAG, "Failed to build API 29+ custom fallback chain", error)
        null
    }

    private fun containsVariationSelector(text: String): Boolean = text.codePoints().anyMatch {
        it in 0xFE00..0xFE0F || it in 0xE0100..0xE01EF
    }

    companion object {
        private const val TAG = "NOM_IME"
        private const val PRIMARY_PATH = "fonts/han_nom_primary.ttf"
        private const val FALLBACK_PATH = "fonts/plangothic_p1.ttf"
        private const val PLANGOTHIC_REQUIRED_SOURCE_ROW = 874
        @Volatile private var instance: NomTypefaceProvider? = null

        fun get(context: Context): NomTypefaceProvider = instance ?: synchronized(this) {
            instance ?: NomTypefaceProvider(context.applicationContext).also { instance = it }
        }

        fun codePoints(text: String): String = text.codePoints().toArray().joinToString(" ") { "U+%04X".format(it) }
    }
}