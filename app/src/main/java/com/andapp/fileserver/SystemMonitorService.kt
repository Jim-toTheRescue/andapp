package com.andapp.fileserver

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Binder
import android.os.Build
import android.os.Environment
import android.os.IBinder
import androidx.core.app.NotificationCompat
import fi.iki.elonen.NanoHTTPD

class SystemMonitorService : Service() {

    private var server: SystemHttpServer? = null
    private val binder = LocalBinder()
    private var serverPort = 8081

    companion object {
        val errorLog = mutableListOf<String>()
        fun addLog(msg: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            errorLog.add("[$time] $msg")
            if (errorLog.size > 50) errorLog.removeFirst()
        }
    }

    inner class LocalBinder : Binder() {
        fun getService(): SystemMonitorService = this@SystemMonitorService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(2, createNotification())
        Thread {
            startServer()
        }.start()
        return START_STICKY
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                "system_monitor_channel",
                "System Monitor",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "system_monitor_channel")
            .setContentTitle("System Monitor")
            .setContentText("Monitor is running on port $serverPort")
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .build()
    }

    fun startServer() {
        try {
            if (server == null) {
                server = SystemHttpServer(serverPort)
                server?.start(0, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun stopServer() {
        server?.stop()
        server = null
    }

    fun isServerRunning(): Boolean = server?.isAlive == true

    fun getServerPort(): Int = serverPort

    inner class SystemHttpServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val decodedUri = java.net.URLDecoder.decode(uri, "UTF-8")
            
            addLog("${session.method} $decodedUri")
            
            return when {
                decodedUri == "/api/system" -> serveSystem()
                decodedUri == "/" -> serveSystemPage()
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not Found")
            }
        }

        private fun serveSystem(): Response {
            try {
                val json = buildString {
                    append("{")
                    
                    // 设备信息
                    append("\"device\":{")
                    append("\"model\":\"${escapeJson(android.os.Build.MODEL)}\",")
                    append("\"brand\":\"${escapeJson(android.os.Build.BRAND)}\",")
                    append("\"manufacturer\":\"${escapeJson(android.os.Build.MANUFACTURER)}\",")
                    append("\"sdk\":${android.os.Build.VERSION.SDK_INT},")
                    append("\"version\":\"${escapeJson(android.os.Build.VERSION.RELEASE)}\"")
                    append("},")
                    
                    // 电池信息
                    val batteryIntent = applicationContext.registerReceiver(null, 
                        android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
                    val batteryLevel = batteryIntent?.let {
                        val level = it.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
                        val scale = it.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, -1)
                        if (level >= 0 && scale > 0) (level * 100 / scale) else -1
                    } ?: -1
                    val batteryStatus = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
                    val batteryStatusText = when (batteryStatus) {
                        android.os.BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                        android.os.BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                        android.os.BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                        android.os.BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                        else -> "未知"
                    }
                    val batteryTemp = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)?.let { it / 10.0 } ?: 0.0
                    append("\"battery\":{")
                    append("\"level\":$batteryLevel,")
                    append("\"status\":\"$batteryStatusText\",")
                    append("\"temperature\":$batteryTemp")
                    append("},")
                    
                    // 存储信息
                    val externalDir = Environment.getExternalStorageDirectory()
                    val totalStorage = externalDir.totalSpace
                    val freeStorage = externalDir.freeSpace
                    val usedStorage = totalStorage - freeStorage
                    append("\"storage\":{")
                    append("\"total\":$totalStorage,")
                    append("\"used\":$usedStorage,")
                    append("\"free\":$freeStorage")
                    append("},")
                    
                    // 内存信息
                    val memInfo = android.app.ActivityManager.MemoryInfo()
                    val activityManager = getSystemService(android.content.Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                    activityManager.getMemoryInfo(memInfo)
                    append("\"memory\":{")
                    append("\"totalMem\":${memInfo.totalMem},")
                    append("\"availMem\":${memInfo.availMem},")
                    append("\"usedMem\":${memInfo.totalMem - memInfo.availMem},")
                    append("\"lowMemory\":${memInfo.lowMem}")
                    append("},")
                    
                    // 运行时间
                    val uptimeMs = android.os.SystemClock.elapsedRealtime()
                    append("\"uptime\":$uptimeMs,")
                    
                    // 当前时间
                    append("\"currentTime\":${System.currentTimeMillis()}")
                    
                    append("}")
                }
                
                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
            } catch (e: Exception) {
                android.util.Log.e("SystemMonitor", "Error getting system info", e)
                return errorResponse("Error: ${e.message}")
            }
        }

        private fun serveSystemPage(): Response {
            val html = generateSystemHtml()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun errorResponse(message: String): Response {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                """{"error":"${escapeJson(message)}"}""")
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        private fun generateSystemHtml(): String {
            return try {
                assets.open("web/system.html").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                addLog("Failed to load system.html: ${e.message}")
                "<html><body><h1>Error loading page</h1><p>${e.message}</p></body></html>"
            }
        }
    }
}
