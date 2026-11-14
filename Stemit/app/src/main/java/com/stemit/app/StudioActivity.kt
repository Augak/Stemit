package com.stemit.app

import android.net.Uri
import android.os.Bundle
import android.text.InputType
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.Locale

class StudioActivity:AppCompatActivity() {
    private var source:File?=null
    private lateinit var selected:TextView
    private lateinit var start:EditText
    private lateinit var end:EditText
    private lateinit var gain:SeekBar
    private lateinit var gainLabel:TextView
    private val picker=registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let { copyInput(it) } }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        val root=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setPadding(20,20,20,20); setBackgroundColor(0xFFF3F6F8.toInt()) }
        root.addView(TextView(this).apply { text="Studio"; textSize=26f; setTextColor(0xFF122438.toInt()) })
        root.addView(TextView(this).apply { text="Trim and adjust volume on 16-bit PCM WAV stems. Original audio is never changed."; textSize=14f; setTextColor(0xFF526273.toInt()) })
        selected=TextView(this).apply { text="No WAV selected"; setPadding(0,16,0,12) }; root.addView(selected)
        root.addView(Button(this).apply { text="Choose WAV file"; setOnClickListener { picker.launch(arrayOf("audio/wav","audio/x-wav")) } })
        start=field("Start time (seconds)","0"); end=field("End time (seconds)","")
        root.addView(start); root.addView(end)
        gainLabel=TextView(this).apply { text="Volume: 100%" }; root.addView(gainLabel)
        gain=SeekBar(this).apply { max=200; progress=100; setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s:SeekBar?,p:Int,user:Boolean) { gainLabel.text="Volume: $p%" }
            override fun onStartTrackingTouch(s:SeekBar?) {}; override fun onStopTrackingTouch(s:SeekBar?) {}
        }) }; root.addView(gain)
        root.addView(Button(this).apply { text="Export edited WAV"; setOnClickListener { export() } })
        root.addView(Button(this).apply { text="Back"; setOnClickListener { finish() } })
        setContentView(ScrollView(this).apply { addView(root) })
        intent.getStringExtra("file")?.let { File(it).takeIf { f->f.exists() }?.let { f->source=f; selected.text=f.name; loadDuration(f) } }
    }
    private fun field(label:String,value:String)=EditText(this).apply { hint=label; setSingleLine(true); inputType=InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL; setText(value) }
    private fun copyInput(uri:Uri) {
        try { val f=File(cacheDir,"studio-input.wav"); contentResolver.openInputStream(uri)?.use { input->f.outputStream().use { input.copyTo(it) } } ?: error("Could not read file")
            WavEditor.inspect(f); source=f; selected.text=f.name; loadDuration(f)
        } catch(e:Exception) { Toast.makeText(this,e.message ?: "Choose a supported WAV file",Toast.LENGTH_LONG).show() }
    }
    private fun loadDuration(file:File) { try { val h=WavEditor.inspect(file); end.setText(String.format(Locale.US,"%.2f",h.dataBytes.toDouble()/(h.sampleRate*h.channels*2))) } catch(_:Exception) {} }
    private fun export() {
        val input=source ?: run { Toast.makeText(this,"Choose a WAV file first.",Toast.LENGTH_SHORT).show(); return }
        val a=start.text.toString().toDoubleOrNull() ?: 0.0
        val b=end.text.toString().toDoubleOrNull() ?: run { Toast.makeText(this,"Enter an end time.",Toast.LENGTH_SHORT).show(); return }
        val dir=File(filesDir,"library").apply { mkdirs() }; val output=File(dir,"edited-${System.currentTimeMillis()}.wav")
        try { WavEditor.edit(input,output,a,b,gain.progress/100f); Toast.makeText(this,"Saved to your Library: ${output.name}",Toast.LENGTH_LONG).show() }
        catch(e:Exception) { output.delete(); Toast.makeText(this,e.message ?: "Export failed",Toast.LENGTH_LONG).show() }
    }
}
