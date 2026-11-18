package com.stemit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.FileProvider
import androidx.core.app.NotificationCompat
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.stemit.app.separation.PolarFormerEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.concurrent.Executors
import org.json.JSONObject

/**
 * Stemit has one job: turn one audio track into vocals and instrumental stems.
 * Navigation is intentionally limited to Home, Library and Settings.
 */
class DashboardActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("stemit", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val engine by lazy { PolarFormerEngine(this) }
    private var selectedUri: Uri? = null
    private var selectedName = "No audio selected"
    private var isProcessing = false
    private var progressValue = 0
    private var progressMessage = "Choose a song to begin"
    private var exportFile: File? = null
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private var sourcePreviewPrepared = false
    private var sourcePreviewButton: MaterialButton? = null
    private var activeTab = "Home"
    private lateinit var page: LinearLayout
    private var progressBar: ProgressBar? = null
    private var progressSpinner: ProgressBar? = null
    private var progressLabel: TextView? = null
    private var progressPercentLabel: TextView? = null
    private var separateButton: MaterialButton? = null
    private var resultVocalsFile: File? = null
    private var resultInstrumentalFile: File? = null
    private var resultDurationSeconds: Float = 0f
    private var resultSourceName: String = ""

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            stopPlayback()
            selectedUri = uri
            selectedName = displayName(uri)
            progressValue = 0
            progressMessage = "Ready to separate"
            resultVocalsFile = null
            resultInstrumentalFile = null
            render("Home")
        }
    }

    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/wav")) { uri ->
        val source = exportFile
        if (uri != null && source != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { output ->
                    source.inputStream().use { input -> input.copyTo(output) }
                } ?: error("Could not create the selected file.")
                Toast.makeText(this, "Audio saved.", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                showInfo("Save failed", e.message ?: "The audio could not be saved.")
            }
        }
        exportFile = null
    }

    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        prefs.edit().putBoolean("notifications", granted).apply()
        if (!granted) Toast.makeText(this, "Notifications remain off.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.getBoolean("onboarding_complete", false)) showWelcome() else render("Home")
    }

    private fun isDark() = prefs.getBoolean("dark_theme", false)
    private fun colorBackground() = if (isDark()) 0xFF101114.toInt() else 0xFFF6F6F4.toInt()
    private fun colorSurface() = if (isDark()) 0xFF1B1D21.toInt() else 0xFFFFFFFF.toInt()
    private fun colorText() = if (isDark()) 0xFFF5F5F2.toInt() else 0xFF191A1C.toInt()
    private fun colorMuted() = if (isDark()) 0xFFADB1B8.toInt() else 0xFF6B7077.toInt()
    private fun colorAccent() = if (isDark()) 0xFF8FD9D1.toInt() else 0xFF087F78.toInt()
    private fun colorBorder() = if (isDark()) 0xFF30343A.toInt() else 0xFFE5E6E3.toInt()
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private fun text(value: String, size: Float = 14f, color: Int = colorText()) = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        includeFontPadding = false
    }

    private fun heading(value: String, size: Float = 25f) = text(value, size).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun paragraph(value: String) = text(value, 14f, colorMuted()).apply {
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun vertical(spacing: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (spacing > 0) setPadding(dp(spacing), dp(spacing), dp(spacing), dp(spacing))
    }

    private fun card(content: View) = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = 0f
        strokeWidth = dp(1)
        strokeColor = colorBorder()
        setCardBackgroundColor(colorSurface())
        addView(content)
    }

    private fun button(caption: String, primary: Boolean = false, onClick: () -> Unit) =
        MaterialButton(this).apply {
            text = caption
            isAllCaps = false
            textSize = 15f
            minHeight = dp(50)
            cornerRadius = dp(14)
            insetTop = 0
            insetBottom = 0
            backgroundTintList = ColorStateList.valueOf(if (primary) colorAccent() else colorSurface())
            setTextColor(if (primary) 0xFFFFFFFF.toInt() else colorText())
            if (!primary) {
                strokeWidth = dp(1)
                strokeColor = ColorStateList.valueOf(colorBorder())
            }
            setOnClickListener { onClick() }
        }

    private fun margin(top: Int = 0, bottom: Int = 12) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }

    private fun showWelcome() {
        val root = vertical(28).apply {
            gravity = Gravity.CENTER
            setBackgroundColor(colorBackground())
        }
        root.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_stemit)
            contentDescription = "Stemit"
            scaleType = ImageView.ScaleType.FIT_CENTER
        }, LinearLayout.LayoutParams(dp(76), dp(76)).apply { gravity = Gravity.CENTER })
        root.addView(heading("Stemit", 30f).apply { gravity = Gravity.CENTER; setPadding(0, dp(18), 0, dp(6)) })
        root.addView(paragraph("Separate a song into vocals and instrumental audio. Nothing else gets in the way.").apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
        })
        root.addView(paragraph("The first use downloads the separation model (about 103 MiB). Your selected audio is processed on this device.").apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            setPadding(0, dp(12), 0, dp(22))
        })
        root.addView(button("Continue", true) {
            prefs.edit().putBoolean("onboarding_complete", true).apply()
            render("Home")
        }, LinearLayout.LayoutParams(-1, -2))
        setContentView(root)
        applyRootInsets(root)
        applySystemBars()
    }

    private fun render(tab: String) {
        activeTab = tab
        applySystemBars()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(colorBackground())
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(22), dp(16), dp(22), dp(14))
        }
        header.addView(ImageView(this).apply {
            setImageResource(R.drawable.ic_stemit)
            contentDescription = "Stemit"
        }, LinearLayout.LayoutParams(dp(34), dp(34)))
        header.addView(heading("Stemit", 21f), LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginStart = dp(10)
        })
        root.addView(header)

        page = vertical().apply { setPadding(dp(20), dp(8), dp(20), dp(24)) }
        val scroll = ScrollView(this).apply {
            isFillViewport = true
            clipToPadding = false
            addView(page)
        }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomNavigation())
        setContentView(root)
        applyRootInsets(root)

        when (tab) {
            "Home" -> home()
            "Library" -> library()
            "Settings" -> settings()
            "Processing" -> processingScreen()
            "Results" -> resultsScreen()
        }
        page.alpha = 0f
        page.translationY = dp(5).toFloat()
        page.animate().alpha(1f).translationY(0f).setDuration(180).start()
    }

    private fun applySystemBars() {
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
        if (Build.VERSION.SDK_INT >= 29) {
            window.isStatusBarContrastEnforced = false
            window.isNavigationBarContrastEnforced = false
        }
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = !isDark()
            isAppearanceLightNavigationBars = !isDark()
        }
    }

    private fun applyRootInsets(root: View) {
        ViewCompat.setOnApplyWindowInsetsListener(root) { view, insets ->
            val systemBars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            view.setPadding(view.paddingLeft, systemBars.top, view.paddingRight, systemBars.bottom)
            insets
        }
        ViewCompat.requestApplyInsets(root)
    }

    private fun bottomNavigation(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(12), dp(8), dp(12), dp(8))
            setBackgroundColor(colorSurface())
            contentDescription = "Main navigation"
        }
        listOf(
            Triple("Home", android.R.drawable.ic_menu_view, "Separate audio"),
            Triple("Library", android.R.drawable.ic_menu_agenda, "Saved audio"),
            Triple("Settings", android.R.drawable.ic_menu_preferences, "App settings")
        ).forEach { (name, icon, description) ->
            val selected = activeTab == name
            val tint = if (selected) colorAccent() else colorMuted()
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                contentDescription = description
                setPadding(dp(8), dp(5), dp(8), dp(4))
                setOnClickListener { if (!isProcessing) render(name) }
            }
            item.isEnabled = !isProcessing
            item.alpha = if (isProcessing) .45f else 1f
            item.addView(ImageView(this).apply {
                setImageResource(icon)
                setColorFilter(tint)
                contentDescription = description
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(text(name, 11f, tint).apply {
                gravity = Gravity.CENTER
                if (selected) typeface = Typeface.DEFAULT_BOLD
                setPadding(0, dp(4), 0, 0)
            })
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    private fun home() {
        page.addView(heading("Separate your track", 27f))
        page.addView(paragraph("Create two clean starting points from one song: vocals and instrumental."), margin(7, 20))

        val choosePanel = vertical(18)
        choosePanel.addView(text("SOURCE AUDIO", 11f, colorMuted()).apply {
            letterSpacing = .08f
            typeface = Typeface.DEFAULT_BOLD
        })
        choosePanel.addView(heading(if (selectedUri == null) "Choose a song" else selectedName, 17f))
        choosePanel.addView(paragraph(if (selectedUri == null)
            "Select an audio file from your device. MP3, WAV, M4A and other Android-supported formats are accepted."
        else "Selected and ready to process."))
        choosePanel.addView(button(if (selectedUri == null) "Choose audio file" else "Change audio file") {
            picker.launch(arrayOf("audio/*"))
        })
        sourcePreviewButton = if (selectedUri != null) button(
            if (player != null && playingFile == null && player?.isPlaying == true) "Pause preview" else "Preview selected audio"
        ) { previewSelectedAudio() } else null
        sourcePreviewButton?.let { choosePanel.addView(it, margin(8, 0)) }
        page.addView(card(choosePanel), margin(0, 14))

        val processPanel = vertical(18)
        processPanel.addView(heading("Vocal separation", 18f))
        processPanel.addView(paragraph("Stemit separates the lead vocal from the backing instrumental. The original file is kept unchanged."))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = progressValue
            visibility = if (isProcessing) View.VISIBLE else View.GONE
        }
        processPanel.addView(progressBar, margin(8, 8))
        progressLabel = paragraph(progressMessage).apply { setPadding(0, dp(2), 0, dp(8)) }
        processPanel.addView(progressLabel)
        separateButton = button(if (isProcessing) "Separating…" else "Separate audio", true) {
            startSeparation()
        }.apply {
            isEnabled = selectedUri != null && !isProcessing
            alpha = if (isEnabled) 1f else .55f
        }
        processPanel.addView(separateButton)
        page.addView(card(processPanel), margin(0, 14))

        val modelText = if (engine.modelInstalled()) {
            "Model downloaded · Ready for on-device processing"
        } else {
            "First use downloads the separation model (about 103 MiB)"
        }
        page.addView(paragraph(modelText).apply {
            textSize = 12f
            setPadding(dp(2), dp(2), dp(2), dp(2))
        })
    }

    private fun startSeparation() {
        val uri = selectedUri ?: return
        if (isProcessing) return
        isProcessing = true
        progressValue = 0
        progressMessage = "Preparing audio…"
        render("Processing")

        worker.execute {
            try {
                val tempDir = File(cacheDir, "separated").apply { mkdirs() }
                val result = engine.separate(uri, tempDir) { update ->
                    runOnUiThread {
                        progressValue = (update.fraction * 100).toInt().coerceIn(0, 100)
                        progressMessage = update.message
                        progressBar?.progress = progressValue
                        progressLabel?.text = progressMessage
                        progressLabel?.contentDescription = progressMessage
                        progressPercentLabel?.text = "$progressValue%"
                    }
                }
                val library = File(filesDir, "library").apply { mkdirs() }
                val stamp = System.currentTimeMillis()
                val vocals = File(library, "vocals-$stamp.wav")
                val instrumental = File(library, "instrumental-$stamp.wav")
                result.vocalsFile.copyTo(vocals, overwrite = true)
                result.instrumentalFile.copyTo(instrumental, overwrite = true)
                runOnUiThread {
                    isProcessing = false
                    progressValue = 100
                    progressMessage = "Separation complete"
                    showResults(vocals, instrumental, result.durationSeconds)
                    sendCompletionNotification(vocals, instrumental)
                }
            } catch (e: OutOfMemoryError) {
                runOnUiThread {
                    isProcessing = false
                    progressMessage = "This device ran out of memory before the separation finished."
                    render("Home")
                    showInfo("Separation stopped", "Stemit stopped before saving incomplete stems. Close other apps, restart your phone, and try a shorter audio file. Your original audio is unchanged.")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    isProcessing = false
                    progressMessage = "Separation failed"
                    render("Home")
                    showInfo("Could not separate this track", e.message ?: e.javaClass.simpleName)
                }
            }
        }
    }

    private fun showResults(vocals: File, instrumental: File, duration: Float) {
        resultVocalsFile = vocals
        resultInstrumentalFile = instrumental
        resultDurationSeconds = duration
        resultSourceName = selectedName
        render("Results")
    }

    private fun processingScreen() {
        page.addView(text("STEMIT  /  PROCESSING", 11f, colorAccent()).apply {
            letterSpacing = .08f
            typeface = Typeface.DEFAULT_BOLD
        })
        page.addView(heading("Creating your stems", 27f), margin(8, 6))
        page.addView(paragraph("Keep Stemit open while the model separates the vocal and instrumental tracks."), margin(0, 20))

        val panel = vertical(20)
        progressSpinner = ProgressBar(this).apply {
            isIndeterminate = true
            contentDescription = "Audio separation is running"
        }
        panel.addView(progressSpinner, LinearLayout.LayoutParams(dp(44), dp(44)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
        })
        progressLabel = paragraph(progressMessage).apply {
            gravity = Gravity.CENTER
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            contentDescription = progressMessage
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        }
        panel.addView(progressLabel, margin(10, 8))
        progressBar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = progressValue
            isIndeterminate = false
        }
        panel.addView(progressBar, margin(0, 6))
        progressPercentLabel = text("$progressValue%", 13f, colorMuted()).apply {
            gravity = Gravity.END
        }
        panel.addView(progressPercentLabel)
        page.addView(card(panel), margin(0, 16))
        page.addView(paragraph("Your original audio is never overwritten. Stems are saved to your private Library only after processing succeeds."))
    }

    private fun resultsScreen() {
        val vocals = resultVocalsFile ?: run {
            page.addView(heading("No results yet", 22f))
            page.addView(button("Back to Home", true) { render("Home") }, margin(12, 0))
            return
        }
        val instrumental = resultInstrumentalFile ?: return
        page.addView(text("STEMIT  /  RESULTS", 11f, colorAccent()).apply {
            letterSpacing = .08f
            typeface = Typeface.DEFAULT_BOLD
        })
        page.addView(heading("Your stems are ready", 27f), margin(8, 6))
        page.addView(paragraph(resultSourceName), margin(0, 4))
        page.addView(paragraph(String.format(Locale.getDefault(), "Separated %.1f seconds of audio. Your two WAV files are saved in this device's private Library.", resultDurationSeconds)), margin(0, 18))
        addStemResultCard("Vocals", "The lead vocal separated from the backing track.", vocals)
        addStemResultCard("Instrumental", "The backing track with the vocal removed.", instrumental)
        page.addView(button("View Library", true) { render("Library") }, margin(6, 10))
        page.addView(button("Separate another track") {
            selectedUri = null
            selectedName = "No audio selected"
            progressValue = 0
            progressMessage = "Choose a song to begin"
            render("Home")
        })
    }

    private fun addStemResultCard(title: String, description: String, file: File) {
        val panel = vertical(16)
        panel.addView(heading(title, 18f))
        panel.addView(paragraph(description), margin(4, 4))
        panel.addView(text(android.text.format.Formatter.formatShortFileSize(this, file.length()), 12f, colorMuted()))
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        actions.addView(button("Play", true) { play(file) }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(6)
        })
        actions.addView(button("Share") { share(file) }, LinearLayout.LayoutParams(0, -2, 1f).apply {
            marginEnd = dp(6)
        })
        actions.addView(button("Save") {
            exportFile = file
            savePicker.launch(file.name)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        panel.addView(actions, margin(10, 0))
        page.addView(card(panel), margin(0, 12))
    }

    private fun library() {
        page.addView(heading("Library", 27f))
        page.addView(paragraph("Your separated stems, saved on this device."), margin(7, 18))
        val files = libraryFiles()
        if (files.isEmpty()) {
            val empty = vertical(20)
            empty.addView(heading("No stems yet", 18f))
            empty.addView(paragraph("When you separate a song, the vocals and instrumental files will appear here."))
            empty.addView(button("Choose a song", true) { render("Home") }, margin(12, 0))
            page.addView(card(empty))
        } else {
            files.forEach { file ->
                val panel = vertical(14)
                val isVocal = file.name.startsWith("vocals-", true)
                panel.addView(heading(if (isVocal) "Vocals" else if (file.name.startsWith("instrumental-", true)) "Instrumental" else file.name, 16f))
                panel.addView(paragraph(file.name), margin(4, 2))
                panel.addView(text(android.text.format.Formatter.formatShortFileSize(this, file.length()), 12f, colorMuted()))
                val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                actions.addView(button(if (playingFile == file && player?.isPlaying == true) "Pause" else "Play") {
                    togglePlayback(file)
                }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                actions.addView(button("Share") { share(file) }, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = dp(6) })
                actions.addView(button("Save") { exportFile = file; savePicker.launch(file.name) },
                    LinearLayout.LayoutParams(0, -2, 1f))
                panel.addView(actions, margin(10, 0))
                panel.addView(button("Delete from Library", false) {
                    MaterialAlertDialogBuilder(this)
                        .setTitle("Delete this stem?")
                        .setMessage("This removes the saved copy from Stemit. Your original source audio is not affected.")
                        .setNegativeButton("Cancel", null)
                        .setPositiveButton("Delete") { _, _ ->
                            if (playingFile == file) stopPlayback()
                            file.delete()
                            render("Library")
                        }.show()
                }, margin(8, 0))
                page.addView(card(panel), margin(0, 10))
            }
        }
    }

    private fun settings() {
        page.addView(heading("Settings", 27f))
        page.addView(paragraph("Only the controls needed to run and manage Stemit."), margin(7, 18))

        val appearance = vertical(16)
        appearance.addView(heading("Appearance", 17f))
        appearance.addView(SwitchMaterial(this).apply {
            text = "Dark appearance"
            setTextColor(colorText())
            isChecked = isDark()
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("dark_theme", enabled).apply()
                AppCompatDelegate.setDefaultNightMode(if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
        })
        page.addView(card(appearance), margin(0, 10))

        val storage = vertical(16)
        storage.addView(heading("Model and storage", 17f))
        storage.addView(paragraph("Separation model: " + if (engine.modelInstalled()) "Downloaded" else "Not downloaded"))
        storage.addView(button("Remove downloaded model", false) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Remove model?")
                .setMessage("The next separation will download the model again.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Remove") { _, _ ->
                    val removed = engine.removeModel()
                    showInfo("Model storage", if (removed) "Model removed." else "No model was removed.")
                    render("Settings")
                }.show()
        }, margin(8, 0))
        storage.addView(button("Clear temporary files", false) {
            MaterialAlertDialogBuilder(this)
                .setTitle("Clear temporary files?")
                .setMessage("This removes temporary processing data only. Your saved stems will remain in the Library.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    val temp = File(cacheDir, "separated")
                    temp.listFiles()?.forEach { it.deleteRecursively() }
                    showInfo("Temporary files", "Temporary processing files cleared.")
                }.show()
        }, margin(8, 0))
        page.addView(card(storage), margin(0, 10))

        val notifications = vertical(16)
        notifications.addView(heading("Completion alerts", 17f))
        notifications.addView(SwitchMaterial(this).apply {
            text = "Notify me when stems are ready"
            setTextColor(colorText())
            isChecked = prefs.getBoolean("notifications", false)
            setOnCheckedChangeListener { _, enabled ->
                if (enabled && Build.VERSION.SDK_INT >= 33 &&
                    ContextCompat.checkSelfPermission(this@DashboardActivity, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                ) {
                    notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                } else {
                    prefs.edit().putBoolean("notifications", enabled).apply()
                }
            }
        })
        page.addView(card(notifications), margin(0, 10))

        val help = vertical(16)
        help.addView(heading("Help", 17f))
        help.addView(button("How Stemit works", false) {
            showInfo("How Stemit works", "Choose one audio file, run separation, then play or save the two resulting WAV stems. Your original audio is not changed. The first run downloads the model; later processing is designed to run on this device.")
        }, margin(8, 0))
        help.addView(button("Privacy", false) {
            showInfo("Privacy", "Your selected audio is processed on this device. Stemit does not upload your music to a Stemit server. The separation model is downloaded once and optional update checks require internet access.")
        }, margin(8, 0))
        help.addView(button("Contact developer", false) {
            try {
                startActivity(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:kastineaugak@gmail.com?subject=Stemit%20support")))
            } catch (_: Exception) {
                showInfo("Email unavailable", "Contact kastineaugak@gmail.com")
            }
        }, margin(8, 0))
        help.addView(button("Check for updates", false) { checkUpdates() }, margin(8, 0))
        page.addView(card(help), margin(0, 10))
        page.addView(paragraph("Stemit · " + BuildConfig.VERSION_NAME).apply {
            textSize = 12f
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, dp(8))
        })
    }

    private fun libraryFiles(): List<File> {
        val dir = File(filesDir, "library").apply { mkdirs() }
        return dir.listFiles()?.filter { it.isFile && it.extension.equals("wav", true) }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun displayName(uri: Uri): String {
        return contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) it.getString(0) else null
        } ?: uri.lastPathSegment?.substringAfterLast('/') ?: "Selected audio"
    }

    private fun previewSelectedAudio() {
        val uri = selectedUri ?: return
        val current = player
        if (current != null && playingFile == null) {
            try {
                if (current.isPlaying) {
                    current.pause()
                    sourcePreviewButton?.text = "Resume preview"
                } else if (sourcePreviewPrepared) {
                    current.start()
                    sourcePreviewButton?.text = "Pause preview"
                }
            } catch (_: IllegalStateException) {
                stopPlayback()
                sourcePreviewButton?.text = "Preview selected audio"
            }
            return
        }
        stopPlayback()
        try {
            val media = MediaPlayer()
            player = media
            playingFile = null
            sourcePreviewPrepared = false
            sourcePreviewButton?.isEnabled = false
            media.setDataSource(this, uri)
            media.setOnPreparedListener {
                sourcePreviewPrepared = true
                sourcePreviewButton?.isEnabled = true
                it.start()
                sourcePreviewButton?.text = "Pause preview"
            }
            media.setOnCompletionListener {
                stopPlayback()
                sourcePreviewButton?.text = "Preview selected audio"
            }
            media.setOnErrorListener { _, _, _ ->
                stopPlayback()
                sourcePreviewButton?.isEnabled = true
                sourcePreviewButton?.text = "Preview selected audio"
                showInfo("Preview unavailable", "This audio file could not be previewed on this device.")
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            sourcePreviewButton?.isEnabled = true
            sourcePreviewButton?.text = "Preview selected audio"
            showInfo("Preview unavailable", "This audio file could not be previewed on this device.")
        }
    }

    private fun togglePlayback(file: File) {
        if (playingFile == file && player != null) {
            if (player?.isPlaying == true) player?.pause() else player?.start()
            render("Library")
            return
        }
        play(file)
    }

    private fun play(file: File) {
        stopPlayback()
        try {
            val media = MediaPlayer()
            player = media
            playingFile = file
            media.setDataSource(file.absolutePath)
            media.setOnPreparedListener {
                it.start()
                if (activeTab == "Library" && !isFinishing) render("Library")
            }
            media.setOnCompletionListener {
                stopPlayback()
                if (activeTab == "Library" && !isFinishing) render("Library")
            }
            media.setOnErrorListener { _, _, _ ->
                stopPlayback()
                if (!isFinishing) showInfo("Playback unavailable", "This audio file could not be played on this device.")
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            stopPlayback()
            showInfo("Playback unavailable", "This audio file could not be played on this device.")
        }
    }

    private fun stopPlayback() {
        try { player?.stop() } catch (_: Exception) {}
        player?.release()
        player = null
        playingFile = null
        sourcePreviewPrepared = false
    }

    private fun share(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/wav"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(intent, "Share audio"))
        } catch (_: Exception) {
            showInfo("Unable to share", "No compatible app could receive this audio file.")
        }
    }

    private fun sendCompletionNotification(vocals: File, instrumental: File) {
        if (!prefs.getBoolean("notifications", false)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            android.app.NotificationChannel("stemit_results", "Separation results", android.app.NotificationManager.IMPORTANCE_DEFAULT)
        )
        val pending = android.app.PendingIntent.getActivity(
            this, 12, Intent(this, DashboardActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(12, NotificationCompat.Builder(this, "stemit_results")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Your stems are ready")
            .setContentText("Vocals and instrumental files are saved in your Library.")
            .setContentIntent(pending).setAutoCancel(true).build())
    }

    private fun checkUpdates() {
        Toast.makeText(this, "Checking for updates…", Toast.LENGTH_SHORT).show()
        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.github.com/repos/Augak/Stemit/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (connection.responseCode == 404) {
                    runOnUiThread { showInfo("No release yet", "There is no official Stemit release published yet.") }
                } else {
                    check(connection.responseCode in 200..299) { "Update check returned HTTP ${connection.responseCode}" }
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name", "latest")
                    val pageUrl = json.optString("html_url", "https://github.com/Augak/Stemit/releases")
                    runOnUiThread {
                        if (compareVersions(tag, BuildConfig.VERSION_NAME) <= 0) {
                            showInfo("No newer release", "Installed version: ${BuildConfig.VERSION_NAME}\nLatest published version: $tag")
                        } else {
                            MaterialAlertDialogBuilder(this).setTitle("Update available")
                                .setMessage("A newer version ($tag) is available.")
                                .setPositiveButton("View release") { _, _ -> openUrl(pageUrl) }
                                .setNegativeButton("Later", null).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showInfo("Update check failed", e.message ?: "Try again later.") }
            } finally {
                connection?.disconnect()
            }
        }
    }

    private fun compareVersions(left: String, right: String): Int {
        val a = left.trimStart('v').substringBefore('-').split('.')
        val b = right.trimStart('v').substringBefore('-').split('.')
        for (index in 0 until maxOf(a.size, b.size)) {
            val av = a.getOrNull(index)?.toIntOrNull() ?: 0
            val bv = b.getOrNull(index)?.toIntOrNull() ?: 0
            if (av != bv) return av.compareTo(bv)
        }
        return 0
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { showInfo("Unable to open link", url) }
    }

    private fun showInfo(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        stopPlayback()
        super.onDestroy()
    }
}
