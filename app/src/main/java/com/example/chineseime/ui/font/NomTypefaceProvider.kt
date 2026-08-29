package com.example.chineseime.ui.font

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.os.Build
import android.util.Log

class NomTypefaceProvider private constructor(context: Context) {
    data class FontChoice(
        val id: String,
        val label: String,
        val description: String,
        val assetPath: String,
        val bundled: Boolean
    )

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

    private val appContext = context.applicationContext
    private val assets = appContext.assets
    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val systemTypeface = Typeface.create("sans-serif", Typeface.NORMAL)

    private val choices = listOf(
        FontChoice(
            id = FONT_MINH_NGUYEN,
            label = "Minh Nguyen",
            description = "Serif · traditional printed Hán-Nôm forms",
            assetPath = PRIMARY_PATH,
            bundled = true
        ),
        FontChoice(
            id = FONT_GOTHIC_NGUYEN,
            label = "Gothic Nguyen",
            description = "Sans serif · clean modern Hán-Nôm forms",
            assetPath = GOTHIC_PATH,
            bundled = false
        ),
        FontChoice(
            id = FONT_NOM_NA_TONG,
            label = "Nom Na Tong",
            description = "Classic Nôm reference font · 26,000+ glyphs",
            assetPath = NOM_NA_TONG_PATH,
            bundled = false
        ),
        FontChoice(
            id = FONT_PLANGOTHIC,
            label = "Plangothic P1",
            description = "Wide CJK extension coverage · fallback-oriented",
            assetPath = FALLBACK_PATH,
            bundled = true
        )
    )

    private val loadedTypefaces: Map<String, Typeface?> = choices.associate { choice ->
        choice.id to loadTypeface(choice)
    }
    private val compositeTypefaces = mutableMapOf<String, Typeface>()

    val status = LoadStatus(
        primaryLoaded = loadedTypefaces[FONT_MINH_NGUYEN] != null,
        fallbackLoaded = loadedTypefaces[FONT_PLANGOTHIC] != null,
        customFallbackLoaded = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
            loadedTypefaces[FONT_MINH_NGUYEN] != null &&
            loadedTypefaces[FONT_PLANGOTHIC] != null
    )

    fun availableChoices(): List<FontChoice> = choices.filter { loadedTypefaces[it.id] != null }

    fun currentChoice(): FontChoice {
        val requested = prefs.getString(PREF_FONT_CHOICE, FONT_MINH_NGUYEN)
        return availableChoices().firstOrNull { it.id == requested }
            ?: availableChoices().firstOrNull { it.id == FONT_MINH_NGUYEN }
            ?: availableChoices().firstOrNull()
            ?: choices.first()
    }

    fun selectFont(id: String): Boolean {
        if (loadedTypefaces[id] == null) return false
        prefs.edit().putString(PREF_FONT_CHOICE, id).apply()
        Log.i(TAG, "Selected Nôm font id=$id")
        return true
    }

    fun typefaceFor(id: String): Typeface {
        val raw = loadedTypefaces[id] ?: return systemTypeface
        if (id == FONT_PLANGOTHIC || Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return raw
        return synchronized(compositeTypefaces) {
            compositeTypefaces[id] ?: buildCompositeTypeface(id)?.also {
                compositeTypefaces[id] = it
            } ?: raw
        }
    }

    fun currentTypeface(): Typeface = typefaceFor(currentChoice().id)

    fun resolve(nomText: String, sourceRow: Int): ResolvedTypeface {
        return try {
            val choice = currentChoice()
            val selectedRaw = loadedTypefaces[choice.id]
            val selectedComposite = typefaceFor(choice.id)
            val plangothic = loadedTypefaces[FONT_PLANGOTHIC]
            val minh = loadedTypefaces[FONT_MINH_NGUYEN]

            val resolved = when {
                selectedRaw != null && hasGlyph(selectedRaw, nomText) -> choice.label to selectedComposite
                plangothic != null && hasGlyph(plangothic, nomText) -> "Plangothic P1 fallback" to plangothic
                minh != null && hasGlyph(minh, nomText) -> "Minh Nguyen fallback" to minh
                selectedRaw != null -> choice.label to selectedComposite
                else -> "System fallback" to systemTypeface
            }

            ResolvedTypeface(
                typeface = resolved.second,
                fontLabel = resolved.first,
                hasGlyph = hasGlyph(resolved.second, nomText)
            )
        } catch (error: Throwable) {
            Log.e(
                TAG,
                "Typeface resolution failed sourceRow=$sourceRow textCodePoints=${codePoints(nomText)}",
                error
            )
            ResolvedTypeface(
                systemTypeface,
                "System fallback after error",
                hasGlyph(systemTypeface, nomText)
            )
        }
    }

    fun loadStatus(): LoadStatus = status

    private fun loadTypeface(choice: FontChoice): Typeface? = try {
        Typeface.createFromAsset(assets, choice.assetPath).also {
            Log.i(TAG, "Loaded ${choice.label} from assets/${choice.assetPath}")
        }
    } catch (error: Throwable) {
        if (choice.bundled) {
            Log.e(TAG, "Failed to load bundled font ${choice.label}", error)
        } else {
            Log.i(TAG, "Optional font not installed: ${choice.label}")
        }
        null
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.Q)
    private fun buildCompositeTypeface(id: String): Typeface? {
        val choice = choices.firstOrNull { it.id == id } ?: return null
        if (loadedTypefaces[id] == null || loadedTypefaces[FONT_PLANGOTHIC] == null) return null
        return try {
            val selectedFont = Font.Builder(assets, choice.assetPath).build()
            val fallbackFont = Font.Builder(assets, FALLBACK_PATH).build()
            val selectedFamily = FontFamily.Builder(selectedFont).build()
            val fallbackFamily = FontFamily.Builder(fallbackFont).build()
            Typeface.CustomFallbackBuilder(selectedFamily)
                .addCustomFallback(fallbackFamily)
                .setSystemFallback("sans-serif")
                .build()
                .also { Log.i(TAG, "Built font chain ${choice.label} -> Plangothic P1 -> sans-serif") }
        } catch (error: Throwable) {
            Log.e(TAG, "Failed to build font chain for ${choice.label}", error)
            null
        }
    }

    private fun hasGlyph(typeface: Typeface, text: String): Boolean =
        Paint().apply { this.typeface = typeface }.hasGlyph(text)

    companion object {
        private const val TAG = "NOM_IME"
        private const val PREFS = "nom_settings"
        private const val PREF_FONT_CHOICE = "nom_font_choice"

        const val FONT_MINH_NGUYEN = "minh_nguyen"
        const val FONT_GOTHIC_NGUYEN = "gothic_nguyen"
        const val FONT_NOM_NA_TONG = "nom_na_tong"
        const val FONT_PLANGOTHIC = "plangothic_p1"

        private const val PRIMARY_PATH = "fonts/han_nom_primary.ttf"
        private const val GOTHIC_PATH = "fonts/gothic_nguyen_regular.ttf"
        private const val NOM_NA_TONG_PATH = "fonts/nom_na_tong_regular.otf"
        private const val FALLBACK_PATH = "fonts/plangothic_p1.ttf"

        @Volatile private var instance: NomTypefaceProvider? = null

        fun get(context: Context): NomTypefaceProvider = instance ?: synchronized(this) {
            instance ?: NomTypefaceProvider(context.applicationContext).also { instance = it }
        }

        fun codePoints(text: String): String = text.codePoints().toArray()
            .joinToString(" ") { "U+%04X".format(it) }
    }
}
