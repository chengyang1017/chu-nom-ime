package com.example.chineseime.ui.curator

import android.graphics.Color
import android.os.Bundle
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.content.res.AppCompatResources
import com.example.chineseime.data.corpus.VerifiedPhraseImportPlan
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.progressindicator.LinearProgressIndicator
import com.google.android.material.textfield.TextInputLayout

class PhraseCuratorActivity : AppCompatActivity() {
    private var curatorView: PhraseCuratorView? = null
    private lateinit var backupController: PhraseBackupController

    private val exportBackupLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("application/json")
    ) { uri ->
        if (uri != null && ::backupController.isInitialized) {
            backupController.exportBackup(uri) { result ->
                result.onSuccess { count ->
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Backup saved")
                        .setMessage("Saved $count verified phrase(s) to the selected file.")
                        .setPositiveButton("Done", null)
                        .show()
                }.onFailure { error ->
                    showBackupError("Export failed", error)
                }
            }
        }
    }

    private val importBackupLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null && ::backupController.isInitialized) {
            backupController.inspectImport(uri) { result ->
                result.onSuccess(::showImportPlan)
                    .onFailure { error -> showBackupError("Import failed", error) }
            }
        }
    }

    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        backupController = PhraseBackupController(this)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BACKGROUND)
        }

        val toolbar = MaterialToolbar(this).apply {
            title = "Phrase Studio"
            setTitleTextColor(TEXT)
            setBackgroundColor(BACKGROUND)
            navigationIcon = AppCompatResources.getDrawable(
                this@PhraseCuratorActivity,
                androidx.appcompat.R.drawable.abc_ic_ab_back_material
            )
            navigationIcon?.setTint(TEXT)
            setNavigationOnClickListener { finish() }
            menu.add("Library").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add("Export backup").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
            menu.add("Import backup").setShowAsAction(MenuItem.SHOW_AS_ACTION_NEVER)
        }
        root.addView(toolbar, LinearLayout.LayoutParams(-1, -2))

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(18), dp(8), dp(18), dp(24))
        }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(BACKGROUND)
            addView(content)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))

        val bottomBar = MaterialCardView(this).apply {
            setCardBackgroundColor(SURFACE)
            radius = dp(18).toFloat()
            cardElevation = dp(6).toFloat()
            strokeColor = BORDER
            strokeWidth = dp(1)
        }
        root.addView(bottomBar, LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(dp(12), dp(4), dp(12), dp(10))
        })

        curatorView = PhraseCuratorView(this).also { it.attach(content, bottomBar) }
        polishStudio(content, bottomBar)

        toolbar.setOnMenuItemClickListener { item ->
            when (item.title?.toString()) {
                "Library" -> {
                    curatorView?.showSavedPhrases()
                    true
                }

                "Export backup" -> {
                    exportBackupLauncher.launch("verified_nom_phrases_backup.json")
                    true
                }

                "Import backup" -> {
                    importBackupLauncher.launch(arrayOf("application/json", "text/plain"))
                    true
                }

                else -> false
            }
        }

        setContentView(root)
    }

    private fun showImportPlan(plan: VerifiedPhraseImportPlan) {
        val message = buildString {
            append("Checked ${plan.totalCount} phrase(s).\n\n")
            append("New: ${plan.newCount}\n")
            append("Already present or duplicated: ${plan.duplicateCount}\n\n")
            append("Existing phrases on this device will not be deleted or overwritten.")
        }

        if (plan.newCount == 0) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Nothing new to restore")
                .setMessage(message)
                .setPositiveButton("Done", null)
                .show()
            return
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Restore backup?")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Import ${plan.newCount}") { _, _ ->
                backupController.applyImport(plan) { result ->
                    result.onSuccess { imported ->
                        MaterialAlertDialogBuilder(this)
                            .setTitle("Backup restored")
                            .setMessage(
                                "Imported ${imported.importedCount} phrase(s). " +
                                    "Skipped ${imported.skippedCount} existing or duplicate phrase(s)."
                            )
                            .setPositiveButton("Done", null)
                            .show()
                    }.onFailure { error ->
                        showBackupError("Restore failed", error)
                    }
                }
            }
            .show()
    }

    private fun showBackupError(title: String, error: Throwable) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(error.message ?: error.javaClass.simpleName)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun polishStudio(content: View, bottomBar: MaterialCardView) {
        normalizeStudioViews(content)

        (bottomBar.getChildAt(0) as? LinearLayout)?.setPadding(
            dp(14),
            dp(8),
            dp(10),
            dp(8)
        )
        polishBottomBar(bottomBar)
    }

    private fun normalizeStudioViews(view: View) {
        when (view) {
            is TextInputLayout -> {
                view.hintEnabled = false
                view.hint = null
                view.placeholderText = null
                view.boxBackgroundMode = TextInputLayout.BOX_BACKGROUND_OUTLINE
                view.boxBackgroundColor = SURFACE_HIGH
                view.boxStrokeColor = ACCENT
                view.boxStrokeWidth = dp(1)
                view.boxStrokeWidthFocused = dp(2)
                view.setBoxCornerRadii(
                    dp(14).toFloat(),
                    dp(14).toFloat(),
                    dp(14).toFloat(),
                    dp(14).toFloat()
                )
                view.editText?.apply {
                    hint = "Type Vietnamese · e.g. tôi yêu em"
                    setHintTextColor(MUTED)
                    background = null
                    setPadding(dp(12), dp(8), dp(12), dp(8))
                }
            }

            is LinearProgressIndicator -> {
                // Token chips and the explicit verified count communicate progress more
                // clearly on small phones. The stock indicator looked like a slider.
                view.visibility = View.GONE
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                normalizeStudioViews(view.getChildAt(index))
            }
        }
    }

    private fun polishBottomBar(view: View) {
        when (view) {
            is MaterialButton -> if (view.text?.toString() == "Save") {
                view.cornerRadius = dp(16)
                view.layoutParams = view.layoutParams.apply {
                    width = dp(96)
                    height = dp(48)
                }
            }

            is TextView -> when {
                view.text?.toString() == "CURRENT NÔM" -> view.textSize = 9f
                view.text?.toString() == "—" -> view.textSize = 24f
                view.text?.toString()?.contains("saved locally") == true -> view.textSize = 10f
            }
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                polishBottomBar(view.getChildAt(index))
            }
        }
    }

    override fun onDestroy() {
        curatorView?.close()
        if (::backupController.isInitialized) backupController.close()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    companion object {
        private val BACKGROUND = Color.rgb(10, 13, 18)
        private val SURFACE = Color.rgb(18, 24, 32)
        private val SURFACE_HIGH = Color.rgb(23, 31, 41)
        private val BORDER = Color.rgb(38, 50, 65)
        private val TEXT = Color.rgb(245, 247, 250)
        private val MUTED = Color.rgb(151, 163, 179)
        private val ACCENT = Color.rgb(111, 199, 255)
    }
}
