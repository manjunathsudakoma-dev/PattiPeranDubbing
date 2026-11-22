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

    private lateinit var videoView: VideoView
    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private lateinit var btnExport: Button
    private lateinit var btnShare: Button
    private lateinit var muteOriginalSwitch: Switch
    private var videoMediaPlayer: MediaPlayer? = null
    @Volatile private var originalVideoMuted = true

    @Volatile private var isRecording = false
    @Volatile private var currentVoiceMode = 0
    private var voiceLowPassState = 0f
    private var recordingThread: Thread? = null
    private var selectedVideoUri: Uri? = null
    private var tempDubWavFile: File? = null
    private var lastExportedUri: Uri? = null
    private var lastExportedMime: String = "video/mp4"

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            selectedVideoUri = uri
            videoView.setVideoURI(uri)
            videoView.start()
            statusText.text = "வீடியோ தயார்! Original audio mute நிலை அமைக்கப்பட்டுள்ளது."
        } else {
            statusText.text = "வீடியோ தேர்வு ரத்து செய்யப்பட்டது."
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
            text = "பாட்டி - பேரன் Live Dubbing Studio"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 20)
        }

        videoView = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                600
            )
        }
        videoView.setOnPreparedListener { mediaPlayer ->
            videoMediaPlayer = mediaPlayer
            applyOriginalAudioMute()
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
            text = "1. கேலரியிலிருந்து வீடியோ தேர்ந்தெடு"
            setOnClickListener {
                statusText.text = "Gallery திறக்கப்படுகிறது..."
                pickVideoLauncher.launch("video/*")
            }
        }

        val voiceLabel = TextView(this).apply {
            text = "2. குரல் தேர்வு (Voice Mode):"
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 20, 0, 8)
        }

        val radioGroup = RadioGroup(this).apply {
            orientation = RadioGroup.HORIZONTAL
            val rbNormal = RadioButton(context).apply { text = "இயல்பு"; setTextColor(0xFFFFFFFF.toInt()); id = 1; isChecked = true }
            val rbPatti = RadioButton(context).apply { text = "பாட்டி (Patti)"; setTextColor(0xFFFFD54F.toInt()); id = 2 }
            val rbPeran = RadioButton(context).apply { text = "பேரன் (Peran)"; setTextColor(0xFF4FC3F7.toInt()); id = 3 }
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
                    1 -> "பாட்டி Voice Effect தேர்வு செய்யப்பட்டது. Live Dubbing தொடங்கலாம்."
                    2 -> "பேரன் Voice Effect தேர்வு செய்யப்பட்டது. Live Dubbing தொடங்கலாம்."
                    else -> "இயல்பு குரல் தேர்வு செய்யப்பட்டது."
                }
            }
        }

        btnRecord = Button(this).apply {
            text = "3. Live Dubbing தொடங்கு (Start)"
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
            text = "4. Export / Save"
            setBackgroundColor(0xFF1565C0.toInt())
            setTextColor(0xFFFFFFFF.toInt())
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
                setMargins(0, 0, 12, 0)
            }
            setOnClickListener { showFileNameDialogAndExport() }
        }

        btnShare = Button(this).apply {
            text = "5. Share"
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
            text = "குறிப்பு: எக்கோ வராமல் இருக்க ஹெட்செட் (Headset) பயன்படுத்தவும்."
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 20, 0, 0)
        }

        rootLayout.addView(titleText)
        rootLayout.addView(videoView)
        rootLayout.addView(muteOriginalSwitch)
        rootLayout.addView(btnSelectVideo)
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
                statusText.text = "மைக்ரோஃபோன் அனுமதி தேவை. Settings-ல் அனுமதி வழங்கவும்."
                Toast.makeText(this, "Record Audio அனுமதி வழங்கப்படவில்லை", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun startDubbing() {
        if (selectedVideoUri == null) {
            statusText.text = "முதலில் 1. Gallery-யிலிருந்து வீடியோ தேர்ந்தெடுக்கவும்."
            Toast.makeText(this, "முதலில் Gallery-யிலிருந்து வீடியோ தேர்ந்தெடுக்கவும்!", Toast.LENGTH_LONG).show()
            return
        }
        isRecording = true
        btnExport.isEnabled = false
        btnRecord.text = "நிறுத்து (Stop Recording)"
        btnRecord.setBackgroundColor(0xFFC62828.toInt())
        statusText.text = "Live Dubbing நடக்கிறது... பேசுங்கள்!"

        if (selectedVideoUri != null && !videoView.isPlaying) {
            videoView.seekTo(0)
            videoView.start()
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
                    btnRecord.text = "3. Live Dubbing தொடங்கு (Start)"
                    btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
                    btnExport.isEnabled = true
                    statusText.text = "இந்த போனில் AudioRecord தொடங்க முடியவில்லை. Microphone permission சரிபார்க்கவும்."
                    recordingThread = null
                }
                return@thread
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                isRecording = false
                runOnUiThread {
                    btnExport.isEnabled = true
                    btnRecord.text = "3. Live Dubbing தொடங்கு (Start)"
                    statusText.text = "Microphone permission தேவை."
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
                btnRecord.text = "3. Live Dubbing தொடங்கு (Start)"
                btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
                btnExport.isEnabled = true
                statusText.text = "ரெக்கார்டிங் முடிந்தது! இப்போது '4. Export / Save' அழுத்தி கேலரியில் சேமிக்கவும்."
            }
        }
    }

    private fun showFileNameDialogAndExport() {
        if (isRecording || recordingThread?.isAlive == true) {
            Toast.makeText(this, "முதலில் Dubbing-ஐ Stop செய்து முடியும் வரை காத்திருக்கவும்.", Toast.LENGTH_LONG).show()
            return
        }
        val wavFile = tempDubWavFile
        if (wavFile == null || !wavFile.exists()) {
            statusText.text = "Export செய்ய முன் வீடியோ தேர்வு செய்து Live Dubbing record செய்து Stop செய்யவும்."
            Toast.makeText(this, "முதலில் Live Dubbing ரெக்கார்ட் செய்து Stop செய்யவும்!", Toast.LENGTH_LONG).show()
            return
        }

        val inputEdit = EditText(this).apply {
            hint = "உதாரணம்: Patti_Comedy_1"
            setText("PattiPeran_${System.currentTimeMillis() / 1000}")
        }

        AlertDialog.Builder(this)
            .setTitle("ஃபைல் பெயரை உள்ளிடவும்")
            .setMessage("கேலரியில் (Gallery) சேமிக்க வேண்டிய வீடியோ பெயரை டைப் செய்யவும்:")
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
        statusText.text = "வீடியோ தயாராகி கேலரியில் சேமிக்கப்படுகிறது... காத்திருக்கவும்."
        thread {
            val videoUri = selectedVideoUri
            if (videoUri != null) {
                val tempMp4 = File(cacheDir, "temp_merged_out.mp4")
                if (tempMp4.exists()) tempMp4.delete()

                val merged = mergeVideoAndWavToMp4(videoUri, wavFile, tempMp4)
                if (merged && tempMp4.exists() && tempMp4.length() > 1000) {
                    val savedUri = saveFileToMediaStoreVideo(tempMp4, "$fileName.mp4")
                    if (savedUri != null) {
                        lastExportedUri = savedUri
                        lastExportedMime = "video/mp4"
                        runOnUiThread {
                            statusText.text = "வெற்றி! கேலரியில் சேமிக்கப்பட்டது: Movies/PattiPeranDubbing/$fileName.mp4"
                            Toast.makeText(this, "கேலரியில் (Gallery) வீடியோ சேமிக்கப்பட்டது!", Toast.LENGTH_LONG).show()
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
                    statusText.text = "ஆடியோ சேமிக்கப்பட்டது: Music/PattiPeranDubbing/$fileName.wav"
                    Toast.makeText(this, "Music ஃபோல்டரில் சேமிக்கப்பட்டது!", Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread {
                    statusText.text = "சேமிப்பதில் பிழை ஏற்பட்டது. மீண்டும் முயற்சிக்கவும்."
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

    private fun mergeVideoAndWavToMp4(videoUri: Uri, wavFile: File, outMp4: File): Boolean {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(this, videoUri)
            val rotationStr = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            val rotation = rotationStr?.toIntOrNull() ?: 0
            retriever.release()

            val extractor = MediaExtractor()
            extractor.setDataSource(this, videoUri, null)
            var videoTrackIndex = -1
            var videoFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val format = extractor.getTrackFormat(i)
                val mime = format.getString(MediaFormat.KEY_MIME) ?: ""
                if (mime.startsWith("video/")) {
                    videoTrackIndex = i
                    videoFormat = format
                    break
                }
            }
            if (videoTrackIndex < 0 || videoFormat == null) return false

            val muxer = MediaMuxer(outMp4.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer.setOrientationHint(rotation)
            val muxerVideoTrack = muxer.addTrack(videoFormat)

            val sampleRate = 44100
            val aacFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, 1).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 16384)
            }
            val encoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            encoder.configure(aacFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder.start()

            // Stream PCM from disk instead of loading the complete recording into RAM.
            val pcmInput = FileInputStream(wavFile).apply { skip(44L) }
            val pcmChunk = ByteArray(64 * 1024)
            var inputEof = false
            var muxerAudioTrack = -1
            var muxerStarted = false
            val bufferInfo = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var presentationTimeUs = 0L

            while (!outputDone) {
                if (!inputDone) {
                    val inIdx = encoder.dequeueInputBuffer(10000)
                    if (inIdx >= 0) {
                        val inBuf = encoder.getInputBuffer(inIdx)!!
                        inBuf.clear()
                        val readLimit = minOf(inBuf.remaining(), pcmChunk.size)
                        val readCount = pcmInput.read(pcmChunk, 0, readLimit)
                        if (readCount <= 0) {
                            encoder.queueInputBuffer(inIdx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                            inputEof = true
                        } else {
                            // Keep PCM samples aligned to 16-bit mono frames.
                            val chunk = readCount - (readCount % 2)
                            if (chunk == 0) {
                                encoder.queueInputBuffer(inIdx, 0, 0, presentationTimeUs, 0)
                            } else {
                                inBuf.put(pcmChunk, 0, chunk)
                                encoder.queueInputBuffer(inIdx, 0, chunk, presentationTimeUs, 0)
                                presentationTimeUs += (chunk / 2L) * 1000000L / sampleRate
                            }
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerAudioTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true

                    extractor.selectTrack(videoTrackIndex)
                    val maxVideoInput = if (videoFormat.containsKey(MediaFormat.KEY_MAX_INPUT_SIZE)) videoFormat.getInteger(MediaFormat.KEY_MAX_INPUT_SIZE).coerceAtLeast(1024 * 1024) else 4 * 1024 * 1024
                    val vidBuf = ByteBuffer.allocate(maxVideoInput)
                    val vidInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val size = extractor.readSampleData(vidBuf, 0)
                        if (size < 0) break
                        vidInfo.offset = 0
                        vidInfo.size = size
                        vidInfo.presentationTimeUs = extractor.sampleTime
                        vidInfo.flags = if ((extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0) {
                            MediaCodec.BUFFER_FLAG_KEY_FRAME
                        } else {
                            0
                        }
                        muxer.writeSampleData(muxerVideoTrack, vidBuf, vidInfo)
                        extractor.advance()
                    }
                } else if (outIdx >= 0) {
                    val outBuf = encoder.getOutputBuffer(outIdx)!!
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0 && bufferInfo.size > 0 && muxerStarted) {
                        outBuf.position(bufferInfo.offset)
                        outBuf.limit(bufferInfo.offset + bufferInfo.size)
                        muxer.writeSampleData(muxerAudioTrack, outBuf, bufferInfo)
                    }
                    encoder.releaseOutputBuffer(outIdx, false)
                    if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        outputDone = true
                    }
                }
            }

            pcmInput.close()
            encoder.stop()
            encoder.release()
            extractor.release()
            if (muxerStarted) {
                muxer.stop()
                muxer.release()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun shareExportedFile() {
        val uriToShare = lastExportedUri
        if (uriToShare == null) {
            statusText.text = "Share செய்ய முன் 4. Export / Save மூலம் கோப்பைச் சேமிக்கவும்."
            Toast.makeText(this, "முதலில் Export / Save செய்யவும்!", Toast.LENGTH_LONG).show()
            return
        }

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = lastExportedMime
            putExtra(Intent.EXTRA_STREAM, uriToShare)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "டப்பிங் வீடியோவைப் பகிரவும் (Share)"))
    }

    // Lightweight offline voice-tone presets. These alter tone/EQ while preserving
    // the exact sample count, so the recorded audio duration stays aligned with video.
    private fun applyVoiceEffect(input: ShortArray, length: Int, mode: Int): ShortArray {
        if (mode == 0) return input.copyOf(length)
        val output = ShortArray(length)
        val smoothing = if (mode == 1) 0.16f else 0.055f
        val dryMix = if (mode == 1) 0.62f else 0.78f
        val toneMix = if (mode == 1) 0.38f else 0.58f
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
        btnRecord.text = "Recording நிறுத்தப்படுகிறது..."
        statusText.text = "Recording file முடிக்கப்படுகிறது. சிறிது காத்திருக்கவும்..."
        if (videoView.isPlaying) videoView.pause()
    }

    private fun applyOriginalAudioMute() {
        val volume = if (originalVideoMuted) 0f else 1f
        try {
            videoMediaPlayer?.setVolume(volume, volume)
        } catch (e: IllegalStateException) {
            statusText.text = "Video audio control இன்னும் தயாராகவில்லை."
        }
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
