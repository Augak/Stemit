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
import android.view.inputmethod.EditorInfo
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.app.NotificationCompat
import androidx.appcompat.app.AppCompatDelegate
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.switchmaterial.SwitchMaterial
import com.stemit.app.separation.PolarFormerEngine
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import org.json.JSONObject

class DashboardActivity : AppCompatActivity() {
    private val prefs by lazy { getSharedPreferences("stemit", MODE_PRIVATE) }
    private val worker = Executors.newSingleThreadExecutor()
    private val ui = Handler(Looper.getMainLooper())
    private val engine by lazy { PolarFormerEngine(this) }
    private var selectedUri: Uri? = null
    private var importingToLibrary = false
    private var exportFile: File? = null
    private var selectedName = "No audio selected"
    private var activeTab = "Home"
    private var query = ""
    private lateinit var root: LinearLayout
    private lateinit var page: LinearLayout

    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) {
            importingToLibrary = false
        } else if (importingToLibrary) {
            importToLibrary(uri)
        } else {
            selectedUri = uri
            selectedName = uri.lastPathSegment?.substringAfterLast('/') ?: "Audio selected"
            showTab("Home")
            Toast.makeText(this, "Audio ready", Toast.LENGTH_SHORT).show()
        }
    }
    private val savePicker = registerForActivityResult(ActivityResultContracts.CreateDocument("audio/*")) { uri ->
        val file = exportFile
        if (uri != null && file != null) {
            try {
                contentResolver.openOutputStream(uri)?.use { output -> file.inputStream().use { it.copyTo(output) } }
                    ?: error("Unable to create destination file.")
                Toast.makeText(this, "Saved a copy to your chosen folder.", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                info("Export failed", e.message ?: "Could not save the audio file.")
            }
        }
        exportFile = null
    }
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) Toast.makeText(this, "Notifications are still off.", Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!prefs.getBoolean("onboarding_complete", false)) onboarding() else showTab("Home")
        askNotificationPermissionOnce()
    }

    private fun dark() = prefs.getBoolean("dark_theme", false)
    private fun background() = if (dark()) 0xFF121212.toInt() else 0xFFF7F7F5.toInt()
    private fun surface() = if (dark()) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
    private fun ink() = if (dark()) 0xFFF5F5F5.toInt() else 0xFF171717.toInt()
    private fun muted() = if (dark()) 0xFFB8B8B8.toInt() else 0xFF666666.toInt()
    private fun accent() = if (dark()) 0xFFC6A7FF.toInt() else 0xFF6842A6.toInt()
    private fun border() = if (dark()) 0xFF383838.toInt() else 0xFFE7E5E2.toInt()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun label(value: String, size: Float = 14f, color: Int = ink()) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
    }
    private fun title(value: String, size: Float = 24f) = label(value, size).apply {
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
    }
    private fun paragraph(value: String) = label(value, 14f, muted()).apply {
        setPadding(0, dp(5), 0, dp(7)); setLineSpacing(dp(2).toFloat(), 1f)
    }
    private fun column() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(15))
    }
    private fun card(view: View) = MaterialCardView(this).apply {
        radius = dp(16).toFloat(); cardElevation = 0f; strokeWidth = dp(1)
        setStrokeColor(border()); setCardBackgroundColor(surface()); addView(view)
    }
    private fun button(text: String, primary: Boolean = false, action: () -> Unit) = MaterialButton(this).apply {
        this.text = text; isAllCaps = false; cornerRadius = dp(12)
        backgroundTintList = android.content.res.ColorStateList.valueOf(if (primary) accent() else surface())
        setTextColor(if (primary) 0xFFFFFFFF.toInt() else ink())
        if (!primary) { strokeWidth = dp(1); strokeColor = android.content.res.ColorStateList.valueOf(border()) }
        setOnClickListener { action() }
    }
    private fun space(top: Int = 0, bottom: Int = 10) = LinearLayout.LayoutParams(-1, -2).apply {
        setMargins(0, dp(top), 0, dp(bottom))
    }

    private fun onboarding() {
        val panel = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
            setPadding(dp(28), dp(28), dp(28), dp(28)); setBackgroundColor(background())
        }
        panel.addView(ImageView(this).apply { setImageResource(R.drawable.ic_stemit); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(84), dp(84)).apply { gravity = Gravity.CENTER })
        panel.addView(title("Welcome to Stemit").apply { gravity = Gravity.CENTER; setPadding(0, dp(16), 0, 0) })
        panel.addView(paragraph("Your sound. Your stems.\n\nSeparate a song into vocals and instrumental audio with on-device processing.").apply { gravity = Gravity.CENTER })
        panel.addView(paragraph("The first separation downloads a model of about 103 MB. After that, processing is designed to run on your phone.").apply { gravity = Gravity.CENTER })
        panel.addView(button("Get started", true) {
            prefs.edit().putBoolean("onboarding_complete", true).apply(); showTab("Home")
        })
        setContentView(panel)
    }

    private fun showTab(tab: String) {
        activeTab = tab
        root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(background()) }
        window.statusBarColor = background()
        window.navigationBarColor = surface()
        if (Build.VERSION.SDK_INT >= 26) window.decorView.systemUiVisibility =
            if (dark()) 0 else View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(11), dp(18), dp(10)); setBackgroundColor(surface())
        }
        top.addView(ImageView(this).apply { setImageResource(R.drawable.ic_stemit); scaleType = ImageView.ScaleType.FIT_CENTER },
            LinearLayout.LayoutParams(dp(38), dp(38)))
        val brand = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(10), 0, 0, 0) }
        brand.addView(title("Stemit", 20f))
        brand.addView(label("Your sound. Your stems.", 11f, muted()))
        top.addView(brand, LinearLayout.LayoutParams(0, -2, 1f))
        top.addView(button("⋮") { menu(itAnchor = top) }.apply { minWidth = dp(42); textSize = 20f })
        root.addView(top)

        page = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(18), dp(18), dp(18), dp(22))
        }
        val scroll = ScrollView(this).apply { isFillViewport = true; addView(page) }
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        root.addView(bottomBar())
        setContentView(root)

        when (tab) {
            "Home" -> home()
            "Search" -> search()
            "Library" -> library()
            "Premium" -> premium()
            "Settings" -> settings()
        }
    }

    private fun bottomBar(): View {
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            setPadding(dp(4), dp(7), dp(4), dp(7))
            setBackgroundColor(surface())
            contentDescription = "Main navigation"
        }
        val destinations = listOf(
            Triple("Home", android.R.drawable.ic_menu_view, "Home"),
            Triple("Search", android.R.drawable.ic_menu_search, "Search"),
            Triple("Library", android.R.drawable.ic_menu_agenda, "Audio library"),
            Triple("Premium", android.R.drawable.btn_star_big_off, "Premium"),
            Triple("Settings", android.R.drawable.ic_menu_preferences, "Settings")
        )
        destinations.forEach { (name, iconRes, description) ->
            val selected = activeTab == name
            val tint = if (selected) accent() else muted()
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                isClickable = true
                isFocusable = true
                isSelected = selected
                contentDescription = "$description tab"
                setPadding(dp(2), dp(3), dp(2), dp(3))
                setOnClickListener { showTab(name) }
            }
            item.addView(ImageView(this).apply {
                setImageResource(iconRes)
                setColorFilter(tint)
                contentDescription = "$description icon"
                scaleType = ImageView.ScaleType.CENTER_INSIDE
            }, LinearLayout.LayoutParams(dp(22), dp(22)))
            item.addView(label(name, 10f, tint).apply {
                gravity = Gravity.CENTER
                if (selected) typeface = Typeface.DEFAULT_BOLD
            })
            bar.addView(item, LinearLayout.LayoutParams(0, -2, 1f))
        }
        return bar
    }

    private fun home() {
        page.addView(title("Good to have you here", 23f))
        page.addView(paragraph("Your sound. Your stems."))
        val chooser = column()
        chooser.addView(title("Choose your audio", 18f))
        chooser.addView(paragraph("Select an MP3, WAV, M4A or another Android-supported audio file."))
        chooser.addView(button("Select audio file") { picker.launch(arrayOf("audio/*")) })
        chooser.addView(paragraph(selectedName).apply { setTextColor(accent()) })
        page.addView(card(chooser), space())

        val studio = column()
        studio.addView(title("Vocal separation", 19f))
        studio.addView(paragraph("Separate your track into two audio stems: vocals and instrumental."))
        val progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 1000; visibility = View.GONE
        }
        val status = paragraph("Ready when you are")
        studio.addView(progress); studio.addView(status)
        lateinit var start: MaterialButton
        start = button("Separate audio", true) {
            val uri = selectedUri
            if (uri == null) {
                Toast.makeText(this, "Select an audio file first.", Toast.LENGTH_SHORT).show()
                return@button
            }
            start.isEnabled = false; progress.visibility = View.VISIBLE; status.text = "Preparing audio…"
            val temp = File(cacheDir, "separated").apply { mkdirs() }
            worker.execute {
                try {
                    val result = engine.separate(uri, temp) { p ->
                        runOnUiThread {
                            if (!isFinishing) { progress.progress = (p.fraction * 1000).toInt(); status.text = p.message }
                        }
                    }
                    val library = File(filesDir, "library").apply { mkdirs() }
                    val stamp = System.currentTimeMillis()
                    val vocals = File(library, "vocals-$stamp.wav")
                    val instrumental = File(library, "instrumental-$stamp.wav")
                    result.vocalsFile.copyTo(vocals, overwrite = true)
                    result.instrumentalFile.copyTo(instrumental, overwrite = true)
                    runOnUiThread {
                        if (!isFinishing) {
                            start.isEnabled = true; progress.visibility = View.GONE; status.text = "Separation complete"
                            resultDialog(vocals, instrumental, result.durationSeconds)
                            notifySeparationComplete(vocals, instrumental)
                        }
                    }
                } catch (e: OutOfMemoryError) {
                    runOnUiThread {
                        start.isEnabled = true; progress.visibility = View.GONE; status.text = "Not enough memory"
                        info("Not enough memory", "Close other apps and try a shorter audio clip. The current separation model can be demanding on phones with 2 GB RAM.")
                    }
                } catch (e: Exception) {
                    runOnUiThread {
                        start.isEnabled = true; progress.visibility = View.GONE; status.text = "Separation could not finish"
                        info("Separation failed", e.message ?: e.javaClass.simpleName)
                    }
                }
            }
        }
        studio.addView(start)
        page.addView(card(studio), space())
        page.addView(button("Open Studio tools", false) { startActivity(Intent(this, StudioActivity::class.java)) }, space())
        page.addView(section("Recent stems", "See all") { showTab("Library") }, space(0, 8))
        val recent = files().take(2)
        if (recent.isEmpty()) page.addView(paragraph("Your completed separations will appear here."))
        else recent.forEach { page.addView(fileCard(it), space(0, 8)) }
    }

    private fun search() {
        page.addView(title("Search", 24f))
        page.addView(paragraph("Find your saved stems and imported audio."))
        val field = EditText(this).apply {
            hint = "Search audio and stems"; setSingleLine(true); textSize = 15f
            setTextColor(ink()); setHintTextColor(muted()); setPadding(dp(14), dp(10), dp(14), dp(10))
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(surface()); cornerRadius = dp(12).toFloat(); setStroke(dp(1), border())
            }
            setText(query); imeOptions = EditorInfo.IME_ACTION_SEARCH
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
                override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                    query = s?.toString().orEmpty()
                    val list = page.findViewWithTag<LinearLayout>("search_results") ?: return
                    list.removeAllViews()
                    val matches = files().filter { it.name.contains(query, true) }
                    if (matches.isEmpty()) list.addView(paragraph(if (query.isBlank()) "No saved audio yet." else "No matching files."))
                    else matches.forEach { list.addView(fileCard(it), space(0, 8)) }
                }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        page.addView(field, space(8, 14))
        page.addView(title("Your files", 18f), space(0, 8))
        val results = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; tag = "search_results" }
        page.addView(results)
        val matches = files().filter { it.name.contains(query, true) }
        if (matches.isEmpty()) results.addView(paragraph("No saved audio yet. Separate a track or import audio in Library."))
        else matches.forEach { results.addView(fileCard(it), space(0, 8)) }
    }

    private fun library() {
        page.addView(title("Library", 24f))
        page.addView(paragraph("Your saved stems and imported audio stay on this device."))
        page.addView(button("Import audio", false) { importingToLibrary = true; picker.launch(arrayOf("audio/*")) }, space(8, 12))
        val all = files()
        page.addView(section("Saved audio", "Refresh") { showTab("Library") }, space(0, 8))
        if (all.isEmpty()) {
            val empty = column()
            empty.addView(title("Your library is empty", 18f))
            empty.addView(paragraph("Choose a track from Home and separate it. Your vocals and instrumental files will appear here."))
            empty.addView(button("Choose audio", true) { showTab("Home") })
            page.addView(card(empty))
        } else {
            all.forEach { page.addView(fileCard(it), space(0, 8)) }
            page.addView(paragraph("Use Open to play, Share to export, or Studio to trim and adjust WAV volume."))
            page.addView(button("Open full Library manager", false) { startActivity(Intent(this, LibraryActivity::class.java)) }, space())
        }
    }

    private fun importToLibrary(uri: Uri) {
        try {
            val dir = File(filesDir, "library").apply { mkdirs() }
            val displayName = contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            } ?: run {
                val ext = android.webkit.MimeTypeMap.getSingleton().getExtensionFromMimeType(contentResolver.getType(uri)) ?: "wav"
                "audio-${System.currentTimeMillis()}.$ext"
            }
            val safeName = displayName.replace(Regex("[^A-Za-z0-9._ -]"), "_")
            val target = File(dir, "${System.currentTimeMillis()}-$safeName")
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
                ?: error("Unable to read selected audio.")
            importingToLibrary = false
            Toast.makeText(this, "Added to your Library.", Toast.LENGTH_SHORT).show()
            showTab("Library")
        } catch (e: Exception) {
            importingToLibrary = false
            info("Import failed", e.message ?: "The audio file could not be imported.")
        }
    }

    private fun notifySeparationComplete(vocals: File, instrumental: File) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val manager = getSystemService(NOTIFICATION_SERVICE) as android.app.NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(
            android.app.NotificationChannel("stemit_results", "Separation results", android.app.NotificationManager.IMPORTANCE_DEFAULT)
                .apply { description = "Notifies you when your audio stems are ready." }
        )
        val pending = android.app.PendingIntent.getActivity(
            this, 12, Intent(this, DashboardActivity::class.java),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
        )
        manager.notify(12, NotificationCompat.Builder(this, "stemit_results")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle("Your Stemit stems are ready")
            .setContentText("${vocals.name} and ${instrumental.name} were saved to your Library.")
            .setContentIntent(pending).setAutoCancel(true).build())
    }

    private fun files(): List<File> {
        val dir = File(filesDir, "library").apply { mkdirs() }
        return dir.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("wav", "mp3", "m4a", "aac", "ogg", "flac") }
            ?.sortedByDescending { it.lastModified() } ?: emptyList()
    }

    private fun fileCard(file: File): View {
        val box = column()
        box.addView(label(file.name, 14f))
        box.addView(label(android.text.format.Formatter.formatShortFileSize(this, file.length()), 11f, muted()))
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(label("Open", 12f, accent()).apply {
            setPadding(dp(5), dp(8), dp(14), dp(4)); setOnClickListener { openFile(file) }
        })
        actions.addView(label("Share", 12f, accent()).apply {
            setPadding(dp(5), dp(8), dp(14), dp(4)); setOnClickListener { shareFile(file) }
        })
        actions.addView(label("Save copy", 12f, accent()).apply {
            setPadding(dp(5), dp(8), dp(14), dp(4))
            setOnClickListener { exportFile = file; savePicker.launch(file.name) }
        })
        actions.addView(label("Studio", 12f, accent()).apply {
            setPadding(dp(5), dp(8), dp(14), dp(4))
            setOnClickListener { startActivity(Intent(this@DashboardActivity, StudioActivity::class.java).putExtra("file", file.absolutePath)) }
        })
        box.addView(actions)
        return card(box)
    }

    private fun premium() {
        page.addView(title("Stemit Premium", 24f))
        page.addView(paragraph("Optional upgrades for creators who need more from their audio workflow."))
        val panel = column()
        panel.addView(title("Support Stemit", 20f))
        panel.addView(paragraph("Core vocal and instrumental separation remains available without a subscription. Premium purchasing is not enabled in this build."))
        panel.addView(paragraph("Planned benefits"))
        listOf("Batch processing", "Additional export controls", "More audio workflow tools", "Support for continued development").forEach {
            panel.addView(paragraph("•  $it"))
        }
        page.addView(card(panel), space(8, 12))
        page.addView(paragraph("No payment will be requested from this screen. Verified plans and billing will be added only when ready."))
    }

    private fun settings() {
        page.addView(title("Settings", 24f))
        page.addView(paragraph("Make Stemit work the way you prefer."))
        val appearance = column()
        appearance.addView(title("Appearance", 17f))
        appearance.addView(SwitchMaterial(this).apply {
            text = "Dark theme"; setTextColor(ink()); isChecked = dark()
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("dark_theme", enabled).apply()
                AppCompatDelegate.setDefaultNightMode(if (enabled) AppCompatDelegate.MODE_NIGHT_YES else AppCompatDelegate.MODE_NIGHT_NO)
            }
        })
        page.addView(card(appearance), space(8, 10))

        val preferences = column()
        preferences.addView(title("App preferences", 17f))
        preferences.addView(SwitchMaterial(this).apply {
            text = "Notifications"; setTextColor(ink()); isChecked = prefs.getBoolean("notifications", false)
            setOnCheckedChangeListener { _, enabled ->
                prefs.edit().putBoolean("notifications", enabled).apply()
                if (enabled) requestNotifications()
            }
        })
        preferences.addView(paragraph("Separation model: " + (if (engine.modelInstalled()) "Downloaded and ready" else "Not downloaded yet")))
        preferences.addView(button("Remove downloaded model", false) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Remove separation model?")
                .setMessage("This frees model storage. The next separation will download the model again.")
                .setNegativeButton("Cancel", null).setPositiveButton("Remove") { _, _ ->
                    val removed = engine.removeModel()
                    info("Model storage", if (removed) "Downloaded model removed." else "Could not remove the model.")
                }.show()
        })
        preferences.addView(button("Storage and cache", false) {
            val bytes = cacheDir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
            info("Storage and cache", "Temporary cache currently uses " + android.text.format.Formatter.formatShortFileSize(this, bytes) + ". Saved stems are kept in your Library.")
        })
        preferences.addView(button("Clear temporary cache", false) {
            androidx.appcompat.app.AlertDialog.Builder(this).setTitle("Clear temporary cache?")
                .setMessage("Temporary processing files will be removed. Saved stems in your Library will remain.")
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Clear") { _, _ ->
                    cacheDir.listFiles()?.forEach { it.deleteRecursively() }
                    Toast.makeText(this, "Temporary cache cleared.", Toast.LENGTH_SHORT).show()
                }.show()
        })
        page.addView(card(preferences), space(0, 10))

        val help = column()
        help.addView(title("Help and information", 17f))
        help.addView(button("FAQ and Help", false) { faq() })
        help.addView(button("About Stemit", false) { about() })
        help.addView(button("Terms of use", false) { info("Terms of use", "Use Stemit only with audio you own or have permission to process. You are responsible for respecting copyright and licensing. Stemit is provided as-is; processing results may vary by recording and device.") })
        help.addView(button("Privacy policy", false) { info("Privacy policy", "Stemit processes selected audio on your device after downloading its separation model. The app does not upload selected music to a Stemit server. Model downloads and optional update checks require internet access. Files remain in app storage unless you choose to share them.") })
        help.addView(button("Open-source licences", false) { openUrl("https://github.com/Augak/Stemit") })
        help.addView(button("Contact developer", false) { contact() })
        help.addView(button("More from the developer", false) { openUrl("https://github.com/Augak") })
        help.addView(button("Check for updates", false) { checkUpdates() })
        page.addView(card(help), space())
        page.addView(paragraph("Version " + BuildConfig.VERSION_NAME))
    }

    private fun section(text: String, action: String, callback: () -> Unit): View {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        row.addView(title(text, 18f), LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(label("$action  ›", 13f, accent()).apply { setOnClickListener { callback() } })
        return row
    }

    private fun menu(itAnchor: View) {
        val popup = PopupMenu(this, itAnchor)
        popup.menu.add("FAQ & Help").setOnMenuItemClickListener { faq(); true }
        popup.menu.add("About Stemit").setOnMenuItemClickListener { about(); true }
        popup.menu.add("Studio tools").setOnMenuItemClickListener { startActivity(Intent(this, StudioActivity::class.java)); true }
        popup.menu.add("Check for updates").setOnMenuItemClickListener { checkUpdates(); true }
        popup.menu.add("Contact developer").setOnMenuItemClickListener { contact(); true }
        popup.show()
    }

    private fun resultDialog(vocals: File, instrumental: File, duration: Float) {
        val box = column()
        box.addView(paragraph("Processed " + String.format("%.1f", duration) + " seconds."))
        box.addView(button("Open vocals", true) { openFile(vocals) })
        box.addView(button("Open instrumental") { openFile(instrumental) })
        box.addView(button("Share vocals") { shareFile(vocals) })
        box.addView(button("Share instrumental") { shareFile(instrumental) })
        box.addView(button("Save vocals to device") { exportFile = vocals; savePicker.launch(vocals.name) })
        box.addView(button("Save instrumental to device") { exportFile = instrumental; savePicker.launch(instrumental.name) })
        MaterialAlertDialogBuilder(this).setTitle("Your stems are ready").setView(box)
            .setPositiveButton("View Library") { _, _ -> showTab("Library") }.show()
    }

    private fun openFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, if (file.extension.equals("wav", true)) "audio/wav" else "audio/*")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(intent) } catch (_: Exception) { info("No audio player", "Install or enable an audio player to open this file.") }
    }

    private fun shareFile(file: File) {
        val uri = FileProvider.getUriForFile(this, "${packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/*"; putExtra(Intent.EXTRA_STREAM, uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try { startActivity(Intent.createChooser(intent, "Share audio")) }
        catch (_: Exception) { info("Unable to share", "This file could not be shared right now.") }
    }

    private fun askNotificationPermissionOnce() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED &&
            !prefs.getBoolean("notification_prompted", false)
        ) {
            prefs.edit().putBoolean("notification_prompted", true).apply()
            requestNotifications()
        }
    }
    private fun requestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
    }
    private fun contact() {
        val intent = Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:kastineaugak@gmail.com?subject=Stemit%20support"))
        try { startActivity(intent) } catch (_: Exception) { info("Email unavailable", "Contact kastineaugak@gmail.com") }
    }
    private fun faq() = info("FAQ & Help", "Stemit separates a supported audio track into vocals and instrumental WAV files.\n\nAfter the first model download, separation is designed to run on your phone without uploading your audio.\n\nThe model is about 103 MB. The neural model needs memory, so close background apps and try a shorter clip if processing fails.\n\nNeed help? Contact the developer from Settings.")
    private fun about() = info("About Stemit", "Stemit · Version " + BuildConfig.VERSION_NAME + "\n\nYour sound. Your stems.\n\nA mobile audio tool for separating vocals and instrumentals. Built by Kastine Augak.")
    private fun info(title: String, message: String) {
        MaterialAlertDialogBuilder(this).setTitle(title).setMessage(message).setPositiveButton("OK", null).show()
    }
    private fun openUrl(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: Exception) { info("Unable to open link", url) }
    }
    private fun checkUpdates() {
        Toast.makeText(this, "Checking for Stemit updates…", Toast.LENGTH_SHORT).show()
        worker.execute {
            var connection: HttpURLConnection? = null
            try {
                connection = (URL("https://api.github.com/repos/Augak/Stemit/releases/latest").openConnection() as HttpURLConnection).apply {
                    connectTimeout = 12000; readTimeout = 12000; setRequestProperty("Accept", "application/vnd.github+json")
                }
                if (connection.responseCode == 404) {
                    runOnUiThread { info("No release published yet", "There is no official Stemit release published yet. Visit the Releases page to check pre-releases.") }
                } else {
                    check(connection.responseCode in 200..299) { "Update check returned HTTP " + connection.responseCode }
                    val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                    val tag = json.optString("tag_name", "latest")
                    val pageUrl = json.optString("html_url", "https://github.com/Augak/Stemit/releases")
                    runOnUiThread {
                        if (tag.trimStart('v') == BuildConfig.VERSION_NAME.trimStart('v')) info("You're up to date", "Installed version: " + BuildConfig.VERSION_NAME + "\nLatest release: $tag")
                        else MaterialAlertDialogBuilder(this).setTitle("Stemit update available")
                            .setMessage("Latest version: $tag\nOpen the official release page to download the update.")
                            .setPositiveButton("View update") { _, _ -> openUrl(pageUrl) }.setNegativeButton("Later", null).show()
                    }
                }
            } catch (e: Exception) {
                runOnUiThread { info("Update check failed", e.message ?: "Please try again later.") }
            } finally { connection?.disconnect() }
        }
    }

    override fun onDestroy() {
        ui.removeCallbacksAndMessages(null)
        worker.shutdownNow()
        super.onDestroy()
    }
}
