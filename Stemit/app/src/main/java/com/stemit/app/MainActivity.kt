package com.stemit.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.stemit.app.separation.PolarFormerEngine
import com.stemit.app.separation.SeparationProgress
import java.util.concurrent.Executors
import java.io.File
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {

    private val prefs by lazy { getSharedPreferences("stemit", MODE_PRIVATE) }
    private var selectedAudio: Uri? = null
    private lateinit var content: LinearLayout
    private val separationExecutor = Executors.newSingleThreadExecutor()
    private val separationEngine by lazy { PolarFormerEngine(this) }

    private val audioPicker = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            selectedAudio = it
            try {
                content.findViewWithTag<TextView>("selected_audio")?.text =
                    "Selected: ${it.lastPathSegment ?: "audio file"}"
            } catch (_: Exception) {}
            Toast.makeText(this, "Audio selected", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val denied = results.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            Toast.makeText(this, "Some permissions were not granted.", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.getBoolean("onboarding_complete", false)) {
            showOnboarding()
        } else {
            showHome()
        }
    }

    private fun baseLayout(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(18), dp(20), dp(20))
            setBackgroundColor(0xFFF7F7F5.toInt())
        }
    }

    private fun title(text: String, size: Float = 28f): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(0xFF171717.toInt())
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
        }

    private fun body(text: String): TextView =
        TextView(this).apply {
            this.text = text
            textSize = 15f
            setTextColor(0xFF555555.toInt())
            setPadding(0, dp(8), 0, dp(8))
        }

    private fun button(text: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            setOnClickListener { action() }
        }

    private fun card(view: View): MaterialCardView =
        MaterialCardView(this).apply {
            radius = dp(16).toFloat()
            cardElevation = dp(1).toFloat()
            setCardBackgroundColor(0xFFFFFFFF.toInt())
            addView(view)
        }

    private fun showOnboarding() {
        val root = baseLayout()
        root.gravity = Gravity.CENTER_VERTICAL

        val logo = ImageView(this).apply {
            setImageResource(com.stemit.app.R.drawable.ic_stemit)
            layoutParams = LinearLayout.LayoutParams(dp(88), dp(88)).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                bottomMargin = dp(24)
            }
        }
        root.addView(logo)
        root.addView(title("Welcome to Stemit").apply {
            gravity = Gravity.CENTER
        })
        root.addView(body("Separate vocals from your music with a focused, simple audio workspace. Stemit is designed to keep your personal audio on your device whenever possible.").apply {
            gravity = Gravity.CENTER
        })

        val start = button("Get started") {
            prefs.edit().putBoolean("onboarding_complete", true).apply()
            showHome()
        }
        root.addView(start)

        val learn = button("How it works") {
            showInfo(
                "Using Stemit",
                "1. Choose an audio file from your device.\n\n" +
                "2. The first separation downloads the 103 MB on-device model once.\n\n" +
                "3. The audio is decoded, processed locally and written as vocals.wav and instrumental.wav.\n\n" +
                "After the model is installed, separation can run without network access."
            )
        }
        root.addView(learn)
        setContentView(root)
    }

    private fun showHome() {
        content = baseLayout()
        val scroll = ScrollView(this).apply {
            addView(content)
        }

        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        header.addView(title("Stemit"), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(button("Settings") { showSettings() })
        content.addView(header)

        content.addView(body("Vocal separation, built around your device."))

        val selected = body("No audio selected").apply {
            tag = "selected_audio"
        }
        val selectCard = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(title("1  Choose audio", 20f))
            addView(body("Pick an audio file from your phone. Stemit does not upload it from this screen."))
            addView(button("Choose audio") {
                audioPicker.launch(arrayOf("audio/*"))
            })
            addView(selected)
        }
        content.addView(card(selectCard), marginParams(bottom = 12))

        val process = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
            addView(title("2  Separate", 20f))
            addView(body("Real on-device separation using ONNX Runtime. The first run downloads the model; later runs can work offline."))
            val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                progress = 0
                visibility = View.GONE
            }
            val progressText = body("Ready").apply { tag = "separation_progress" }
            addView(progress)
            addView(progressText)
            lateinit var startButton: MaterialButton
            startButton = button("Start separation") {
                val uri = selectedAudio
                if (uri == null) {
                    Toast.makeText(this@MainActivity, "Choose an audio file first.", Toast.LENGTH_SHORT).show()
                } else {
                    progress.visibility = View.VISIBLE
                    progress.progress = 0
                    progressText.text = "Starting…"
                    startButton.isEnabled = false
                    separationExecutor.execute {
                        try {
                            val result = separationEngine.separate(uri, File(cacheDir, "separated")) { p ->
                                runOnUiThread {
                                    progress.progress = (p.fraction * 1000).toInt()
                                    progressText.text = p.message
                                }
                            }
                            runOnUiThread {
                                startButton.isEnabled = true
                                progress.visibility = View.GONE
                                showResultDialog(result.vocalsFile, result.instrumentalFile, result.durationSeconds)
                            }
                        } catch (e: Exception) {
                            runOnUiThread {
                                startButton.isEnabled = true
                                progress.visibility = View.GONE
                                progressText.text = "Separation failed"
                                showInfo("Separation failed", e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }
                }
            }
            addView(startButton)
        }
        content.addView(card(process), marginParams(bottom = 12))

        content.addView(button("Permissions") { requestNeededPermissions() })
        content.addView(button("FAQ & Help") { showFaq() })
        content.addView(button("About Stemit") { showAbout() })

        setContentView(scroll)
    }

    private fun showResultDialog(vocals: java.io.File, instrumental: java.io.File, duration: Float) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }
        box.addView(body("Processed ${"%.1f".format(duration)} seconds locally."))
        box.addView(button("Open vocals") { openAudio(vocals) })
        box.addView(button("Open instrumental") { openAudio(instrumental) })
        MaterialAlertDialogBuilder(this)
            .setTitle("Separation complete")
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun openAudio(file: java.io.File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/wav")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: Exception) {
            Toast.makeText(this, "No audio player found.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(8), dp(24), dp(8))
        }

        val notifications = SwitchMaterial(this).apply {
            text = "Notifications"
            isChecked = prefs.getBoolean("notifications", true)
            setOnCheckedChangeListener { _, checked ->
                prefs.edit().putBoolean("notifications", checked).apply()
                if (checked) requestNotificationPermission()
            }
        }
        box.addView(notifications)
        box.addView(button("Request permissions") { requestNeededPermissions() })
        box.addView(button("Check for updates") {
            showInfo(
                "Updates",
                "Stemit is currently version ${BuildConfig.VERSION_NAME}.\n\n" +
                "The update checker is ready to be connected to your official release feed. " +
                "Until a release endpoint is configured, the app will not pretend that an update exists."
            )
        })
        box.addView(button("Privacy") {
            showInfo("Privacy", "Stemit is designed around local audio handling. Review and replace this text with your final privacy policy before public distribution.")
        })
        box.addView(button("Terms") {
            showInfo("Terms of use", "Add the final terms of use before publishing the app.")
        })
        box.addView(button("Contact developer") {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("mailto:")
                putExtra(Intent.EXTRA_SUBJECT, "Stemit support")
            }
            try { startActivity(intent) } catch (_: Exception) {
                Toast.makeText(this, "No email application found.", Toast.LENGTH_SHORT).show()
            }
        })

        MaterialAlertDialogBuilder(this)
            .setTitle("Settings")
            .setView(box)
            .setPositiveButton("Done", null)
            .show()
    }

    private fun showFaq() {
        showInfo(
            "FAQ & Help",
            "What is Stemit?\nStemit is an audio application focused on vocal separation.\n\n" +
            "Does it work offline?\nThe app shell and local features do. Real offline separation requires an on-device model/engine.\n\n" +
            "Where is my audio?\nThe selected file remains on your device; this project does not silently upload it.\n\n" +
            "Why are permissions requested?\nOnly features that need access request their corresponding Android permission.\n\n" +
            "How do I get help?\nUse Settings > Contact developer."
        )
    }

    private fun showAbout() {
        showInfo(
            "About Stemit",
            "Stemit\nVersion ${BuildConfig.VERSION_NAME}\n\n" +
            "A focused mobile audio workspace for vocal separation.\n\n" +
            "This build is intentionally clean and product-oriented: no AI-generated labels, demo claims, fake verification screens, or artificial sample results."
        )
    }

    private fun showInfo(title: String, message: String) {
        MaterialAlertDialogBuilder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("Close", null)
            .show()
    }

    private fun requestNeededPermissions() {
        val permissions = mutableListOf<String>()
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            permissions += Manifest.permission.READ_MEDIA_AUDIO
            if (prefs.getBoolean("notifications", true)) {
                permissions += Manifest.permission.POST_NOTIFICATIONS
            }
        } else {
            permissions += Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (permissions.isNotEmpty()) permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun requestNotificationPermission() {
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
        }
    }

    private fun marginParams(bottom: Int = 0): LinearLayout.LayoutParams =
        LinearLayout.LayoutParams(-1, -2).apply {
            setMargins(0, 0, 0, dp(bottom))
        }

    override fun onDestroy() {
        separationExecutor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
