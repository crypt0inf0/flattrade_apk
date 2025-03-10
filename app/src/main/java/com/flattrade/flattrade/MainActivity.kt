package com.example.flattradeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.text.method.ScrollingMovementMethod
import android.widget.Button
import android.widget.EditText
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {

    // UI Elements
    private lateinit var statusTextView: TextView
    private lateinit var toggleConnectionButton: Button
    private lateinit var logTextView: TextView
    private lateinit var clearLogsButton: Button
    private lateinit var logScrollView: ScrollView
    private lateinit var authTokenInput: EditText
    private lateinit var saveAuthTokenButton: Button
    private lateinit var targetValueInput: EditText
    private lateinit var saveTargetValueButton: Button

    // Stored values
    private var authorizationSusertoken: String = ""
    private var targetValue: Float = 0.0f

    // Broadcast receiver for log and status messages from the service
    private val broadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            intent?.let {
                if (it.hasExtra("status_update")) {
                    val status = it.getStringExtra("status_update") ?: "DISCONNECTED"
                    runOnUiThread { statusTextView.text = "Status: $status" }
                }
                if (it.hasExtra("log_message")) {
                    val message = it.getStringExtra("log_message") ?: ""
                    logToUi(message)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI Elements
        statusTextView = findViewById(R.id.statusTextView)
        toggleConnectionButton = findViewById(R.id.toggleConnectionButton)
        logTextView = findViewById(R.id.logTextView)
        clearLogsButton = findViewById(R.id.clearLogsButton)
        logScrollView = findViewById(R.id.logScrollView)
        authTokenInput = findViewById(R.id.authTokenInput)
        saveAuthTokenButton = findViewById(R.id.saveAuthTokenButton)
        targetValueInput = findViewById(R.id.targetValueInput)
        saveTargetValueButton = findViewById(R.id.saveTargetValueButton)

        logTextView.movementMethod = ScrollingMovementMethod()

        loadSavedValues()

        // Save / Edit auth token
        saveAuthTokenButton.setOnClickListener {
            if (authTokenInput.isEnabled) {
                authorizationSusertoken = authTokenInput.text.toString().trim()
                saveAuthTokenValue()
                authTokenInput.isEnabled = false
                saveAuthTokenButton.text = "Edit"
            } else {
                authTokenInput.isEnabled = true
                saveAuthTokenButton.text = "Save"
            }
        }

        // Save / Edit target value
        saveTargetValueButton.setOnClickListener {
            if (targetValueInput.isEnabled) {
                targetValue = targetValueInput.text.toString().toFloatOrNull() ?: 0.0f
                saveTargetValue()
                targetValueInput.isEnabled = false
                saveTargetValueButton.text = "Edit"
            } else {
                targetValueInput.isEnabled = true
                saveTargetValueButton.text = "Save"
            }
        }

        // Toggle connection button: Start service if not running; otherwise, stop it.
        toggleConnectionButton.setOnClickListener {
            if (WebSocketService.isServiceRunning) {
                stopService(Intent(this, WebSocketService::class.java))
                logToUi("WebSocket Service stopped.")
                toggleConnectionButton.text = "Start Connection"
            } else {
                startService(Intent(this, WebSocketService::class.java))
                logToUi("WebSocket Service started.")
                toggleConnectionButton.text = "Stop Connection"
            }
        }

        clearLogsButton.setOnClickListener {
            logTextView.text = ""
        }

        logToUi("App started")

        // Register broadcast receiver for log and status updates
        registerReceiver(broadcastReceiver, IntentFilter("com.example.flattradeapp.LOG"))
        registerReceiver(broadcastReceiver, IntentFilter("com.example.flattradeapp.STATUS"))

        // Do not auto-start the WebSocket service here;
        // it will only start when the "Start Connection" button is tapped.
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(broadcastReceiver)
    }

    private fun loadSavedValues() {
        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        authorizationSusertoken = sharedPreferences.getString("authorizationSusertoken", "") ?: ""
        targetValue = sharedPreferences.getFloat("targetValue", 0.0f)
        authTokenInput.setText(authorizationSusertoken)
        targetValueInput.setText(targetValue.toString())
        authTokenInput.isEnabled = false
        targetValueInput.isEnabled = false
    }

    private fun saveAuthTokenValue() {
        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putString("authorizationSusertoken", authorizationSusertoken)
            apply()
        }
        logToUi("Authorization token saved")
    }

    private fun saveTargetValue() {
        val sharedPreferences = getSharedPreferences("AppPrefs", MODE_PRIVATE)
        with(sharedPreferences.edit()) {
            putFloat("targetValue", targetValue)
            apply()
        }
        logToUi("Target value saved: $targetValue")
    }

    private fun logToUi(message: String) {
        val timeStamp = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
        val logMessage = "[$timeStamp] $message\n"
        runOnUiThread {
            logTextView.append(logMessage)
            logScrollView.post {
                logScrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }
    }
}
