package com.manjunath.pattiperandubbing

import android.Manifest
import android.content.pm.PackageManager
import android.media.*
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var videoView: VideoView
    private lateinit var statusText: TextView
    private lateinit var btnRecord: Button
    private var isRecording = false
    private var currentPitch = 1.0f
    private var selectedVideoUri: Uri? = null

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

        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
            setBackgroundColor(0xFF121212.toInt())
        }

        val titleText = TextView(this).apply {
            text = "பாட்டி - பேரன் Live Dubbing Studio"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(0, 0, 0, 24)
        }

        videoView = VideoView(this).apply {
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                650
            )
        }

        val btnSelectVideo = Button(this).apply {
            text = "1. கேலரியிலிருந்து வீடியோ தேர்ந்தெடு"
            setOnClickListener { pickVideoLauncher.launch("video/*") }
        }

        val voiceLabel = TextView(this).apply {
            text = "2. குரல் தேர்வு (Voice Mode):"
            setTextColor(0xFFCCCCCC.toInt())
            setPadding(0, 24, 0, 8)
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
                    2 -> 0.80f // பாட்டி குரல் (Base / Aged tone)
                    3 -> 1.32f // பேரன் குரல் (High pitch kid tone)
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

        statusText = TextView(this).apply {
            text = "குறிப்பு: எக்கோ வராமல் இருக்க ஹெட்செட் (Headset) பயன்படுத்தவும்."
            setTextColor(0xFFAAAAAA.toInt())
            setPadding(0, 24, 0, 0)
        }

        rootLayout.addView(titleText)
        rootLayout.addView(videoView)
        rootLayout.addView(btnSelectVideo)
        rootLayout.addView(voiceLabel)
        rootLayout.addView(radioGroup)
        rootLayout.addView(btnRecord)
        rootLayout.addView(statusText)

        setContentView(rootLayout)
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
        btnRecord.text = "நிறுத்து & சேமி (Stop & Save)"
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

            val musicDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
            if (!musicDir.exists()) musicDir.mkdirs()
            val outFile = File(musicDir, "PattiPeran_Dub_${System.currentTimeMillis()}.wav")
            val fos = FileOutputStream(outFile)
            fos.write(ByteArray(44)) // WAV header placeholder

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

            runOnUiThread {
                statusText.text = "சேமிக்கப்பட்டது: Music/${outFile.name}"
            }
        }
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
