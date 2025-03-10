package com.example.flattradeapp

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

// Data class to track order fills (if needed)
data class OrderInfo(
    val symbol: String,
    val exchange: String,
    val productCode: String,
    val totalQty: Int,
    var filledQty: Int,
    var soldQty: Int,
    val processedFills: MutableSet<String>,
    var price: Float
)

class WebSocketService : Service() {

    companion object {
        var isServiceRunning = false
    }

    private val CHANNEL_ID = "WebSocketServiceChannel"
    private var webSocket: WebSocket? = null
    private var isConnected = false
    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()
    private var heartbeatJob: Job? = null
    private val scope = CoroutineScope(Dispatchers.IO)

    // Authentication details and target value – loaded from SharedPreferences
    private val authorizationUid = "FZ0xxxx"  // Update with your actual UID
    private val authorizationActid = "FZ0xxxx"  // Update with your actual ActID
    private var authorizationSusertoken: String = ""
    private var targetValue: Float = 0.0f
    private val appInstId = "29db902d-0978-408e-9c37-b3de9e1210f2"

    // Order tracker map
    private val orderTracker = mutableMapOf<String, OrderInfo>()

    override fun onCreate() {
        super.onCreate()
        isServiceRunning = true

        // Create and start the foreground notification
        createNotificationChannel()
        startForeground(1, createNotification("Connecting to WebSocket..."))

        // Load token and target value from SharedPreferences
        val prefs = getSharedPreferences("AppPrefs", Context.MODE_PRIVATE)
        authorizationSusertoken = prefs.getString("authorizationSusertoken", "") ?: ""
        targetValue = prefs.getFloat("targetValue", 0.0f)
        if (authorizationSusertoken.isEmpty()) {
            sendStatus("DISCONNECTED")
            sendLog("Authorization token is empty. Stopping service.")
            stopSelf()
            return
        }
        connectWebSocket()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        disconnectWebSocket()
        scope.cancel()
        isServiceRunning = false
        sendStatus("DISCONNECTED")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotification(content: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("WebSocket Service")
            .setContentText(content)
            .setSmallIcon(R.drawable.ic_launcher_foreground) // Replace with your own icon if needed
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WebSocket Service Channel",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateNotification(content: String) {
        val notification = createNotification(content)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(1, notification)
    }

    private fun connectWebSocket() {
        val request = Request.Builder()
            .url("wss://web.flattrade.in/NorenWSWeb/")
            .header(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/90.0.4430.93 Safari/537.36"
            )
            .build()

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                isConnected = true
                // Prepare authentication data JSON
                val authData = JSONObject().apply {
                    put("t", "c")
                    put("uid", authorizationUid)
                    put("actid", authorizationActid)
                    put("susertoken", authorizationSusertoken)
                    put("source", "WEB")
                }
                webSocket.send(authData.toString())
                sendLog("Sent authData: ${authData.toString()}")
                sendStatus("CONNECTED")
                updateNotification("Connected to WebSocket")
                startHeartbeat()
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                try {
                    val data = JSONObject(text)
                    sendLog("Received: $text")

                    // Check for buy order fill messages (reporttype == "Fill")
                    if (data.optString("t") == "om" &&
                        (data.optString("pcode") == "M" || data.optString("pcode") == "I") &&
                        data.optString("trantype") == "B" &&
                        data.optString("reporttype") == "Fill") {

                        val orderId = data.optString("norenordno")
                        val fillId = data.optString("flid")
                        val fillQty = data.optString("flqty").toIntOrNull() ?: 0
                        val fillPrice = data.optString("flprc").toFloatOrNull() ?: 0f

                        // Initialize order tracking if this is the first fill for this order
                        if (!orderTracker.containsKey(orderId)) {
                            orderTracker[orderId] = OrderInfo(
                                symbol = data.optString("tsym"),
                                exchange = data.optString("exch"),
                                productCode = data.optString("pcode"),
                                totalQty = data.optString("qty").toIntOrNull() ?: 0,
                                filledQty = 0,
                                soldQty = 0,
                                processedFills = mutableSetOf(),
                                price = fillPrice
                            )
                        }
                        val orderInfo = orderTracker[orderId]!!
                        if (!orderInfo.processedFills.contains(fillId)) {
                            orderInfo.processedFills.add(fillId)
                            orderInfo.filledQty += fillQty

                            // Calculate sell order: sell this fill only, price is fillPrice + 0.05
                            val sellQty = fillQty
                            val sellPrice = String.format("%.2f", fillPrice + 0.05f)
                            sendLog("Placing sell order for $sellQty contracts at $sellPrice")

                            placeOrder(
                                data.optString("exch"),
                                data.optString("pcode"),
                                sellPrice,
                                sellQty.toString(),
                                data.optString("tsym")
                            )
                            orderInfo.soldQty += sellQty
                        }
                    }
                } catch (e: Exception) {
                    sendLog("Error parsing message: ${e.message}")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                isConnected = false
                stopHeartbeat()
                sendLog("WebSocket failure: ${t.message}")
                sendStatus("DISCONNECTED")
                updateNotification("Disconnected from WebSocket")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                isConnected = false
                stopHeartbeat()
                sendLog("WebSocket closed: Code $code, Reason: $reason")
                sendStatus("DISCONNECTED")
                updateNotification("Disconnected from WebSocket")
            }
        }

        webSocket = client.newWebSocket(request, listener)
    }

    private fun disconnectWebSocket() {
        isConnected = false
        webSocket?.close(1000, "Service stopping")
        stopHeartbeat()
    }

    private fun startHeartbeat() {
        heartbeatJob = scope.launch {
            while (isActive && isConnected) {
                delay(30000)
                val heartbeat = JSONObject().apply { put("t", "h") }
                webSocket?.send(heartbeat.toString())
                sendLog("Sent heartbeat: ${heartbeat.toString()}")
            }
        }
    }

    private fun stopHeartbeat() {
        heartbeatJob?.cancel()
        heartbeatJob = null
    }

    // Place order function – sends an HTTP POST request to place a sell order
    private fun placeOrder(
        exSeg: String,
        exProd: String,
        price: String,
        quantity: String,
        tradingSymbol: String
    ) {
        sendLog("Placing order: $tradingSymbol, Qty: $quantity, Price: $price")
        scope.launch {
            try {
                val requestUrl = "https://web.flattrade.in/NorenWClientWeb/PlaceOrder"
                val orderData = JSONObject().apply {
                    put("uid", authorizationUid)
                    put("actid", authorizationActid)
                    put("exch", exSeg)
                    put("tsym", tradingSymbol)
                    put("qty", quantity)
                    put("prc", price)
                    put("prd", exProd)
                    put("trantype", "S")
                    put("prctyp", "LMT")
                    put("ret", "DAY")
                    put("channel", "Windows")
                    put("usr_agent", "BrowserAgent.Firefox")
                    put("app_inst_id", appInstId)
                    put("ordersource", "WEB")
                }
                val jData = orderData.toString()
                sendLog("Order data: $jData")
                val requestBody = "jData=$jData&jKey=$authorizationSusertoken"
                    .toRequestBody("application/x-www-form-urlencoded".toMediaType())
                val request = Request.Builder()
                    .url(requestUrl)
                    .post(requestBody)
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .build()
                withContext(Dispatchers.IO) {
                    client.newCall(request).execute().use { response ->
                        val responseBody = response.body?.string() ?: ""
                        if (response.isSuccessful) {
                            sendLog("Order placed successfully: $responseBody")
                        } else {
                            sendLog("Error placing order: $responseBody")
                        }
                    }
                }
            } catch (e: Exception) {
                sendLog("Exception placing order: ${e.message}")
            }
        }
    }

    // Helper function to broadcast log messages to MainActivity
    private fun sendLog(message: String) {
        val intent = Intent("com.example.flattradeapp.LOG")
        intent.putExtra("log_message", message)
        sendBroadcast(intent)
    }

    // Helper function to broadcast status updates to MainActivity
    private fun sendStatus(status: String) {
        val intent = Intent("com.example.flattradeapp.STATUS")
        intent.putExtra("status_update", status)
        sendBroadcast(intent)
    }
}
