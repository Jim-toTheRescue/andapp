package com.andapp.fileserver

import android.Manifest
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.NetworkInterface

class MainActivity : AppCompatActivity() {

    private lateinit var statusText: TextView
    private lateinit var toggleButton: Button
    private lateinit var loadingProgress: ProgressBar
    private lateinit var hintText: TextView
    private lateinit var logText: TextView
    private var isServiceRunning = false
    private var isTransitioning = false

    companion object {
        private const val PERMISSION_REQUEST_CODE = 100
        private const val NOTIFICATION_PERMISSION_REQUEST_CODE = 101
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        statusText = findViewById(R.id.statusText)
        toggleButton = findViewById(R.id.toggleButton)
        loadingProgress = findViewById(R.id.loadingProgress)
        hintText = findViewById(R.id.hintText)
        logText = findViewById(R.id.logText)

        // 定时刷新日志
        val handler = android.os.Handler(android.os.Looper.getMainLooper())
        handler.postDelayed(object : Runnable {
            override fun run() {
                refreshLog()
                handler.postDelayed(this, 1000)
            }
        }, 1000)

        toggleButton.setOnClickListener {
            if (isTransitioning) return@setOnClickListener
            
            if (checkPermissions()) {
                requestNotificationPermission()
                if (isServiceRunning) {
                    stopService()
                } else {
                    startService()
                }
            } else {
                requestPermissions()
            }
        }

        if (checkPermissions()) {
            checkServiceStatus()
            requestNotificationPermission()
        } else {
            statusText.text = "需要存储权限才能运行"
            toggleButton.text = "授予权限"
            toggleButton.setBackgroundResource(R.drawable.btn_round_gray)
        }
    }

    private fun checkPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(
                    this,
                    Manifest.permission.POST_NOTIFICATIONS
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_REQUEST_CODE
                )
            }
        }
    }

    private fun requestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                intent.addCategory("android.intent.category.DEFAULT")
                intent.data = Uri.parse(String.format("package:%s", packageName))
                startActivity(intent)
            } catch (e: Exception) {
                val intent = Intent()
                intent.action = Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                startActivity(intent)
            }
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE
                ),
                PERMISSION_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                Toast.makeText(this, "权限已授予", Toast.LENGTH_SHORT).show()
                checkServiceStatus()
                requestNotificationPermission()
            } else {
                Toast.makeText(this, "需要存储权限才能运行服务器", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (checkPermissions()) {
            checkServiceStatus()
        }
    }

    private fun startService() {
        isTransitioning = true
        showLoading("正在启动...")
        
        val intent = Intent(this, FileServerService::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(intent)
        } else {
            startService(intent)
        }
        
        lifecycleScope.launch {
            delay(500) // 等待服务启动
            withContext(Dispatchers.IO) {
                // 再次检查服务是否真的启动了
                var attempts = 0
                while (attempts < 5) {
                    if (isServiceRunning(FileServerService::class.java)) {
                        break
                    }
                    delay(200)
                    attempts++
                }
            }
            isServiceRunning = isServiceRunning(FileServerService::class.java)
            isTransitioning = false
            hideLoading()
            updateUI()
        }
    }

    private fun stopService() {
        isTransitioning = true
        showLoading("正在停止...")
        
        val intent = Intent(this, FileServerService::class.java)
        stopService(intent)
        
        lifecycleScope.launch {
            delay(300)
            isServiceRunning = false
            isTransitioning = false
            hideLoading()
            updateUI()
        }
    }

    private fun showLoading(message: String) {
        toggleButton.isEnabled = false
        toggleButton.setBackgroundResource(R.drawable.btn_round_gray)
        toggleButton.text = ""
        loadingProgress.visibility = android.view.View.VISIBLE
        statusText.text = message
        hintText.text = ""
    }

    private fun hideLoading() {
        loadingProgress.visibility = android.view.View.GONE
        toggleButton.isEnabled = true
    }

    private fun refreshLog() {
        if (FileServerService.errorLog.isEmpty()) {
            logText.text = if (isServiceRunning) "等待请求..." else "服务器未运行"
        } else {
            logText.text = FileServerService.errorLog.joinToString("\n")
        }
    }

    private fun checkServiceStatus() {
        isServiceRunning = isServiceRunning(FileServerService::class.java)
        updateUI()
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun updateUI() {
        if (isServiceRunning) {
            val ip = getLocalIpAddress()
            statusText.text = "服务器运行中"
            toggleButton.text = "停止服务器"
            toggleButton.setBackgroundResource(R.drawable.btn_round_red)
            hintText.text = "http://$ip:8080"
        } else {
            statusText.text = "服务器已停止"
            toggleButton.text = "启动服务器"
            toggleButton.setBackgroundResource(R.drawable.btn_round_green)
            hintText.text = "点击启动后，其他设备可通过浏览器访问"
        }
    }

    private fun getLocalIpAddress(): String {
        try {
            val interfaces = NetworkInterface.getNetworkInterfaces()
            while (interfaces.hasMoreElements()) {
                val intf = interfaces.nextElement()
                val addrs = intf.inetAddresses
                while (addrs.hasMoreElements()) {
                    val addr = addrs.nextElement()
                    if (!addr.isLoopbackAddress && addr is java.net.Inet4Address) {
                        return addr.hostAddress ?: "unknown"
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return "unknown"
    }
}