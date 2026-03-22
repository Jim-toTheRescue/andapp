package com.andapp.fileserver

import android.Manifest
import android.app.ActivityManager
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.NetworkInterface
import java.net.URL

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
        private const val INSTALL_PERMISSION_REQUEST_CODE = 102
        private const val UPDATE_CHECK_DELAY = 2000L
        private const val GITHUB_API_URL = "https://api.github.com/repos/Jim-toTheRescue/andapp/releases/tags/latest"
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

        lifecycleScope.launch {
            delay(UPDATE_CHECK_DELAY)
            checkForUpdate()
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

    private fun checkForUpdate() {
        lifecycleScope.launch {
            try {
                val currentVersion = getCurrentVersion()
                FileServerService.addLog("更新检查: 当前版本=$currentVersion")
                
                val updateInfo = withContext(Dispatchers.IO) {
                    fetchLatestRelease()
                }
                
                updateInfo?.let { (latestVersion, downloadUrl, releaseName) ->
                    FileServerService.addLog("更新检查: 最新版本=$latestVersion")
                    if (compareVersions(currentVersion, latestVersion) < 0) {
                        FileServerService.addLog("发现新版本 $latestVersion，弹出更新提示")
                        showUpdateDialog(latestVersion, downloadUrl, releaseName)
                    } else {
                        FileServerService.addLog("当前已是最新版本")
                    }
                } ?: FileServerService.addLog("更新检查: 获取版本失败")
            } catch (e: Exception) {
                FileServerService.addLog("更新检查失败: ${e.message}")
            }
        }
    }

    private fun getCurrentVersion(): String {
        return try {
            val packageInfo = packageManager.getPackageInfo(packageName, 0)
            packageInfo.versionName ?: "0"
        } catch (e: Exception) {
            "0"
        }
    }

    private fun fetchLatestRelease(): Triple<String, String, String>? {
        return try {
            FileServerService.addLog("更新检查: 正在请求 $GITHUB_API_URL")
            val url = URL(GITHUB_API_URL)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"
            connection.setRequestProperty("Accept", "application/json")
            connection.connectTimeout = 10000
            
            val responseCode = connection.responseCode
            FileServerService.addLog("更新检查: 响应状态码=$responseCode")
            
            if (responseCode == 200) {
                val response = connection.inputStream.bufferedReader().readText()
                FileServerService.addLog("更新检查: 获取到 Release 数据，长度=${response.length}")
                val json = JSONObject(response)
                
                val tagName = json.getString("tag_name")
                val name = json.optString("name", "Latest Build")
                FileServerService.addLog("更新检查: tag=$tagName, name=$name")
                
                val assets = json.getJSONArray("assets")
                var downloadUrl = ""
                FileServerService.addLog("更新检查: 附件数量=${assets.length()}")
                
                for (i in 0 until assets.length()) {
                    val asset = assets.getJSONObject(i)
                    val assetName = asset.getString("name")
                    FileServerService.addLog("更新检查: 附件[$i] name=$assetName")
                    if (assetName.endsWith(".apk")) {
                        downloadUrl = asset.getString("browser_download_url")
                        FileServerService.addLog("更新检查: 找到 APK=$downloadUrl")
                        break
                    }
                }
                
                if (downloadUrl.isNotEmpty()) {
                    Triple(tagName, downloadUrl, name)
                } else {
                    FileServerService.addLog("更新检查: 未找到 APK 附件")
                    null
                }
            } else {
                FileServerService.addLog("更新检查: HTTP 错误 $responseCode")
                null
            }
        } catch (e: Exception) {
            FileServerService.addLog("更新检查: 异常=${e.javaClass.simpleName}: ${e.message}")
            null
        }
    }

    private fun compareVersions(current: String, latest: String): Int {
        val currentParts = current.split(".").mapNotNull { it.toIntOrNull() }
        val latestParts = latest.split(".").mapNotNull { it.toIntOrNull() }
        
        val maxLen = maxOf(currentParts.size, latestParts.size)
        
        for (i in 0 until maxLen) {
            val c = currentParts.getOrElse(i) { 0 }
            val l = latestParts.getOrElse(i) { 0 }
            if (c != l) return c.compareTo(l)
        }
        return 0
    }

    private fun showUpdateDialog(version: String, downloadUrl: String, releaseName: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setTitle("发现新版本")
                .setMessage("最新版本: $version\n$releaseName\n\n是否立即更新？")
                .setPositiveButton("更新") { _, _ ->
                    downloadAndInstall(downloadUrl)
                }
                .setNegativeButton("稍后") { dialog, _ ->
                    dialog.dismiss()
                }
                .setCancelable(true)
                .show()
        }
    }

    private fun downloadAndInstall(downloadUrl: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "正在下载更新...", Toast.LENGTH_SHORT).show()
                }
                
                val apkFile = withContext(Dispatchers.IO) {
                    downloadApk(downloadUrl)
                }
                
                apkFile?.let {
                    withContext(Dispatchers.Main) {
                        installApk(it)
                    }
                } ?: withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "下载失败", Toast.LENGTH_SHORT).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(this@MainActivity, "更新失败: ${e.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun downloadApk(url: String): File? {
        return try {
            val downloadUrl = URL(url)
            val connection = downloadUrl.openConnection() as HttpURLConnection
            connection.connect()
            
            val inputStream = connection.inputStream
            val apkFile = File(getExternalFilesDir(null), "update.apk")
            val outputStream = FileOutputStream(apkFile)
            
            val buffer = ByteArray(4096)
            var bytesRead: Int
            while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                outputStream.write(buffer, 0, bytesRead)
            }
            
            outputStream.close()
            inputStream.close()
            connection.disconnect()
            
            apkFile
        } catch (e: Exception) {
            null
        }
    }

    private fun installApk(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (!packageManager.canRequestPackageInstalls()) {
                requestInstallPermission()
                return
            }
        }
        performInstall(apkFile)
    }

    private fun requestInstallPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            AlertDialog.Builder(this)
                .setTitle("需要安装权限")
                .setMessage("请在设置中允许安装未知来源应用")
                .setPositiveButton("去设置") { _, _ ->
                    startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                        data = Uri.parse("package:$packageName")
                    })
                }
                .setNegativeButton("取消", null)
                .show()
        }
    }

    private fun performInstall(apkFile: File) {
        try {
            val uri = FileProvider.getUriForFile(
                this,
                "${packageName}.fileprovider",
                apkFile
            )
            
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            
            startActivity(intent)
        } catch (e: Exception) {
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(Uri.fromFile(apkFile), "application/vnd.android.package-archive")
            }
            startActivity(intent)
        }
    }
}