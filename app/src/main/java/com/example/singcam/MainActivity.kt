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
import android.graphics.Color
import android.widget.Chronometer
import android.widget.LinearLayout
import android.view.ViewGroup
import android.view.View
import android.os.SystemClock
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
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
    private lateinit var playBtn: TextView
    private lateinit var recBtn: View
    private lateinit var recDot: View
    private lateinit var flipBtn: TextView
    private lateinit var recBadge: View
    private lateinit var timer: Chronometer
    private lateinit var filterRow: LinearLayout

    private val filterNames = listOf(
        "None", "Lo-Res", "Handheld", "Rio de Janeiro", "Soft Light", "Zoom Blur",
        "Hyper", "Gritty", "Grainy", "Midnight", "Black & White", "Fade"
    )
    private val chips = ArrayList<TextView>()
    private var filterIndex = 0
    private var processor: FilterProcessor? = null

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
            else toast("Camera and microphone permissions are required")
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        previewView = findViewById(R.id.previewView)
        songName = findViewById(R.id.songName)
        playBtn = findViewById(R.id.playBtn)
        recBtn = findViewById(R.id.recBtn)
        recDot = findViewById(R.id.recDot)
        flipBtn = findViewById(R.id.flipBtn)
        recBadge = findViewById(R.id.recBadge)
        timer = findViewById(R.id.timer)
        filterRow = findViewById(R.id.filterRow)
        buildFilterChips()

        findViewById<View>(R.id.pickBtn).setOnClickListener {
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
        if (player == null) { toast("This song could not be played"); return }
        player?.setVolume(volume, volume)
        player?.setOnCompletionListener { playBtn.text = "▶" }
        songName.text = "🎵  " + displayName(uri)
        playBtn.text = "▶"
    }

    private fun displayName(uri: Uri): String =
        contentResolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "Song"

    private fun togglePlay() {
        val p = player ?: return toast("Choose a song first")
        if (p.isPlaying) { p.pause(); playBtn.text = "▶" }
        else { p.start(); playBtn.text = "⏸" }
    }

    // ---------- Camera ----------
    private fun startCamera() {
        val f = ProcessCameraProvider.getInstance(this)
        f.addListener({ cameraProvider = f.get(); bindCamera() }, ContextCompat.getMainExecutor(this))
    }

    // ---------- Filters ----------
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun buildFilterChips() {
        filterNames.forEachIndexed { i, name ->
            val chip = TextView(this).apply {
                text = name
                setTextColor(Color.WHITE)
                textSize = 13f
                setPadding(dp(16), dp(9), dp(16), dp(9))
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply { rightMargin = dp(8) }
                setOnClickListener { selectFilter(i) }
            }
            chips.add(chip)
            filterRow.addView(chip)
        }
        updateChips()
    }

    private fun updateChips() {
        chips.forEachIndexed { i, chip ->
            chip.setBackgroundResource(
                if (i == filterIndex) R.drawable.bg_chip_selected else R.drawable.bg_chip
            )
        }
    }

    private fun selectFilter(i: Int) {
        if (recording != null) { toast("Stop recording to change the filter"); return }
        if (i == filterIndex) return
        val needRebind = (i == 0) != (filterIndex == 0)
        filterIndex = i
        processor?.filterId = i
        updateChips()
        if (needRebind) bindCamera()
    }

    private fun onFilterFailed(t: Throwable) {
        if (filterIndex == 0 && processor == null) return
        processor?.release()
        processor = null
        filterIndex = 0
        updateChips()
        toast("Filters are not supported on this device")
        bindCamera()
    }

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.from(Quality.HD, FallbackStrategy.lowerQualityOrHigherThan(Quality.SD)))
            .build()
        val vc = VideoCapture.withOutput(recorder)
        videoCapture = vc

        val group = UseCaseGroup.Builder().addUseCase(preview).addUseCase(vc)
        if (filterIndex != 0) {
            val p = processor ?: FilterProcessor { t -> runOnUiThread { onFilterFailed(t) } }
                .also { processor = it }
            p.filterId = filterIndex
            group.addEffect(FilterEffect(p) { t -> runOnUiThread { onFilterFailed(t) } })
        }
        provider.unbindAll()
        provider.bindToLifecycle(this, lens, group.build())
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
            .withAudioEnabled() // mic ON: captures your voice and the music from the speaker
            .start(ContextCompat.getMainExecutor(this)) { event ->
                when (event) {
                    is VideoRecordEvent.Start -> {
                        recDot.setBackgroundResource(R.drawable.bg_rec_stop)
                        flipBtn.isEnabled = false; flipBtn.alpha = 0.4f
                        filterRow.alpha = 0.4f
                        recBadge.visibility = View.VISIBLE
                        timer.base = SystemClock.elapsedRealtime(); timer.start()
                    }
                    is VideoRecordEvent.Finalize -> {
                        recDot.setBackgroundResource(R.drawable.bg_rec_dot)
                        flipBtn.isEnabled = true; flipBtn.alpha = 1f
                        filterRow.alpha = 1f
                        timer.stop(); recBadge.visibility = View.GONE
                        toast(if (event.hasError()) "Recording failed"
                              else "Video saved to Movies/SingCam")
                    }
                    else -> {}
                }
            }
    }

    private fun toast(m: String) = Toast.makeText(this, m, Toast.LENGTH_SHORT).show()

    override fun onDestroy() {
        recording?.stop()
        player?.release()
        processor?.release()
        super.onDestroy()
    }
}
