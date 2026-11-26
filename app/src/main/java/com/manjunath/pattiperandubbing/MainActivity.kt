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
import android.os.Environment
import android.provider.MediaStore
import android.widget.*
import android.view.Gravity
import android.view.TextureView
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.Effects
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Presentation
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
    private lateinit var muteOriginalSwitch: Switch
    
    @Volatile private var originalVideoMuted = true

    @Volatile private var isRecording = false
    @Volatile private var currentVoiceMode = 0
    private var voiceLowPassState = 0f
    private var recordingThread: Thread? = null
    private var selectedVideoUri: Uri? = null
    private var tempDubWavFile: File? = null
    private var lastExportedUri: Uri? = null
    private var lastExportedMime: String = "video/mp4"
    @Volatile private var selectedAspectRatio = 0 // 0 = 9:16 Shorts, 1 = 16:9 YouTube

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
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
                pickVideoLauncher.launch("video/*")
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
        rootLayout.addView(muteOriginalSwitch)
        rootLayout.addView(btnSelectVideo)
        rootLayout.addView(ratioLabel)
        rootLayout.addView(ratioGroup)
        rootLayout.addView(voiceLabel)
        rootLayout.addView(radioGroup)
        rootLayout.addView(btnRecord)
        rootLayout.addView(actionRow)
        rootLayout.addView(statusText)

        scrollView.addView(rootLayout)
        setContentView(scrollView)
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
                    btnRecord.text = "3. START / STOP DUBBING"
                    btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
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
                    btnRecord.text = "3. START / STOP DUBBING"
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

            val outFile = File(cacheDir, "temp_dub_voice.wav")
            val fos = FileOutputStream(outFile)
            fos.write(ByteArray(44))

            recorder.startRecording()
            player.play()

            val buffer = ShortArray(minBufSize / 2)
            var totalAudioLen = 0L

            while (isRecording) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) {
                    val processed = applyVoiceEffect(buffer, read, currentVoiceMode)
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
                btnRecord.text = "3. START / STOP DUBBING"
                btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
                btnExport.isEnabled = true
                statusText.text = "Recording complete. Press EXPORT / SAVE to create your video."
            }
        }
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
        statusText.text = "Creating video and saving to Gallery... Please wait."
        thread {
            val videoUri = selectedVideoUri
            if (videoUri != null) {
                val tempAspectVideo = File(cacheDir, "temp_aspect_video.mp4")
                val tempMp4 = File(cacheDir, "temp_merged_out.mp4")
                if (tempAspectVideo.exists()) tempAspectVideo.delete()
                if (tempMp4.exists()) tempMp4.delete()

                runOnUiThread { statusText.text = if (selectedAspectRatio == 0) "Cropping video to 9:16 Shorts..." else "Cropping video to 16:9 YouTube..." }
                val transformed = transformVideoToAspectRatio(videoUri, tempAspectVideo, selectedAspectRatio)
                val mergeSource = if (transformed) Uri.fromFile(tempAspectVideo) else videoUri
                val merged = transformed && mergeVideoAndWavToMp4(mergeSource, wavFile, tempMp4)
                if (merged && tempMp4.exists() && tempMp4.length() > 1000) {
                    val savedUri = saveFileToMediaStoreVideo(tempMp4, "$fileName.mp4")
                    if (savedUri != null) {
                        lastExportedUri = savedUri
                        lastExportedMime = "video/mp4"
                        runOnUiThread {
                            statusText.text = "Success! Saved to Movies/PattiPeranDubbing/$fileName.mp4"
                            Toast.makeText(this, "Video saved to Gallery!", Toast.LENGTH_LONG).show()
                        }
                        return@thread
                    }
                }
            }

            // Fallback: Save audio via MediaStore Music if video merge was skipped
            val savedAudioUri = saveFileToMediaStoreAudio(wavFile, "$fileName.wav")
            if (savedAudioUri != null) {
                lastExportedUri = savedAudioUri
                lastExportedMime = "audio/wav"
                runOnUiThread {
                    statusText.text = "Audio saved to Music/PattiPeranDubbing/$fileName.wav"
                    Toast.makeText(this, "Audio saved to Music folder.", Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread {
                    statusText.text = "Could not save the file. Please try again."
                }
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
    private fun transformVideoToAspectRatio(inputUri: Uri, outputFile: File, ratio: Int): Boolean {
        val width = if (ratio == 0) 1080 else 1920
        val height = if (ratio == 0) 1920 else 1080
        val latch = CountDownLatch(1)
        var success = false
        try {
            val editedItem = EditedMediaItem.Builder(MediaItem.fromUri(inputUri))
                .setEffects(Effects(
                    emptyList(),
                    listOf(Presentation.createForWidthAndHeight(
                        width,
                        height,
                        Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                    ))
                ))
                .build()
            val transformer = Transformer.Builder(this)
                .addListener(object : Transformer.Listener {
                    override fun onCompleted(composition: Composition, exportResult: ExportResult) {
                        success = outputFile.exists() && outputFile.length() > 1000
                        latch.countDown()
                    }
                    override fun onError(composition: Composition, exportResult: ExportResult, exportException: ExportException) {
                        exportException.printStackTrace()
                        success = false
                        latch.countDown()
                    }
                })
                .build()
            transformer.start(editedItem, outputFile.absolutePath)
            if (!latch.await(5, TimeUnit.MINUTES)) {
                transformer.cancel()
                return false
            }
            return success
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    private fun mergeVideoAndWavToMp4(videoUri: Uri, wavFile: File, outMp4: File): Boolean {
        val encodedAudio = File(cacheDir, "dub_audio_track.mp4")
        return try {
            if (encodedAudio.exists()) encodedAudio.delete()
            if (!encodeWavToAacMp4(wavFile, encodedAudio)) return false

            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, videoUri)
            val rotation = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            retriever.release()

            val videoExtractor = MediaExtractor()
            videoExtractor.setDataSource(this, videoUri, null)
            var videoSourceTrack = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until videoExtractor.trackCount) {
                val format = videoExtractor.getTrackFormat(i)
                if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("video/")) {
                    videoSourceTrack = i
                    videoFormat = format
                    break
                }
            }
            if (videoSourceTrack < 0 || videoFormat == null) {
                videoExtractor.release()
                return false
            }

            val audioExtractor = MediaExtractor()
            audioExtractor.setDataSource(encodedAudio.absolutePath)
            var audioSourceTrack = -1
            var audioFormat: MediaFormat? = null
            for (i in 0 until audioExtractor.trackCount) {
                val format = audioExtractor.getTrackFormat(i)
                if ((format.getString(MediaFormat.KEY_MIME) ?: "").startsWith("audio/")) {
                    audioSourceTrack = i
                    audioFormat = format
                    break
                }
            }
            if (audioSourceTrack < 0 || audioFormat == null) {
                videoExtractor.release()
                audioExtractor.release()
                return false
            }

            val muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val outVideoTrack = muxer.addTrack(videoFormat)
            val outAudioTrack = muxer.addTrack(audioFormat)
            muxer.start()
            videoExtractor.selectTrack(videoSourceTrack)
            audioExtractor.selectTrack(audioSourceTrack)

            val videoMax = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(256 * 1024) else 4 * 1024 * 1024
            val audioMax = if (audioFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) audioFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(16 * 1024) else 256 * 1024
            val videoBuffer = ByteBuffer.allocateDirect(videoMax)
            val audioBuffer = ByteBuffer.allocateDirect(audioMax)
            val info = MediaCodec.BufferInfo()
            var videoDone = false
            var audioDone = false

            // Interleave audio/video samples by presentation timestamp.
            while (!videoDone || !audioDone) {
                val nextVideoTime = if (videoDone) Long.MAX_VALUE else videoExtractor.sampleTime
                val nextAudioTime = if (audioDone) Long.MAX_VALUE else audioExtractor.sampleTime
                if (nextVideoTime == -1L) videoDone = true
                if (nextAudioTime == -1L) audioDone = true
                if (videoDone && audioDone) break

                if (!videoDone && (audioDone || nextVideoTime <= nextAudioTime)) {
                    videoBuffer.clear()
                    val size = videoExtractor.readSampleData(videoBuffer, 0)
                    if (size < 0) {
                        videoDone = true
                    } else {
                        info.set(0, size, videoExtractor.sampleTime, videoExtractor.sampleFlags)
                        muxer.writeSampleData(outVideoTrack, videoBuffer, info)
                        videoExtractor.advance()
                    }
                } else if (!audioDone) {
                    audioBuffer.clear()
                    val size = audioExtractor.readSampleData(audioBuffer, 0)
                    if (size < 0) {
                        audioDone = true
                    } else {
                        info.set(0, size, audioExtractor.sampleTime, audioExtractor.sampleFlags)
                        muxer.writeSampleData(outAudioTrack, audioBuffer, info)
                        audioExtractor.advance()
                    }
                }
            }

            muxer.stop()
            muxer.release()
            videoExtractor.release()
            audioExtractor.release()
            encodedAudio.delete()
            outMp4.exists() && outMp4.length() > 1000
        } catch (e: Exception) {
            e.printStackTrace()
            try { encodedAudio.delete() } catch (_: Exception) {}
            false
        }
    }

    private fun encodeWavToAacMp4(wavFile: File, outFile: File): Boolean {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        var input: FileInputStream? = null
        return try {
            val sampleRate = 44100
            val format = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()
            muxer = MediaMuxer(outFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            input = FileInputStream(wavFile).apply { skip(44L) }

            val pcm = ByteArray(64 * 1024)
            val info = MediaCodec.BufferInfo()
            var audioTrack = -1
            var muxerStarted = false
            var inputDone = false
            var outputDone = false
            var pts = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inputIndex = encoder.dequeueInputBuffer(10000)
                    if (inputIndex >= 0) {
                        val buffer = encoder.getInputBuffer(inputIndex)!!
                        buffer.clear()
                        val count = input.read(pcm, 0, minOf(buffer.remaining(), pcm.size))
                        if (count <= 0) {
                            encoder.queueInputBuffer(inputIndex, 0, 0, pts, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val aligned = count - (count % 2)
                            buffer.put(pcm, 0, aligned)
                            encoder.queueInputBuffer(inputIndex, 0, aligned, pts, 0)
                            pts += (aligned / 2L) * 1000000L / sampleRate
                        }
                    }
                }

                val outputIndex = encoder.dequeueOutputBuffer(info, 10000)
                when {
                    outputIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        audioTrack = muxer.addTrack(encoder.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }
                    outputIndex >= 0 -> {
                        val output = encoder.getOutputBuffer(outputIndex)!!
                        if ((info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && info.size > 0 && muxerStarted) {
                            output.position(info.offset)
                            output.limit(info.offset + info.size)
                            muxer.writeSampleData(audioTrack, output, info)
                        }
                        encoder.releaseOutputBuffer(outputIndex, false)
                        if ((info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) outputDone = true
                    }
                }
            }
            input.close()
            encoder.stop()
            encoder.release()
            encoder = null
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
                muxer = null
            }
            outFile.exists() && outFile.length() > 500
        } catch (e: Exception) {
            e.printStackTrace()
            false
        } finally {
            try { input?.close() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
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
                            Player.STATE_READY -> statusText.text = "Video loaded. Press START DUBBING when ready."
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
        videoPlayer?.release()
        videoPlayer = null
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
