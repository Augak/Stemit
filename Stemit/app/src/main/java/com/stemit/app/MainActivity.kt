package com.stemit.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.stemit.app.separation.PolarFormerEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial

class MainActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("stemit", MODE_PRIVATE) }
    private var selectedAudio: Uri? = null
    private lateinit var content: LinearLayout
    private val executor = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val engine by lazy { PolarFormerEngine(this) }

    private val audioPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            selectedAudio = it
            content.findViewWithTag<TextView>("selected_audio")?.text =
                it.lastPathSegment?.substringAfterLast('/') ?: "Audio selected"
            Toast.makeText(this, "Audio ready", Toast.LENGTH_SHORT).show()
        }
    }

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted) Toast.makeText(this, "Notifications remain off. You can enable them in Android Settings.", Toast.LENGTH_LONG).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        showSplash()
        ui.postDelayed({
            if (isFinishing || isDestroyed) return@postDelayed
            if (!prefs.getBoolean("onboarding_complete", false)) showOnboarding() else showHome()
            requestFirstRunPermission()
        }, 850)
    }

    private fun showSplash() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(dp(28), dp(24), dp(28), dp(24))
            setBackgroundColor(0xFF101D2B.toInt())
        }
        root.addView(TextView(this).apply {
            text = "S"
            textSize = 52f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-black", Typeface.NORMAL)
            setTextColor(0xFF62D6D0.toInt())
        })
        root.addView(TextView(this).apply {
            text = "STEMIT"
            textSize = 27f
            letterSpacing = .18f
            gravity = Gravity.CENTER
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            setTextColor(0xFFFFFFFF.toInt())
        })
        root.addView(TextView(this).apply {
            text = "Your sound. Your stems."
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(0xFFB9C7D4.toInt())
            setPadding(0, dp(8), 0, dp(26))
        })
        root.addView(ProgressBar(this).apply { isIndeterminate = true })
        setContentView(root)
    }

    private fun baseLayout(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(18), dp(18), dp(18), dp(26))
        setBackgroundColor(0xFFF3F6F8.toInt())
    }

    private fun heading(text: String, size: Float = 25f): TextView = TextView(this).apply {
        this.text = text
        textSize = size
        setTextColor(0xFF122438.toInt())
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }

    private fun body(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 14f
        setTextColor(0xFF526273.toInt())
        setLineSpacing(dp(2).toFloat(), 1f)
        setPadding(0, dp(7), 0, dp(7))
    }

    private fun actionButton(text: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = dp(12)
            setOnClickListener { action() }
        }

    private fun card(view: View): MaterialCardView = MaterialCardView(this).apply {
        radius = dp(18).toFloat()
        cardElevation = dp(1).toFloat()
        strokeWidth = dp(1)
        setStrokeColor(0xFFE2E9EE.toInt())
        setCardBackgroundColor(0xFFFFFFFF.toInt())
        addView(view)
    }

    private fun showOnboarding() {
        val root = baseLayout()
        root.gravity = Gravity.CENTER_VERTICAL
        root.addView(heading("Welcome to Stemit").apply { gravity = Gravity.CENTER })
        root.addView(body("Separate a song into vocals and instrumental audio. Choose a file, run the on-device model, then open your two audio stems.").apply { gravity = Gravity.CENTER })
        root.addView(body("The first separation downloads a large model (about 103 MB). Processing also needs free memory and storage.").apply { gravity = Gravity.CENTER })
        root.addView(actionButton("Get started") {
            prefs.edit().putBoolean("onboarding_complete", true).apply()
            showHome()
        })
        root.addView(actionButton("How it works") {
            showInfo("How Stemit works", "1. Choose an audio file using Android's secure file picker.\n\n2. On the first run, Stemit downloads its separation model.\n\n3. Your audio is decoded and processed on this device.\n\n4. Save or open the vocals and instrumental WAV files.\n\nThe file picker grants access only to the file you choose; broad storage permission is not needed.")
        })
        setContentView(root)
    }

    private fun showHome() {
        content = baseLayout()
        val scroll = ScrollView(this).apply { addView(content) }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        header.addView(heading("Stemit", 29f), LinearLayout.LayoutParams(0, -2, 1f))
        header.addView(actionButton("Menu  ⋮") { showMenu(it) })
        content.addView(header)
        content.addView(body("Your sound. Your stems."))
        val choose = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(heading("Choose your audio", 19f))
            addView(body("Select an MP3, WAV, M4A or another Android-supported audio file."))
            addView(actionButton("Choose audio file") { audioPicker.launch(arrayOf("audio/*")) })
            addView(TextView(this@MainActivity).apply {
                tag = "selected_audio"
                text = "No audio selected"
                textSize = 13f
                setTextColor(0xFF148C89.toInt())
                setPadding(0, dp(8), 0, 0)
            })
        }
        content.addView(card(choose), params(0, 14))

        val process = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(16))
            addView(heading("Separate track", 19f))
            addView(body("Stemit creates two WAV files: vocals and instrumental. The first run downloads the model; later runs can work offline."))
            val progress = ProgressBar(this@MainActivity, null, android.R.attr.progressBarStyleHorizontal).apply {
                max = 1000
                visibility = View.GONE
            }
            val status = body("Ready when you are").apply { tag = "separation_status" }
            addView(progress)
            addView(status)
            lateinit var start: MaterialButton
            start = actionButton("Separate audio") {
                val uri = selectedAudio
                if (uri == null) {
                    Toast.makeText(this@MainActivity, "Choose an audio file first.", Toast.LENGTH_SHORT).show()
                    return@actionButton
                }
                start.isEnabled = false
                progress.visibility = View.VISIBLE
                status.text = "Preparing audio…"
                executor.execute {
                    try {
                        val result = engine.separate(uri, File(cacheDir, "separated")) { p ->
                            runOnUiThread {
                                if (!isFinishing) {
                                    progress.progress = (p.fraction * 1000).toInt()
                                    status.text = p.message
                                }
                            }
                        }
                        runOnUiThread {
                            if (!isFinishing) {
                                start.isEnabled = true
                                progress.visibility = View.GONE
                                status.text = "Separation complete"
                                showResultDialog(result.vocalsFile, result.instrumentalFile, result.durationSeconds)
                            }
                        }
                    } catch (e: OutOfMemoryError) {
                        runOnUiThread {
                            start.isEnabled = true
                            progress.visibility = View.GONE
                            status.text = "Not enough memory for this audio."
                            showInfo("Not enough memory", "This phone ran out of memory while processing. Close other apps and try a shorter audio clip. The current separation model is demanding for phones with 2 GB RAM.")
                        }
                    } catch (e: Exception) {
                        runOnUiThread {
                            start.isEnabled = true
                            progress.visibility = View.GONE
                            status.text = "Separation could not finish"
                            showInfo("Separation failed", e.message ?: e.javaClass.simpleName)
                        }
                    }
                }
            }
            addView(start)
        }
        content.addView(card(process), params(0, 14))
        content.addView(body("Tip: keep Stemit open while processing. Long tracks need more memory and time."))
        setContentView(scroll)
    }

    private fun showMenu(anchor: View) {
        val popup = PopupMenu(this, anchor)
        popup.menu.add("FAQ & Help").setOnMenuItemClickListener { showFaq(); true }
        popup.menu.add("About Stemit").setOnMenuItemClickListener { showAbout(); true }
        popup.menu.add("Settings").setOnMenuItemClickListener { showSettings(); true }
        popup.menu.add("Check for updates").setOnMenuItemClickListener { checkForUpdates(); true }
        popup.menu.add("Contact developer").setOnMenuItemClickListener { contactDeveloper(); true }
        popup.menu.add("More from the developer").setOnMenuItemClickListener { openUrl("https://github.com/Augak"); true }
        popup.show()
    }

    private fun showResultDialog(vocals: File, instrumental: File, duration: Float) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        box.addView(body("Processed \${"%.1f".format(duration)} seconds."))
        box.addView(actionButton("Open vocals") { openAudio(vocals) })
        box.addView(actionButton("Open instrumental") { openAudio(instrumental) })
        MaterialAlertDialogBuilder(this).setTitle("Your stems are ready").setView(box).setPositiveButton("Done", null).show()
    }

    private fun openAudio(file: File) {
        val uri = FileProvider.getUriForFile(this, "\${packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "audio/wav")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: Exception) { showInfo("No audio player", "Install or enable an audio player to open this WAV file.") }
    }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(20), dp(8), dp(20), dp(8)) }
        val notifications = SwitchMaterial(this).apply {
            text = "Allow notifications"
            isChecked = prefs.getBoolean("notifications", false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("notifications", enabled).apply()
                if (enabled) requestNotificationPermission()
            }
        }
        box.addView(notifications)
        box.addView(actionButton("Permissions") { explainPermissions() })
        box.addView(actionButton("Check for updates") { checkForUpdates() })
        box.addView(actionButton("More from the developer") { openUrl("https://github.com/Augak") })
        box.addView(actionButton("Contact developer") { contactDeveloper() })
        MaterialAlertDialogBuilder(this).setTitle("Settings").setView(box).setPositiveButton("Close", null).show()
    }

    private fun explainPermissions() {
        val message = if (Build.VERSION.SDK_INT >= 33) {
            "Stemit uses Android's secure file picker, so it does not need full access to your music or files. Notifications are optional and can be enabled below."
        } else {
            "Stemit uses Android's secure file picker and does not need broad storage access. No runtime permission is required for the core audio workflow."
        }
        MaterialAlertDialogBuilder(this).setTitle("App permissions").setMessage(message)
            .setPositiveButton("Enable notifications") { _, _ -> requestNotificationPermission() }
            .setNegativeButton("Close", null).show()
    }

    private fun requestFirstRunPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_prompted", false)
        ) {
            prefs.edit().putBoolean("notification_prompted", true).apply()
            requestNotificationPermission()
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    private fun contactDeveloper() {
        val intent = Intent(Intent.ACTION_SENDTO).apply {
            data = Uri.parse("mailto:kastineaugak@gmail.com?subject=Stemit%20support")
        }
        try { startActivity(intent) } catch (_: Exception) {
            showInfo("Email unavailable", "Send your support request to kastineaugak@gmail.com")
        }
    }

    private fun checkForUpdates() {
        showInfo("Checking for updates", "Checking the official Stemit release page…")
        executor.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.github.com/repos/Augak/Stemit/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000
                    readTimeout = 12000
                    setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (connection.responseCode == 404) {
                    runOnUiThread { showInfo("No release published yet", "This build is \${BuildConfig.VERSION_NAME}. There is not an official Stemit release published yet. Check the releases page later.") }
                } else {
                    check(connection.responseCode in 200..299) { "Update check returned HTTP \${connection.responseCode}" }
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name", "latest")
                    val page = json.optString("html_url", "https://github.com/Augak/Stemit/releases")
                    val current = BuildConfig.VERSION_NAME
                    runOnUiThread {
                        if (tag.trimStart('v') == current.trimStart('v')) {
                            showInfo("You're up to date", "Installed version: \$current\nLatest release: \$tag")
                        } else {
                            MaterialAlertDialogBuilder(this).setTitle("Stemit update available")
                                .setMessage("Installed: \$current\nLatest: \$tag\n\nOpen the official release page to download and install the update.")
                                .setPositiveButton("View update") { _, _ -> openUrl(page) }
                                .setNegativeButton("Later", null).show()
                        }
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { showInfo("Update check failed", e.message ?: "Please try again later.") }
            } finally { connection?.disconnect() }
        }
    }

    private fun showFaq() = showInfo(
        "FAQ & Help",
        "What does Stemit do?\nIt separates a supported audio track into vocals and instrumental WAV files.\n\nDoes it work offline?\nAfter the first model download, separation is designed to run on your phone without sending your audio to a server.\n\nWhy does the first run take time?\nStemit downloads a model of about 103 MB.\n\nWhy can separation stop?\nThe neural model needs substantial memory. Close background apps and try a shorter clip if your phone reports low memory.\n\nWhich files can I select?\nUse common audio formats supported by Android's media decoder.\n\nNeed help?\nUse Menu > Contact developer."
    )

    private fun showAbout() = showInfo(
        "About Stemit",
        "Stemit · Version \${BuildConfig.VERSION_NAME}\n\nA mobile audio tool for separating vocals and instrumentals. Built by Kastine Augak.\n\nMore from the developer: github.com/Augak"
    )

    private fun showInfo(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }

    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }
        catch (_: Exception) { showInfo("Unable to open link", url) }
    }

    private fun params(top: Int, bottom: Int) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()
}
