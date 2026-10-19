package com.vociferate.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import kotlin.math.sqrt

class MainActivity : Activity() {

    private lateinit var statusText: TextView
    private lateinit var levelText: TextView
    private lateinit var peakText: TextView
    private lateinit var samplesText: TextView
    private lateinit var durationText: TextView
    private lateinit var errorText: TextView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var meter: View

    private var projection: MediaProjection? = null
    private var recorder: AudioRecord? = null
    private var captureThread: Thread? = null

    @Volatile
    private var capturing = false

    private var projectionResultCode: Int? = null
    private var projectionData: Intent? = null
    private var receiverRegistered = false
    private var serviceReady = false
    private var sessionStartTime = 0L
    private var lastError = "None"

    @Volatile
    private var totalSamples = 0L

    @Volatile
    private var peakPercent = 0

    private val mainHandler = Handler(Looper.getMainLooper())

    companion object {
        private const val REQUEST_CAPTURE = 1001
        private const val REQUEST_MIC_PERMISSION = 1002

        private const val SAMPLE_RATE = 16000
        private const val UPDATE_INTERVAL_MS = 500L

        private const val BG = 0xFF0B1020.toInt()
        private const val CARD = 0xFF151D30.toInt()
        private const val WHITE = 0xFFF8FAFC.toInt()
        private const val MUTED = 0xFF9CA3AF.toInt()
        private const val GREEN = 0xFF34D399.toInt()
        private const val BLUE = 0xFF60A5FA.toInt()
        private const val RED = 0xFFF87171.toInt()
    }

    private val serviceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != AudioCaptureService.ACTION_READY) {
                return
            }

            serviceReady = true

            val resultCode = projectionResultCode
            val data = projectionData

            if (resultCode != null && data != null) {
                projectionResultCode = null
                projectionData = null
                beginAudioCapture(resultCode, data)
            }
        }
    }

    private val dashboardUpdater = object : Runnable {
        override fun run() {
            if (!capturing) return

            val elapsed = (System.currentTimeMillis() - sessionStartTime)
                .coerceAtLeast(0L) / 1000L

            val minutes = elapsed / 60
            val seconds = elapsed % 60

            durationText.text = String.format(
                java.util.Locale.US,
                "%02d:%02d",
                minutes,
                seconds
            )

            samplesText.text = "%,d".format(
                java.util.Locale.US,
                totalSamples
            )

            peakText.text = "$peakPercent%"

            mainHandler.postDelayed(this, UPDATE_INTERVAL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildDashboard()
        refreshIdleState()
    }

    private fun buildDashboard() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(BG)
        }

        val scroll = ScrollView(this)

        val content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(22), dp(26), dp(22), dp(28))
        }

        val brand = TextView(this).apply {
            text = "VOCIFERATE"
            textSize = 27f
            setTextColor(WHITE)
            typeface = Typeface.create("sans-serif", Typeface.BOLD)
            letterSpacing = 0.08f
        }

        val subtitle = TextView(this).apply {
            text = "INTERNAL AUDIO  /  DIAGNOSTICS"
            textSize = 11f
            setTextColor(BLUE)
            letterSpacing = 0.08f
            setPadding(0, dp(5), 0, dp(22))
        }

        content.addView(brand)
        content.addView(subtitle)

        val statusCard = makeCard()

        statusCard.addView(label("CAPTURE STATUS"))

        statusText = TextView(this).apply {
            text = "Ready to start"
            textSize = 19f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(GREEN)
            setPadding(0, dp(8), 0, dp(4))
        }

        statusCard.addView(statusText)

        val statusHint = TextView(this).apply {
            text = "Capture only includes audio permitted by Android."
            textSize = 12f
            setTextColor(MUTED)
        }

        statusCard.addView(statusHint)
        content.addView(statusCard)

        val meterCard = makeCard()

        meterCard.addView(label("LIVE AUDIO SIGNAL"))

        levelText = TextView(this).apply {
            text = "0%"
            textSize = 34f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WHITE)
            setPadding(0, dp(8), 0, dp(10))
        }

        meterCard.addView(levelText)

        val meterBackground = GradientDrawable().apply {
            setColor(0xFF263248.toInt())
            cornerRadius = dp(8).toFloat()
        }

        val meterContainer = LinearLayout(this).apply {
            background = meterBackground
            setPadding(dp(3), dp(3), dp(3), dp(3))
        }

        meter = View(this).apply {
            background = GradientDrawable().apply {
                setColor(GREEN)
                cornerRadius = dp(6).toFloat()
            }
            minimumHeight = dp(12)
        }

        meterContainer.addView(
            meter,
            LinearLayout.LayoutParams(0, dp(12), 0f)
        )

        meterCard.addView(meterContainer)

        val meterHint = TextView(this).apply {
            text = "The meter reflects captured signal strength."
            textSize = 12f
            setTextColor(MUTED)
            setPadding(0, dp(10), 0, 0)
        }

        meterCard.addView(meterHint)
        content.addView(meterCard)

        val statsRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val peakCard = makeCard()
        peakCard.addView(label("PEAK LEVEL"))

        peakText = valueText("0%")
        peakCard.addView(peakText)

        val samplesCard = makeCard()
        samplesCard.addView(label("SAMPLES READ"))

        samplesText = valueText("0")
        samplesCard.addView(samplesText)

        statsRow.addView(
            peakCard,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginEnd = dp(6)
            }
        )

        statsRow.addView(
            samplesCard,
            LinearLayout.LayoutParams(0, -2, 1f).apply {
                marginStart = dp(6)
            }
        )

        content.addView(statsRow)

        val durationCard = makeCard()
        durationCard.addView(label("SESSION DURATION"))

        durationText = valueText("00:00")
        durationCard.addView(durationText)

        content.addView(durationCard)

        val errorCard = makeCard()
        errorCard.addView(label("DIAGNOSTICS"))

        errorText = TextView(this).apply {
            text = "No errors reported"
            textSize = 13f
            setTextColor(GREEN)
            setPadding(0, dp(8), 0, 0)
        }

        errorCard.addView(errorText)
        content.addView(errorCard)

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, 0)
        }

        startButton = Button(this).apply {
            text = "START AUDIO CAPTURE"
            isAllCaps = false
            setOnClickListener {
                startCaptureRequest()
            }
        }

        stopButton = Button(this).apply {
            text = "STOP CAPTURE"
            isAllCaps = false
            setOnClickListener {
                stopCapture()
            }
        }

        buttons.addView(
            startButton,
            LinearLayout.LayoutParams(-1, dp(52)).apply {
                bottomMargin = dp(8)
            }
        )

        buttons.addView(
            stopButton,
            LinearLayout.LayoutParams(-1, dp(52))
        )

        content.addView(buttons)

        val footer = TextView(this).apply {
            text = "VOCIFERATE  •  AUDIO TEST BUILD 0.2"
            textSize = 10f
            gravity = Gravity.CENTER
            setTextColor(MUTED)
            setPadding(0, dp(24), 0, dp(4))
        }

        content.addView(footer)

        scroll.addView(content)
        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }

    private fun makeCard(): LinearLayout {
        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(15), dp(16), dp(16))

            background = GradientDrawable().apply {
                setColor(CARD)
                cornerRadius = dp(16).toFloat()
            }

            val params = LinearLayout.LayoutParams(-1, -2)
            params.bottomMargin = dp(12)
            layoutParams = params
        }
    }

    private fun label(textValue: String): TextView {
        return TextView(this).apply {
            text = textValue
            textSize = 10f
            letterSpacing = 0.08f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(MUTED)
        }
    }

    private fun valueText(value: String): TextView {
        return TextView(this).apply {
            text = value
            textSize = 24f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(WHITE)
            setPadding(0, dp(8), 0, 0)
        }
    }

    private fun dp(value: Int): Int {
        return (value * resources.displayMetrics.density).toInt()
    }

    private fun refreshIdleState() {
        statusText.text = "Ready to start"
        statusText.setTextColor(GREEN)
        levelText.text = "0%"
        peakText.text = "0%"
        samplesText.text = "0"
        durationText.text = "00:00"
        errorText.text = lastError
        updateMeter(0)
        startButton.isEnabled = true
        stopButton.isEnabled = false
    }

    private fun startCaptureRequest() {
        if (capturing || projectionData != null) return

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
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == REQUEST_MIC_PERMISSION) {
            if (
                grantResults.isNotEmpty() &&
                grantResults[0] == PackageManager.PERMISSION_GRANTED
            ) {
                requestScreenCapturePermission()
            } else {
                showError("Microphone permission denied")
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

    @Deprecated("Uses compatible activity result callback")
    override fun onActivityResult(
        requestCode: Int,
        resultCode: Int,
        data: Intent?
    ) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode != REQUEST_CAPTURE) return

        if (resultCode != RESULT_OK || data == null) {
            showError("Screen capture permission denied")
            return
        }

        projectionResultCode = resultCode
        projectionData = data
        serviceReady = false

        statusText.text = "Starting capture service..."
        statusText.setTextColor(BLUE)
        startButton.isEnabled = false
        stopButton.isEnabled = true

        val filter = IntentFilter(AudioCaptureService.ACTION_READY)

        try {
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(
                    serviceReceiver,
                    filter,
                    Context.RECEIVER_NOT_EXPORTED
                )
            } else {
                @Suppress("DEPRECATION")
                registerReceiver(serviceReceiver, filter)
            }

            receiverRegistered = true

            val serviceIntent = Intent(
                this,
                AudioCaptureService::class.java
            ).setAction(AudioCaptureService.ACTION_START)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }

            mainHandler.postDelayed({
                if (!serviceReady && projectionData != null) {
                    showError("Capture service timed out")
                    stopCapture()
                }
            }, 5000)

        } catch (e: Exception) {
            showError("Service error: ${e.message ?: "unknown"}")
            stopCapture()
        }
    }

    private fun beginAudioCapture(resultCode: Int, data: Intent) {
        try {
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            projection = manager.getMediaProjection(resultCode, data)

            val mediaProjection = projection
                ?: throw IllegalStateException(
                    "MediaProjection unavailable"
                )

            val config =
                AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
                    .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                    .addMatchingUsage(AudioAttributes.USAGE_GAME)
                    .build()

            val format = AudioFormat.Builder()
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .setSampleRate(SAMPLE_RATE)
                .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .build()

            val minBufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )

            if (minBufferSize <= 0) {
                throw IllegalStateException(
                    "Unsupported audio format: $minBufferSize"
                )
            }

            recorder = AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(minBufferSize * 2)
                .setAudioPlaybackCaptureConfig(config)
                .build()

            val audioRecorder = recorder
                ?: throw IllegalStateException("Recorder unavailable")

            if (audioRecorder.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException(
                    "AudioRecord initialization failed"
                )
            }

            audioRecorder.startRecording()

            capturing = true
            sessionStartTime = System.currentTimeMillis()
            totalSamples = 0L
            peakPercent = 0
            lastError = "None"

            statusText.text = "Capture running"
            statusText.setTextColor(GREEN)
            levelText.text = "Waiting for audio..."
            errorText.text = "No read errors"
            errorText.setTextColor(GREEN)

            startButton.isEnabled = false
            stopButton.isEnabled = true

            mainHandler.removeCallbacks(dashboardUpdater)
            mainHandler.post(dashboardUpdater)

            captureThread = Thread {
                val bufferSize = (minBufferSize / 2)
                    .coerceAtLeast(512)

                val audioBuffer = ShortArray(bufferSize)

                while (capturing) {
                    val count = try {
                        audioRecorder.read(
                            audioBuffer,
                            0,
                            audioBuffer.size,
                            AudioRecord.READ_BLOCKING
                        )
                    } catch (e: Exception) {
                        mainHandler.post {
                            showError(
                                "Read exception: ${e.message ?: "unknown"}"
                            )
                        }
                        break
                    }

                    if (count > 0) {
                        totalSamples += count

                        var sumSquares = 0.0
                        var localPeak = 0

                        for (i in 0 until count) {
                            val sample = audioBuffer[i].toInt()
                            val magnitude = kotlin.math.abs(sample)
                                .coerceAtMost(32767)

                            if (magnitude > localPeak) {
                                localPeak = magnitude
                            }

                            sumSquares += sample.toDouble() * sample
                        }

                        val rms = sqrt(sumSquares / count)

                        val percent = (
                            rms / 32767.0 * 100.0
                        ).toInt().coerceIn(0, 100)

                        val thisPeak = (
                            localPeak / 32767.0 * 100.0
                        ).toInt().coerceIn(0, 100)

                        if (thisPeak > peakPercent) {
                            peakPercent = thisPeak
                        }

                        mainHandler.post {
                            if (capturing) {
                                levelText.text = "$percent%"
                                updateMeter(percent)
                            }
                        }

                    } else if (count < 0 && capturing) {
                        mainHandler.post {
                            showError(
                                "AudioRecord read error: $count"
                            )
                        }
                        break
                    }
                }
            }.also {
                it.name = "VociferateAudioDiagnostics"
                it.start()
            }

        } catch (e: Exception) {
            showError(
                "Capture error: ${e.message ?: "unknown"}"
            )
            stopCapture()
        }
    }

    private fun updateMeter(percent: Int) {
        val parent = meter.parent as? LinearLayout ?: return
        val availableWidth = parent.width - parent.paddingLeft -
            parent.paddingRight

        if (availableWidth <= 0) return

        val width = (
            availableWidth * percent.coerceIn(0, 100) / 100f
        ).toInt()

        meter.layoutParams = meter.layoutParams.apply {
            this.width = width
        }

        meter.requestLayout()
    }

    private fun showError(message: String) {
        lastError = message
        errorText.text = message
        errorText.setTextColor(RED)
    }

    private fun stopCapture() {
        capturing = false
        mainHandler.removeCallbacks(dashboardUpdater)

        val oldRecorder = recorder
        recorder = null

        try {
            oldRecorder?.stop()
        } catch (_: Exception) {
        }

        try {
            oldRecorder?.release()
        } catch (_: Exception) {
        }

        captureThread = null

        try {
            projection?.stop()
        } catch (_: Exception) {
        }

        projection = null
        projectionData = null
        projectionResultCode = null
        serviceReady = false

        if (receiverRegistered) {
            try {
                unregisterReceiver(serviceReceiver)
            } catch (_: Exception) {
            }
            receiverRegistered = false
        }

        try {
            stopService(
                Intent(this, AudioCaptureService::class.java)
            )
        } catch (_: Exception) {
        }

        statusText.text = "Capture stopped"
        statusText.setTextColor(MUTED)
        startButton.isEnabled = true
        stopButton.isEnabled = false

        levelText.text = "0%"
        updateMeter(0)
    }

    override fun onDestroy() {
        mainHandler.removeCallbacksAndMessages(null)
        stopCapture()
        super.onDestroy()
    }
}
