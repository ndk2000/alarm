package com.ccsoft.alarm.alarm

import android.content.Context
import android.util.Log
import com.ccsoft.alarm.db.Alarm
import com.ccsoft.alarm.db.AlarmDatabase
import com.ccsoft.alarm.db.AlarmGroup
import com.ccsoft.alarm.db.HourlyChime
import com.ccsoft.alarm.db.CheckInGroupEntity
import com.ccsoft.alarm.db.CheckInTaskEntity
import kotlinx.coroutines.*
import java.io.*
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import org.json.JSONObject
import org.json.JSONArray
import java.util.*
import java.util.zip.ZipOutputStream
import java.util.zip.ZipEntry

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
                    launch { handleClient(socket) }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Server exception", e)
            }
        }
    }

    fun stop() {
        isRunning = false
        try { serverSocket?.close() } catch (e: Exception) { e.printStackTrace() }
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
            Log.i(TAG, "Request: $headerLine from $remoteAddress")

            val tokenizer = StringTokenizer(headerLine)
            if (!tokenizer.hasMoreTokens()) return
            val method = tokenizer.nextToken()
            if (!tokenizer.hasMoreTokens()) return
            val uri = URLDecoder.decode(tokenizer.nextToken(), "UTF-8")

            var contentLength = 0
            var contentType = ""
            var line: String?
            while (input.readLine().also { line = it } != null) {
                if (line!!.isEmpty()) break
                if (line!!.lowercase().startsWith("content-length:")) contentLength = line!!.substring(15).trim().toInt()
                if (line!!.lowercase().startsWith("content-type:")) contentType = line!!.substring(13).trim()
            }

            when {
                method == "GET" && uri == "/" -> serveDashboard(output)
                method == "GET" && uri.startsWith("/backup") -> handleDownloadBackup(output, uri)
                method == "POST" && uri == "/restore" -> handleRestoreBackup(socket.getInputStream(), contentLength, contentType, output)
                else -> serve404(output)
            }
            output.flush()
            socket.close()
        } catch (e: Exception) {
            Log.e(TAG, "Error handling client", e)
        }
    }

    private fun serveDashboard(output: BufferedOutputStream) {
        val html = """<html><body><h1>打卡闹钟 Sync Server</h1><p>Running on Android</p></body></html>"""
        sendResponse(output, "text/html", html.toByteArray())
    }

    private fun handleDownloadBackup(output: BufferedOutputStream, uri: String) {
        Log.d(TAG, "Generating backup ZIP...")
        try {
            val isChimesOnly = uri.contains("type=chimes_only")
            val backupFile = File(context.cacheDir, "alarm_backup.zip")
            val db = AlarmDatabase.getDatabase(context, CoroutineScope(Dispatchers.IO))

            ZipOutputStream(FileOutputStream(backupFile)).use { zos ->
                if (!isChimesOnly) {
                    val root = JSONObject()
                    val groups = runBlocking { db.alarmDao().getAllGroups() }
                    val alarms = runBlocking { db.alarmDao().getAllAlarms() }
                    val chimes = runBlocking { db.alarmDao().getAllHourlyChimes() }
                    val checkinGroups = runBlocking { db.checkinDao().getAllGroups() }

                    // 序列化组
                    val groupsArr = JSONArray()
                    groups.forEach { g -> groupsArr.put(JSONObject().apply { put("id", g.id); put("name", g.name); put("isEnabled", g.isEnabled) }) }
                    root.put("groups", groupsArr)

                    // 序列化闹钟
                    val alarmsArr = JSONArray()
                    alarms.forEach { a ->
                        alarmsArr.put(JSONObject().apply {
                            put("id", a.id); put("groupId", a.groupId); put("hour", a.hour); put("minute", a.minute)
                            put("daysOfWeek", a.daysOfWeek); put("isEnabled", a.isEnabled); put("label", a.label)
                            put("ringtonePath", a.ringtonePath); put("vibrate", a.vibrate)
                        })
                    }
                    root.put("alarms", alarmsArr)

                    // 序列化报时
                    val chimesArr = JSONArray()
                    chimes.forEach { c ->
                        chimesArr.put(JSONObject().apply { put("hour", c.hour); put("isEnabled", c.isEnabled); put("useTts", c.useTts); put("vibrate", c.vibrate) })
                    }
                    root.put("chimes", chimesArr)

                    // 序列化打卡组
                    val checkinGroupsArr = JSONArray()
                    checkinGroups.forEach { g ->
                        checkinGroupsArr.put(JSONObject().apply {
                            put("id", g.id); put("name", g.name); put("isEnabled", g.isEnabled)
                            put("ringtonePath", g.ringtonePath ?: JSONObject.NULL); put("boundAlarmGroupId", g.boundAlarmGroupId)
                            put("createdAt", g.createdAt)
                        })
                    }
                    root.put("checkinGroups", checkinGroupsArr)

                    // 序列化打卡事项
                    val checkinTasksObj = JSONObject()
                    checkinGroups.forEach { g ->
                        val tasks = runBlocking { db.checkinDao().getTasksByGroup(g.id) }
                        val tasksArr = JSONArray()
                        tasks.forEach { t ->
                            tasksArr.put(JSONObject().apply {
                                put("id", t.id); put("name", t.name); put("hour", t.hour); put("minute", t.minute)
                                put("orderIndex", t.orderIndex); put("ringtonePath", t.ringtonePath ?: JSONObject.NULL); put("useTts", t.useTts)
                            })
                        }
                        checkinTasksObj.put(g.id.toString(), tasksArr)
                    }
                    root.put("checkinTasks", checkinTasksObj)

                    zos.putNextEntry(ZipEntry("config.json"))
                    zos.write(root.toString().toByteArray(Charsets.UTF_8))
                    zos.closeEntry()

                    // 打包引用的语音文件
                    val added = mutableSetOf<String>()
                    val allPaths = mutableSetOf<String>()
                    alarms.forEach { it.ringtonePath?.let { p -> allPaths.add(pathCleanup(p)) } }
                    checkinGroups.forEach { it.ringtonePath?.let { p -> allPaths.add(pathCleanup(p)) } }
                    checkinGroups.forEach { g ->
                        runBlocking { db.checkinDao().getTasksByGroup(g.id) }.forEach { t ->
                            t.ringtonePath?.let { p -> allPaths.add(pathCleanup(p)) }
                        }
                    }

                    allPaths.filter { it.isNotEmpty() }.forEach { path ->
                        val f = File(path)
                        if (f.exists() && f.isFile && added.add(f.canonicalPath)) {
                            zos.putNextEntry(ZipEntry("ringtones/${f.name}"))
                            f.inputStream().use { it.copyTo(zos) }
                            zos.closeEntry()
                        }
                    }
                }

                // 报时缓存
                val chimeDir = File(context.filesDir, "chime_cache")
                if (chimeDir.exists()) {
                    chimeDir.listFiles()?.filter { it.extension == "wav" }?.forEach { f ->
                        zos.putNextEntry(ZipEntry("chimes/${f.name}"))
                        f.inputStream().use { it.copyTo(zos) }
                        zos.closeEntry()
                    }
                }
            }

            val header = "HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Length: ${backupFile.length()}\r\nConnection: close\r\n\r\n"
            output.write(header.toByteArray(Charsets.ISO_8859_1))
            backupFile.inputStream().use { it.copyTo(output) }
        } catch (e: Exception) {
            Log.e(TAG, "Backup failed", e)
        }
    }

    private fun pathCleanup(p: String): String = p.trim()

    private fun handleRestoreBackup(inputStream: InputStream, length: Int, type: String, output: BufferedOutputStream) {
        // 极简实现：WifiSyncClient 是主动拉取模式，WifiSyncServer 主要负责提供 /backup。
        // 原有的 /restore 逻辑在网页端使用，如果用户主要用手机对手机同步，这部分非核心。
        // 这里发送 501 Not Implemented 避免崩溃，如需网页恢复功能可后续补全。
        sendResponse(output, "text/plain", "Restore via web not implemented in this version. Use App Pull Sync.".toByteArray())
    }

    private fun sendResponse(output: BufferedOutputStream, type: String, body: ByteArray) {
        val header = "HTTP/1.1 200 OK\r\nContent-Type: $type\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
    }

    private fun serve404(output: BufferedOutputStream) {
        val body = "404 Not Found".toByteArray()
        val header = "HTTP/1.1 404 Not Found\r\nContent-Type: text/plain\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n"
        output.write(header.toByteArray(Charsets.ISO_8859_1))
        output.write(body)
    }
}
