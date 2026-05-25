package com.example.alarm

import android.content.Context
import android.util.Log
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
    enum class ImportMode { CLEAR, MERGE, ONLY_CHIMES }

    companion object {
        private const val TAG = "WifiSyncClient"
        private const val DEFAULT_PORT = 8080
        private const val CONNECT_TIMEOUT = 10000
        private const val READ_TIMEOUT = 60000
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
    suspend fun syncFromRemote(ipAddress: String, port: Int = DEFAULT_PORT, importMode: ImportMode = ImportMode.CLEAR): SyncResult {
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
                val importOk = importBackupToLocal(tempZip, importMode)

                if (tempZip.exists()) {
                    tempZip.delete()
                    Log.d(TAG, "Temporary ZIP deleted.")
                }

                if (importOk) {
                    Log.i(TAG, ">>> [SYNC SUCCESS] Sync from $ipAddress completed.")
                    SyncResult.Success("同步成功！已从 $ipAddress 拉取全部配置和铃声")
                } else {
                    Log.e(TAG, "Import failed (importBackupToLocal returned false)")
                    SyncResult.Error("同步失败：备份数据解析出错，请确认对方版本是否一致")
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
    private suspend fun importBackupToLocal(zipFile: File, importMode: ImportMode = ImportMode.CLEAR): Boolean {
        return try {
            // 获取配置路径
            val prefsSettings = context.getSharedPreferences("app_settings", Context.MODE_PRIVATE)
            val savedPath = prefsSettings.getString("recording_path", "")
            val recordingPath = if (!savedPath.isNullOrEmpty()) {
                savedPath
            } else {
                android.os.Environment.getExternalStorageDirectory().absolutePath + "/0"
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

                    // 导入分组
                    val idMap = mutableMapOf<Long, Long>()
                    val groupsArr = root.getJSONArray("groups")
                    Log.d(TAG, "Importing ${groupsArr.length()} groups...")
                    for (i in 0 until groupsArr.length()) {
                        val g = groupsArr.getJSONObject(i)
                        val oldId = g.getLong("id")
                        val newId = db.alarmDao().insertGroup(
                            com.example.db.AlarmGroup(
                                name = g.getString("name"),
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
