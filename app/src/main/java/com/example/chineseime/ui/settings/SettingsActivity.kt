package com.example.chineseime.ui.settings

import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.text.util.Linkify
import android.util.Log
import android.view.Gravity
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.example.chineseime.R
import com.example.chineseime.data.local.NomCsvLoader
import com.example.chineseime.data.model.NomSourceEntry
import com.example.chineseime.ui.curator.PhraseCuratorActivity
import com.example.chineseime.ui.font.NomTypefaceProvider
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.google.android.material.textfield.TextInputEditText
import com.google.android.material.textfield.TextInputLayout
import org.json.JSONObject

class SettingsActivity : AppCompatActivity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND

        val metadata = assets.open("hannom_rcv_metadata.json")
            .bufferedReader(Charsets.UTF_8)
            .use { JSONObject(it.readText()) }
        val provider = NomTypefaceProvider.get(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }
        root.addView(MaterialToolbar(this).apply {
            title = "Chữ Nôm IME"
            setTitleTextColor(TEXT)
            setBackgroundColor(BACKGROUND)
        }, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(6), dp(18), dp(26))
        }
        content.addView(TextView(this).apply {
            text = "Bàn phím chữ Nôm"
            textSize = 31f
            setTextColor(TEXT)
        })
        content.addView(TextView(this).apply {
            text = getString(R.string.settings_description)
            textSize = 14f
            setTextColor(MUTED)
            setPadding(0, dp(4), 0, dp(16))
        })

        content.addView(buildSetupCard())
        content.addView(space(dp(12)))
        content.addView(buildTypingCard())

        if (applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0) {
            content.addView(space(dp(12)))
            content.addView(buildStudioCard())
        }

        content.addView(space(dp(12)))
        content.addView(buildDictionaryCard(metadata, provider))
        content.addView(space(dp(16)))
        content.addView(TextView(this).apply {
            text = "Offline by design · Nôm data stays on your device"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        })

        root.addView(ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content)
        }, LinearLayout.LayoutParams(-1, 0, 1f))

        setContentView(root)
    }

    private fun buildSetupCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(eyebrow("01  SETUP"))
            addView(title("Start typing in Nôm"))
            addView(body("Enable the keyboard once, then choose it whenever you want to type."))
            addView(primaryButton(getString(R.string.enable_ime)) {
                startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(14), 0, 0) })
            addView(secondaryButton(getString(R.string.choose_ime)) {
                (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager).showInputMethodPicker()
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(8), 0, 0) })
        })
    }

    private fun buildTypingCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(eyebrow("02  TYPING"))
            addView(title("Input behaviour"))
            addView(body("Keep sentence composition predictable while you type Vietnamese or Telex."))
            addView(SwitchMaterial(this@SettingsActivity).apply {
                text = "Space selects the first candidate"
                textSize = 15f
                setTextColor(TEXT)
                thumbTintList = ColorStateList.valueOf(ACCENT)
                trackTintList = ColorStateList(
                    arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                    intArrayOf(ACCENT_DARK, SURFACE_HIGH)
                )
                val prefs = getSharedPreferences("nom_settings", MODE_PRIVATE)
                isChecked = prefs.getBoolean("space_select_first", false)
                setOnCheckedChangeListener { _, checked ->
                    prefs.edit().putBoolean("space_select_first", checked).apply()
                }
                setPadding(0, dp(12), 0, 0)
            })
            addView(TextView(this@SettingsActivity).apply {
                text = "Sentence mode keeps this off by default so spaces remain part of the Vietnamese phrase."
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(4), 0, 0)
            })
        })
    }

    private fun buildStudioCard(): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(eyebrow("03  STUDIO"))
            addView(title("Verified phrase workspace"))
            addView(body("Review dictionary evidence and save phrases locally before you export a corpus."))
            addView(primaryButton("Open Phrase Studio") {
                startActivity(Intent(this@SettingsActivity, PhraseCuratorActivity::class.java))
            }, LinearLayout.LayoutParams(-1, dp(52)).apply { setMargins(0, dp(14), 0, 0) })

            val testInput = TextInputEditText(this@SettingsActivity).apply {
                minLines = 2
                maxLines = 3
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
                setTextColor(TEXT)
                setHintTextColor(MUTED)
                hint = "Tap here and switch to the Nôm keyboard"
                background = null
                setPadding(dp(12), dp(9), dp(12), dp(9))
            }
            addView(TextInputLayout(this@SettingsActivity).apply {
                setHintEnabled(false)
                boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                boxBackgroundColor = SURFACE_HIGH
                boxStrokeColor = ACCENT
                boxStrokeWidth = dp(1)
                boxStrokeWidthFocused = dp(2)
                setBoxCornerRadii(
                    dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat(), dp(14).toFloat()
                )
                addView(testInput)
            }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, dp(12), 0, 0) })
        })
    }

    private fun buildDictionaryCard(
        metadata: JSONObject,
        provider: NomTypefaceProvider
    ): MaterialCardView = surfaceCard().apply {
        addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(eyebrow("DATA & TYPE"))
            addView(title("Dictionary and Nôm fonts"))
            addView(body("Hội Bảo tồn Di sản chữ Nôm · ${metadata.getInt("extractedRowCount")} source records"))

            addView(secondaryButton("Nôm font · ${provider.currentChoice().label}") {
                showFontPicker(provider)
            }, LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(14), 0, 0) })
            addView(secondaryButton("View data details") { showDataDetails(metadata) },
                LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(8), 0, 0) })
            addView(secondaryButton("Run glyph diagnostics") { showGlyphDiagnostics(provider) },
                LinearLayout.LayoutParams(-1, dp(48)).apply { setMargins(0, dp(8), 0, 0) })

            addView(TextView(this@SettingsActivity).apply {
                text = "Installed: ${provider.availableChoices().joinToString(" · ") { it.label }}"
                textSize = 12f
                setTextColor(MUTED)
                setPadding(0, dp(12), 0, 0)
            })
        })
    }

    private fun showFontPicker(provider: NomTypefaceProvider) {
        val current = provider.currentChoice().id
        val list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }

        provider.availableChoices().forEach { choice ->
            val selected = choice.id == current
            list.addView(MaterialCardView(this).apply {
                setCardBackgroundColor(if (selected) ACCENT_DARK else SURFACE_HIGH)
                radius = dp(16).toFloat()
                strokeColor = if (selected) ACCENT else BORDER
                strokeWidth = dp(if (selected) 2 else 1)
                cardElevation = 0f
                addView(LinearLayout(this@SettingsActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(14), dp(12), dp(14), dp(12))
                    addView(TextView(this@SettingsActivity).apply {
                        text = if (selected) "${choice.label}  ✓" else choice.label
                        textSize = 16f
                        setTextColor(TEXT)
                    })
                    addView(TextView(this@SettingsActivity).apply {
                        text = choice.description
                        textSize = 11.5f
                        setTextColor(MUTED)
                        setPadding(0, dp(2), 0, 0)
                    })
                    addView(TextView(this@SettingsActivity).apply {
                        text = "碎 㤇 㛪 𤻒"
                        typeface = provider.typefaceFor(choice.id)
                        textSize = 30f
                        includeFontPadding = true
                        setTextColor(TEXT)
                        setPadding(0, dp(7), 0, 0)
                    })
                })
                setOnClickListener {
                    if (provider.selectFont(choice.id)) recreate()
                }
            }, LinearLayout.LayoutParams(-1, -2).apply {
                setMargins(0, 0, 0, dp(8))
            })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Choose Nôm font")
            .setMessage("The selected font is used first. Missing glyphs still fall back automatically.")
            .setView(ScrollView(this).apply { addView(list) })
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showDataDetails(metadata: JSONObject) {
        val textView = TextView(this).apply {
            textSize = 14f
            setTextColor(TEXT)
            setPadding(dp(22), dp(8), dp(22), dp(8))
            text = buildString {
                append("Hội Bảo tồn Di sản chữ Nôm\n\n")
                append(metadata.getString("sourceUrl")); append("\n\n")
                append("Số bản ghi: "); append(metadata.getInt("extractedRowCount")); append('\n')
                append("Ngày lấy dữ liệu: "); append(metadata.getString("fetchedAt")); append("\n\n")
                append("CSV SHA-256\n"); append(metadata.getString("csvSha256"))
            }
            Linkify.addLinks(this, Linkify.WEB_URLS)
        }
        MaterialAlertDialogBuilder(this)
            .setTitle("Dictionary source")
            .setView(ScrollView(this).apply { addView(textView) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showGlyphDiagnostics(provider: NomTypefaceProvider) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(6), dp(10), dp(10))
        }
        try {
            val csv = assets.open("hannom_rcv_standard_nom.csv")
                .bufferedReader(Charsets.UTF_8)
                .use { it.readText() }
            val entries = NomCsvLoader.load(csv)
            val bmp = entries.first { entry ->
                entry.nomRaw.codePoints().allMatch { it <= 0xFFFF && !isVariationSelector(it) }
            }
            val supplementary = entries.first { entry ->
                entry.sourceRow != 874 &&
                    entry.nomRaw.codePoints().anyMatch { it > 0xFFFF && !isVariationSelector(it) } &&
                    !entry.nomRaw.codePoints().anyMatch { isVariationSelector(it) }
            }
            val fallback = entries.first { it.sourceRow == 874 }
            val variation = entries.first { it.nomRaw.codePoints().anyMatch(::isVariationSelector) }

            listOf(
                "BMP" to bmp,
                "Supplementary" to supplementary,
                "P1 fallback" to fallback,
                "Variation selector" to variation
            ).forEach { (label, entry) -> addFontTestRow(box, label, entry, provider) }

            val variationEntries = entries.filter { entry ->
                entry.nomRaw.codePoints().anyMatch(::isVariationSelector)
            }
            val passed = variationEntries.count { entry ->
                provider.resolve(entry.nomRaw, entry.sourceRow).hasGlyph
            }
            Log.i(TAG, "variation selector runtime summary fullStringHasGlyph=$passed total=${variationEntries.size}")
        } catch (error: Throwable) {
            Log.e(TAG, "Settings font tests failed", error)
            box.addView(TextView(this).apply {
                text = "Font test error: ${error.message}"
                setTextColor(DANGER)
            })
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Nôm glyph diagnostics")
            .setView(ScrollView(this).apply { addView(box) })
            .setPositiveButton("Done", null)
            .show()
    }

    private fun addFontTestRow(
        box: LinearLayout,
        label: String,
        entry: NomSourceEntry,
        provider: NomTypefaceProvider
    ) {
        val resolved = provider.resolve(entry.nomRaw, entry.sourceRow)
        box.addView(MaterialCardView(this).apply {
            setCardBackgroundColor(SURFACE_HIGH)
            radius = dp(14).toFloat()
            strokeColor = BORDER
            strokeWidth = dp(1)
            cardElevation = 0f
            addView(LinearLayout(this@SettingsActivity).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(12), dp(14), dp(12))
                addView(TextView(this@SettingsActivity).apply {
                    text = "$label · row ${entry.sourceRow} · ${resolved.fontLabel}"
                    textSize = 11f
                    setTextColor(MUTED)
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = entry.nomRaw
                    typeface = resolved.typeface
                    textSize = 34f
                    includeFontPadding = true
                    setTextColor(TEXT)
                    setPadding(0, dp(6), 0, dp(2))
                    post {
                        Log.i(
                            TAG,
                            "settings font test label=$label sourceRow=${entry.sourceRow} codePoints=${NomTypefaceProvider.codePoints(entry.nomRaw)} font=${resolved.fontLabel} paintHasGlyph=${paint.hasGlyph(entry.nomRaw)}"
                        )
                    }
                })
                addView(TextView(this@SettingsActivity).apply {
                    text = NomTypefaceProvider.codePoints(entry.nomRaw)
                    textSize = 10f
                    setTextColor(MUTED)
                })
            })
        }, LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 0, 0, dp(8)) })
    }

    private fun surfaceCard() = MaterialCardView(this).apply {
        setCardBackgroundColor(SURFACE)
        radius = dp(18).toFloat()
        strokeColor = BORDER
        strokeWidth = dp(1)
        cardElevation = 0f
    }

    private fun primaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 15f
        cornerRadius = dp(16)
        backgroundTintList = ColorStateList.valueOf(ACCENT)
        setTextColor(ON_ACCENT)
        setOnClickListener { click() }
    }

    private fun secondaryButton(label: String, click: () -> Unit) = MaterialButton(this).apply {
        text = label
        textSize = 14f
        cornerRadius = dp(14)
        backgroundTintList = ColorStateList.valueOf(SURFACE_HIGH)
        strokeColor = ColorStateList.valueOf(BORDER)
        strokeWidth = dp(1)
        setTextColor(TEXT)
        setOnClickListener { click() }
    }

    private fun eyebrow(value: String) = TextView(this).apply {
        text = value
        textSize = 11f
        letterSpacing = 0.08f
        setTextColor(ACCENT)
    }

    private fun title(value: String) = TextView(this).apply {
        text = value
        textSize = 21f
        setTextColor(TEXT)
        setPadding(0, dp(5), 0, 0)
    }

    private fun body(value: String) = TextView(this).apply {
        text = value
        textSize = 13f
        setTextColor(MUTED)
        setPadding(0, dp(5), 0, 0)
    }

    private fun space(height: Int) = TextView(this).apply {
        layoutParams = LinearLayout.LayoutParams(1, height)
    }

    private fun isVariationSelector(cp: Int) = cp in 0xFE00..0xFE0F || cp in 0xE0100..0xE01EF
    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        const val TAG = "NOM_IME"
        private val BACKGROUND = Color.rgb(10, 13, 18)
        private val SURFACE = Color.rgb(18, 24, 32)
        private val SURFACE_HIGH = Color.rgb(24, 33, 43)
        private val BORDER = Color.rgb(38, 50, 65)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(151, 163, 179)
        private val ACCENT = Color.rgb(111, 199, 255)
        private val ACCENT_DARK = Color.rgb(35, 74, 100)
        private val ON_ACCENT = Color.rgb(4, 17, 26)
        private val DANGER = Color.rgb(255, 120, 135)
    }
}
