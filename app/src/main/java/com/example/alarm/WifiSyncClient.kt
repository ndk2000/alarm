package com.example.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL

/**
 * WiFi 同步客户端
 * 主动连接另一台手机上的 WifiSyncServer，拉取完整的闹钟配置和铃声备份
 */
class WifiSyncClient(private val context: Context) {
    enum class ImportMode { CLEAR, MERGE, ONLY_CHIMES, SELECTIVE }

    companion object {
        private const val TAG = "WifiSyncClient"
        private const val DEFAULT_PORT = 8080
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 60000
    }

    /** 检查是否有外部存储写入权限（适配 API 29+ 和 30+） */
    private fun hasStoragePermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: 使用 MANAGE_EXTERNAL_STORAGE
            Environment.isExternalStorageManager()
        } else {
            // Android 10 及以下: 使用 WRITE_EXTERNAL_STORAGE 权限
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
    }

    sealed class SyncResult {
        data class Success(val message: String) : SyncResult()
        data class Error(val message: String) : SyncResult()
    }

    /**
     * 从远程地址同步数据
     * @param ipAddress 目标手机 IP 地址
     * @param port 端口号，默认 8080
     * @param importMode 导入模式：CLEAR 清空后导入，MERGE 合并导入
     * @return SyncResult 表示同步结果
     */
    /**
     * 从远程同步数据，支持选择性同步
     * @param selectedGroupNames 选择同步的闹钟组与打卡组名称集合（仅 SELECTIVE 模式使用）
     */
    suspend fun syncFromRemote(ipAddress: String, port: Int = DEFAULT_PORT, importMode: ImportMode = ImportMode.CLEAR, selectedGroupNames: Set<String>? = null): SyncResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, ">>> [SYNC START] Target: $ipAddress:$port")

                // Step 1: Ping
                Log.d(TAG, "Step 1: Pinging server...")
                val pingResult = pingServer(ipAddress, port)
                if (pingResult !is PingResult.Ok) {
                    val msg = when (pingResult) {
                        is PingResult.Error -> pingResult.message
                        else -> "无法连接到 $ipAddress:$port"
                    }
                    Log.e(TAG, "Ping failed: $msg")
                    return@withContext SyncResult.Error(msg)
                }
                Log.d(TAG, "Ping success.")

                // Step 2: Download
                Log.d(TAG, "Step 2: Downloading backup ZIP...")
                val tempZip = downloadBackup(ipAddress, port, importMode)
                if (tempZip == null) {
                    Log.e(TAG, "Download failed (tempZip is null)")
                    return@withContext SyncResult.Error("下载备份失败，请检查网络或对方服务状态")
                }
                Log.i(TAG, "Download success. Size: ${tempZip.length()} bytes")

                // Step 3: Import
                Log.d(TAG, "Step 3: Importing backup to local storage...")
                val importOk = importBackupToLocal(tempZip, importMode, selectedGroupNames)

                if (tempZip.exists()) {
                    tempZip.delete()
                    Log.d(TAG, "Temporary ZIP deleted.")
                }

                if (importOk) {
                    Log.i(TAG, ">>> [SYNC SUCCESS] Sync from $ipAddress completed.")
                    SyncResult.Success("同步成功！已从 $ipAddress 拉取全部配置和铃声")
                } else {
                    Log.e(TAG, "Import failed (importBackupToLocal returned false)")
                    // 检查是否是权限问题（importBackupToLocal 内部已经记录了具体原因）
                    if (!hasStoragePermission()) {
                        SyncResult.Error("同步失败：缺少「所有文件访问权限」，请在设置中开启后重试")
                    } else {
                        SyncResult.Error("同步失败：备份数据解析出错，请确认对方版本是否一致")
                    }
                }
            } catch (e: java.net.ConnectException) {
                Log.e(TAG, "Connection refused", e)
                SyncResult.Error("连接被拒绝：请确认对方手机已开启 WiFi 同步开关")
            } catch (e: java.net.SocketTimeoutException) {
                Log.e(TAG, "Connection timeout", e)
                SyncResult.Error("连接超时：请检查 IP 是否输入正确，或两机是否在同一 WiFi")
            } catch (e: Exception) {
                Log.e(TAG, "Unexpected sync exception", e)
                SyncResult.Error("同步出错 [${e.javaClass.simpleName}]: ${e.message}")
            }
        }
    }

    /**
     * 获取远程备份中的闹钟组和打卡组名称列表（用于选择性同步）
     * @return Pair(闹钟组名列表, 打卡组名列表) 或 null（失败）
     */
    suspend fun fetchRemoteGroupList(ipAddress: String, port: Int = DEFAULT_PORT): Pair<List<String>, List<String>>? {
        return withContext(Dispatchers.IO) {
            try {
                val tempZip = downloadBackup(ipAddress, port, ImportMode.CLEAR) ?: return@withContext null
                val alarmGroups = mutableListOf<String>()
                val checkinGroups = mutableListOf<String>()
                try {
                    val zip = java.util.zip.ZipInputStream(java.io.FileInputStream(tempZip))
                    var entry = zip.nextEntry
                    while (entry != null) {
                        if (entry.name == "config.json") {
                            val jsonStr = zip.bufferedReader().readText()
                            val root = org.json.JSONObject(jsonStr)
                            val groupsArr = root.getJSONArray("groups")
                            for (i in 0 until groupsArr.length()) {
                                alarmGroups.add(groupsArr.getJSONObject(i).getString("name"))
                            }
                            if (root.has("checkinGroups")) {
                                val cgArr = root.getJSONArray("checkinGroups")
                                for (i in 0 until cgArr.length()) {
                                    checkinGroups.add(cgArr.getJSONObject(i).getString("name"))
                                }
                            }
                        }
                        entry = zip.nextEntry
                    }
                    zip.close()
                } catch (_: Exception) { }
                if (tempZip.exists()) tempZip.delete()
                if (alarmGroups.isEmpty() && checkinGroups.isEmpty()) null
                else Pair(alarmGroups, checkinGroups)
            } catch (_: Exception) { null }
        }
    }

    // ──────────────────── 内部步骤 ────────────────────

    private sealed class PingResult {
        object Ok : PingResult()
        data class Error(val message: String) : PingResult()
    }

    /** 确认远程服务可用 */
    private fun pingServer(ipAddress: String, port: Int): PingResult {
        return try {
            val url = URL("http://$ipAddress:$port/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            conn.instanceFollowRedirects = true

            val code = conn.responseCode
            conn.disconnect()

            if (code == 200) PingResult.Ok
            else PingResult.Error("远程服务器返回异常状态码: $code")
        } catch (e: java.net.ConnectException) {
            PingResult.Error("连接被拒绝，请确认对方已开启同步服务")
        } catch (e: java.net.SocketTimeoutException) {
            PingResult.Error("连接超时，请检查网络")
        } catch (e: Exception) {
            PingResult.Error("连接失败：${e.localizedMessage ?: "未知错误"}")
        }
    }

    /** 下载 /backup ZIP 到临时文件 */
    private fun downloadBackup(ipAddress: String, port: Int, importMode: ImportMode): File? {
        return try {
            val urlString = if (importMode == ImportMode.ONLY_CHIMES) {
                "http://$ipAddress:$port/backup?type=chimes_only"
            } else {
                "http://$ipAddress:$port/backup"
            }
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT

            if (conn.responseCode != 200) {
                Log.w(TAG, "备份下载失败，HTTP ${conn.responseCode}")
                conn.disconnect()
                return null
            }

            val contentLength = conn.contentLength
            Log.d(TAG, "Expected backup size: $contentLength bytes")

            val tempZip = File(context.cacheDir, "remote_sync_${System.currentTimeMillis()}.zip")
            conn.inputStream.use { input ->
                tempZip.outputStream().use { output ->
                    val buffer = ByteArray(16384)
                    var bytesRead: Int
                    var totalRead = 0
                    while (input.read(buffer).also { bytesRead = it } != -1) {
                        output.write(buffer, 0, bytesRead)
                        totalRead += bytesRead
                    }
                    output.flush()
                }
            }
            conn.disconnect()

            val actualSize = tempZip.length()
            if (contentLength > 0 && actualSize != contentLength.toLong()) {
                Log.e(TAG, "Download incomplete! Expected $contentLength, got $actualSize")
                tempZip.delete()
                return null
            }

            Log.d(TAG, "Download successful: $actualSize bytes")
            tempZip
        } catch (e: Exception) {
            Log.e(TAG, "下载备份失败", e)
            null
        }
    }

    /** 将下载的 ZIP 备份导入本地数据库 */
    private suspend fun importBackupToLocal(zipFile: File, importMode: ImportMode = ImportMode.CLEAR, selectedGroupNames: Set<String>? = null): Boolean {
        return try {
            // 获取配置路径
            val prefsSettings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val savedPath = prefsSettings.getString("recording_path", "")
            val recordingPath = if (!savedPath.isNullOrEmpty()) {
                savedPath
            } else {
                android.os.Environment.getExternalStorageDirectory().absolutePath + "/0"
            }

            // 检查外部存储权限
            if (!hasStoragePermission()) {
                Log.e(TAG, "没有外部存储写入权限，无法导入铃声文件。路径: $recordingPath")
                return false
            }

            val ringtonesDir = File(recordingPath)
            if (!ringtonesDir.exists()) ringtonesDir.mkdirs()

            // 报时缓存目录
            val chimeDir = File(context.filesDir, "chime_cache")
            if (!chimeDir.exists()) chimeDir.mkdirs()

            var configJson: String? = null

            java.util.zip.ZipInputStream(java.io.FileInputStream(zipFile)).use { zis ->
                var entry: java.util.zip.ZipEntry? = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "config.json" -> {
                            configJson = zis.bufferedReader().readText()
                        }
                        entry.name.startsWith("ringtones/") -> {
                            val fileName = entry.name.substringAfter("ringtones/")
                            if (fileName.isNotEmpty()) {
                                // 核心修复：直接流式写入文件，不通过内存中转，解决大文件（如 M4A）解压损坏问题
                                val targetFile = File(ringtonesDir, fileName)
                                Log.d(TAG, "Streaming extraction: $fileName")
                                targetFile.outputStream().use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                        }
                        entry.name.startsWith("chimes/") -> {
                            val fileName = entry.name.substringAfter("chimes/")
                            if (fileName.isNotEmpty()) {
                                val targetFile = File(chimeDir, fileName)
                                Log.d(TAG, "Extracting chime: $fileName")
                                targetFile.outputStream().use { fos ->
                                    zis.copyTo(fos)
                                }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            // 如果只是同步报时语音，到这里就结束了
            if (importMode == ImportMode.ONLY_CHIMES) {
                Log.i(TAG, "Chime only sync completed.")
                return true
            }

            // 写数据库
            configJson?.let { jsonStr ->
                val root = org.json.JSONObject(jsonStr)
                Log.d(TAG, "Config JSON parsed successfully.")

                // 数据库操作需要在 IO 线程执行
                withContext(Dispatchers.IO) {
                    val db = com.example.db.AlarmDatabase.getDatabase(
                        context,
                        kotlinx.coroutines.CoroutineScope(Dispatchers.IO)
                    )

                    // 根据模式处理现有数据
                    if (importMode == ImportMode.CLEAR) {
                        val existingGroups = db.alarmDao().getAllGroups()
                        Log.d(TAG, "Deleting ${existingGroups.size} existing groups...")
                        existingGroups.forEach { db.alarmDao().deleteGroup(it) }
                    } else {
                        Log.d(TAG, "Merge mode: keeping existing groups")
                    }

                    // 导入分组（选择性同步时只导入选中的组）
                    val idMap = mutableMapOf<Long, Long>()
                    val groupsArr = root.getJSONArray("groups")
                    val isSelective = importMode == ImportMode.SELECTIVE && selectedGroupNames != null
                    Log.d(TAG, "Importing ${groupsArr.length()} groups... (selective=${isSelective}, selected=${selectedGroupNames?.size ?: "all"})")
                    for (i in 0 until groupsArr.length()) {
                        val g = groupsArr.getJSONObject(i)
                        val groupName = g.getString("name")
                        // 选择性模式：跳过不在选中列表的组
                        if (isSelective && groupName !in selectedGroupNames!!) continue
                        val oldId = g.getLong("id")
                        val newId = db.alarmDao().insertGroup(
                            com.example.db.AlarmGroup(
                                name = groupName,
                                isEnabled = g.getBoolean("isEnabled")
                            )
                        )
                        idMap[oldId] = newId
                    }

                    // 导入闹钟
                    val alarmsArr = root.getJSONArray("alarms")
                    Log.d(TAG, "Importing ${alarmsArr.length()} alarms...")
                    var alarmsImported = 0
                    for (i in 0 until alarmsArr.length()) {
                        val a = alarmsArr.getJSONObject(i)
                        val newGroupId = idMap[a.getLong("groupId")] ?: continue

                        var ringtonePath: String? =
                            if (a.isNull("ringtonePath")) null else a.getString("ringtonePath")
                        if (ringtonePath != null && (ringtonePath.contains("/files/custom_ringtones/") || ringtonePath.contains("/0/"))) {
                            val fileName = ringtonePath.substringAfterLast("/")
                            ringtonePath = File(ringtonesDir, fileName).absolutePath
                        } else if (ringtonePath == "null") {
                            ringtonePath = null
                        }

                        db.alarmDao().insertAlarm(
                            com.example.db.Alarm(
                                groupId = newGroupId,
                                hour = a.getInt("hour"),
                                minute = a.getInt("minute"),
                                daysOfWeek = a.getString("daysOfWeek"),
                                isEnabled = a.getBoolean("isEnabled"),
                                label = a.getString("label"),
                                ringtonePath = ringtonePath,
                                vibrate = a.getBoolean("vibrate")
                            )
                        )
                        alarmsImported++
                    }
                    Log.d(TAG, "Successfully inserted $alarmsImported alarms.")

                    // 导入整点报时
                    val chimesArr = root.getJSONArray("chimes")
                    for (i in 0 until chimesArr.length()) {
                        val c = chimesArr.getJSONObject(i)
                        db.alarmDao().updateHourlyChime(
                            com.example.db.HourlyChime(
                                hour = c.getInt("hour"),
                                isEnabled = c.getBoolean("isEnabled"),
                                useTts = c.getBoolean("useTts"),
                                vibrate = c.getBoolean("vibrate")
                            )
                        )
                    }

                    // 导入打卡组
                    val checkinGroupIdMap = mutableMapOf<Long, Long>()
                    if (root.has("checkinGroups")) {
                        val checkinGroupsArr = root.getJSONArray("checkinGroups")
                        if (importMode == ImportMode.CLEAR) {
                            db.checkinDao().getAllGroups().forEach { db.checkinDao().deleteGroup(it) }
                        }
                        for (i in 0 until checkinGroupsArr.length()) {
                            val g = checkinGroupsArr.getJSONObject(i)
                            val groupName = g.getString("name")
                            // 选择性模式：跳过不在选中列表的组
                            if (isSelective && groupName !in selectedGroupNames!!) continue
                            val rawRingtonePath = if (g.isNull("ringtonePath")) null else g.getString("ringtonePath")
                            var newRingtonePath: String? = rawRingtonePath
                            if (newRingtonePath != null && (newRingtonePath.contains("/files/custom_ringtones/") || newRingtonePath.contains("/0/"))) {
                                newRingtonePath = File(ringtonesDir, newRingtonePath.substringAfterLast("/")).absolutePath
                            }
                            val oldBoundAlarmGroupId = if (g.has("boundAlarmGroupId")) g.getLong("boundAlarmGroupId") else -1L
                            val newBoundAlarmGroupId = idMap[oldBoundAlarmGroupId] ?: -1L
                            val newId = db.checkinDao().insertGroup(
                                com.example.db.CheckInGroupEntity(
                                    name = g.getString("name"),
                                    isEnabled = g.getBoolean("isEnabled"),
                                    ringtonePath = newRingtonePath,
                                    boundAlarmGroupId = newBoundAlarmGroupId,
                                    createdAt = if (g.has("createdAt")) g.getLong("createdAt") else System.currentTimeMillis()
                                )
                            )
                            checkinGroupIdMap[g.getLong("id")] = newId
                        }
                    }

                    // 导入打卡事项
                    if (root.has("checkinTasks")) {
                        val checkinTasksObj = root.getJSONObject("checkinTasks")
                        for (oldGroupIdStr in checkinTasksObj.keys()) {
                            val oldGroupId = oldGroupIdStr.toLongOrNull() ?: continue
                            val newGroupId = checkinGroupIdMap[oldGroupId] ?: continue
                            val tasksArr = checkinTasksObj.getJSONArray(oldGroupIdStr)
                            for (j in 0 until tasksArr.length()) {
                                val t = tasksArr.getJSONObject(j)
                                val rawRingtonePath = if (t.isNull("ringtonePath")) null else t.getString("ringtonePath")
                                var newRingtonePath: String? = rawRingtonePath
                                if (newRingtonePath != null && (newRingtonePath.contains("/files/custom_ringtones/") || newRingtonePath.contains("/0/"))) {
                                    newRingtonePath = File(ringtonesDir, newRingtonePath.substringAfterLast("/")).absolutePath
                                }
                                db.checkinDao().insertTask(
                                    com.example.db.CheckInTaskEntity(
                                        groupId = newGroupId,
                                        name = t.getString("name"),
                                        hour = if (t.has("hour")) t.getInt("hour") else 8,
                                        minute = if (t.has("minute")) t.getInt("minute") else 0,
                                        orderIndex = if (t.has("orderIndex")) t.getInt("orderIndex") else 0,
                                        ringtonePath = newRingtonePath,
                                        useTts = if (t.has("useTts")) t.getBoolean("useTts") else false
                                    )
                                )
                            }
                        }
                    }

                    // 重新调度所有闹钟
                    val allAlarms = db.alarmDao().getAllAlarms()
                    val allGroups = db.alarmDao().getAllGroups()
                    allAlarms.forEach { alarm ->
                        val group = allGroups.find { it.id == alarm.groupId }
                        AlarmScheduler.scheduleAlarm(context, alarm, group)
                    }
                    AlarmScheduler.scheduleNextHourlyChime(context)
                }

                return true
            }

            false
        } catch (e: Exception) {
            Log.e(TAG, "导入备份到本地失败", e)
            false
        }
    }
}
