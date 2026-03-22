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

    companion object {
        val errorLog = mutableListOf<String>()
        fun addLog(msg: String) {
            val time = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date())
            errorLog.add("[$time] $msg")
            if (errorLog.size > 50) errorLog.removeFirst()
        }
    }

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
                // 0 = 无超时限制，支持大文件上传
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

    inner class FileHttpServer(port: Int) : NanoHTTPD(port) {

        override fun serve(session: IHTTPSession): Response {
            val uri = session.uri
            val decodedUri = java.net.URLDecoder.decode(uri, "UTF-8")
            
            addLog("${session.method} $decodedUri")
            
            return when {
                decodedUri == "/" -> serveIndex()
                decodedUri.startsWith("/download/") -> serveDownload(decodedUri)
                decodedUri == "/upload/chunk" -> serveUploadChunk(session)
                decodedUri.startsWith("/api/files") -> serveFileList(session)
                decodedUri == "/api/delete" && session.method == Method.POST -> serveDelete(session)
                decodedUri == "/api/move" && session.method == Method.POST -> serveMove(session)
                decodedUri == "/api/directories" -> serveDirectories(session)
                decodedUri == "/api/mkdir" && session.method == Method.POST -> serveMkdir(session)
                else -> serveFile(session, decodedUri)
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
                addLog("下载失败: 文件不存在 $filePath")
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }

            addLog("下载: ${file.name}")
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
                return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                    """{"error":"${escapeJson(e.javaClass.simpleName)}: ${escapeJson(e.message ?: "未知错误")}"}""")
            }
        }
        
        private fun serveUploadChunk(session: IHTTPSession): Response {
            try {
                addLog("收到上传请求")
                
                val fileName = session.parameters["filename"]?.firstOrNull()?.let {
                    java.net.URLDecoder.decode(it, "UTF-8")
                } ?: return errorResponse("Missing filename")
                val uploadPath = session.parameters["path"]?.firstOrNull()
                val chunkIndex = session.parameters["chunk"]?.firstOrNull()?.toIntOrNull() ?: 0
                val isLast = session.parameters["last"]?.firstOrNull() == "true"
                
                // 从 header 获取内容长度
                val contentLength = session.headers["content-length"]?.toLongOrNull() ?: -1
                addLog("文件名: $fileName, 块: $chunkIndex, 大小: $contentLength")
                
                if (contentLength <= 0) {
                    addLog("错误: 无效的内容长度")
                    return errorResponse("Invalid content length")
                }
                
                val targetPath = determineUploadTargetPath(uploadPath)
                val targetDir = File(targetPath)
                
                if (!targetDir.exists()) {
                    addLog("错误: 目录不存在")
                    return errorResponse("Directory not exists: $targetPath")
                }
                if (!targetDir.canWrite()) {
                    addLog("错误: 目录不可写")
                    return errorResponse("Cannot write to: $targetPath")
                }
                
                val targetFile = File(targetDir, fileName)
                addLog("写入: ${targetFile.absolutePath}")
                
                // 根据 Content-Length 读取指定字节数，不等待 EOF
                java.io.FileOutputStream(targetFile, chunkIndex > 0).use { output ->
                    val buffer = ByteArray(8192)
                    var remaining = contentLength
                    while (remaining > 0) {
                        val toRead = minOf(buffer.size.toLong(), remaining).toInt()
                        val bytesRead = session.inputStream.read(buffer, 0, toRead)
                        if (bytesRead == -1) break
                        output.write(buffer, 0, bytesRead)
                        remaining -= bytesRead
                    }
                    addLog("写入完成: ${contentLength - remaining} 字节")
                }
                
                addLog("上传成功")
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"success":true}""")
                    
            } catch (e: Exception) {
                addLog("异常: ${e.javaClass.simpleName}: ${e.message}")
                return errorResponse("${e.javaClass.simpleName}: ${e.message}")
            }
        }
        
        private fun errorResponse(message: String): Response {
            return newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "application/json", 
                """{"error":"${escapeJson(message)}"}""")
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
                }?.sortedWith(compareBy<File> { !it.isDirectory }.thenByDescending { it.lastModified() }) ?: emptyList()
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

        private fun serveDelete(session: IHTTPSession): Response {
            val requestBody = HashMap<String, String>()
            session.parseBody(requestBody)
            val body = requestBody["postData"] ?: return errorResponse("Missing request body")
            
            try {
                val json = org.json.JSONObject(body)
                val path = json.getString("path")
                val file = File(path)
                
                if (!file.exists()) {
                    return errorResponse("File not found")
                }
                
                // 检查权限：确保路径在允许的范围内
                val externalPath = Environment.getExternalStorageDirectory().absolutePath
                if (!file.absolutePath.startsWith(externalPath) && 
                    !file.absolutePath.startsWith(filesDir.absolutePath)) {
                    return errorResponse("Permission denied")
                }
                
                val success = if (file.isDirectory) {
                    file.deleteRecursively()
                } else {
                    file.delete()
                }
                
                return if (success) {
                    newFixedLengthResponse(Response.Status.OK, "application/json", 
                        """{"success":true,"message":"Deleted successfully"}""")
                } else {
                    errorResponse("Failed to delete file")
                }
            } catch (e: Exception) {
                android.util.Log.e("FileServer", "Error deleting file", e)
                return errorResponse("Error: ${e.message}")
            }
        }

        private fun serveMove(session: IHTTPSession): Response {
            val requestBody = HashMap<String, String>()
            session.parseBody(requestBody)
            val body = requestBody["postData"] ?: return errorResponse("Missing request body")
            
            try {
                val json = org.json.JSONObject(body)
                val sourcePath = json.getString("source")
                val targetDir = json.getString("targetDir")
                
                val sourceFile = File(sourcePath)
                val targetDirFile = File(targetDir)
                
                if (!sourceFile.exists()) {
                    return errorResponse("Source file not found")
                }
                
                if (!targetDirFile.exists() || !targetDirFile.isDirectory) {
                    return errorResponse("Target directory not found")
                }
                
                // 检查权限
                val externalPath = Environment.getExternalStorageDirectory().absolutePath
                if (!sourceFile.absolutePath.startsWith(externalPath) && 
                    !sourceFile.absolutePath.startsWith(filesDir.absolutePath)) {
                    return errorResponse("Permission denied")
                }
                
                val targetFile = File(targetDirFile, sourceFile.name)
                
                // 检查目标是否已存在
                if (targetFile.exists()) {
                    return errorResponse("File already exists in target directory")
                }
                
                // 使用renameTo进行高效移动（不会复制数据）
                var success = sourceFile.renameTo(targetFile)
                
                // 如果renameTo失败（比如跨文件系统），使用复制+删除
                if (!success) {
                    android.util.Log.w("FileServer", "renameTo failed, trying copy+delete")
                    success = try {
                        sourceFile.copyTo(targetFile)
                        sourceFile.delete()
                        true
                    } catch (e: Exception) {
                        false
                    }
                }
                
                return if (success) {
                    newFixedLengthResponse(Response.Status.OK, "application/json", 
                        """{"success":true,"message":"Moved successfully","newPath":"${escapeJson(targetFile.absolutePath)}"}""")
                } else {
                    errorResponse("Failed to move file")
                }
            } catch (e: Exception) {
                android.util.Log.e("FileServer", "Error moving file", e)
                return errorResponse("Error: ${e.message}")
            }
        }

        private fun serveDirectories(session: IHTTPSession): Response {
            val path = session.parameters["path"]?.firstOrNull()?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            } ?: Environment.getExternalStorageDirectory().absolutePath
            
            val dir = File(path)
            if (!dir.exists() || !dir.isDirectory) {
                return newFixedLengthResponse(Response.Status.OK, "application/json", """{"error":"Invalid directory"}""")
            }
            
            val directories = try {
                dir.listFiles()
                    ?.filter { it.isDirectory && it.canRead() && !it.name.startsWith(".") }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { dir ->
                        """{"name":"${escapeJson(dir.name)}","path":"${escapeJson(dir.absolutePath)}"}"""
                    } ?: emptyList()
            } catch (e: Exception) {
                emptyList()
            }
            
            // 获取父目录
            val parentPath = dir.parent
            
            val json = buildString {
                append("""{"currentPath":"${escapeJson(dir.absolutePath)}",""")
                if (parentPath != null) {
                    append(""","parentPath":"${escapeJson(parentPath)}",""")
                }
                append(""","directories":[${directories.joinToString(",")}]}"")
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }

        private fun serveMkdir(session: IHTTPSession): Response {
            val requestBody = HashMap<String, String>()
            session.parseBody(requestBody)
            val body = requestBody["postData"] ?: return errorResponse("Missing request body")
            
            try {
                val json = org.json.JSONObject(body)
                val parentPath = json.getString("parentPath")
                val dirName = json.getString("dirName")
                
                if (dirName.isEmpty() || dirName.contains("/") || dirName.contains("\\")) {
                    return errorResponse("Invalid directory name")
                }
                
                val parentDir = File(parentPath)
                if (!parentDir.exists() || !parentDir.isDirectory) {
                    return errorResponse("Parent directory not found")
                }
                
                // 检查权限
                val externalPath = Environment.getExternalStorageDirectory().absolutePath
                if (!parentDir.absolutePath.startsWith(externalPath) && 
                    !parentDir.absolutePath.startsWith(filesDir.absolutePath)) {
                    return errorResponse("Permission denied")
                }
                
                val newDir = File(parentDir, dirName)
                if (newDir.exists()) {
                    return errorResponse("Directory already exists")
                }
                
                val success = newDir.mkdirs()
                
                return if (success) {
                    newFixedLengthResponse(Response.Status.OK, "application/json", 
                        """{"success":true,"message":"Directory created","path":"${escapeJson(newDir.absolutePath)}"}""")
                } else {
                    errorResponse("Failed to create directory")
                }
            } catch (e: Exception) {
                android.util.Log.e("FileServer", "Error creating directory", e)
                return errorResponse("Error: ${e.message}")
            }
        }

        private fun serveFile(session: IHTTPSession, uri: String): Response {
            val file = File(uri)
            
            if (!file.exists() || !file.canRead()) {
                return newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "File not found")
            }

            if (file.isDirectory) {
                return serveIndex()
            }

            val mimeType = getMimeType(file.name)
            val fileLength = file.length()
            val rangeHeader = session.headers["range"]
            
            if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
                // 处理 Range 请求
                val range = rangeHeader.substring(6)
                val parts = range.split("-")
                val start = parts[0].toLongOrNull() ?: 0L
                val end = if (parts.size > 1 && parts[1].isNotEmpty()) {
                    parts[1].toLongOrNull() ?: (fileLength - 1)
                } else {
                    fileLength - 1
                }
                
                val contentLength = end - start + 1
                val fis = FileInputStream(file)
                fis.skip(start)
                
                val response = newChunkedResponse(Response.Status.PARTIAL_CONTENT, mimeType, fis)
                response.addHeader("Content-Range", "bytes $start-$end/$fileLength")
                response.addHeader("Content-Length", contentLength.toString())
                response.addHeader("Accept-Ranges", "bytes")
                return response
            } else {
                // 普通请求
                val fis = FileInputStream(file)
                val response = newChunkedResponse(Response.Status.OK, mimeType, fis)
                response.addHeader("Accept-Ranges", "bytes")
                response.addHeader("Content-Length", fileLength.toString())
                return response
            }
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
                fileName.endsWith(".mkv") -> "video/x-matroska"
                fileName.endsWith(".avi") -> "video/x-msvideo"
                fileName.endsWith(".webm") -> "video/webm"
                fileName.endsWith(".mov") -> "video/quicktime"
                fileName.endsWith(".wmv") -> "video/x-ms-wmv"
                fileName.endsWith(".flv") -> "video/x-flv"
                fileName.endsWith(".m4v") -> "video/x-m4v"
                fileName.endsWith(".3gp") -> "video/3gpp"
                fileName.endsWith(".ogv") -> "video/ogg"
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
    <link rel="icon" href="data:image/svg+xml,<svg xmlns='http://www.w3.org/2000/svg' viewBox='0 0 100 100'><text y='.9em' font-size='90'>🤖</text></svg>">
    <title>瓦力</title>
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
        .btn-move {
            background: #ff9800;
            color: white;
        }
        .btn-move:hover {
            background: #f57c00;
        }
        .btn-delete {
            background: #f44336;
            color: white;
        }
        .btn-delete:hover {
            background: #d32f2f;
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
        .mkdir-btn {
            background: #2196f3;
            color: white;
            padding: 8px 16px;
            border: none;
            border-radius: 4px;
            cursor: pointer;
            font-size: 14px;
        }
        .mkdir-btn:hover {
            background: #1976d2;
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
        <h1>🤖 瓦力</h1>
        <div class="breadcrumb" id="breadcrumb"></div>
        <div class="upload-bar">
            <button class="upload-btn" onclick="document.getElementById('fileInput').click()">上传文件</button>
            <input type="file" id="fileInput" multiple style="display:none" onchange="uploadFiles(this.files)">
            <button class="mkdir-btn" onclick="showMkdirDialog()">新建文件夹</button>
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
                'mp4': '🎬', 'avi': '🎬', 'mkv': '🎬', 'webm': '🎬', 'mov': '🎬', 'wmv': '🎬', 'flv': '🎬', 'm4v': '🎬', '3gp': '🎬', 'ogv': '🎬',
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
                    html += '<div class="file-actions">';
                    html += '<a class="btn btn-download" href="/download/' + filePath + '" download>下载</a>';
                    html += '<button class="btn btn-move" onclick="showMoveDialog(\'' + filePath.replace(/'/g, "\\'") + '\')">移动</button>';
                    html += '<button class="btn btn-delete" onclick="deleteFile(\'' + filePath.replace(/'/g, "\\'") + '\')">删除</button>';
                    html += '</div>';
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
        
        const CHUNK_SIZE = 100 * 1024 * 1024; // 100MB per chunk
        
        function generateFileId() {
            return Date.now().toString(36) + Math.random().toString(36).substr(2, 9);
        }
        
        function uploadFiles(files) {
            if (!files || files.length === 0) return;
            
            const progress = document.getElementById('uploadProgress');
            progress.style.display = 'block';
            
            const CHUNK_SIZE = 100 * 1024 * 1024; // 100MB
            let fileIndex = 0;
            
            function uploadNextFile() {
                if (fileIndex >= files.length) {
                    progress.textContent = '全部上传完成';
                    loadFiles(currentPath);
                    setTimeout(() => { progress.style.display = 'none'; }, 3000);
                    return;
                }
                
                const file = files[fileIndex];
                const totalChunks = Math.ceil(file.size / CHUNK_SIZE);
                let chunkIndex = 0;
                
                function uploadNextChunk() {
                    if (chunkIndex >= totalChunks) {
                        fileIndex++;
                        uploadNextFile();
                        return;
                    }
                    
                    const start = chunkIndex * CHUNK_SIZE;
                    const end = Math.min(start + CHUNK_SIZE, file.size);
                    const chunk = file.slice(start, end);
                    const isLast = (chunkIndex === totalChunks - 1);
                    
                    progress.textContent = file.name + '\n' + formatProgress(end, file.size);
                    
                    const url = '/upload/chunk?filename=' + encodeURIComponent(file.name) + 
                               '&path=' + encodeURIComponent(currentPath || '') + 
                               '&chunk=' + chunkIndex + 
                               '&last=' + isLast;
                    
                    const xhr = new XMLHttpRequest();
                    xhr.upload.onprogress = function(e) {
                        if (e.lengthComputable) {
                            const uploaded = start + e.loaded;
                            progress.textContent = file.name + '\n' + formatProgress(uploaded, file.size);
                        }
                    };
                    xhr.onload = function() {
                        if (xhr.status === 200) {
                            chunkIndex++;
                            uploadNextChunk();
                        } else {
                            try {
                                const err = JSON.parse(xhr.responseText);
                                progress.innerHTML = '上传失败<br>' + (err.error || '') + '<br><pre style="font-size:10px;text-align:left">' + (err.stack || '') + '</pre>';
                            } catch(e) {
                                progress.textContent = '上传失败: HTTP ' + xhr.status;
                            }
                        }
                    };
                    xhr.onerror = function() {
                        progress.textContent = '上传失败: 网络错误';
                    };
                    xhr.open('POST', url, true);
                    xhr.setRequestHeader('Content-Type', 'application/octet-stream');
                    xhr.send(chunk);
                }
                
                uploadNextChunk();
            }
            
            uploadNextFile();
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
        
        async function deleteFile(path) {
            if (!confirm('确定要删除此文件/文件夹吗？')) return;
            
            try {
                const response = await fetch('/api/delete', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ path: path })
                });
                const result = await response.json();
                
                if (result.success) {
                    loadFiles(currentPath);
                } else {
                    alert('删除失败: ' + (result.error || '未知错误'));
                }
            } catch (error) {
                alert('删除失败: ' + error.message);
            }
        }
        
        // 目标目录选择器
        let moveSourcePath = '';
        let selectedTargetPath = '';
        
        async function showMoveDialog(sourcePath) {
            moveSourcePath = sourcePath;
            selectedTargetPath = currentPath || '/';
            
            // 创建模态对话框
            const modal = document.createElement('div');
            modal.id = 'moveModal';
            modal.style.cssText = 'position:fixed;top:0;left:0;right:0;bottom:0;background:rgba(0,0,0,0.5);display:flex;align-items:center;justify-content:center;z-index:2000;';
            
            modal.innerHTML = `
                <div style="background:white;border-radius:12px;width:90%;max-width:400px;max-height:80vh;display:flex;flex-direction:column;">
                    <div style="padding:16px;border-bottom:1px solid #eee;">
                        <h3 style="margin:0 0 8px 0;">选择目标目录</h3>
                        <div id="currentDirPath" style="font-size:13px;color:#666;word-break:break-all;">/</div>
                    </div>
                    <div id="directoryList" style="flex:1;overflow-y:auto;padding:8px 0;">
                        <div style="padding:20px;text-align:center;color:#999;">加载中...</div>
                    </div>
                    <div style="padding:16px;border-top:1px solid #eee;display:flex;gap:10px;">
                        <button onclick="closeMoveDialog()" style="flex:1;padding:10px;border:1px solid #ddd;border-radius:6px;background:white;cursor:pointer;">取消</button>
                        <button onclick="confirmMove()" style="flex:1;padding:10px;border:none;border-radius:6px;background:#1976d2;color:white;cursor:pointer;">移动到这里</button>
                    </div>
                </div>
            `;
            
            document.body.appendChild(modal);
            loadDirectories('/');
        }
        
        async function loadDirectories(path) {
            const listEl = document.getElementById('directoryList');
            const pathEl = document.getElementById('currentDirPath');
            
            try {
                const response = await fetch('/api/directories?path=' + encodeURIComponent(path));
                const data = await response.json();
                
                if (data.error) {
                    listEl.innerHTML = '<div style="padding:20px;text-align:center;color:#f44336;">' + data.error + '</div>';
                    return;
                }
                
                selectedTargetPath = data.currentPath;
                pathEl.textContent = data.currentPath;
                
                let html = '';
                
                // 返回上级目录
                if (data.parentPath) {
                    html += "<div style=\"padding:12px 16px;cursor:pointer;display:flex;align-items:center;gap:10px;\" onclick=\"loadDirectories('" + data.parentPath.replace(/'/g, "\\'") + "')\">" +
                        "<span style=\"font-size:18px;\">📁</span>" +
                        "<span style=\"color:#1976d2;\">.. 返回上级</span>" +
                    "</div>";
                }
                
                // 目录列表
                if (data.directories && data.directories.length > 0) {
                    data.directories.forEach(dir => {
                        html += "<div style=\"padding:12px 16px;cursor:pointer;display:flex;align-items:center;gap:10px;border-bottom:1px solid #f5f5f5;\" " +
                            "onclick=\"loadDirectories('" + dir.path.replace(/'/g, "\\'") + "')\" " +
                            "onmouseover=\"this.style.background='#f5f5f5'\" " +
                            "onmouseout=\"this.style.background='white'\">" +
                            "<span style=\"font-size:18px;\">📁</span>" +
                            "<span>" + dir.name + "</span>" +
                        "</div>";
                    });
                } else {
                    html += '<div style="padding:20px;text-align:center;color:#999;">此目录没有子文件夹</div>';
                }
                
                listEl.innerHTML = html;
            } catch (error) {
                listEl.innerHTML = '<div style="padding:20px;text-align:center;color:#f44336;">加载失败</div>';
            }
        }
        
        function closeMoveDialog() {
            const modal = document.getElementById('moveModal');
            if (modal) modal.remove();
        }
        
        async function confirmMove() {
            if (!selectedTargetPath) {
                alert('请选择目标目录');
                return;
            }
            
            closeMoveDialog();
            
            try {
                const response = await fetch('/api/move', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ source: moveSourcePath, targetDir: selectedTargetPath })
                });
                const result = await response.json();
                
                if (result.success) {
                    loadFiles(currentPath);
                    alert('移动成功');
                } else {
                    alert('移动失败: ' + (result.error || '未知错误'));
                }
            } catch (error) {
                alert('移动失败: ' + error.message);
            }
        }
        
        function showMkdirDialog() {
            const dirName = prompt('请输入新文件夹名称:');
            if (!dirName || dirName.trim() === '') return;
            createDirectory(dirName.trim());
        }
        
        async function createDirectory(dirName) {
            try {
                const response = await fetch('/api/mkdir', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ parentPath: currentPath || '/', dirName: dirName })
                });
                const result = await response.json();
                
                if (result.success) {
                    loadFiles(currentPath);
                } else {
                    alert('创建失败: ' + (result.error || '未知错误'));
                }
            } catch (error) {
                alert('创建失败: ' + error.message);
            }
        }
    </script>
</body>
</html>
            """.trimIndent()
        }
    }
}
