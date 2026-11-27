package com.manjunath.pattiperandubbing

import android.Manifest
import android.app.AlertDialog
import android.content.ContentValues
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import android.text.Spannable
import android.text.SpannableString
import android.text.style.ForegroundColorSpan
import android.view.Gravity
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.effect.Presentation
import androidx.media3.effect.TextOverlay
import androidx.media3.effect.TextureOverlay
import com.google.common.collect.ImmutableList
import androidx.media3.effect.OverlayEffect
import androidx.media3.transformer.Transformer
import androidx.media3.common.util.UnstableApi
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var videoTextureView: TextureView
    private var videoPlayer: ExoPlayer? = null
    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnExport: Button
    private lateinit var btnShare: Button
    private lateinit var btnPreviewDub: Button
    private lateinit var btnSaveDraft: Button
    private var dubPreviewPlayer: MediaPlayer? = null
    @Volatile private var voiceVolume = 1.0f
    @Volatile private var syncOffsetMs = 0
    @Volatile private var noiseReductionEnabled = true
    private lateinit var captionInput: EditText
    private lateinit var captionLanguage: Spinner
    private lateinit var muteOriginalSwitch: Switch
    private lateinit var videoSeekBar: SeekBar
    private lateinit var videoTimeText: TextView
    private var isUserSeeking = false
    private val seekHandler = Handler(Looper.getMainLooper())
    private val seekUpdater = object : Runnable {
        override fun run() {
            val player = videoPlayer
            if (player != null && !isUserSeeking) {
                val duration = player.duration.coerceAtLeast(0L)
                val position = player.currentPosition.coerceAtLeast(0L)
                videoSeekBar.max = 1000
                videoSeekBar.progress = if (duration > 0L) {
                    ((position * 1000L) / duration).toInt().coerceIn(0, 1000)
                } else 0
                videoTimeText.text = "${formatVideoTime(position)} / ${formatVideoTime(duration)}"
            }
            seekHandler.postDelayed(this, 250L)
        }
    }
    
    @Volatile private var originalVideoMuted = true

    @Volatile private var isRecording = false
    @Volatile private var isPaused = false
    private lateinit var btnPauseResume: Button
    @Volatile private var currentVoiceMode = 0
    private var voiceLowPassState = 0f
    private var recordingThread: Thread? = null
    private var selectedVideoUri: Uri? = null
    private var tempDubWavFile: File? = null
    private var lastExportedUri: Uri? = null
    private var lastExportedMime: String = "video/mp4"
    @Volatile private var selectedAspectRatio = 0 // 0 = 9:16 Shorts, 1 = 16:9 YouTube

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) {
            try { contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } catch (_: Exception) {}
            selectedVideoUri = uri
            loadVideoPreview(uri)
        } else {
            statusText.text = "Video selection cancelled."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val scrollView = ScrollView(this).apply {
            setBackgroundColor(0xFF121212.toInt())
        }

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 48)
        }

        val titleText = TextView(this).apply {
            text = "Patti - Peran Live Dubbing Studio"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 20)
        }

        val videoPreviewContainer = FrameLayout(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (260 * resources.displayMetrics.density).toInt()
            ).apply { setMargins(0, 8, 0, 16) }
            setBackgroundColor(0xFF000000.toInt())
        }
        videoTextureView = TextureView(this).apply {
            layoutParams = FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
                Gravity.CENTER
            )
        }
        videoPreviewContainer.addView(videoTextureView)

        videoSeekBar = SeekBar(this).apply {
            max = 1000
            progress = 0
            isEnabled = false
            contentDescription = "Video seek bar"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) {
                        val duration = videoPlayer?.duration ?: 0L
                        val target = if (duration > 0L) duration * progress / 1000L else 0L
                        videoTimeText.text = "${formatVideoTime(target)} / ${formatVideoTime(duration)}"
                    }
                }

                override fun onStartTrackingTouch(seekBar: SeekBar?) {
                    isUserSeeking = true
                }

                override fun onStopTrackingTouch(seekBar: SeekBar?) {
                    val player = videoPlayer
                    val duration = player?.duration ?: 0L
                    if (player != null && duration > 0L) {
                        player.seekTo(duration * videoSeekBar.progress / 1000L)
                    }
                    isUserSeeking = false
                }
            })
        }

        videoTimeText = TextView(this).apply {
            text = "00:00 / 00:00"
            textSize = 12f
            setTextColor(0xFFCCCCCC.toInt())
            gravity = Gravity.END
            setPadding(0, 0, 0, 12)
        }

        videoTextureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {
                selectedVideoUri?.let { loadVideoPreview(it) }
            }
            override fun onSurfaceTextureSizeChanged(surface: android.graphics.SurfaceTexture, width: Int, height: Int) {}
            override fun onSurfaceTextureDestroyed(surface: android.graphics.SurfaceTexture): Boolean = true
            override fun onSurfaceTextureUpdated(surface: android.graphics.SurfaceTexture) {}
        }

        muteOriginalSwitch = Switch(this).apply {
            text = "Original Video Audio: MUTE"
            textSize = 16f
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = true
            setPadding(8, 12, 8, 12)
            setOnCheckedChangeListener { _, checked ->
                originalVideoMuted = checked
                text = if (checked) "Original Video Audio: MUTE" else "Original Video Audio: ON"
                applyOriginalAudioMute()
                Toast.makeText(this@MainActivity, if (checked) "Original video sound muted" else "Original video sound on", Toast.LENGTH_SHORT).show()
            }
        }

        val btnSelectVideo = Button(this).apply {
            text = "1. SELECT VIDEO FROM GALLERY"
            setOnClickListener {
                statusText.text = "Opening Gallery..."
                pickVideoLauncher.launch(arrayOf("video/*"))
            }
        }

        val ratioLabel = TextView(this).apply {
            text = "3. VIDEO FORMAT"
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 18, 0, 8)
        }
        val ratioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val rbShorts = RadioButton(context).apply { text = "9:16 SHORTS"; setTextColor(0xFFFFFFFF.toInt()); id = 91; isChecked = true }
            val rbYoutube = RadioButton(context).apply { text = "16:9 YOUTUBE"; setTextColor(0xFFFFFFFF.toInt()); id = 92 }
            addView(rbShorts)
            addView(rbYoutube)
            setOnCheckedChangeListener { _, checkedId ->
                selectedAspectRatio = if (checkedId == 92) 1 else 0
                statusText.text = if (selectedAspectRatio == 0) "Export format: 9:16 YouTube Shorts / Reels." else "Export format: 16:9 YouTube video."
            }
        }

        val voiceLabel = TextView(this).apply {
            text = "4. SELECT VOICE MODE"
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 20, 0, 8)
        }

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val rbNormal = RadioButton(context).apply { text = "NORMAL"; setTextColor(0xFFFFFFFF.toInt()); id = 1; isChecked = true }
            val rbPatti = RadioButton(context).apply { text = "PATTI"; setTextColor(0xFFFFD54F.toInt()); id = 2 }
            val rbPeran = RadioButton(context).apply { text = "PERAN"; setTextColor(0xFF4FC3F7.toInt()); id = 3 }
            addView(rbNormal)
            addView(rbPatti)
            addView(rbPeran)

            setOnCheckedChangeListener { _, checkedId ->
                currentVoiceMode = when (checkedId) {
                    2 -> 1
                    3 -> 2
                    else -> 0
                }
                voiceLowPassState = 0f
                statusText.text = when (currentVoiceMode) {
                    1 -> "PATTI tone effect selected. Press START DUBBING."
                    2 -> "PERAN tone effect selected. Press START DUBBING."
                    else -> "NORMAL voice selected."
                }
            }
        }

        btnRecord = Button(this).apply {
            text = "5. START / STOP DUBBING"
            setBackgroundColor(0xFF2E7D32.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (!isRecording) {
                    checkPermissionAndStart()
                } else {
                    stopDubbing()
                }
            }
        }

        btnPauseResume = Button(this).apply {
            text = "PAUSE"
            isEnabled = false
            setBackgroundColor(0xFFEF6C00.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener {
                if (isRecording) {
                    isPaused = !isPaused
                    if (isPaused) {
                        videoPlayer?.pause()
                        text = "RESUME"
                        statusText.text = "Recording paused. Press RESUME to continue."
                    } else {
                        videoPlayer?.play()
                        text = "PAUSE"
                        statusText.text = "Recording resumed. Speak now."
                    }
                }
            }
        }

        val volumeLabel = TextView(this).apply {
            text = "DUBBING VOICE VOLUME: 100%"
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 16, 0, 4)
        }
        val voiceVolumeSeek = SeekBar(this).apply {
            max = 200
            progress = 100
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    voiceVolume = progress / 100f
                    volumeLabel.text = "DUBBING VOICE VOLUME: $progress%"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        btnSaveDraft = Button(this).apply {
            text = "SAVE DRAFT"
            setOnClickListener { saveDraft() }
        }
        val noiseReductionSwitch = Switch(this).apply {
            text = "BASIC NOISE GATE (OFFLINE)"
            setTextColor(0xFFFFFFFF.toInt())
            isChecked = true
            setOnCheckedChangeListener { _, checked -> noiseReductionEnabled = checked }
        }
        val syncLabel = TextView(this).apply {
            text = "VOICE SYNC: 0 ms"
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 14, 0, 4)
        }
        val syncSeek = SeekBar(this).apply {
            max = 100
            progress = 50
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                    syncOffsetMs = (progress - 50) * 100
                    syncLabel.text = "VOICE SYNC: ${if (syncOffsetMs > 0) "+" else ""}${syncOffsetMs} ms"
                }
                override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                override fun onStopTrackingTouch(seekBar: SeekBar?) {}
            })
        }
        val captionLabel = TextView(this).apply {
            text = "CAPTION TEXT (Tamil / Telugu / English)"
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 14, 0, 4)
        }
        captionLanguage = Spinner(this).apply {
            adapter = ArrayAdapter(this@MainActivity, android.R.layout.simple_spinner_dropdown_item, listOf("Tamil", "Telugu", "English"))
        }
        captionInput = EditText(this).apply {
            hint = "Type caption here (single caption for the full video)"
            setTextColor(0xFFFFFFFF.toInt())
            setHintTextColor(0xFFAAAAAA.toInt())
            setBackgroundColor(0xFF292929.toInt())
        }
        btnPreviewDub = Button(this).apply {
            text = "PREVIEW RECORDED DUBBING"
            setOnClickListener { previewDubbing() }
        }

        val btnEraseDubbing = Button(this).apply {
            text = "ERASE ALL DUBBING"
            setBackgroundColor(0xFFB71C1C.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            setOnClickListener { confirmEraseDubbing() }
        }

        val actionRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 20, 0, 8)
        }

        btnExport = Button(this).apply {
            text = "6. EXPORT / SAVE"
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 12, 0)
            }
            setOnClickListener { showFileNameDialogAndExport() }
        }

        btnShare = Button(this).apply {
            text = "7. SHARE"
            setBackgroundColor(0xFF6A1B9A.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(12, 0, 0, 0)
            }
            setOnClickListener { shareExportedFile() }
        }

        actionRow.addView(btnExport)
        actionRow.addView(btnShare)

        statusText = TextView(this).apply {
            text = "TIP: Use headphones to reduce echo. Select a video, choose a voice mode, then start dubbing."
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 20, 0, 0)
        }

        rootLayout.addView(titleText)
        rootLayout.addView(videoPreviewContainer)
        rootLayout.addView(videoSeekBar)
        rootLayout.addView(videoTimeText)
        rootLayout.addView(muteOriginalSwitch)
        rootLayout.addView(btnSelectVideo)
        rootLayout.addView(ratioLabel)
        rootLayout.addView(ratioGroup)
        rootLayout.addView(voiceLabel)
        rootLayout.addView(radioGroup)
        rootLayout.addView(volumeLabel)
        rootLayout.addView(voiceVolumeSeek)
        rootLayout.addView(noiseReductionSwitch)
        rootLayout.addView(syncLabel)
        rootLayout.addView(syncSeek)
        rootLayout.addView(captionLabel)
        rootLayout.addView(captionLanguage)
        rootLayout.addView(captionInput)
        rootLayout.addView(btnRecord)
        rootLayout.addView(btnPauseResume)
        rootLayout.addView(btnEraseDubbing)
        rootLayout.addView(btnSaveDraft)
        rootLayout.addView(btnPreviewDub)
        rootLayout.addView(actionRow)
        rootLayout.addView(statusText)

        scrollView.addView(rootLayout)
        setContentView(scrollView)
        restoreDraft()
        seekHandler.post(seekUpdater)
    }

    private fun formatVideoTime(milliseconds: Long): String {
        if (milliseconds <= 0L) return "00:00"
        val totalSeconds = milliseconds / 1000L
        val hours = totalSeconds / 3600L
        val minutes = (totalSeconds % 3600L) / 60L
        val seconds = totalSeconds % 60L
        return if (hours > 0L) {
            String.format(java.util.Locale.US, "%02d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), 101)
        } else {
            startDubbing()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 101) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startDubbing()
            } else {
                statusText.text = "Microphone permission is required. Please allow it in Settings."
                Toast.makeText(this, "Microphone permission was not granted.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startDubbing() {
        if (selectedVideoUri == null) {
            statusText.text = "Please select a video from Gallery first."
            Toast.makeText(this, "Please select a video from Gallery first.", Toast.LENGTH_LONG).show()
            return
        }
        isRecording = true
        isPaused = false
        btnPauseResume.isEnabled = true
        btnPauseResume.text = "PAUSE"
        btnExport.isEnabled = false
        btnRecord.text = "STOP RECORDING"
        btnRecord.setBackgroundColor(0xFFC62828.toInt())
        statusText.text = "Recording in progress... Speak now."

        videoPlayer?.let { player ->
            if (player.playbackState == Player.STATE_ENDED) player.seekTo(0)
            player.play()
        }

        voiceLowPassState = 0f
        recordingThread = thread {
            val sampleRate = 44100
            val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
            val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
            if (minBufSize <= 0) {
                isRecording = false
                runOnUiThread {
                    btnRecord.text = "5. START / STOP DUBBING"
                    btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
                    btnPauseResume.isEnabled = false
                    btnPauseResume.text = "PAUSE"
                    btnExport.isEnabled = true
                    statusText.text = "Could not start audio recording. Please check microphone permission."
                    recordingThread = null
                }
                return@thread
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                isRecording = false
                runOnUiThread {
                    btnExport.isEnabled = true
                    btnRecord.text = "5. START / STOP DUBBING"
                    btnPauseResume.isEnabled = false
                    statusText.text = "Microphone permission is required."
                    recordingThread = null
                }
                return@thread
            }

            val recorder = AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, sampleRate, channelConfigIn, audioFormat, minBufSize)
            val player = AudioTrack.Builder()
                .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build())
                .setAudioFormat(AudioFormat.Builder().setEncoding(audioFormat).setSampleRate(sampleRate).setChannelMask(channelConfigOut).build())
                .setBufferSizeInBytes(minBufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()

            val outFile = File(filesDir, "temp_dub_voice.wav")
            val fos = FileOutputStream(outFile)
            fos.write(ByteArray(44))

            recorder.startRecording()
            player.play()

            val buffer = ShortArray(minBufSize / 2)
            var totalAudioLen = 0L

            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0 && !isPaused) {
                    val cleaned = applyNoiseGate(buffer, read)
                    val toned = applyVoiceEffect(cleaned, read, currentVoiceMode)
                    val processed = ShortArray(toned.size) { i ->
                        (toned[i].toFloat() * voiceVolume).toInt().coerceIn(-32768, 32767).toShort()
                    }
                    player.write(processed, 0, processed.size)

                    val byteBuf = ByteArray(processed.size * 2)
                    for (i in processed.indices) {
                        byteBuf[i * 2] = (processed[i].toInt() and 0x00FF).toByte()
                        byteBuf[i * 2 + 1] = (processed[i].toInt() shr 8).toByte()
                    }
                    fos.write(byteBuf)
                    totalAudioLen += byteBuf.size
                }
            }

            recorder.stop()
            recorder.release()
            player.stop()
            player.release()
            fos.close()

            writeWavHeader(outFile, totalAudioLen, sampleRate.toLong(), 1, (sampleRate * 2).toLong())
            tempDubWavFile = outFile

            runOnUiThread {
                recordingThread = null
                btnRecord.isEnabled = true
                isPaused = false
                btnRecord.text = "5. START / STOP DUBBING"
                btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
                btnPauseResume.isEnabled = false
                btnPauseResume.text = "PAUSE"
                btnExport.isEnabled = true
                statusText.text = "Recording complete. Press EXPORT / SAVE to create your video."
            }
        }
    }

    private fun saveDraft() {
        if (isRecording || recordingThread?.isAlive == true) {
            Toast.makeText(this, "Stop recording before saving the draft.", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = selectedVideoUri
        if (uri == null) {
            statusText.text = "Select a video before saving a draft."
            return
        }
        try {
            getSharedPreferences("patti_peran_draft", MODE_PRIVATE).edit()
                .putString("video_uri", uri.toString())
                .putInt("aspect_ratio", selectedAspectRatio)
                .putInt("voice_mode", currentVoiceMode)
                .putInt("sync_offset_ms", syncOffsetMs)
                .putString("caption_text", captionInput.text.toString())
                .putInt("caption_language", captionLanguage.selectedItemPosition)
                .putString("wav_path", tempDubWavFile?.absolutePath ?: "")
                .apply()
            statusText.text = "Draft saved on this phone. Reopen the app to continue."
            Toast.makeText(this, "Draft saved.", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            statusText.text = "Could not save draft."
        }
    }

    private fun restoreDraft() {
        val prefs = getSharedPreferences("patti_peran_draft", MODE_PRIVATE)
        val savedUri = prefs.getString("video_uri", null) ?: return
        try {
            selectedVideoUri = Uri.parse(savedUri)
            selectedAspectRatio = prefs.getInt("aspect_ratio", 0)
            currentVoiceMode = prefs.getInt("voice_mode", 0)
            syncOffsetMs = prefs.getInt("sync_offset_ms", 0)
            captionInput.setText(prefs.getString("caption_text", "") ?: "")
            captionLanguage.setSelection(prefs.getInt("caption_language", 0).coerceIn(0, 2))
            val savedWav = prefs.getString("wav_path", "") ?: ""
            if (savedWav.isNotBlank()) {
                val candidate = File(savedWav)
                if (candidate.exists() && candidate.length() > 44L) tempDubWavFile = candidate
            }
            btnExport.isEnabled = tempDubWavFile != null
            statusText.text = "Draft restored. Your previous video selection is ready."
            if (videoTextureView.isAvailable) selectedVideoUri?.let { loadVideoPreview(it) }
        } catch (e: Exception) {
            statusText.text = "Saved draft could not be restored. Please select the video again."
        }
    }

    private fun createSyncedWav(source: File, output: File, offsetMs: Int): Boolean {
        return try {
            val offsetBytes = (kotlin.math.abs(offsetMs).toLong() * 44100L * 2L) / 1000L
            FileInputStream(source).use { input ->
                FileOutputStream(output).use { out ->
                    input.skip(44L)
                    out.write(ByteArray(44))
                    if (offsetMs > 0) out.write(ByteArray(offsetBytes.toInt()))
                    if (offsetMs < 0) input.skip(offsetBytes)
                    input.copyTo(out)
                }
            }
            val dataLength = output.length() - 44L
            if (dataLength <= 0L || dataLength > 0x7fffffffL) return false
            writeWavHeader(output, dataLength, 44100L, 1, 88200L)
            true
        } catch (e: Exception) {
            android.util.Log.e("PattiPeranSync", "Could not adjust voice sync", e)
            false
        }
    }

    private fun previewDubbing() {
        val wav = tempDubWavFile
        if (wav == null || !wav.exists() || wav.length() <= 44L) {
            statusText.text = "Record and stop your dubbing before previewing."
            Toast.makeText(this, "No completed dubbing to preview.", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            dubPreviewPlayer?.release()
            dubPreviewPlayer = android.media.MediaPlayer().apply {
                setDataSource(wav.absolutePath)
                setOnPreparedListener { media ->
                    media.start()
                    statusText.text = "Playing dubbing preview..."
                }
                setOnCompletionListener { media ->
                    media.release()
                    dubPreviewPlayer = null
                    statusText.text = "Dubbing preview finished."
                }
                setOnErrorListener { media, _, _ ->
                    media.release()
                    dubPreviewPlayer = null
                    statusText.text = "Could not play dubbing preview."
                    true
                }
                prepareAsync()
            }
        } catch (e: Exception) {
            statusText.text = "Preview error. Please try recording again."
        }
    }

    private fun confirmEraseDubbing() {
        if (isRecording || recordingThread?.isAlive == true) {
            Toast.makeText(this, "Stop recording and wait before erasing.", Toast.LENGTH_LONG).show()
            return
        }

        val wavFile = tempDubWavFile ?: File(cacheDir, "temp_dub_voice.wav")
        if (!wavFile.exists()) {
            statusText.text = "There is no dubbing recording to erase."
            Toast.makeText(this, "No dubbing recording found.", Toast.LENGTH_SHORT).show()
            return
        }

        AlertDialog.Builder(this)
            .setTitle("ERASE ALL DUBBING?")
            .setMessage("This will delete the complete current dubbing recording. Your selected video will remain. You can record again from the beginning.")
            .setPositiveButton("Erase") { _, _ ->
                val deleted = wavFile.delete()
                tempDubWavFile = null
                getSharedPreferences("patti_peran_draft", MODE_PRIVATE).edit().remove("wav_path").apply()
                btnExport.isEnabled = false
                videoPlayer?.seekTo(0)
                videoPlayer?.pause()
                statusText.text = if (deleted || !wavFile.exists()) {
                    "Dubbing erased. Select START DUBBING to record again."
                } else {
                    "Could not erase the recording. Please try again."
                }
                Toast.makeText(
                    this,
                    if (deleted || !wavFile.exists()) "All dubbing erased." else "Erase failed.",
                    Toast.LENGTH_LONG
                ).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun showFileNameDialogAndExport() {
        if (isRecording || recordingThread?.isAlive == true) {
            Toast.makeText(this, "Please stop recording and wait for it to finish.", Toast.LENGTH_LONG).show()
            return
        }
        val wavFile = tempDubWavFile
        if (wavFile == null || !wavFile.exists()) {
            statusText.text = "Select a video, record your dubbing, and stop before exporting."
            Toast.makeText(this, "Please record and stop dubbing before exporting.", Toast.LENGTH_LONG).show()
            return
        }

        val inputEdit = EditText(this).apply {
            hint = "Example: Patti_Comedy_1"
            setText("PattiPeran_${System.currentTimeMillis() / 1000}")
        }

        AlertDialog.Builder(this)
            .setTitle("ENTER FILE NAME")
            .setMessage("Enter a name for the video to save in Gallery:")
            .setView(inputEdit)
            .setPositiveButton("Save to Gallery") { _, _ ->
                val rawName = inputEdit.text.toString().trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val finalName = if (rawName.isEmpty()) "PattiPeran_Dub" else rawName
                exportToGallery(wavFile, finalName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportToGallery(wavFile: File, fileName: String) {
        val captionForExport = captionInput.text.toString().trim()
        btnExport.isEnabled = false
        statusText.text = "Preparing your dubbed MP4..."
        thread {
            val videoUri = selectedVideoUri
            if (videoUri == null) {
                runOnUiThread {
                    btnExport.isEnabled = true
                    statusText.text = "Please select a video before exporting."
                }
                return@thread
            }

            val tempMp4 = File(cacheDir, "temp_merged_out.mp4")
            if (tempMp4.exists()) tempMp4.delete()

            runOnUiThread {
                statusText.text = if (selectedAspectRatio == 0) {
                    "Exporting 9:16 video with your recorded voice..."
                } else {
                    "Exporting 16:9 video with your recorded voice..."
                }
            }

            val shiftedWav = File(cacheDir, "temp_dub_voice_synced.wav")
            val syncReady = createSyncedWav(wavFile, shiftedWav, syncOffsetMs)
            val merged = syncReady && composeVideoAndWavToMp4(videoUri, shiftedWav, tempMp4, selectedAspectRatio, captionForExport)
            if (merged && tempMp4.exists() && tempMp4.length() > 1000) {
                val savedUri = saveFileToMediaStoreVideo(tempMp4, "$fileName.mp4")
                if (savedUri != null) {
                    lastExportedUri = savedUri
                    lastExportedMime = "video/mp4"
                    runOnUiThread {
                        btnExport.isEnabled = true
                        statusText.text = "Success! Saved to Movies/PattiPeranDubbing/$fileName.mp4"
                        Toast.makeText(this, "Dubbed video saved to Gallery!", Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
            }

            runOnUiThread {
                btnExport.isEnabled = true
                statusText.text = "MP4 export failed. Your recording is kept. Try another standard MP4 video."
                Toast.makeText(this, "Video export failed. No audio-only file was saved.", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun saveFileToMediaStoreVideo(sourceFile: File, displayName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/PattiPeranDubbing")
                    put(MediaStore.Video.Media.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            }
            val itemUri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Video.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            itemUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun saveFileToMediaStoreAudio(sourceFile: File, displayName: String): Uri? {
        return try {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Audio.Media.MIME_TYPE, "audio/wav")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.Audio.Media.RELATIVE_PATH, Environment.DIRECTORY_MUSIC + "/PattiPeranDubbing")
                    put(MediaStore.Audio.Media.IS_PENDING, 1)
                }
            }
            val resolver = contentResolver
            val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            }
            val itemUri = resolver.insert(collection, values) ?: return null
            resolver.openOutputStream(itemUri)?.use { out ->
                FileInputStream(sourceFile).use { input ->
                    input.copyTo(out)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                values.clear()
                values.put(MediaStore.Audio.Media.IS_PENDING, 0)
                resolver.update(itemUri, values, null, null)
            }
            itemUri
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    @OptIn(UnstableApi::class)
    private fun composeVideoAndWavToMp4(
        videoUri: Uri,
        wavFile: File,
        outputFile: File,
        ratio: Int,
        captionText: String
    ): Boolean {
        val width = if (ratio == 0) 1080 else 1920
        val height = if (ratio == 0) 1920 else 1080
        val completed = CountDownLatch(1)
        val startPosted = CountDownLatch(1)
        val success = java.util.concurrent.atomic.AtomicBoolean(false)
        val failureMessage = java.util.concurrent.atomic.AtomicReference<String?>(null)
        val transformerHolder = arrayOfNulls<Transformer>(1)

        return try {
            if (!wavFile.exists() || wavFile.length() <= 44L) {
                failureMessage.set("The recorded voice file is empty.")
                return false
            }

            // Keep the selected video as the visual track, remove its original audio,
            // and add the recorded PCM WAV as a parallel audio-only sequence.
            val videoItem = EditedMediaItem.Builder(MediaItem.fromUri(videoUri))
                .setRemoveAudio(true)
                .setEffects(
                    Effects(
                        emptyList(),
                        listOf(
                            Presentation.createForWidthAndHeight(
                                width,
                                height,
                                Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                            )
                        ) + if (captionText.isNotBlank()) {
                            val styledCaption = SpannableString(captionText).apply {
                                setSpan(ForegroundColorSpan(android.graphics.Color.WHITE), 0, length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                            }
                            listOf(OverlayEffect(ImmutableList.of<TextureOverlay>(TextOverlay.createStaticTextOverlay(styledCaption))))
                        } else emptyList()
                    )
                )
                .build()

            val dubbedAudioItem = EditedMediaItem.Builder(
                MediaItem.fromUri(Uri.fromFile(wavFile))
            )
                .setRemoveVideo(true)
                .build()

            val videoSequence = EditedMediaItemSequence(listOf(videoItem))
            val audioSequence = EditedMediaItemSequence(listOf(dubbedAudioItem))
            val composition = Composition.Builder(videoSequence, audioSequence).build()

            // Transformer must be created and started on the app's main thread.
            runOnUiThread {
                try {
                    val transformer = Transformer.Builder(this)
                        .addListener(object : Transformer.Listener {
                            override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                                success.set(outputFile.exists() && outputFile.length() > 1000L)
                                completed.countDown()
                            }

                            override fun onError(
                                composition: Composition,
                                exportResult: ExportResult,
                                exportException: ExportException
                            ) {
                                failureMessage.set(exportException.message ?: "Media3 export error")
                                exportException.printStackTrace()
                                success.set(false)
                                completed.countDown()
                            }
                        })
                        .build()
                    transformerHolder[0] = transformer
                    transformer.start(composition, outputFile.absolutePath)
                } catch (e: Exception) {
                    failureMessage.set(e.message ?: "Could not start MP4 export")
                    completed.countDown()
                } finally {
                    startPosted.countDown()
                }
            }

            if (!startPosted.await(30, TimeUnit.SECONDS)) {
                failureMessage.set("Timed out while starting MP4 export")
                return false
            }
            if (!completed.await(10, TimeUnit.MINUTES)) {
                runOnUiThread { transformerHolder[0]?.cancel() }
                failureMessage.set("MP4 export timed out")
                return false
            }
            if (!success.get()) {
                android.util.Log.e("PattiPeranExport", failureMessage.get() ?: "MP4 export failed")
            }
            success.get()
        } catch (e: Exception) {
            android.util.Log.e("PattiPeranExport", "MP4 composition export failed", e)
            false
        }
    }

    private fun shareExportedFile() {
        val uriToShare = lastExportedUri
        if (uriToShare == null) {
            statusText.text = "Please export and save the video before sharing."
            Toast.makeText(this, "Please export and save the video first.", Toast.LENGTH_LONG).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = lastExportedMime
            putExtra(Intent.EXTRA_STREAM, uriToShare)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "Share dubbed video"))
    }

    // Lightweight offline voice-tone presets. These alter tone/EQ while preserving
    // the exact sample count, so the recorded audio duration stays aligned with video.
    private fun applyNoiseGate(input: ShortArray, length: Int): ShortArray {
        if (!noiseReductionEnabled || length <= 0) return input.copyOf(length)
        var sum = 0.0
        for (i in 0 until length) {
            val sample = input[i].toDouble()
            sum += sample * sample
        }
        val rms = kotlin.math.sqrt(sum / length)
        val gain = when {
            rms < 180.0 -> 0.08f
            rms < 420.0 -> 0.35f
            else -> 1.0f
        }
        return ShortArray(length) { i ->
            (input[i].toFloat() * gain).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    private fun applyVoiceEffect(input: ShortArray, length: Int, mode: Int): ShortArray {
        if (mode == 0) return input.copyOf(length)
        val output = ShortArray(length)
        // Stronger EQ contrast makes the two offline presets easier to hear.
        // These are tone filters, not AI age/gender voice conversion.
        val smoothing = if (mode == 1) 0.12f else 0.018f
        val dryMix = if (mode == 1) 0.20f else 0.24f
        val toneMix = if (mode == 1) 0.80f else 0.76f
        for (i in 0 until length) {
            val sample = input[i].toFloat()
            voiceLowPassState += smoothing * (sample - voiceLowPassState)
            val tone = if (mode == 1) voiceLowPassState else sample - voiceLowPassState
            val mixed = (sample * dryMix) + (tone * toneMix)
            // Gentle peak control prevents clipping after the tone mix.
            val limited = (mixed / (1f + kotlin.math.abs(mixed) / 42000f)).coerceIn(-32768f, 32767f)
            output[i] = limited.toInt().toShort()
        }
        return output
    }

    private fun stopDubbing() {
        isRecording = false
        isPaused = false
        btnPauseResume.isEnabled = false
        btnPauseResume.text = "PAUSE"
        btnRecord.isEnabled = false
        btnRecord.text = "Stopping recording..."
        statusText.text = "Finalizing recording... Please wait."
        videoPlayer?.pause()
    }

    private fun loadVideoPreview(uri: Uri) {
        if (!videoTextureView.isAvailable) {
            statusText.text = "Preparing video preview..."
            return
        }
        try {
            videoPlayer?.release()
            videoPlayer = ExoPlayer.Builder(this).build().also { player ->
                player.setVideoTextureView(videoTextureView)
                player.volume = if (originalVideoMuted) 0f else 1f
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        when (playbackState) {
                            Player.STATE_READY -> {
                                videoSeekBar.isEnabled = true
                                videoSeekBar.progress = 0
                                videoTimeText.text = "00:00 / ${formatVideoTime(player.duration)}"
                                statusText.text = "Video loaded. Drag the seek dot to move through the video."
                            }
                            Player.STATE_BUFFERING -> statusText.text = "Loading video preview..."
                            Player.STATE_ENDED -> statusText.text = "Video playback finished. Press START DUBBING to replay."
                        }
                    }
                    override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                        statusText.text = "Video playback error: ${error.errorCodeName}. Try a standard MP4 (H.264/AAC)."
                    }
                })
                player.setMediaItem(MediaItem.fromUri(uri))
                player.prepare()
                player.playWhenReady = true
            }
        } catch (e: Exception) {
            statusText.text = "Unable to open video preview: ${e.message ?: "unknown error"}"
        }
    }

    private fun applyOriginalAudioMute() {
        videoPlayer?.volume = if (originalVideoMuted) 0f else 1f
    }

    override fun onStop() {
        super.onStop()
        videoPlayer?.pause()
    }

    override fun onDestroy() {
        seekHandler.removeCallbacks(seekUpdater)
        videoPlayer?.release()
        videoPlayer = null
        dubPreviewPlayer?.release()
        dubPreviewPlayer = null
        super.onDestroy()
    }

    private fun writeWavHeader(file: File, totalAudioLen: Long, sampleRate: Long, channels: Int, byteRate: Long) {
        val totalDataLen = totalAudioLen + 36
        val header = ByteArray(44)
        header[0] = 'R'.code.toByte(); header[1] = 'I'.code.toByte(); header[2] = 'F'.code.toByte(); header[3] = 'F'.code.toByte()
        header[4] = (totalDataLen and 0xff).toByte(); header[5] = (totalDataLen shr 8 and 0xff).toByte()
        header[6] = (totalDataLen shr 16 and 0xff).toByte(); header[7] = (totalDataLen shr 24 and 0xff).toByte()
        header[8] = 'W'.code.toByte(); header[9] = 'A'.code.toByte(); header[10] = 'V'.code.toByte(); header[11] = 'E'.code.toByte()
        header[12] = 'f'.code.toByte(); header[13] = 'm'.code.toByte(); header[14] = 't'.code.toByte(); header[15] = ' '.code.toByte()
        header[16] = 16; header[17] = 0; header[18] = 0; header[19] = 0
        header[20] = 1; header[21] = 0
        header[22] = channels.toByte(); header[23] = 0
        header[24] = (sampleRate and 0xff).toByte(); header[25] = (sampleRate shr 8 and 0xff).toByte()
        header[26] = (sampleRate shr 16 and 0xff).toByte(); header[27] = (sampleRate shr 24 and 0xff).toByte()
        header[28] = (byteRate and 0xff).toByte(); header[29] = (byteRate shr 8 and 0xff).toByte()
        header[30] = (byteRate shr 16 and 0xff).toByte(); header[31] = (byteRate shr 24 and 0xff).toByte()
        header[32] = 2; header[33] = 0; header[34] = 16; header[35] = 0
        header[36] = 'd'.code.toByte(); header[37] = 'a'.code.toByte(); header[38] = 't'.code.toByte(); header[39] = 'a'.code.toByte()
        header[40] = (totalAudioLen and 0xff).toByte(); header[41] = (totalAudioLen shr 8 and 0xff).toByte()
        header[42] = (totalAudioLen shr 16 and 0xff).toByte(); header[43] = (totalAudioLen shr 24 and 0xff).toByte()

        RandomAccessFile(file, "rw").use { raf ->
            raf.seek(0)
            raf.write(header)
        }
    }
}
