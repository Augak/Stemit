package com.stemit.app

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class LibraryActivity : AppCompatActivity() {
    private val libraryDir by lazy { File(filesDir, "library").apply { mkdirs() } }
    private var player: MediaPlayer? = null
    private var playingFile: File? = null
    private lateinit var list: LinearLayout
    private val picker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { importFile(it) }
    }

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); render() }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20,20,20,20); setBackgroundColor(0xFFF3F6F8.toInt()) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(this).apply { text="Library"; textSize=26f; setTextColor(0xFF122438.toInt()) }, LinearLayout.LayoutParams(0,-2,1f))
        header.addView(Button(this).apply { text="Add audio"; setOnClickListener { picker.launch(arrayOf("audio/*")) } })
        root.addView(header)
        root.addView(TextView(this).apply { text="Your saved stems and imported audio stay on this device."; textSize=14f; setTextColor(0xFF526273.toInt()) })
        val scroll=ScrollView(this)
        list=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        scroll.addView(list); root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        root.addView(Button(this).apply { text="Back to Stemit"; setOnClickListener { finish() } })
        setContentView(root); refresh()
    }

    private fun refresh() {
        list.removeAllViews()
        val files=libraryDir.listFiles()?.filter { it.isFile && it.extension.lowercase() in listOf("wav","mp3","m4a","aac","ogg","flac") }?.sortedByDescending { it.lastModified() }.orEmpty()
        if(files.isEmpty()) list.addView(TextView(this).apply { text="Your library is empty. Separate a track or use Add audio to import a file."; textSize=15f; setPadding(0,32,0,32); setTextColor(0xFF526273.toInt()) })
        files.forEach { file ->
            val card=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(14,12,14,12); setBackgroundColor(0xFFFFFFFF.toInt()) }
            card.addView(TextView(this).apply { text=file.name; textSize=16f; setTextColor(0xFF122438.toInt()) })
            card.addView(TextView(this).apply { text="${formatBytes(file.length())}  •  ${SimpleDateFormat("dd MMM yyyy",Locale.getDefault()).format(Date(file.lastModified()))}"; textSize=12f; setTextColor(0xFF647486.toInt()) })
            val actions=LinearLayout(this)
            actions.addView(Button(this).apply { text=if(playingFile==file && player?.isPlaying==true) "Pause" else "Play"; setOnClickListener { toggle(file); text=if(player?.isPlaying==true) "Pause" else "Play" } })
            actions.addView(Button(this).apply { text="Share"; setOnClickListener { share(file) } })
            actions.addView(Button(this).apply { text="Studio"; setOnClickListener { startActivity(Intent(this@LibraryActivity,StudioActivity::class.java).putExtra("file",file.absolutePath)) } })
            actions.addView(Button(this).apply { text="Delete"; setOnClickListener {
                androidx.appcompat.app.AlertDialog.Builder(this@LibraryActivity).setMessage("Delete ${file.name}?").setNegativeButton("Cancel",null).setPositiveButton("Delete") { _,_ -> if(playingFile==file) stopPlayer(); file.delete(); refresh() }.show()
            } })
            card.addView(actions)
            list.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=12 })
        }
    }

    private fun toggle(file:File) {
        if(playingFile==file && player!=null) { if(player!!.isPlaying) player!!.pause() else player!!.start(); return }
        stopPlayer()
        try { player=MediaPlayer().apply { setDataSource(file.absolutePath); setOnCompletionListener { stopPlayer(); refresh() }; prepare(); start() }; playingFile=file; refresh() }
        catch(e:Exception) { stopPlayer(); Toast.makeText(this,"This format could not be played on this device.",Toast.LENGTH_LONG).show() }
    }

    private fun importFile(uri:Uri) {
        try {
            val name=(contentResolver.query(uri,arrayOf(android.provider.OpenableColumns.DISPLAY_NAME),null,null,null)?.use { if(it.moveToFirst()) it.getString(0) else null } ?: "audio-${System.currentTimeMillis()}.audio").replace(Regex("[^A-Za-z0-9._ -]"),"_")
            val target=File(libraryDir,"${System.currentTimeMillis()}-$name")
            contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } } ?: error("Unable to read selected file")
            refresh()
        } catch(e:Exception) { Toast.makeText(this,e.message ?: "Import failed",Toast.LENGTH_LONG).show() }
    }

    private fun share(file:File) {
        val uri=FileProvider.getUriForFile(this,"${packageName}.files",file)
        val intent=Intent(Intent.ACTION_SEND).apply { type="audio/*"; putExtra(Intent.EXTRA_STREAM,uri); addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        startActivity(Intent.createChooser(intent,"Share audio"))
    }

    private fun stopPlayer() { player?.runCatching { stop(); release() }; player=null; playingFile=null }
    private fun formatBytes(bytes:Long):String = if(bytes<1_000_000) "${bytes/1024} KB" else String.format(Locale.getDefault(),"%.1f MB",bytes/1_000_000.0)
    override fun onDestroy() { stopPlayer(); super.onDestroy() }
}
