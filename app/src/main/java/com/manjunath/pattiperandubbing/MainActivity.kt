package com.manjunath.pattiperandubbing

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.StrictMode
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
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

    private var isRecording = false
    private var currentPitch = 1.0f
    private var selectedVideoUri: Uri? = null
    private var tempDubWavFile: File? = null
    private var lastExportedFile: File? = null

    private val pickVideoLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            selectedVideoUri = it
            videoView.setVideoURI(it)
            videoView.start()
            statusText.text = "வீடியோ தயார்! குரலைத் தேர்ந்தெடுத்து Dubbing தொடங்கவும்."
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Allow direct file URI sharing seamlessly
        val builder = StrictMode.VmPolicy.Builder()
        StrictMode.setVmPolicy(builder.build())

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

        val btnSelectVideo = Button(this).apply {
            text = "1. கேலரியிலிருந்து வீடியோ தேர்ந்தெடு"
            setOnClickListener { pickVideoLauncher.launch("video/*") }
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
                currentPitch = when (checkedId) {
                    2 -> 0.80f
                    3 -> 1.32f
                    else -> 1.0f
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

        // Separate Export & Share Buttons Row
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

    private fun startDubbing() {
        isRecording = true
        btnRecord.text = "நிறுத்து (Stop Recording)"
        btnRecord.setBackgroundColor(0xFFC62828.toInt())
        statusText.text = "Live Dubbing நடக்கிறது... பேசுங்கள்!"

        if (selectedVideoUri != null && !videoView.isPlaying) {
            videoView.seekTo(0)
            videoView.start()
        }

        thread {
            val sampleRate = 44100
            val channelConfigIn = AudioFormat.CHANNEL_IN_MONO
            val channelConfigOut = AudioFormat.CHANNEL_OUT_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT

            val minBufSize = AudioRecord.getMinBufferSize(sampleRate, channelConfigIn, audioFormat)
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return@thread

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
                    val processed = applyPitchShift(buffer, read, currentPitch)
                    player.playbackRate = (sampleRate * currentPitch).toInt()
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
                statusText.text = "ரெக்கார்டிங் முடிந்தது! இப்போது '4. Export / Save' அழுத்தி பெயர் கொடுத்து சேமிக்கவும்."
            }
        }
    }

    private fun showFileNameDialogAndExport() {
        val wavFile = tempDubWavFile
        if (wavFile == null || !wavFile.exists()) {
            Toast.makeText(this, "முதலில் Live Dubbing ரெக்கார்ட் செய்யவும்!", Toast.LENGTH_SHORT).show()
            return
        }

        val inputEdit = EditText(this).apply {
            hint = "உதாரணம்: Patti_Comedy_1"
            setText("PattiPeran_${System.currentTimeMillis() / 1000}")
        }

        AlertDialog.Builder(this)
            .setTitle("ஃபைல் பெயரை உள்ளிடவும் (File Name)")
            .setMessage("Downloads ஃபோல்டரில் சேமிக்க வேண்டிய பெயரை டைப் செய்யவும்:")
            .setView(inputEdit)
            .setPositiveButton("Save to Downloads") { _, _ ->
                val rawName = inputEdit.text.toString().trim().replace(Regex("[^a-zA-Z0-9_-]"), "_")
                val finalName = if (rawName.isEmpty()) "PattiPeran_Dub" else rawName
                exportToDownloads(wavFile, finalName)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun exportToDownloads(wavFile: File, fileName: String) {
        statusText.text = "Downloads ஃபோல்டரில் Export ஆகிறது... காத்திருக்கவும்."
        thread {
            val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
            if (!downloadsDir.exists()) downloadsDir.mkdirs()

            val videoUri = selectedVideoUri
            if (videoUri != null) {
                val mp4Out = File(downloadsDir, "$fileName.mp4")
                val merged = mergeVideoAndWavToMp4(videoUri, wavFile, mp4Out)
                if (merged && mp4Out.exists() && mp4Out.length() > 1000) {
                    lastExportedFile = mp4Out
                    runOnUiThread {
                        statusText.text = "வெற்றி! வீடியோ சேமிக்கப்பட்டது: Downloads/${mp4Out.name}"
                        Toast.makeText(this, "Downloads/${mp4Out.name} சேமிக்கப்பட்டது!", Toast.LENGTH_LONG).show()
                    }
                    return@thread
                }
            }

            // Fallback to saving WAV audio directly in Downloads
            val wavOut = File(downloadsDir, "$fileName.wav")
            wavFile.copyTo(wavOut, overwrite = true)
            lastExportedFile = wavOut
            runOnUiThread {
                statusText.text = "வெற்றி! ஆடியோ சேமிக்கப்பட்டது: Downloads/${wavOut.name}"
                Toast.makeText(this, "Downloads/${wavOut.name} சேமிக்கப்பட்டது!", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun mergeVideoAndWavToMp4(videoUri: Uri, wavFile: File, outMp4: File): Boolean {
        return try {
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

            // Copy video track first after audio format is ready
            val pcmBytes = wavFile.readBytes().drop(44).toByteArray()
            var pcmOffset = 0
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
                        if (pcmOffset >= pcmBytes.size) {
                            encoder.queueInputBuffer(inIdx, 0, 0, presentationTimeUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputDone = true
                        } else {
                            val chunk = minOf(inBuf.remaining(), pcmBytes.size - pcmOffset)
                            inBuf.put(pcmBytes, pcmOffset, chunk)
                            encoder.queueInputBuffer(inIdx, 0, chunk, presentationTimeUs, 0)
                            pcmOffset += chunk
                            presentationTimeUs += (chunk / 2L) * 1000000L / sampleRate
                        }
                    }
                }

                val outIdx = encoder.dequeueOutputBuffer(bufferInfo, 10000)
                if (outIdx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerAudioTrack = muxer.addTrack(encoder.outputFormat)
                    muxer.start()
                    muxerStarted = true

                    // Write all video samples
                    extractor.selectTrack(videoTrackIndex)
                    val vidBuf = ByteBuffer.allocate(1024 * 1024)
                    val vidInfo = MediaCodec.BufferInfo()
                    while (true) {
                        val size = extractor.readSampleData(vidBuf, 0)
                        if (size < 0) break
                        vidInfo.offset = 0
                        vidInfo.size = size
                        vidInfo.presentationTimeUs = extractor.sampleTime
                        vidInfo.flags = extractor.sampleFlags
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
        val fileToShare = lastExportedFile
        if (fileToShare == null || !fileToShare.exists()) {
            Toast.makeText(this, "முதலில் '4. Export / Save' அழுத்தி ஃபைலை சேமிக்கவும்!", Toast.LENGTH_SHORT).show()
            return
        }

        val mimeType = if (fileToShare.extension.equals("mp4", true)) "video/mp4" else "audio/wav"
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, Uri.fromFile(fileToShare))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "டப்பிங் வீடியோவைப் பகிரவும் (Share)"))
    }

    private fun applyPitchShift(input: ShortArray, length: Int, pitch: Float): ShortArray {
        if (pitch == 1.0f) return input.copyOf(length)
        val outLen = (length / pitch).toInt().coerceAtLeast(1)
        val output = ShortArray(outLen)
        for (i in 0 until outLen) {
            val srcIdx = (i * pitch).toInt().coerceIn(0, length - 1)
            output[i] = input[srcIdx]
        }
        return output
    }

    private fun stopDubbing() {
        isRecording = false
        btnRecord.text = "3. Live Dubbing தொடங்கு (Start)"
        btnRecord.setBackgroundColor(0xFF2E7D32.toInt())
        if (videoView.isPlaying) videoView.pause()
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
