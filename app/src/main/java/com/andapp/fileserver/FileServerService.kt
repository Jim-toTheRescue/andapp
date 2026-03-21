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
                decodedUri == "/upload" && session.method == Method.POST -> serveUpload(session)
                decodedUri.startsWith("/api/files") -> serveFileList(session)
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

        private fun serveUpload(session: IHTTPSession): Response {
            android.util.Log.d("FileServer", "Upload request received, method: ${session.method}")
            
            // 从 query 参数获取目标路径和文件名
            val uploadPath = session.parameters["path"]?.firstOrNull()
            val fileName = session.parameters["filename"]?.firstOrNull()?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: "upload_${System.currentTimeMillis()}"
            
            android.util.Log.d("FileServer", "Upload path: $uploadPath, filename: $fileName")
            
            // 确定目标目录
            val targetPath = determineUploadTargetPath(uploadPath)
            android.util.Log.d("FileServer", "Target path: $targetPath")
            
            val targetDir = File(targetPath)
            if (!targetDir.exists()) {
                targetDir.mkdirs()
            }
            
            if (!targetDir.canWrite()) {
                android.util.Log.e("FileServer", "Cannot write to: $targetPath")
                return newFixedLengthResponse(Response.Status.BAD_REQUEST, "application/json", 
                    """{"error":"无法写入目录: ${escapeJson(targetPath)}"}""")
            }
            
            val targetFile = File(targetDir, fileName)
            android.util.Log.d("FileServer", "Target file: ${targetFile.absolutePath}")
            
            try {
                // 流式写入：直接从输入流写入文件，不加载到内存
                val inputStream = session.inputStream
                var totalBytes = 0L
                
                targetFile.outputStream().use { outputStream ->
                    val buffer = ByteArray(8192) // 8KB 缓冲区
                    var bytesRead: Int
                    
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        outputStream.write(buffer, 0, bytesRead)
                        totalBytes += bytesRead
                    }
                }
                
                android.util.Log.d("FileServer", "Upload completed: ${targetFile.absolutePath}, size: $totalBytes")
                
                // 通知系统扫描新文件（可能失败但不影响上传结果）
                try {
                    notifyMediaScanner(targetFile)
                } catch (e: Exception) {
                    android.util.Log.w("FileServer", "MediaScanner notification failed", e)
                }
                
                val json = """{"success":true,"targetDir":"${escapeJson(targetPath)}","files":["${escapeJson(fileName)}"],"size":$totalBytes}"""
                return newFixedLengthResponse(Response.Status.OK, "application/json", json)
                
            } catch (e: Exception) {
                // 删除不完整的文件
                if (targetFile.exists()) {
                    targetFile.delete()
                }
                android.util.Log.e("FileServer", "Upload failed: ${e.javaClass.simpleName}", e)
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                    """{"error":"上传失败: ${escapeJson(e.message ?: "未知错误")}"}""")
            }
        }
        
        private fun determineUploadTargetPath(uploadPath: String?): String {
            // 如果没有指定路径，使用应用私有目录
            if (uploadPath.isNullOrEmpty()) {
                return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath 
                    ?: filesDir.absolutePath
            }
            
            val targetDir = File(uploadPath)
            
            // 检查目录是否存在且可写
            if (targetDir.exists() && targetDir.canWrite()) {
                return uploadPath
            }
            
            // 如果目标路径不可写，检查是否是外部存储根目录
            val externalRoot = Environment.getExternalStorageDirectory().absolutePath
            if (uploadPath.startsWith(externalRoot)) {
                // 尝试使用应用私有目录下的对应子目录
                val relativePath = uploadPath.removePrefix(externalRoot).removePrefix("/")
                val appDir = getExternalFilesDir(null)
                if (appDir != null) {
                    val fallbackDir = File(appDir, relativePath)
                    fallbackDir.mkdirs()
                    if (fallbackDir.canWrite()) {
                        android.util.Log.w("FileServer", "Using app private dir: ${fallbackDir.absolutePath}")
                        return fallbackDir.absolutePath
                    }
                }
            }
            
            // 最终回退到应用下载目录
            return getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)?.absolutePath 
                ?: filesDir.absolutePath
        }
        
        private fun notifyMediaScanner(file: File) {
            try {
                val mimeType = getMimeType(file.name)
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, file.name)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, file.parent?.removePrefix(Environment.getExternalStorageDirectory().absolutePath)?.removePrefix("/") ?: "")
                }
                
                val uri = if (mimeType.startsWith("image/")) {
                    contentResolver.insert(android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                } else if (mimeType.startsWith("video/")) {
                    contentResolver.insert(android.provider.MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
                } else if (mimeType.startsWith("audio/")) {
                    contentResolver.insert(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values)
                } else {
                    contentResolver.insert(android.provider.MediaStore.Files.getContentUri("external"), values)
                }
                
                uri?.let {
                    contentResolver.openOutputStream(it)?.use { os ->
                        file.inputStream().use { it.copyTo(os) }
                    }
                    android.util.Log.d("FileServer", "MediaStore synced: $uri")
                }
            } catch (e: Exception) {
                android.util.Log.w("FileServer", "MediaStore sync failed, using MediaScannerConnection", e)
                // 回退到 MediaScannerConnection
                android.media.MediaScannerConnection.scanFile(
                    applicationContext,
                    arrayOf(file.absolutePath),
                    arrayOf(getMimeType(file.name)),
                    null
                )
            }
        }

        private fun serveFileList(session: IHTTPSession): Response {
            val path = session.parameters["path"]?.firstOrNull()?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: run {
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
        .upload-bar {
            background: white;
            padding: 15px;
            border-radius: 8px;
            margin-bottom: 20px;
            box-shadow: 0 2px 4px rgba(0,0,0,0.1);
            display: flex;
            align-items: center;
            gap: 15px;
        }
        .upload-btn {
            background: #4caf50;
            color: white;
            padding: 8px 16px;
            border: none;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
        }
        .upload-btn:hover {
            background: #388e3c;
        }
        .upload-hint {
            color: #999;
            font-size: 13px;
        }
        .drop-overlay {
            display: none;
            position: fixed;
            top: 0; left: 0; right: 0; bottom: 0;
            background: rgba(25, 118, 210, 0.1);
            border: 3px dashed #1976d2;
            z-index: 1000;
            justify-content: center;
            align-items: center;
            font-size: 24px;
            color: #1976d2;
        }
        .drop-overlay.active {
            display: flex;
        }
        .upload-progress {
            display: none;
            padding: 10px;
            background: #e3f2fd;
            border-radius: 4px;
            margin-top: 10px;
        }
    </style>
</head>
<body>
    <div class="drop-overlay" id="dropOverlay">释放文件以上传</div>
    <div class="container">
        <h1>📁 File Server</h1>
        <div class="breadcrumb" id="breadcrumb"></div>
        <div class="upload-bar">
            <button class="upload-btn" onclick="document.getElementById('fileInput').click()">上传文件</button>
            <input type="file" id="fileInput" multiple style="display:none" onchange="uploadFiles(this.files)">
            <span class="upload-hint">或拖拽文件到页面上传</span>
            <div class="upload-progress" id="uploadProgress"></div>
        </div>
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
        
        async function loadFiles(path = '', pushState = true) {
            currentPath = path;
            renderBreadcrumb(path);
            
            if (pushState) {
                const newUrl = path ? '/?path=' + encodeURIComponent(path) : '/';
                history.pushState({ path: path }, '', newUrl);
            }
            
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
                        html += '<a class="file-name" href="' + filePath + '" target="_blank">' + name + '</a>';
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
        
        function formatProgress(loaded, total) {
            if (total === 0) return '0%';
            const percent = Math.round((loaded / total) * 100);
            return percent + '% (' + formatSize(loaded) + ' / ' + formatSize(total) + ')';
        }
        
        function uploadFiles(files) {
            if (!files || files.length === 0) return;
            
            const progress = document.getElementById('uploadProgress');
            progress.style.display = 'block';
            
            // 逐个上传文件
            let currentIndex = 0;
            
            function uploadNext() {
                if (currentIndex >= files.length) {
                    progress.textContent = '全部上传完成';
                    loadFiles(currentPath);
                    setTimeout(() => { progress.style.display = 'none'; }, 3000);
                    return;
                }
                
                const file = files[currentIndex];
                progress.textContent = '上传 (' + (currentIndex + 1) + '/' + files.length + '): ' + file.name;
                
                // 直接发送原始文件，文件名通过 query 参数传递
                const url = '/upload?path=' + encodeURIComponent(currentPath || '') + 
                           '&filename=' + encodeURIComponent(file.name);
                
                const xhr = new XMLHttpRequest();
                
                xhr.upload.onprogress = function(e) {
                    if (e.lengthComputable) {
                        progress.textContent = '上传 ' + file.name + '\n' + formatProgress(e.loaded, e.total);
                    }
                };
                
                xhr.onload = function() {
                    if (xhr.status === 200) {
                        try {
                            const result = JSON.parse(xhr.responseText);
                            if (result.success) {
                                currentIndex++;
                                uploadNext();
                            } else {
                                progress.textContent = '上传失败: ' + (result.error || '未知错误');
                            }
                        } catch (e) {
                            progress.textContent = '解析响应失败';
                        }
                    } else {
                        progress.textContent = '上传失败: HTTP ' + xhr.status;
                    }
                };
                
                xhr.onerror = function() {
                    progress.textContent = '上传失败: 网络错误或文件太大';
                };
                
                xhr.ontimeout = function() {
                    progress.textContent = '上传超时';
                };
                
                // 直接发送文件二进制流，不使用 FormData
                xhr.open('POST', url, true);
                xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                xhr.timeout = 0; // 无超时限制
                xhr.send(file);
            }
            
            uploadNext();
        }
        
        const dropOverlay = document.getElementById('dropOverlay');
        
        document.addEventListener('dragover', (e) => {
            e.preventDefault();
            dropOverlay.classList.add('active');
        });
        
        document.addEventListener('dragleave', (e) => {
            if (e.relatedTarget === null) {
                dropOverlay.classList.remove('active');
            }
        });
        
        document.addEventListener('drop', (e) => {
            e.preventDefault();
            dropOverlay.classList.remove('active');
            uploadFiles(e.dataTransfer.files);
        });
        
        window.addEventListener('popstate', (e) => {
            const path = (e.state && e.state.path) || new URLSearchParams(window.location.search).get('path') || '';
            loadFiles(path, false);
        });
        
        const initialPath = new URLSearchParams(window.location.search).get('path') || '';
        loadFiles(initialPath, false);
    </script>
</body>
</html>
            """.trimIndent()
        }
    }
}
