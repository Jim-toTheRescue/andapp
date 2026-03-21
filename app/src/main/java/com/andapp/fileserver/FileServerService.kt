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
import java.io.File
import java.io.FileInputStream
import java.net.URLEncoder

class FileServerService : Service() {

    private var server: FileHttpServer? = null
    private val binder = LocalBinder()
    private var serverPort = 8080

    inner class LocalBinder : Binder() {
        fun getService(): FileServerService = this@FileServerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(1, createNotification())
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
                "file_server_channel",
                "File Server",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, "file_server_channel")
            .setContentTitle("File Server")
            .setContentText("Server is running on port $serverPort")
            .setSmallIcon(android.R.drawable.ic_menu_upload)
            .build()
    }

    fun startServer() {
        try {
            if (server == null) {
                server = FileHttpServer(serverPort)
                server?.start()
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

    inner class FileHttpServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val decodedUri = java.net.URLDecoder.decode(uri, "UTF-8")
            
            return when {
                decodedUri == "/" -> serveIndex()
                decodedUri.startsWith("/download/") -> serveDownload(decodedUri)
                decodedUri.startsWith("/api/files") -> serveFileList(decodedUri)
                else -> serveFile(decodedUri)
            }
        }

        private fun serveIndex(): Response {
            val html = generateIndexHtml()
            return newFixedLengthResponse(Response.Status.OK, "text/html", html)
        }

        private fun serveDownload(uri: String): Response {
            val filePath = uri.removePrefix("/download/")
            val file = File(filePath)
            
            if (!file.exists() || !file.canRead()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }

            val fis = FileInputStream(file)
            val mimeType = getMimeType(file.name)
            val encodedName = URLEncoder.encode(file.name, "UTF-8").replace("+", "%20")
            
            val response = newChunkedResponse(Response.Status.OK, mimeType, fis)
            response.addHeader("Content-Disposition", "attachment; filename*=UTF-8''$encodedName")
            return response
        }

        private fun serveFileList(uri: String): Response {
            val path = uri.substringAfter("/api/files?path=").let {
                if (it == uri) {
                    // 尝试外部存储，如果不可用则使用应用私有目录
                    val externalPath = Environment.getExternalStorageDirectory().absolutePath
                    val externalDir = File(externalPath)
                    if (externalDir.exists() && externalDir.canRead()) {
                        externalPath
                    } else {
                        // 使用应用私有目录作为备选
                        android.util.Log.w("FileServer", "External storage not accessible, using app private directory")
                        filesDir.absolutePath
                    }
                } else {
                    java.net.URLDecoder.decode(it, "UTF-8")
                }
            }
            
            android.util.Log.d("FileServer", "Listing files for path: $path")
            
            val dir = File(path)
            if (!dir.exists()) {
                android.util.Log.e("FileServer", "Directory does not exist: $path")
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"error":"Directory not found","path":"${escapeJson(path)}"}""")
            }
            if (!dir.isDirectory) {
                android.util.Log.e("FileServer", "Path is not a directory: $path")
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"error":"Not a directory","path":"${escapeJson(path)}"}""")
            }
            if (!dir.canRead()) {
                android.util.Log.e("FileServer", "Cannot read directory: $path")
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"error":"Permission denied","path":"${escapeJson(path)}"}""")
            }

            val files = try {
                dir.listFiles()?.filter { 
                    try { it.canRead() } catch (e: Exception) { false }
                }?.sortedWith(compareBy<File> { !it.isDirectory }.thenBy { it.name.lowercase() }) ?: emptyList()
            } catch (e: Exception) {
                android.util.Log.e("FileServer", "Error listing files", e)
                emptyList()
            }
            
            android.util.Log.d("FileServer", "Found ${files.size} files")
            
            val json = buildString {
                append("[")
                files.forEachIndexed { index, file ->
                    if (index > 0) append(",")
                    val size = try { file.length() } catch (e: Exception) { 0L }
                    val lastModified = try { file.lastModified() } catch (e: Exception) { 0L }
                    append("""{"name":"${escapeJson(file.name)}","path":"${escapeJson(file.absolutePath)}","isDirectory":${file.isDirectory},"size":${size},"lastModified":${lastModified}}""")
                }
                append("]")
            }

            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }

        private fun serveFile(uri: String): Response {
            val file = File(uri)
            
            if (!file.exists() || !file.canRead()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }

            if (file.isDirectory) {
                return serveIndex()
            }

            val fis = FileInputStream(file)
            val mimeType = getMimeType(file.name)
            return newChunkedResponse(Response.Status.OK, mimeType, fis)
        }

        private fun getMimeType(fileName: String): String {
            return when {
                fileName.endsWith(".html") || fileName.endsWith(".htm") -> "text/html"
                fileName.endsWith(".css") -> "text/css"
                fileName.endsWith(".js") -> "application/javascript"
                fileName.endsWith(".json") -> "application/json"
                fileName.endsWith(".png") -> "image/png"
                fileName.endsWith(".jpg") || fileName.endsWith(".jpeg") -> "image/jpeg"
                fileName.endsWith(".gif") -> "image/gif"
                fileName.endsWith(".pdf") -> "application/pdf"
                fileName.endsWith(".txt") -> "text/plain"
                fileName.endsWith(".mp3") -> "audio/mpeg"
                fileName.endsWith(".mp4") -> "video/mp4"
                fileName.endsWith(".zip") -> "application/zip"
                fileName.endsWith(".apk") -> "application/vnd.android.package-archive"
                else -> "application/octet-stream"
            }
        }

        private fun escapeJson(str: String): String {
            return str.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t")
        }

        private fun generateIndexHtml(): String {
            return """
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <title>File Server</title>
    <style>
        * {
            margin: 0;
            padding: 0;
            box-sizing: border-box;
        }
        body {
            font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif;
            background: #f5f5f5;
            padding: 20px;
        }
        .container {
            max-width: 1200px;
            margin: 0 auto;
        }
        h1 {
            color: #333;
            margin-bottom: 20px;
            display: flex;
            align-items: center;
            gap: 10px;
        }
        .breadcrumb {
            background: white;
            padding: 15px;
            border-radius: 8px;
            margin-bottom: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
        }
        .breadcrumb a {
            color: #1976d2;
            text-decoration: none;
        }
        .breadcrumb a:hover {
            text-decoration: underline;
        }
        .breadcrumb span {
            color: #666;
        }
        .file-list {
            background: white;
            border-radius: 8px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            overflow: hidden;
        }
        .file-item {
            display: flex;
            align-items: center;
            padding: 12px 20px;
            border-bottom: 1px solid #eee;
            transition: background 0.2s;
        }
        .file-item:hover {
            background: #f8f9fa;
        }
        .file-item:last-child {
            border-bottom: none;
        }
        .file-icon {
            width: 40px;
            height: 40px;
            display: flex;
            align-items: center;
            justify-content: center;
            font-size: 24px;
            margin-right: 15px;
        }
        .file-info {
            flex: 1;
        }
        .file-name {
            color: #333;
            text-decoration: none;
            font-weight: 500;
        }
        .file-name:hover {
            color: #1976d2;
        }
        .file-meta {
            color: #999;
            font-size: 12px;
            margin-top: 4px;
        }
        .file-actions {
            display: flex;
            gap: 10px;
        }
        .btn {
            padding: 6px 12px;
            border: none;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
            text-decoration: none;
        }
        .btn-download {
            background: #1976d2;
            color: white;
        }
        .btn-download:hover {
            background: #1565c0;
        }
        .empty {
            padding: 40px;
            text-align: center;
            color: #999;
        }
        .loading {
            padding: 40px;
            text-align: center;
            color: #999;
        }
    </style>
</head>
<body>
    <div class="container">
        <h1>📁 File Server</h1>
        <div class="breadcrumb" id="breadcrumb"></div>
        <div class="file-list" id="fileList">
            <div class="loading">Loading...</div>
        </div>
    </div>
    
    <script>
        let currentPath = '';
        
        function formatSize(bytes) {
            if (bytes === 0) return '0 B';
            const k = 1024;
            const sizes = ['B', 'KB', 'MB', 'GB'];
            const i = Math.floor(Math.log(bytes) / Math.log(k));
            return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i];
        }
        
        function formatDate(timestamp) {
            return new Date(timestamp).toLocaleString();
        }
        
        function getIcon(file) {
            if (file.isDirectory) return '📁';
            const ext = file.name.split('.').pop().toLowerCase();
            const icons = {
                'jpg': '🖼️', 'jpeg': '🖼️', 'png': '🖼️', 'gif': '🖼️',
                'mp3': '🎵', 'wav': '🎵', 'ogg': '🎵',
                'mp4': '🎬', 'avi': '🎬', 'mkv': '🎬',
                'pdf': '📄', 'doc': '📄', 'docx': '📄',
                'xls': '📊', 'xlsx': '📊',
                'zip': '📦', 'rar': '📦', '7z': '📦',
                'apk': '📱',
                'txt': '📝', 'md': '📝'
            };
            return icons[ext] || '📄';
        }
        
        function renderBreadcrumb(path) {
            const parts = path ? path.split('/') : [];
            let html = "<a href=\"#\" onclick=\"loadFiles('')\">🏠 Root</a>";
            let currentPath = '';
            
            parts.forEach((part, index) => {
                if (part) {
                    currentPath += '/' + part;
                    html += " / <a href=\"#\" onclick=\"loadFiles('" + currentPath + "')\">" + part + "</a>";
                }
            });
            
            document.getElementById('breadcrumb').innerHTML = html;
        }
        
        async function loadFiles(path = '') {
            currentPath = path;
            renderBreadcrumb(path);
            
            const fileList = document.getElementById('fileList');
            fileList.innerHTML = '<div class="loading">Loading...</div>';
            
            try {
                const url = path ? '/api/files?path=' + encodeURIComponent(path) : '/api/files';
                const response = await fetch(url);
                const data = await response.json();
                
                if (data.error) {
                    fileList.innerHTML = '<div class="empty">Error: ' + data.error + '<br>Path: ' + (data.path || '') + '</div>';
                    return;
                }
                
                const files = data;
                
                if (!files || files.length === 0) {
                    fileList.innerHTML = '<div class="empty">No files found</div>';
                    return;
                }
                
                let html = '';
                files.forEach(file => {
                    const icon = getIcon(file);
                    const name = file.name;
                    const filePath = file.path;
                    const isDir = file.isDirectory;
                    const size = formatSize(file.size);
                    const date = formatDate(file.lastModified);
                    
                    html += '<div class="file-item">';
                    html += '<div class="file-icon">' + icon + '</div>';
                    html += '<div class="file-info">';
                    if (isDir) {
                        html += '<div class="file-name" style="cursor:pointer;color:#1976d2" onclick="loadFiles(this.getAttribute(\'data-path\'))" data-path="' + filePath.replace(/&/g, '&amp;').replace(/"/g, '&quot;') + '">' + name + '</div>';
                    } else {
                        html += '<span class="file-name">' + name + '</span>';
                    }
                    html += '<div class="file-meta">';
                    html += isDir ? 'Folder' : size;
                    html += ' • ' + date;
                    html += '</div>';
                    html += '</div>';
                    if (!isDir) {
                        html += '<div class="file-actions">';
                        html += '<a class="btn btn-download" href="/download/' + filePath + '" download>Download</a>';
                        html += '</div>';
                    }
                    html += '</div>';
                });
                
                fileList.innerHTML = html;
            } catch (error) {
                console.error('Error loading files:', error);
                fileList.innerHTML = '<div class="empty">Error loading files: ' + error.message + '<br><br>Please check:<br>1. Server is running<br>2. Storage permissions are granted<br>3. Check Android logcat for details</div>';
            }
        }
        
        loadFiles();
    </script>
</body>
</html>
            """.trimIndent()
        }
    }
}
