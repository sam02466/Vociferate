package com.vociferate.app

import android.app.Activity
import android.os.Bundle
import android.graphics.Color
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

class MainActivity : Activity() {

    private lateinit var status: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(17, 24, 39))
        }

        val title = TextView(this).apply {
            text = "VOCIFERATE"
            textSize = 30f
            setTextColor(Color.WHITE)
            gravity = Gravity.CENTER
        }

        val subtitle = TextView(this).apply {
            text = "Internal Audio Bridge"
            textSize = 16f
            setTextColor(Color.LTGRAY)
            gravity = Gravity.CENTER
        }

        status = TextView(this).apply {
            text = "Prototype initialized"
            textSize = 16f
            setTextColor(Color.rgb(134, 239, 172))
            gravity = Gravity.CENTER
            setPadding(0, 32, 0, 24)
        }

        val startButton = Button(this).apply {
            text = "Start Audio Capture"
            setOnClickListener {
                status.text =
                    "Audio capture is not implemented yet."
            }
        }

        val stopButton = Button(this).apply {
            text = "Stop Sharing"
            setOnClickListener {
                status.text = "Sharing stopped."
            }
        }

        layout.addView(title)
        layout.addView(subtitle)
        layout.addView(status)
        layout.addView(startButton)
        layout.addView(stopButton)

        setContentView(layout)
    }
}
