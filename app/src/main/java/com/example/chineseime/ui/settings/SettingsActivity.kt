package com.example.chineseime.ui.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.util.Linkify
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import androidx.appcompat.app.AppCompatActivity
import com.example.chineseime.R
import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.model.NomSourceEntry
import com.example.chineseime.ui.font.NomTypefaceProvider
import com.example.chineseime.ui.curator.PhraseCuratorView
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    private var phraseCurator: PhraseCuratorView? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        title = getString(R.string.settings_title)
        val metadata = assets.open("hannom_rcv_metadata.json").bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
        val provider = NomTypefaceProvider.get(this)
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(48, 64, 48, 48) }
        box.addView(TextView(this).apply { text = getString(R.string.settings_description); textSize = 18f })
        box.addView(Button(this).apply { text = getString(R.string.enable_ime); setOnClickListener { startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS)) } })
        box.addView(Button(this).apply { text = getString(R.string.choose_ime); setOnClickListener { (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker() } })
        box.addView(SwitchCompat(this).apply {
            text = "空格选择首选候选（整句模式默认关闭）"
            val prefs = getSharedPreferences("nom_settings", MODE_PRIVATE)
            isChecked = prefs.getBoolean("space_select_first", false)
            setOnCheckedChangeListener { _, checked -> prefs.edit().putBoolean("space_select_first", checked).apply() }
        })
        box.addView(sectionTitle("Ngu\u1ed3n d\u1eef li\u1ec7u"))
        box.addView(TextView(this).apply {
            textSize = 15f
            text = buildString {
                append("H\u1ed9i B\u1ea3o t\u1ed3n Di s\u1ea3n ch\u1eef N\u00f4m\n")
                append(metadata.getString("sourceUrl")); append("\n\n")
                append("S\u1ed1 b\u1ea3n ghi: "); append(metadata.getInt("extractedRowCount")); append('\n')
                append("Ng\u00e0y l\u1ea5y d\u1eef li\u1ec7u: "); append(metadata.getString("fetchedAt")); append('\n')
                append("CSV SHA-256: "); append(metadata.getString("csvSha256"))
            }
            Linkify.addLinks(this, Linkify.WEB_URLS)
        })
        box.addView(sectionTitle("Ph\u00f4ng ch\u1eef H\u00e1n N\u00f4m v\u00e0 gi\u1ea5y ph\u00e9p"))
        box.addView(TextView(this).apply {
            textSize = 14f
            text = "Minh Nguy\u00ean Regular — TKYKmori / H\u1ed9i B\u1ea3o t\u1ed3n Di s\u1ea3n ch\u1eef N\u00f4m\n" +
                "Plangothic P1 Regular — Plangothic Project V2.9.5795\n" +
                "C\u1ea3 hai ph\u00f4ng ch\u1eef: SIL Open Font License 1.1\n" +
                "B\u1ea3n quy\u1ec1n: assets/licenses/minh_nguyen_ofl.txt v\u00e0 assets/licenses/plangothic_ofl.txt"
        })
        box.addView(sectionTitle("Ki\u1ec3m tra hi\u1ec3n th\u1ecb ch\u1eef N\u00f4m"))
        addFontTests(box, provider)
        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            box.addView(EditText(this).apply { hint = "Debug IME test field"; minLines = 2; inputType = android.text.InputType.TYPE_CLASS_TEXT })
            box.addView(sectionTitle("Verified Nôm Phrase Curator"))
            phraseCurator=PhraseCuratorView(this).also { it.attach(box) }
        }
        setContentView(ScrollView(this).apply { addView(box) })
    }

    override fun onDestroy() { phraseCurator?.close();super.onDestroy() }

    private fun addFontTests(box: LinearLayout, provider: NomTypefaceProvider) {
        try {
            val csv = assets.open("hannom_rcv_standard_nom.csv").bufferedReader(Charsets.UTF_8).use { it.readText() }
            val entries = NomCsvLoader.load(csv)
            val bmp = entries.first { entry -> entry.nomRaw.codePoints().allMatch { it <= 0xFFFF && !isVariationSelector(it) } }
            val supplementary = entries.first { entry ->
                entry.sourceRow != 874 && entry.nomRaw.codePoints().anyMatch { it > 0xFFFF && !isVariationSelector(it) } &&
                    !entry.nomRaw.codePoints().anyMatch { isVariationSelector(it) }
            }
            val fallback = entries.first { it.sourceRow == 874 }
            val variation = entries.first { it.nomRaw.codePoints().anyMatch { cp -> isVariationSelector(cp) } }
            listOf("BMP" to bmp, "Supplementary" to supplementary, "P1 fallback" to fallback, "Variation selector" to variation).forEach { (label, entry) ->
                addFontTestRow(box, label, entry, provider)
            }
            val variationEntries = entries.filter { entry -> entry.nomRaw.codePoints().anyMatch { cp -> isVariationSelector(cp) } }
            val variationPassed = variationEntries.count { entry -> provider.resolve(entry.nomRaw, entry.sourceRow).hasGlyph }
            Log.i(TAG, "variation selector runtime summary fullStringHasGlyph=$variationPassed total=${variationEntries.size} sourceRows=${variationEntries.joinToString { it.sourceRow.toString() }}")
            if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
                variationEntries.forEach { entry ->
                    val resolved = provider.resolve(entry.nomRaw, entry.sourceRow)
                    box.addView(TextView(this).apply {
                        text = entry.nomRaw; typeface = resolved.typeface; textSize = 22f
                        includeFontPadding = true; maxLines = 1; ellipsize = null; gravity = Gravity.CENTER_VERTICAL
                        minimumHeight = 56; setTextColor(Color.rgb(241, 241, 241))
                        post {
                            val pixelWidth = paint.measureText(entry.nomRaw)
                            val glyphHeight = paint.fontMetrics.descent - paint.fontMetrics.ascent
                            val clipped = pixelWidth <= 0f || glyphHeight + paddingTop + paddingBottom > height
                            Log.i(TAG, "variation runtime sourceRow=${entry.sourceRow} codePoints=${NomTypefaceProvider.codePoints(entry.nomRaw)} font=${resolved.fontLabel} fullStringHasGlyph=${paint.hasGlyph(entry.nomRaw)} pixelWidth=$pixelWidth viewWidth=$width clipped=$clipped")
                        }
                    })
                }
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Settings font tests failed", error)
            box.addView(TextView(this).apply { text = "Font test error: ${error.message}"; setTextColor(Color.RED) })
        }
    }

    private fun addFontTestRow(box: LinearLayout, label: String, entry: NomSourceEntry, provider: NomTypefaceProvider) {
        val resolved = provider.resolve(entry.nomRaw, entry.sourceRow)
        box.addView(TextView(this).apply {
            textSize = 12f
            text = "$label — sourceRow=${entry.sourceRow} — ${resolved.fontLabel} — ${NomTypefaceProvider.codePoints(entry.nomRaw)}"
        })
        box.addView(TextView(this).apply {
            text = entry.nomRaw; typeface = resolved.typeface; textSize = 28f
            includeFontPadding = true; maxLines = 1; ellipsize = null; gravity = Gravity.CENTER_VERTICAL
            minimumHeight = 64; setTextColor(Color.rgb(241, 241, 241))
            post { Log.i(TAG, "settings font test label=$label sourceRow=${entry.sourceRow} codePoints=${NomTypefaceProvider.codePoints(entry.nomRaw)} font=${resolved.fontLabel} paintHasGlyph=${paint.hasGlyph(entry.nomRaw)} pixelWidth=${paint.measureText(entry.nomRaw)} height=$height") }
        })
    }

    private fun sectionTitle(value: String) = TextView(this).apply { text = value; textSize = 17f; setPadding(0, 36, 0, 8) }
    private fun isVariationSelector(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
    companion object { const val TAG = "NOM_IME" }
}
