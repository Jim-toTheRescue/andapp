package com.andapp.fileserver

import android.app.ActivityManager
import android.content.Context
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.SystemClock

class SystemMonitor(private val context: Context) {

    fun getSystemInfo(): String {
        return buildString {
            append("{")
            
            // 设备信息
            append("\"device\":{")
            append("\"model\":\"${escapeJson(Build.MODEL)}\",")
            append("\"brand\":\"${escapeJson(Build.BRAND)}\",")
            append("\"manufacturer\":\"${escapeJson(Build.MANUFACTURER)}\",")
            append("\"sdk\":${Build.VERSION.SDK_INT},")
            append("\"version\":\"${escapeJson(Build.VERSION.RELEASE)}\"")
            append("},")
            
            // 电池信息
            val batteryIntent = context.registerReceiver(null, 
                android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
            val batteryLevel = batteryIntent?.let {
                val level = it.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                val scale = it.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                if (level >= 0 && scale > 0) (level * 100 / scale) else -1
            } ?: -1
            val batteryStatus = batteryIntent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            val batteryStatusText = when (batteryStatus) {
                BatteryManager.BATTERY_STATUS_CHARGING -> "充电中"
                BatteryManager.BATTERY_STATUS_DISCHARGING -> "放电中"
                BatteryManager.BATTERY_STATUS_FULL -> "已充满"
                BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "未充电"
                else -> "未知"
            }
            val batteryTemp = batteryIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)?.let { it / 10.0 } ?: 0.0
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
            val memInfo = ActivityManager.MemoryInfo()
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            activityManager.getMemoryInfo(memInfo)
            append("\"memory\":{")
            append("\"totalMem\":${memInfo.totalMem},")
            append("\"availMem\":${memInfo.availMem},")
            append("\"usedMem\":${memInfo.totalMem - memInfo.availMem},")
            append("\"lowMemory\":${memInfo.lowMemory}")
            append("},")
            
            // 运行时间
            val uptimeMs = SystemClock.elapsedRealtime()
            append("\"uptime\":$uptimeMs,")
            
            // 当前时间
            append("\"currentTime\":${System.currentTimeMillis()}")
            
            append("}")
        }
    }

    private fun escapeJson(str: String): String {
        return str.replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
    }
}
