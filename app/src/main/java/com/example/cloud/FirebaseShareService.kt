package com.example.cloud

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * 通过 Firebase Firestore REST API 实现云端分享。
 */
class FirebaseShareService(
    context: Context
) : CloudService {

    private val prefs = context.getCloudPrefs()
    private val projectId = prefs.getString(CloudConfigKeys.PREF_FIREBASE_PROJECT_ID, "").let { 
        if (it.isNullOrBlank()) CloudConfigKeys.DEFAULT_FIREBASE_PROJECT_ID else it 
    }
    private val apiKey = prefs.getString(CloudConfigKeys.PREF_FIREBASE_API_KEY, "").let {
        if (it.isNullOrBlank()) CloudConfigKeys.DEFAULT_FIREBASE_API_KEY else it
    }

    override val serviceName: String = "Firebase"
    override val configured: Boolean = true // Firebase 始终有默认值

    private val COLLECTION = "shared_alarm_configs"
    private val BASE_URL = "https://firestore.googleapis.com/v1/projects/$projectId/databases/(default)/documents/$COLLECTION"

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()

    override suspend fun checkConnection(): CloudService.ConnectionStatus = withContext(Dispatchers.IO) {
        if (!configured) return@withContext CloudService.ConnectionStatus.NotConfigured
        try {
            // 使用一个简单的 list 操作来检查连接和 Key 是否有效
            val url = "$BASE_URL?pageSize=1&key=$apiKey"
            val request = Request.Builder().url(url).head().build()
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                CloudService.ConnectionStatus.Connected
            } else {
                val errorMsg = when (response.code) {
                    400 -> "API Key 无效或 Project ID 错误 (400)"
                    403 -> "权限受限，请确认 Firestore 已开启 (403)"
                    404 -> "找不到集合 (404)"
                    else -> "服务器响应错误 (${response.code})"
                }
                CloudService.ConnectionStatus.Error(errorMsg)
            }
        } catch (e: java.net.UnknownHostException) {
            CloudService.ConnectionStatus.Error("无法解析域名，请检查网络 (UnknownHost)")
        } catch (e: java.net.SocketTimeoutException) {
            CloudService.ConnectionStatus.Error("连接超时，请检查网络 (Timeout)")
        } catch (e: Exception) {
            CloudService.ConnectionStatus.Error("连接异常: ${e.message}")
        }
    }

    override suspend fun uploadConfig(jsonString: String): String? = withContext(Dispatchers.IO) {
        try {
            val shareCode = generateShareCode()
            val documentData = JSONObject().apply {
                put("fields", JSONObject().apply {
                    put("data", JSONObject().apply {
                        put("stringValue", jsonString)
                    })
                    put("createdAt", JSONObject().apply {
                        put("integerValue", (System.currentTimeMillis() / 1000).toString())
                    })
                    put("downloadCount", JSONObject().apply {
                        put("integerValue", "0")
                    })
                })
            }

            val url = "$BASE_URL?documentId=$shareCode&key=$apiKey"
            val body = documentData.toString().toRequestBody(jsonMediaType)
            val request = Request.Builder()
                .url(url)
                .post(body)
                .header("Content-Type", "application/json")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                shareCode
            } else {
                val errorBody = response.body?.string() ?: "未知错误"
                android.util.Log.e("FirebaseShare", "上传失败: $errorBody")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("FirebaseShare", "上传异常", e)
            null
        }
    }

    override suspend fun downloadConfig(shareCode: String): String? = withContext(Dispatchers.IO) {
        try {
            val code = shareCode.trim().uppercase()
            if (code.length != 6 || !code.all { it.isUpperCase() || it.isDigit() }) {
                return@withContext null
            }

            val url = "$BASE_URL/$code?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .get()
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                val errorBody = response.body?.string() ?: "未知错误"
                android.util.Log.e("FirebaseShare", "下载失败: $errorBody")
                return@withContext null
            }

            val responseBody = response.body?.string() ?: return@withContext null
            val doc = JSONObject(responseBody)
            val fields = doc.optJSONObject("fields") ?: return@withContext null

            val dataField = fields.optJSONObject("data") ?: return@withContext null
            val jsonString = dataField.optString("stringValue")
                ?: dataField.optString("string_value")
                ?: return@withContext null

            if (jsonString.isBlank()) return@withContext null

            incrementDownloadCount(code)

            jsonString
        } catch (e: Exception) {
            android.util.Log.e("FirebaseShare", "下载异常", e)
            null
        }
    }

    private suspend fun incrementDownloadCount(code: String) {
        try {
            val getUrl = "$BASE_URL/$code?key=$apiKey"
            val getRequest = Request.Builder().url(getUrl).get().build()
            val getResponse = client.newCall(getRequest).execute()
            val body = getResponse.body?.string() ?: return
            val doc = JSONObject(body)
            val fields = doc.optJSONObject("fields") ?: return
            val currentCount = fields.optJSONObject("downloadCount")?.optString("integerValue", "0")?.toIntOrNull() ?: 0

            val updateData = JSONObject().apply {
                put("fields", JSONObject().apply {
                    put("downloadCount", JSONObject().apply {
                        put("integerValue", (currentCount + 1).toString())
                    })
                })
            }
            val updateUrl = "$BASE_URL/$code?key=$apiKey&updateMask.fieldPaths=downloadCount"
            val updateBody = updateData.toString().toRequestBody(jsonMediaType)
            val updateRequest = Request.Builder()
                .url(updateUrl)
                .patch(updateBody)
                .header("Content-Type", "application/json")
                .build()
            client.newCall(updateRequest).execute()
        } catch (_: Exception) {
        }
    }

    override suspend fun listConfigs(): List<CloudService.CloudConfig> = withContext(Dispatchers.IO) {
        try {
            val url = "$BASE_URL?key=$apiKey"
            val request = Request.Builder().url(url).get().build()
            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                android.util.Log.e("FirebaseShare", "列出配置失败: ${response.code}")
                return@withContext emptyList()
            }
            val responseBody = response.body?.string() ?: return@withContext emptyList()
            val doc = JSONObject(responseBody)
            val documents = doc.optJSONArray("documents") ?: return@withContext emptyList()

            val result = mutableListOf<CloudService.CloudConfig>()
            for (i in 0 until documents.length()) {
                val docItem = documents.getJSONObject(i)
                val fields = docItem.optJSONObject("fields") ?: continue
                val name = docItem.optString("name", "")
                val shareCode = name.substringAfterLast("/").takeIf { it.length == 6 } ?: continue

                val createdAt = fields.optJSONObject("createdAt")?.optString("integerValue", "0")?.toLongOrNull()
                val downloadCount = fields.optJSONObject("downloadCount")?.optString("integerValue", "0")?.toIntOrNull() ?: 0

                val dataField = fields.optJSONObject("data")
                val rawData = dataField?.optString("stringValue") ?: ""
                val preview = rawData.take(100).replace(Regex("[{}\"\\\\]"), "").take(50)

                val type = when {
                    rawData.contains("alarm_group") -> ShareDataType.ALARM_GROUP
                    rawData.contains("check_in_group") -> ShareDataType.CHECK_IN_GROUP
                    else -> ShareDataType.ALARM_GROUP
                }

                result.add(CloudService.CloudConfig(shareCode, createdAt ?: 0, downloadCount, preview, type))
            }

            result.sortedByDescending { it.createdAt }
        } catch (e: Exception) {
            android.util.Log.e("FirebaseShare", "列出配置异常", e)
            emptyList()
        }
    }

    override suspend fun deleteConfig(shareCode: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val code = shareCode.trim().uppercase()
            val url = "$BASE_URL/$code?key=$apiKey"
            val request = Request.Builder()
                .url(url)
                .delete()
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("FirebaseShare", "删除配置异常", e)
            false
        }
    }

    override fun buildAlarmConfigJson(groupName: String, alarmsJson: JSONArray): String {
        return JSONObject().apply {
            put("formatVersion", 1)
            put("exportType", "alarm_group")
            put("exportTime", System.currentTimeMillis())
            put("groupName", groupName)
            put("alarms", alarmsJson)
        }.toString()
    }

    override fun buildCheckInConfigJson(groupName: String, tasksJson: JSONArray): String {
        return JSONObject().apply {
            put("formatVersion", 1)
            put("exportType", "check_in_group")
            put("exportTime", System.currentTimeMillis())
            put("groupName", groupName)
            put("tasks", tasksJson)
        }.toString()
    }

    override fun parseCheckInConfig(jsonString: String): Pair<String, JSONArray>? {
        return try {
            val root = JSONObject(jsonString)
            val exportType = root.optString("exportType", "")
            if (exportType != "check_in_group") return null
            val groupName = root.optString("groupName", "导入的打卡组")
            val tasks = root.optJSONArray("tasks") ?: return null
            Pair(groupName, tasks)
        } catch (e: Exception) {
            null
        }
    }

    private fun generateShareCode(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        val sb = StringBuilder(6)
        repeat(6) { sb.append(chars.random()) }
        return sb.toString()
    }
}
