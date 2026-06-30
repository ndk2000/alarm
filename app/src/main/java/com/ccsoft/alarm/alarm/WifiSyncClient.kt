package com.ccsoft.alarm.alarm

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.core.content.ContextCompat
import com.ccsoft.alarm.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.net.HttpURLConnection
import java.net.URL
import java.util.zip.ZipInputStream
import java.util.zip.ZipEntry

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
     * 从远程同步数据，支持选择性同步
     * @param selectedGroupNames 选择同步的闹钟组与打卡组名称集合（仅 SELECTIVE 模式使用）
     */
    suspend fun syncFromRemote(ipAddress: String, port: Int = DEFAULT_PORT, importMode: ImportMode = ImportMode.CLEAR, selectedGroupNames: Set<String>? = null): SyncResult {
        return withContext(Dispatchers.IO) {
            try {
                Log.i(TAG, ">>> [SYNC START] Target: $ipAddress:$port, mode=$importMode")

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

    /** 获取远程组列表 */
    suspend fun fetchRemoteGroupList(ipAddress: String, port: Int = DEFAULT_PORT): Pair<List<String>, List<String>>? {
        return withContext(Dispatchers.IO) {
            try {
                val tempZip = downloadBackup(ipAddress, port, ImportMode.CLEAR) ?: return@withContext null
                val alarmGroups = mutableListOf<String>()
                val checkinGroups = mutableListOf<String>()
                try {
                    ZipInputStream(FileInputStream(tempZip)).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (entry.name == "config.json") {
                                val jsonStr = zis.bufferedReader().readText()
                                val root = org.json.JSONObject(jsonStr)
                                if (root.has("groups")) {
                                    val groupsArr = root.getJSONArray("groups")
                                    for (i in 0 until groupsArr.length()) {
                                        alarmGroups.add(groupsArr.getJSONObject(i).getString("name"))
                                    }
                                }
                                if (root.has("checkinGroups")) {
                                    val cgArr = root.getJSONArray("checkinGroups")
                                    for (i in 0 until cgArr.length()) {
                                        checkinGroups.add(cgArr.getJSONObject(i).getString("name"))
                                    }
                                }
                            }
                            entry = zis.nextEntry
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "解析远程备份失败: ${e.message}")
                }
                if (tempZip.exists()) tempZip.delete()
                if (alarmGroups.isEmpty() && checkinGroups.isEmpty()) null
                else Pair(alarmGroups, checkinGroups)
            } catch (_: Exception) { null }
        }
    }

    private sealed class PingResult {
        object Ok : PingResult()
        data class Error(val message: String) : PingResult()
    }

    private fun pingServer(ipAddress: String, port: Int): PingResult {
        return try {
            val url = URL("http://$ipAddress:$port/")
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            val code = conn.responseCode
            conn.disconnect()
            if (code == 200) PingResult.Ok else PingResult.Error("HTTP $code")
        } catch (e: Exception) {
            PingResult.Error(e.message ?: "Ping failed")
        }
    }

    private fun downloadBackup(ipAddress: String, port: Int, importMode: ImportMode): File? {
        return try {
            val urlString = if (importMode == ImportMode.ONLY_CHIMES) "http://$ipAddress:$port/backup?type=chimes_only" else "http://$ipAddress:$port/backup"
            val url = URL(urlString)
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = CONNECT_TIMEOUT
            conn.readTimeout = READ_TIMEOUT
            if (conn.responseCode != 200) { conn.disconnect(); return null }
            val tempZip = File(context.cacheDir, "remote_sync_${System.currentTimeMillis()}.zip")
            conn.inputStream.use { input -> tempZip.outputStream().use { output -> input.copyTo(output) } }
            conn.disconnect()
            tempZip
        } catch (e: Exception) {
            Log.e(TAG, "Download failed", e)
            null
        }
    }

    private suspend fun importBackupToLocal(zipFile: File, importMode: ImportMode = ImportMode.CLEAR, selectedGroupNames: Set<String>? = null): Boolean {
        Log.i(TAG, "Starting import: mode=$importMode, selectedCount=${selectedGroupNames?.size ?: 0}")
        return try {
            val prefsSettings = PreferencesManager(context)
            val savedPath = prefsSettings.getRecordingPath()
            val recordingsDir = if (savedPath.isNotEmpty()) File(savedPath) else File(context.filesDir, "recordings")
            if (!recordingsDir.exists()) recordingsDir.mkdirs()

            val customRingtonesDir = File(context.filesDir, "custom_ringtones")
            if (!customRingtonesDir.exists()) customRingtonesDir.mkdirs()

            val chimeDir = File(context.filesDir, "chime_cache")
            if (!chimeDir.exists()) chimeDir.mkdirs()

            var configJson: String? = null

            ZipInputStream(FileInputStream(zipFile)).use { zis ->
                var entry = zis.nextEntry
                while (entry != null) {
                    when {
                        entry.name == "config.json" -> {
                            configJson = zis.bufferedReader().readText()
                        }
                        entry.name.startsWith("ringtones/") -> {
                            val fileName = entry.name.substringAfter("ringtones/")
                            if (fileName.isNotEmpty()) {
                                // 启发式去向判断，确保文件被放在 loadCustomRingtones 或 loadLocalRecordings 能扫到的地方
                                val targetFile = if (fileName.contains("recording") || fileName.endsWith(".m4a")) {
                                    File(recordingsDir, fileName)
                                } else {
                                    File(customRingtonesDir, fileName)
                                }
                                Log.d(TAG, "Extracting ringtone: $fileName to ${targetFile.parentFile?.name}")
                                targetFile.outputStream().use { fos -> zis.copyTo(fos) }
                            }
                        }
                        entry.name.startsWith("chimes/") -> {
                            val fileName = entry.name.substringAfter("chimes/")
                            if (fileName.isNotEmpty()) {
                                File(chimeDir, fileName).outputStream().use { fos -> zis.copyTo(fos) }
                            }
                        }
                    }
                    zis.closeEntry()
                    entry = zis.nextEntry
                }
            }

            if (configJson == null && importMode != ImportMode.ONLY_CHIMES) {
                Log.e(TAG, "config.json missing")
                return false
            }

            if (importMode == ImportMode.ONLY_CHIMES) return true

            configJson?.let { jsonStr ->
                val root = org.json.JSONObject(jsonStr)
                withContext(Dispatchers.IO) {
                    val db = com.ccsoft.alarm.db.AlarmDatabase.getDatabase(context, kotlinx.coroutines.CoroutineScope(Dispatchers.IO))
                    
                    if (importMode == ImportMode.CLEAR) {
                        db.alarmDao().getAllGroups().forEach { db.alarmDao().deleteGroup(it) }
                        db.checkinDao().getAllGroups().forEach { db.checkinDao().deleteGroup(it) }
                    }

                    val isSelective = importMode == ImportMode.SELECTIVE && selectedGroupNames != null
                    val idMap = mutableMapOf<Long, Long>()

                    if (root.has("groups")) {
                        val arr = root.getJSONArray("groups")
                        for (i in 0 until arr.length()) {
                            val g = arr.getJSONObject(i)
                            val name = g.getString("name")
                            if (isSelective && name !in selectedGroupNames!!) continue
                            val newId = db.alarmDao().insertGroup(com.ccsoft.alarm.db.AlarmGroup(name = name, isEnabled = g.getBoolean("isEnabled")))
                            idMap[g.getLong("id")] = newId
                        }
                    }

                    if (root.has("alarms")) {
                        val arr = root.getJSONArray("alarms")
                        for (i in 0 until arr.length()) {
                            val a = arr.getJSONObject(i)
                            val newGroupId = idMap[a.getLong("groupId")] ?: continue
                            var rPath = if (a.isNull("ringtonePath")) null else a.getString("ringtonePath")
                            if (rPath != null) {
                                val fname = rPath.substringAfterLast("/")
                                rPath = if (rPath.contains("/custom_ringtones/")) File(customRingtonesDir, fname).absolutePath else File(recordingsDir, fname).absolutePath
                            }
                            db.alarmDao().insertAlarm(com.ccsoft.alarm.db.Alarm(
                                groupId = newGroupId, hour = a.getInt("hour"), minute = a.getInt("minute"),
                                daysOfWeek = a.getString("daysOfWeek"), isEnabled = a.getBoolean("isEnabled"),
                                label = a.getString("label"), ringtonePath = rPath, vibrate = a.getBoolean("vibrate")
                            ))
                        }
                    }

                    if (root.has("chimes")) {
                        val arr = root.getJSONArray("chimes")
                        for (i in 0 until arr.length()) {
                            val c = arr.getJSONObject(i)
                            db.alarmDao().updateHourlyChime(com.ccsoft.alarm.db.HourlyChime(hour = c.getInt("hour"), isEnabled = c.getBoolean("isEnabled"), useTts = c.getBoolean("useTts"), vibrate = c.getBoolean("vibrate")))
                        }
                    }

                    val checkinIdMap = mutableMapOf<Long, Long>()
                    if (root.has("checkinGroups")) {
                        val arr = root.getJSONArray("checkinGroups")
                        for (i in 0 until arr.length()) {
                            val g = arr.getJSONObject(i)
                            val name = g.getString("name")
                            if (isSelective && name !in selectedGroupNames!!) continue
                            var rPath = if (g.isNull("ringtonePath")) null else g.getString("ringtonePath")
                            if (rPath != null) {
                                val fname = rPath.substringAfterLast("/")
                                rPath = if (rPath.contains("/custom_ringtones/")) File(customRingtonesDir, fname).absolutePath else File(recordingsDir, fname).absolutePath
                            }
                            val newId = db.checkinDao().insertGroup(com.ccsoft.alarm.db.CheckInGroupEntity(
                                name = name, isEnabled = g.getBoolean("isEnabled"), ringtonePath = rPath,
                                boundAlarmGroupId = idMap[if (g.has("boundAlarmGroupId")) g.getLong("boundAlarmGroupId") else -1L] ?: -1L,
                                createdAt = if (g.has("createdAt")) g.getLong("createdAt") else System.currentTimeMillis()
                            ))
                            checkinIdMap[g.getLong("id")] = newId
                        }
                    }

                    if (root.has("checkinTasks")) {
                        val obj = root.getJSONObject("checkinTasks")
                        val oldIdToName = mutableMapOf<Long, String>()
                        if (root.has("checkinGroups")) {
                            val arr = root.getJSONArray("checkinGroups")
                            for (i in 0 until arr.length()) {
                                val g = arr.getJSONObject(i)
                                oldIdToName[g.getLong("id")] = g.getString("name")
                            }
                        }
                        for (oldIdStr in obj.keys()) {
                            val oldId = oldIdStr.toLong()
                            val name = oldIdToName[oldId] ?: ""
                            if (isSelective && name !in selectedGroupNames!!) continue
                            val newGroupId = checkinIdMap[oldId] ?: continue
                            val arr = obj.getJSONArray(oldIdStr)
                            for (j in 0 until arr.length()) {
                                val t = arr.getJSONObject(j)
                                var rPath = if (t.isNull("ringtonePath")) null else t.getString("ringtonePath")
                                if (rPath != null) {
                                    val fname = rPath.substringAfterLast("/")
                                    rPath = if (rPath.contains("/custom_ringtones/")) File(customRingtonesDir, fname).absolutePath else File(recordingsDir, fname).absolutePath
                                }
                                db.checkinDao().insertTask(com.ccsoft.alarm.db.CheckInTaskEntity(
                                    groupId = newGroupId, name = t.getString("name"),
                                    hour = if (t.has("hour")) t.getInt("hour") else 8,
                                    minute = if (t.has("minute")) t.getInt("minute") else 0,
                                    orderIndex = if (t.has("orderIndex")) t.getInt("orderIndex") else 0,
                                    ringtonePath = rPath, useTts = if (t.has("useTts")) t.getBoolean("useTts") else false
                                ))
                            }
                        }
                    }

                    db.alarmDao().getAllAlarms().forEach { a ->
                        AlarmScheduler.scheduleAlarm(context, a, db.alarmDao().getAllGroups().find { it.id == a.groupId })
                    }
                    AlarmScheduler.scheduleNextHourlyChime(context)
                }
                true
            } ?: false
        } catch (e: Exception) {
            Log.e(TAG, "Import critical error", e)
            false
        }
    }
}
