package com.example.alarm

import android.content.Context
import android.os.Build
import android.util.Log
import com.example.db.Alarm
import com.example.db.AlarmDatabase
import com.example.db.AlarmGroup
import com.example.db.HourlyChime
import com.example.db.AlarmRepository
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.util.*
import java.util.zip.Deflater

class WifiSyncServer(
    private val context: Context,
    private val port: Int = 8080,
    private val onConfigChanged: () -> Unit
) {
    private val TAG = "WifiSyncServer"
    private var serverSocket: ServerSocket? = null
    private var isRunning = false
    private val scope = CoroutineScope(Dispatchers.IO + Job())

    fun start() {
        if (isRunning) return
        isRunning = true
        scope.launch {
            try {
                serverSocket = ServerSocket(port)
                Log.d(TAG, "Server started on port $port")
                while (isRunning) {
                    val socket = serverSocket?.accept() ?: break
                    launch {
                        handleClient(socket)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server exception", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try {
            serverSocket?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        serverSocket = null
        scope.cancel()
        Log.d(TAG, "Server stopped")
    }

    private suspend fun handleClient(socket: Socket) {
        val remoteAddress = socket.inetAddress.hostAddress
        try {
            val input = BufferedReader(InputStreamReader(socket.getInputStream()))
            val output = BufferedOutputStream(socket.getOutputStream())

            val headerLine = input.readLine() ?: return
            Log.i(TAG, "Incoming request from $remoteAddress: $headerLine")

            val tokenizer = StringTokenizer(headerLine)
            if (!tokenizer.hasMoreTokens()) return
            val method = tokenizer.nextToken()
            if (!tokenizer.hasMoreTokens()) return
            var uri = tokenizer.nextToken()

            // Decode URI
            uri = URLDecoder.decode(uri, "UTF-8")

            // Read headers
            var contentLength = 0
            var contentType = ""
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.lowercase(Locale.US).startsWith("content-length:")) {
                    contentLength = line!!.substring(15).trim().toInt()
                }
                if (line!!.lowercase(Locale.US).startsWith("content-type:")) {
                    contentType = line!!.substring(13).trim()
                }
            }

            if (method == "GET" && uri == "/") {
                serveDashboard(output)
            } else if (method == "GET" && uri == "/config") {
                serveConfigJson(output)
            } else if (method == "POST" && uri.startsWith("/toggle-group")) {
                handleToggleGroup(uri, output)
            } else if (method == "POST" && uri.startsWith("/toggle-alarm")) {
                handleToggleAlarm(uri, output)
            } else if (method == "POST" && uri == "/upload") {
                handleRingtoneUpload(socket.getInputStream(), contentLength, contentType, output)
            } else if (method == "GET" && uri.startsWith("/backup")) {
                handleDownloadBackup(output, uri)
            } else if (method == "POST" && uri == "/restore") {
                handleRestoreBackup(socket.getInputStream(), contentLength, contentType, output)
            } else {
                serve404(output)
            }

            output.flush()
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    private fun serveDashboard(output: BufferedOutputStream) {
        val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
        val savedPath = prefs.getString("recording_path", "")
        val recordingPath = if (!savedPath.isNullOrEmpty()) {
            savedPath
        } else {
            android.os.Environment.getExternalStorageDirectory().absolutePath + "/0"
        }
        val ringtonesDir = File(recordingPath)

        if (!ringtonesDir.exists()) ringtonesDir.mkdirs()
        val customRingtones = ringtonesDir.listFiles()?.map { it.name } ?: emptyList()

        // We fetch Alarms and Groups on IO thread synchronously
        val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
        val groups = runBlocking { db.alarmDao().getAllGroups() }
        val alarms = runBlocking {
            val list = mutableListOf<Alarm>()
            groups.forEach { grp ->
                list.addAll(db.alarmDao().getAlarmsByGroup(grp.id))
            }
            list
        }

        val htmlBuilder = StringBuilder()
        htmlBuilder.append("""
            <!DOCTYPE html>
            <html lang="zh-CN">
            <head>
                <meta charset="UTF-8">
                <meta name="viewport" content="width=device-width, initial-scale=1.0">
                <title>分组闹钟 - WiFi 同步中心</title>
                <style>
                    body {
                        font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, "Helvetica Neue", Arial, sans-serif;
                        background: #121318;
                        color: #E3E2E6;
                        margin: 0;
                        padding: 0;
                        display: flex;
                        justify-content: center;
                    }
                    .container {
                        max-width: 800px;
                        width: 100%;
                        padding: 24px;
                    }
                    header {
                        padding-bottom: 24px;
                        border-bottom: 1px solid #44474F;
                        margin-bottom: 24px;
                        text-align: center;
                    }
                    h1 {
                        color: #ADC6FF;
                        margin: 0 0 8px 0;
                        font-size: 28px;
                    }
                    p {
                        color: #C4C6D0;
                        margin: 0;
                    }
                    .section {
                        background: #1F2027;
                        border-radius: 16px;
                        padding: 20px;
                        margin-bottom: 24px;
                        box-shadow: 0 4px 12px rgba(0,0,0,0.2);
                    }
                    h2 {
                        color: #ADC6FF;
                        margin-top: 0;
                        font-size: 20px;
                        border-bottom: 1px solid #44474F;
                        padding-bottom: 10px;
                    }
                    .group-item {
                        background: #2A2B31;
                        border-radius: 12px;
                        padding: 15px;
                        margin-bottom: 15px;
                    }
                    .group-header {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        font-weight: bold;
                        font-size: 18px;
                        color: #BAC6EA;
                    }
                    .switch-btn {
                        background: #3B4764;
                        color: #DDE2F9;
                        border: none;
                        padding: 8px 16px;
                        border-radius: 20px;
                        cursor: pointer;
                        font-weight: 600;
                        transition: all 0.2s;
                    }
                    .switch-btn.active {
                        background: #ADC6FF;
                        color: #002E69;
                    }
                    .alarm-list {
                        margin-top: 10px;
                        padding-left: 15px;
                        border-left: 2px solid #3B4764;
                    }
                    .alarm-item {
                        display: flex;
                        justify-content: space-between;
                        align-items: center;
                        padding: 8px 0;
                        border-bottom: 1px dashed #44474F;
                    }
                    .alarm-item:last-child {
                        border-bottom: none;
                    }
                    .alarm-time {
                        font-size: 18px;
                        font-weight: bold;
                        color: #E3E2E6;
                    }
                    .alarm-label {
                        color: #909090;
                        font-size: 14px;
                        margin-left: 10px;
                    }
                    .ringtone-list {
                        margin-top: 10px;
                    }
                    .ringtone-item {
                        background: #2A2B31;
                        padding: 10px 15px;
                        border-radius: 8px;
                        margin-bottom: 8px;
                        font-size: 14px;
                        color: #C4C6D0;
                    }
                    .upload-form {
                        display: flex;
                        flex-direction: column;
                        gap: 12px;
                    }
                    input[type="file"] {
                        background: #2A2B31;
                        padding: 12px;
                        border-radius: 8px;
                        color: #E3E2E6;
                        border: 1px dashed #44474F;
                        cursor: pointer;
                    }
                    .submit-btn {
                        background: #ADC6FF;
                        color: #002E69;
                        border: none;
                        padding: 12px;
                        border-radius: 8px;
                        font-weight: bold;
                        cursor: pointer;
                        font-size: 16px;
                        transition: opacity 0.2s;
                    }
                    .submit-btn:hover {
                        opacity: 0.9;
                    }
                    .backup-section {
                        display: flex;
                        gap: 12px;
                        margin-top: 20px;
                    }
                    .backup-btn {
                        flex: 1;
                        background: #3B4764;
                        color: #DDE2F9;
                        border: none;
                        padding: 12px;
                        border-radius: 8px;
                        font-weight: bold;
                        cursor: pointer;
                        text-align: center;
                        text-decoration: none;
                    }
                </style>
                <script>
                    function toggleGroup(id, enabled) {
                        fetch('/toggle-group?id=' + id + '&enabled=' + !enabled, { method: 'POST' })
                        .then(() => location.reload());
                    }
                    function toggleAlarm(id, enabled) {
                        fetch('/toggle-alarm?id=' + id + '&enabled=' + !enabled, { method: 'POST' })
                        .then(() => location.reload());
                    }
                </script>
            </head>
            <body>
                <div class="container">
                    <header>
                        <h1>⏰ 分组闹钟 WiFi 同步控制台</h1>
                        <p>在同一局域网下，通过浏览器无线更新配置并上传您的专属铃声</p>
                    </header>
                    
                    <div class="section">
                        <h2>👥 闹钟分组与配置</h2>
        """.trimIndent())

        if (groups.isEmpty()) {
            htmlBuilder.append("<p>没有创建中的闹钟分组</p>")
        } else {
            groups.forEach { group ->
                val isActiveClass = if (group.isEnabled) "active" else ""
                val activeText = if (group.isEnabled) "已启用" else "已禁用"
                htmlBuilder.append("""
                    <div class="group-item">
                        <div class="group-header">
                            <span>${group.name}</span>
                            <button class="switch-btn $isActiveClass" onclick="toggleGroup(${group.id}, ${group.isEnabled})">
                                $activeText
                            </button>
                        </div>
                        <div class="alarm-list">
                """.trimIndent())

                val groupAlarms = alarms.filter { it.groupId == group.id }
                if (groupAlarms.isEmpty()) {
                    htmlBuilder.append("<div style='color: #909090; padding: 5px 0;'>空分组</div>")
                } else {
                    groupAlarms.forEach { alarm ->
                        val alarmActiveText = if (alarm.isEnabled) "开启" else "关闭"
                        val alarmActiveBtnClass = if (alarm.isEnabled) "active" else ""
                        val timeStr = String.format("%02d:%02d", alarm.hour, alarm.minute)
                        val ringtoneName = alarm.ringtonePath?.substringAfterLast("/") ?: "系统默认"
                        htmlBuilder.append("""
                            <div class="alarm-item">
                                <div>
                                    <span class="alarm-time">$timeStr</span>
                                    <span class="alarm-label">${alarm.label} (${alarm.getActiveDaysDesc()}) - [🎵 $ringtoneName]</span>
                                </div>
                                <button class="switch-btn $alarmActiveBtnClass" style="padding: 4px 10px; font-size: 12px;" onclick="toggleAlarm(${alarm.id}, ${alarm.isEnabled})">
                                    $alarmActiveText
                                </button>
                            </div>
                        """.trimIndent())
                    }
                }

                htmlBuilder.append("""
                        </div>
                    </div>
                """.trimIndent())
            }
        }

        htmlBuilder.append("""
                    </div>
                    
                    <div class="section">
                        <h2>🎵 上传自定义铃声</h2>
                        <form class="upload-form" action="/upload" method="POST" enctype="multipart/form-data">
                            <input type="file" name="ringtone" accept=".mp3,.wav" required>
                            <input type="submit" class="submit-btn" value="📤 上传并保存铃声到手机">
                        </form>
                        <div style="margin-top: 15px;">
                            <p style="font-weight: bold; margin-bottom: 8px;">已就绪的自定义铃声：</p>
                            <div class="ringtone-list">
        """.trimIndent())

        if (customRingtones.isEmpty()) {
            htmlBuilder.append("<p style='color: #909090; font-size: 14px;'>暂无已上传的自定义铃声，请在上方上传 MP3 或 WAV 格式铃声</p>")
        } else {
            customRingtones.forEach { name ->
                htmlBuilder.append("""
                    <div class="ringtone-item">🎵 $name</div>
                """.trimIndent())
            }
        }

        htmlBuilder.append("""
                            </div>
                        </div>
                    </div>

                    <div class="section">
                        <h2>💾 备份与还原 (含数据库与铃声)</h2>
                        <p style="font-size: 13px; margin-bottom: 12px; color: #909090;">您可以将所有闹钟配置和自定义铃声打包导出，或通过备份包还原。</p>
                        <div class="backup-section">
                            <a href="/backup" class="backup-btn">📥 导出备份 (ZIP)</a>
                        </div>
                        <form class="upload-form" style="margin-top: 15px;" action="/restore" method="POST" enctype="multipart/form-data">
                            <label style="font-size: 14px; font-weight: bold;">上传备份文件还原：</label>
                            <input type="file" name="backup" accept=".zip" required>
                            <input type="submit" class="submit-btn" style="background: #FA5A5A; color: white;" value="⚠️ 上传并还原全部配置" onclick="return confirm('确定要还原吗？这会覆盖当前所有闹钟数据！')">
                        </form>
                    </div>
                </div>
            </body>
            </html>
        """.trimIndent())

        sendResponse(output, "text/html; charset=UTF-8", htmlBuilder.toString().toByteArray())
    }

    private fun serveConfigJson(output: BufferedOutputStream) {
        // Simple manual JSON serialization
        val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
        val groups = runBlocking { db.alarmDao().getAllGroups() }
        val sb = StringBuilder()
        sb.append("[")
        groups.forEachIndexed { index, group ->
            sb.append("""{"id":${group.id},"name":"${group.name}","enabled":${group.isEnabled}}""")
            if (index < groups.size - 1) sb.append(",")
        }
        sb.append("]")
        sendResponse(output, "application/json", sb.toString().toByteArray())
    }

    private fun handleToggleGroup(uri: String, output: BufferedOutputStream) {
        val params = getParams(uri)
        val id = params["id"]?.toLongOrNull()
        val enabled = params["enabled"]?.toBoolean()
        if (id != null && enabled != null) {
            val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
            runBlocking {
                val group = db.alarmDao().getAllGroups().find { it.id == id }
                if (group != null) {
                    db.alarmDao().updateGroup(group.copy(isEnabled = enabled))
                    // Re-schedule alarms
                    val groupAlarms = db.alarmDao().getAlarmsByGroup(group.id)
                    groupAlarms.forEach { alarm ->
                        AlarmScheduler.scheduleAlarm(context, alarm, group.copy(isEnabled = enabled))
                    }
                    onConfigChanged()
                }
            }
        }
        sendRedirect(output, "/")
    }

    private fun handleToggleAlarm(uri: String, output: BufferedOutputStream) {
        val params = getParams(uri)
        val id = params["id"]?.toLongOrNull()
        val enabled = params["enabled"]?.toBoolean()
        if (id != null && enabled != null) {
            val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
            runBlocking {
                val alarm = db.alarmDao().getAlarmById(id)
                if (alarm != null) {
                    val group = db.alarmDao().getAllGroups().find { it.id == alarm.groupId }
                    db.alarmDao().updateAlarm(alarm.copy(isEnabled = enabled))
                    // Re-schedule
                    AlarmScheduler.scheduleAlarm(context, alarm.copy(isEnabled = enabled), group)
                    onConfigChanged()
                }
            }
        }
        sendRedirect(output, "/")
    }

    private fun handleRingtoneUpload(
        inputStream: InputStream,
        contentLength: Int,
        contentType: String,
        output: BufferedOutputStream
    ) {
        if (contentLength <= 0 || !contentType.contains("multipart/form-data")) {
            sendResponse(output, "text/plain", "Invalid upload request".toByteArray())
            return
        }

        try {
            // Find boundary
            val boundaryPart = contentType.substringAfter("boundary=")
            val boundary = "--$boundaryPart"

            val stream = DataInputStream(inputStream)
            val tempBuffer = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val readLength = stream.read(tempBuffer, totalRead, contentLength - totalRead)
                if (readLength == -1) break
                totalRead += readLength
            }

            // Parse filename from multipart body
            val searchStr = "Content-Disposition: form-data;"
            val byteBoundary = boundary.toByteArray()
            val byteBuffer = tempBuffer.sliceArray(0 until totalRead)

            var filename = "ringtone_${System.currentTimeMillis()}.mp3"
            var fileDataStart = -1
            var fileDataEnd = -1

            // Simple parser to find Content-Disposition and filename
            val contentStr = String(byteBuffer, 0, Math.min(byteBuffer.size, 4000))
            if (contentStr.contains("filename=\"")) {
                filename = contentStr.substringAfter("filename=\"").substringBefore("\"")
            }

            // Find file binary boundary starting index
            // Typically file starts after double CRLF ending headers
            val headerEndMarker = "\r\n\r\n".toByteArray()
            var headersEndIndex = -1
            for (i in 0 until byteBuffer.size - 4) {
                if (byteBuffer[i] == headerEndMarker[0] &&
                    byteBuffer[i+1] == headerEndMarker[1] &&
                    byteBuffer[i+2] == headerEndMarker[2] &&
                    byteBuffer[i+3] == headerEndMarker[3]) {
                    // Let's check if this is the SECOND or FIRST, actually it is the header of the file part.
                    // We can match after Content-Type
                    val subStr = String(byteBuffer, 0, i)
                    if (subStr.contains("Content-Type:")) {
                        headersEndIndex = i + 4
                        break
                    }
                }
            }

            // Find boundary end starting index (which is boundary byte array at the end of the file data)
            if (headersEndIndex != -1) {
                fileDataStart = headersEndIndex
                for (j in fileDataStart until byteBuffer.size - byteBoundary.size) {
                    var match = true
                    for (k in byteBoundary.indices) {
                        if (byteBuffer[j + k] != byteBoundary[k]) {
                            match = false
                            break
                        }
                    }
                    if (match) {
                        fileDataEnd = j - 2 // Skip CRLF before boundary
                        break
                    }
                }
            }

            if (fileDataStart != -1 && fileDataEnd > fileDataStart) {
                val fileBytes = byteBuffer.sliceArray(fileDataStart until fileDataEnd)
                
                val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val savedUploadPath = prefs.getString("recording_path", "")
                val recordingPath = if (!savedUploadPath.isNullOrEmpty()) {
                    savedUploadPath
                } else {
                    android.os.Environment.getExternalStorageDirectory().absolutePath + "/0"
                }
                val ringtonesDir = File(recordingPath)

                if (!ringtonesDir.exists()) ringtonesDir.mkdirs()

                val targetFile = File(ringtonesDir, filename)
                val fos = FileOutputStream(targetFile)
                fos.write(fileBytes)
                fos.flush()
                fos.close()
                Log.d(TAG, "Uploaded custom ringtone: ${targetFile.absolutePath}")
                onConfigChanged()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Ringtone extraction failed", e)
        }

        // Return dashboard immediately
        sendRedirect(output, "/")
    }

    private fun handleDownloadBackup(output: BufferedOutputStream, uri: String = "") {
        try {
            val params = getParams(uri)
            val isChimesOnly = params["type"] == "chimes_only"
            
            Log.d(TAG, "Generating backup ZIP (isChimesOnly=$isChimesOnly)...")
            val backupFile = File(context.cacheDir, "alarm_backup.zip")
            
            val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
            val groups = runBlocking { db.alarmDao().getAllGroups() }
            val alarms = runBlocking { db.alarmDao().getAllAlarms() }
            val chimes = runBlocking { db.alarmDao().getAllHourlyChimes() }

            java.util.zip.ZipOutputStream(java.io.FileOutputStream(backupFile), Charsets.UTF_8).use { zos ->
                if (!isChimesOnly) {
                    val rootJson = org.json.JSONObject()
                    
                    val groupsJson = org.json.JSONArray()
                    groups.forEach { g ->
                        groupsJson.put(org.json.JSONObject().apply {
                            put("id", g.id)
                            put("name", g.name)
                            put("isEnabled", g.isEnabled)
                        })
                    }
                    rootJson.put("groups", groupsJson)

                    val alarmsJson = org.json.JSONArray()
                    alarms.forEach { a ->
                        alarmsJson.put(org.json.JSONObject().apply {
                            put("id", a.id)
                            put("groupId", a.groupId)
                            put("hour", a.hour)
                            put("minute", a.minute)
                            put("daysOfWeek", a.daysOfWeek)
                            put("isEnabled", a.isEnabled)
                            put("label", a.label)
                            put("ringtonePath", a.ringtonePath)
                            put("vibrate", a.vibrate)
                        })
                    }
                    rootJson.put("alarms", alarmsJson)

                    val chimesJson = org.json.JSONArray()
                    chimes.forEach { c ->
                        chimesJson.put(org.json.JSONObject().apply {
                            put("hour", c.hour)
                            put("isEnabled", c.isEnabled)
                            put("useTts", c.useTts)
                            put("vibrate", c.vibrate)
                        })
                    }
                    rootJson.put("chimes", chimesJson)

                    zos.putNextEntry(java.util.zip.ZipEntry("config.json"))
                    zos.write(rootJson.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 遍历所有闹钟，将每个闹钟引用的铃声文件打进包（不论文件在哪个目录）
                    val addedFiles = mutableSetOf<String>()
                    alarms.forEach { alarm ->
                        val ringtonePath = alarm.ringtonePath
                        if (!ringtonePath.isNullOrEmpty()) {
                            val file = File(ringtonePath)
                            if (file.isFile && file.exists()) {
                                val canonicalPath = file.canonicalPath
                                if (canonicalPath !in addedFiles) {
                                    addedFiles.add(canonicalPath)
                                    val zipName = "ringtones/${file.name}"
                                    val entry = java.util.zip.ZipEntry(zipName)
                                    zos.putNextEntry(entry)
                                    
                                    java.io.FileInputStream(file).use { fis ->
                                        val buffer = ByteArray(16384)
                                        var bytesRead: Int
                                        while (fis.read(buffer).also { bytesRead = it } != -1) {
                                            zos.write(buffer, 0, bytesRead)
                                        }
                                    }
                                    zos.closeEntry()
                                }
                            }
                        }
                    }
                }

                // 总是打包报时缓存（如果存在）
                val chimeDir = File(context.filesDir, "chime_cache")
                if (chimeDir.exists()) {
                    Log.d(TAG, "Packing ${chimeDir.listFiles()?.size ?: 0} chime cache files...")
                    chimeDir.listFiles()?.forEach { file ->
                        if (file.isFile && file.extension == "wav") {
                            val zipName = "chimes/${file.name}"
                            zos.putNextEntry(java.util.zip.ZipEntry(zipName))
                            java.io.FileInputStream(file).use { fis ->
                                val buffer = ByteArray(16384)
                                var bytesRead: Int
                                while (fis.read(buffer).also { bytesRead = it } != -1) {
                                    zos.write(buffer, 0, bytesRead)
                                }
                            }
                            zos.closeEntry()
                        }
                    }
                }

                zos.finish()
            }

            // 用字节流直接写 HTTP 响应头，避免 PrintWriter 字符流与二进制数据混用
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Content-Disposition: attachment; filename=\"alarm_backup_${System.currentTimeMillis()}.zip\"\r\n" +
                "Content-Length: ${backupFile.length()}\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            output.write(header.toByteArray(charset("ISO-8859-1")))
            output.flush()
            java.io.FileInputStream(backupFile).use { it.copyTo(output) }
            output.flush()
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
            // 发送错误响应
            try {
                val errBody = "{\"error\":\"Backup failed\"}".toByteArray()
                val header = "HTTP/1.1 500 Internal Server Error\r\n" +
                    "Content-Type: application/json\r\n" +
                    "Content-Length: ${errBody.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                output.write(header.toByteArray(charset("ISO-8859-1")))
                output.write(errBody)
                output.flush()
            } catch (_: Exception) {}
        }
    }

    private fun handleRestoreBackup(
        inputStream: InputStream,
        contentLength: Int,
        contentType: String,
        output: BufferedOutputStream
    ) {
        try {
            // Find boundary
            val boundaryPart = contentType.substringAfter("boundary=")
            val boundary = "--$boundaryPart"

            val stream = DataInputStream(inputStream)
            val tempBuffer = ByteArray(contentLength)
            var totalRead = 0
            while (totalRead < contentLength) {
                val readLength = stream.read(tempBuffer, totalRead, contentLength - totalRead)
                if (readLength == -1) break
                totalRead += readLength
            }

            val byteBoundary = boundary.toByteArray()
            val byteBuffer = tempBuffer.sliceArray(0 until totalRead)

            var headersEndIndex = -1
            val headerEndMarker = "\r\n\r\n".toByteArray()
            for (i in 0 until byteBuffer.size - 4) {
                if (byteBuffer[i] == headerEndMarker[0] && byteBuffer[i+1] == headerEndMarker[1] &&
                    byteBuffer[i+2] == headerEndMarker[2] && byteBuffer[i+3] == headerEndMarker[3]) {
                    val subStr = String(byteBuffer, 0, i)
                    if (subStr.contains("Content-Type:")) {
                        headersEndIndex = i + 4
                        break
                    }
                }
            }

            var fileDataStart = headersEndIndex
            var fileDataEnd = -1
            if (fileDataStart != -1) {
                for (j in fileDataStart until byteBuffer.size - byteBoundary.size) {
                    var match = true
                    for (k in byteBoundary.indices) {
                        if (byteBuffer[j + k] != byteBoundary[k]) { match = false; break }
                    }
                    if (match) { fileDataEnd = j - 2; break }
                }
            }

            if (fileDataStart != -1 && fileDataEnd > fileDataStart) {
                val zipBytes = byteBuffer.sliceArray(fileDataStart until fileDataEnd)
                val tempZip = File(context.cacheDir, "restore.zip")
                tempZip.writeBytes(zipBytes)

                // Now use a simplified version of the restore logic or call into VM if possible.
                // Since we are in WifiSyncServer, we'll do the DB work here.
                val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))
                
                // 获取配置路径
                val prefs = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
                val savedRestorePath = prefs.getString("recording_path", "")
                val recordingPath = if (!savedRestorePath.isNullOrEmpty()) {
                    savedRestorePath
                } else {
                    android.os.Environment.getExternalStorageDirectory().absolutePath + "/0"
                }
                val ringtonesDir = File(recordingPath)
                if (!ringtonesDir.exists()) ringtonesDir.mkdirs()

                var configJson: String? = null
                java.util.zip.ZipInputStream(java.io.FileInputStream(tempZip)).use { zis ->
                    var entry: java.util.zip.ZipEntry? = zis.nextEntry
                    while (entry != null) {
                        if (entry.name == "config.json") {
                            configJson = zis.bufferedReader().readText()
                        } else if (entry.name.startsWith("ringtones/")) {
                            val fileName = entry.name.substringAfter("ringtones/")
                            if (fileName.isNotEmpty()) {
                                File(ringtonesDir, fileName).outputStream().use { zis.copyTo(it) }
                            }
                        }
                        zis.closeEntry()
                        entry = zis.nextEntry
                    }
                }

                configJson?.let { jsonStr ->
                    val root = org.json.JSONObject(jsonStr)
                    runBlocking {
                        db.alarmDao().getAllGroups().forEach { db.alarmDao().deleteGroup(it) }
                        val idMap = mutableMapOf<Long, Long>()
                        val groupsArr = root.getJSONArray("groups")
                        for (i in 0 until groupsArr.length()) {
                            val g = groupsArr.getJSONObject(i)
                            val newId = db.alarmDao().insertGroup(AlarmGroup(name = g.getString("name"), isEnabled = g.getBoolean("isEnabled")))
                            idMap[g.getLong("id")] = newId
                        }
                        val alarmsArr = root.getJSONArray("alarms")
                        for (i in 0 until alarmsArr.length()) {
                            val a = alarmsArr.getJSONObject(i)
                            val newGroupId = idMap[a.getLong("groupId")] ?: continue
                            var rPath = if (a.isNull("ringtonePath")) null else a.getString("ringtonePath")
                            if (rPath != null && (rPath.contains("/files/custom_ringtones/") || rPath.contains("/0/"))) {
                                rPath = File(ringtonesDir, rPath.substringAfterLast("/")).absolutePath
                            }
                            db.alarmDao().insertAlarm(Alarm(
                                groupId = newGroupId, hour = a.getInt("hour"), minute = a.getInt("minute"),
                                daysOfWeek = a.getString("daysOfWeek"), isEnabled = a.getBoolean("isEnabled"),
                                label = a.getString("label"), ringtonePath = rPath, vibrate = a.getBoolean("vibrate")
                            ))
                        }
                        val chimesArr = root.getJSONArray("chimes")
                        for (i in 0 until chimesArr.length()) {
                            val c = chimesArr.getJSONObject(i)
                            db.alarmDao().updateHourlyChime(HourlyChime(hour = c.getInt("hour"), isEnabled = c.getBoolean("isEnabled"), useTts = c.getBoolean("useTts"), vibrate = c.getBoolean("vibrate")))
                        }
                        db.alarmDao().getAllAlarms().forEach { alarm ->
                            val group = db.alarmDao().getAllGroups().find { it.id == alarm.groupId }
                            AlarmScheduler.scheduleAlarm(context, alarm, group)
                        }
                        AlarmScheduler.scheduleNextHourlyChime(context)
                    }
                    onConfigChanged()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Restore failed", e)
        }
        sendRedirect(output, "/")
    }

    private fun getParams(url: String): Map<String, String> {
        val params = HashMap<String, String>()
        val queryStart = url.indexOf('?')
        if (queryStart >= 0 && queryStart < url.length - 1) {
            val query = url.substring(queryStart + 1)
            query.split('&').forEach { param ->
                val pair = param.split('=')
                if (pair.size == 2) {
                    params[pair[0]] = pair[1]
                }
            }
        }
        return params
    }

    private fun sendResponse(output: BufferedOutputStream, contentType: String, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray(charset("ISO-8859-1")))
        output.flush()
        output.write(body)
        output.flush()
    }

    private fun sendRedirect(output: BufferedOutputStream, path: String) {
        val header = "HTTP/1.1 303 See Other\r\n" +
            "Location: $path\r\n" +
            "Content-Length: 0\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray(charset("ISO-8859-1")))
        output.flush()
    }

    private fun serve404(output: BufferedOutputStream) {
        val body = "404 Not Found".toByteArray()
        val header = "HTTP/1.1 404 Not Found\r\n" +
            "Content-Type: text/plain\r\n" +
            "Content-Length: ${body.size}\r\n" +
            "Connection: close\r\n" +
            "\r\n"
        output.write(header.toByteArray(charset("ISO-8859-1")))
        output.flush()
        output.write(body)
        output.flush()
    }
}
