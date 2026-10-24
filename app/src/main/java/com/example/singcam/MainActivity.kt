package com.example.singcam

import android.Manifest
import android.annotation.SuppressLint
import android.content.ContentValues
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.widget.Button
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.*
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var songName: TextView
    private lateinit var playBtn: Button
    private lateinit var recBtn: Button
    private lateinit var flipBtn: Button

    private var cameraProvider: ProcessCameraProvider? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var recording: Recording? = null
    private var lens = CameraSelector.DEFAULT_BACK_CAMERA

    private var player: MediaPlayer? = null
    private var volume = 0.6f

    private val pickAudio =
        registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) loadSong(uri)
        }

    private val askPermissions =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { r ->
            if (r.values.all { it }) startCamera()
            else toast("Camera aur Mic ki permission chahiye")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        songName = findViewById(R.id.songName)
        playBtn = findViewById(R.id.playBtn)
        recBtn = findViewById(R.id.recBtn)
        flipBtn = findViewById(R.id.flipBtn)

        findViewById<Button>(R.id.pickBtn).setOnClickListener {
            pickAudio.launch(arrayOf("audio/*"))
        }
        playBtn.setOnClickListener { togglePlay() }
        recBtn.setOnClickListener { toggleRecord() }
        flipBtn.setOnClickListener {
            lens = if (lens == CameraSelector.DEFAULT_BACK_CAMERA)
                CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            bindCamera()
        }
        findViewById<SeekBar>(R.id.volumeBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) {
                    volume = p / 100f
                    player?.setVolume(volume, volume)
                }
                override fun onStartTrackingTouch(s: SeekBar?) {}
                override fun onStopTrackingTouch(s: SeekBar?) {}
            })

        val needed = arrayOf(Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO)
        if (needed.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED })
            startCamera() else askPermissions.launch(needed)
    }

    // ---------- Music ----------
    private fun loadSong(uri: Uri) {
        player?.release()
        player = MediaPlayer.create(this, uri)
        if (player == null) { toast("Ye gana play nahi ho paya"); return }
        player?.setVolume(volume, volume)
        player?.setOnCompletionListener { playBtn.text = "Play" }
        songName.text = "🎵 " + displayName(uri)
        playBtn.text = "Play"
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "Gana"

    private fun togglePlay() {
        val p = player ?: return toast("Pehle gana chuno")
        if (p.isPlaying) { p.pause(); playBtn.text = "Play" }
        else { p.start(); playBtn.text = "Pause" }
    }

    // ---------- Camera ----------
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ cameraProvider = f.get(); bindCamera() }, ContextCompat.getMainExecutor(this))
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        videoCapture = VideoCapture.withOutput(recorder)
        provider.unbindAll()
        provider.bindToLifecycle(this, lens, preview, videoCapture)
    }

    @SuppressLint("MissingPermission")
    private fun toggleRecord() {
        val current = recording
        if (current != null) { current.stop(); recording = null; return }

        val vc = videoCapture ?: return
        val name = "SingCam_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/SingCam")
        }
        val options = MediaStoreOutputOptions.Builder(
            contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        ).setContentValues(values).build()

        recording = vc.output.prepareRecording(this, options)
            .withAudioEnabled() // mic ON — gana chalta rahega
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recBtn.text = "Stop"; flipBtn.isEnabled = false
                    }
                    is VideoRecordEvent.Finalize -> {
                        recBtn.text = "Record"; flipBtn.isEnabled = true
                        toast(if (event.hasError()) "Recording fail hui"
                              else "Video save ho gayi: Movies/SingCam")
                    }
                    else -> {}
                }
            }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        recording?.stop()
        player?.release()
        super.onDestroy()
    }
}
