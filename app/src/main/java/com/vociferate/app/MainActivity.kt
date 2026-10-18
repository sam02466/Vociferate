package com.vociferate.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var level: TextView

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null
    private var capturing = false

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_MIC_PERMISSION = 1002
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(0xFF111827.toInt())
        }

        val title = TextView(this).apply {
            text = "VOCIFERATE"
            textSize = 30f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Internal Audio Bridge"
            textSize = 16f
            setTextColor(0xFFD1D5DB.toInt())
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Ready to capture"
            textSize = 16f
            setTextColor(0xFF86EFAC.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 28, 0, 16)
        }

        level = TextView(this).apply {
            text = "Audio level: --"
            textSize = 20f
            setTextColor(0xFFFFFFFF.toInt())
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 24)
        }

        val startButton = Button(this).apply {
            text = "Start Internal Audio Capture"
            setOnClickListener {
                startCaptureRequest()
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop Capture"
            setOnClickListener {
                stopCapture()
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)
        layout.addView(level)
        layout.addView(startButton)
        layout.addView(stopButton)

        setContentView(layout)
    }

    private fun startCaptureRequest() {
        if (capturing) return

        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.RECORD_AUDIO),
                REQUEST_MIC_PERMISSION
            )
            return
        }

        requestScreenCapturePermission()
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode, permissions, grantResults
        )

        if (requestCode == REQUEST_MIC_PERMISSION) {
            if (grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                requestScreenCapturePermission()
            } else {
                status.text = "Audio permission denied"
            }
        }
    }

    private fun requestScreenCapturePermission() {
        val manager = getSystemService(
            Context.MEDIA_PROJECTION_SERVICE
        ) as MediaProjectionManager

        startActivityForResult(
            manager.createScreenCaptureIntent(),
            REQUEST_CAPTURE
        )
    }

    @Deprecated("Uses the compatible activity result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            status.text = "Screen capture permission denied"
            return
        }

        try {
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val mediaProjection = projection
                ?: throw IllegalStateException("Projection unavailable")

            val config = AudioPlaybackCaptureConfiguration.Builder(
                mediaProjection
            )
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(16000)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val bufferSize = AudioRecord.getMinBufferSize(
                16000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (bufferSize <= 0) {
                throw IllegalStateException(
                    "Unsupported audio recording format"
                )
            }

            recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            if (recorder?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "Audio recorder initialization failed"
                )
            }

            recorder?.startRecording()
            capturing = true

            status.text = "Capturing eligible internal audio"
            level.text = "Audio level: waiting for sound"

            captureThread = Thread {
                val audioBuffer = ShortArray(bufferSize)

                while (capturing) {
                    val count = try {
                        recorder?.read(
                            audioBuffer,
                            0,
                            audioBuffer.size
                        ) ?: -1
                    } catch (_: Exception) {
                        -1
                    }

                    if (count > 0) {
                        var sum = 0.0

                        for (i in 0 until count) {
                            val sample = audioBuffer[i].toDouble()
                            sum += sample * sample
                        }

                        val rms = sqrt(sum / count)
                        val percent = (
                            rms / Short.MAX_VALUE * 100
                        ).toInt().coerceIn(0, 100)

                        mainHandler.post {
                            if (capturing) {
                                level.text = "Audio level: $percent%"
                            }
                        }
                    } else if (count < 0 && capturing) {
                        mainHandler.post {
                            status.text = "Audio capture read failed"
                        }
                        break
                    }
                }
            }.also {
                it.name = "VociferateAudioCapture"
                it.start()
            }

        } catch (e: Exception) {
            stopCapture()
            status.text = "Capture error: ${e.message ?: "Unknown error"}"
        }
    }

    private fun stopCapture() {
        capturing = false

        try {
            recorder?.stop()
        } catch (_: Exception) {
        }

        try {
            recorder?.release()
        } catch (_: Exception) {
        }

        recorder = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null
        captureThread = null

        status.text = "Capture stopped"
        level.text = "Audio level: --"
    }

    override fun onDestroy() {
        stopCapture()
        super.onDestroy()
    }
}
