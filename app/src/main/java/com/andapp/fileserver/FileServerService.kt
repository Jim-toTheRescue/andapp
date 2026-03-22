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

        private fun readRequestBody(session: IHTTPSession): String? {
            return try {
                val contentLength = session.headers["content-length"]?.toIntOrNull() ?: 0
                if (contentLength > 0) {
                    val buffer = ByteArray(contentLength)
                    val inputStream = session.inputStream
                    var totalRead = 0
                    while (totalRead < contentLength) {
                        val read = inputStream.read(buffer, totalRead, contentLength - totalRead)
                        if (read == -1) break
                        totalRead += read
                    }
                    String(buffer, 0, totalRead, Charsets.UTF_8)
                } else {
                    null
                }
            } catch (e: Exception) {
                addLog("Error reading request body: ${e.message}")
                null
            }
        }

        private fun serveDelete(session: IHTTPSession): Response {
            addLog("=== serveDelete ===")
            val body = readRequestBody(session)
            addLog("Request body: $body")
            
            if (body == null) return errorResponse("Missing request body")
            
            try {
                val json = org.json.JSONObject(body)
                val path = json.getString("path")
                addLog("Path: $path")
                
                val file = File(path)
                addLog("File exists: ${file.exists()}, isFile: ${file.isFile}, canRead: ${file.canRead()}")
                
                if (!file.exists()) {
                    addLog("File not found: $path")
                    return errorResponse("File not found: $path")
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
            addLog("=== serveMove ===")
            val body = readRequestBody(session)
            addLog("Request body: $body")
            
            if (body == null) return errorResponse("Missing request body")
            
            try {
                val json = org.json.JSONObject(body)
                val sourcePath = json.getString("source")
                val targetDir = json.getString("targetDir")
                addLog("Source: $sourcePath, TargetDir: $targetDir")
                
                val sourceFile = File(sourcePath)
                val targetDirFile = File(targetDir)
                addLog("Source exists: ${sourceFile.exists()}, TargetDir exists: ${targetDirFile.exists()}")
                
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
            addLog("=== serveDirectories ===")
            val path = session.parameters["path"]?.firstOrNull()?.let {
                java.net.URLDecoder.decode(it, "UTF-8")
            }?.takeIf { it != "/" } ?: Environment.getExternalStorageDirectory().absolutePath
            
            addLog("Path: $path")
            val dir = File(path)
            addLog("Dir exists: ${dir.exists()}, isDirectory: ${dir.isDirectory}, canRead: ${dir.canRead()}")
            
            if (!dir.exists() || !dir.isDirectory) {
                addLog("Invalid directory: $path")
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
                append("""{"currentPath":"${escapeJson(dir.absolutePath)}"""")
                if (parentPath != null) {
                    append(""","parentPath":"${escapeJson(parentPath)}"""")
                }
                append(""","directories":[${directories.joinToString(",")}]}""")
            }
            
            return newFixedLengthResponse(Response.Status.OK, "application/json", json)
        }

        private fun serveMkdir(session: IHTTPSession): Response {
            addLog("=== serveMkdir ===")
            val body = readRequestBody(session)
            addLog("Request body: $body")
            
            if (body == null) return errorResponse("Missing request body")
            
            try {
                val json = org.json.JSONObject(body)
                val parentPath = json.getString("parentPath")
                val dirName = json.getString("dirName")
                addLog("ParentPath: $parentPath, DirName: $dirName")
                
                if (dirName.isEmpty() || dirName.contains("/") || dirName.contains("\\")) {
                    return errorResponse("Invalid directory name")
                }
                
                val parentDir = File(parentPath)
                addLog("ParentDir exists: ${parentDir.exists()}, isDirectory: ${parentDir.isDirectory}, canRead: ${parentDir.canRead()}")
                
                if (!parentDir.exists()) {
                    return errorResponse("Parent directory not found: $parentPath")
                }
                if (!parentDir.isDirectory) {
                    return errorResponse("Parent path is not a directory: $parentPath")
                }
                if (!parentDir.canRead()) {
                    return errorResponse("Cannot read parent directory: $parentPath")
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
                val response = newChunkedResponse(NanoHTTPD.Response.Status.OK, mimeType, fis)
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
            return try {
                assets.open("web/index.html").bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                addLog("Failed to load index.html: ${e.message}")
                "<html><body><h1>Error loading page</h1><p>${e.message}</p></body></html>"
            }
        }
    }
}
