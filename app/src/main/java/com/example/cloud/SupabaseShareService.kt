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
 * 通过 Supabase REST API 实现云端分享。
 *
 * 使用说明：
 * 1. 注册 https://supabase.com （免费注册）
 * 2. 创建新项目
 * 3. 设置 → Database → 复制"项目 URL"和"anon/public key"
 * 4. 在 App 设置中填入
 *
 * 建表 SQL（首次使用时执行一次）：
 *
 * CREATE TABLE shared_configs (
 *   id BIGSERIAL PRIMARY KEY,
 *   share_code TEXT UNIQUE NOT NULL,
 *   data TEXT NOT NULL,
 *   created_at TIMESTAMP WITH TIME ZONE DEFAULT NOW(),
 *   download_count INTEGER DEFAULT 0
 * );
 *
 * -- 启用 RLS 但允许公开读取/创建
 * ALTER TABLE shared_configs ENABLE ROW LEVEL SECURITY;
 *
 * CREATE POLICY "allow_public_read" ON shared_configs
 *   FOR SELECT USING (true);
 * CREATE POLICY "allow_public_insert" ON shared_configs
 *   FOR INSERT WITH CHECK (true);
 * CREATE POLICY "allow_public_delete" ON shared_configs
 *   FOR DELETE USING (true);
 */
class SupabaseShareService(
    private val context: Context
) : CloudService {

    private val prefs = context.getCloudPrefs()
    private val supabaseUrl = prefs.getString(CloudConfigKeys.PREF_SUPABASE_URL, "").let {
        if (it.isNullOrBlank()) CloudConfigKeys.DEFAULT_SUPABASE_URL else it
    }
    private val supabaseKey = prefs.getString(CloudConfigKeys.PREF_SUPABASE_ANON_KEY, "").let {
        if (it.isNullOrBlank()) CloudConfigKeys.DEFAULT_SUPABASE_ANON_KEY else it
    }

    private val baseUrl: String = supabaseUrl.trim().removeSuffix("/").let {
        if (it.isBlank()) ""
        else if (it.startsWith("http://") || it.startsWith("https://")) it
        else "https://$it"
    }

    override val serviceName: String = "Supabase"

    override val configured: Boolean =
        baseUrl.isNotBlank() && supabaseKey.isNotBlank() &&
        !supabaseUrl.contains("your-project") && !supabaseKey.contains("your-key")

    companion object {
        private const val TABLE = "shared_configs"
        private const val DEFAULT_LIMIT = 100
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build()

    private val jsonMediaType = "application/json".toMediaType()
    private val contentTypeHeader = "application/json".toMediaType().let { "application/json; charset=utf-8" }

    override suspend fun checkConnection(): CloudService.ConnectionStatus = withContext(Dispatchers.IO) {
        if (!configured) return@withContext CloudService.ConnectionStatus.NotConfigured
        try {
            val url = "$baseUrl/rest/v1/$TABLE?limit=1"
            val request = Request.Builder()
                .url(url)
                .head()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                CloudService.ConnectionStatus.Connected
            } else {
                val errorMsg = when (response.code) {
                    401 -> "API Key 无效 (401)"
                    403 -> "权限受限 (403)"
                    404 -> "找不到表格 (404)"
                    else -> "服务器响应错误 (${response.code})"
                }
                CloudService.ConnectionStatus.Error(errorMsg)
            }
        } catch (e: java.net.UnknownHostException) {
            CloudService.ConnectionStatus.Error("无法解析域名，请检查网络 (UnknownHost)")
        } catch (e: java.net.ConnectException) {
            CloudService.ConnectionStatus.Error("连接被拒绝，请检查 URL (ConnectException)")
        } catch (e: java.net.SocketTimeoutException) {
            CloudService.ConnectionStatus.Error("连接超时，请检查网络 (Timeout)")
        } catch (e: Exception) {
            CloudService.ConnectionStatus.Error("连接异常: ${e.message}")
        }
    }

    override suspend fun uploadConfig(jsonString: String): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        try {
            val shareCode = generateShareCode()

            val bodyJson = JSONObject().apply {
                put("share_code", shareCode)
                put("data", jsonString)
                put("download_count", 0)
            }
            val body = bodyJson.toString().toRequestBody(contentTypeHeader.toMediaType())

            val url = "$baseUrl/rest/v1/$TABLE"
            val request = Request.Builder()
                .url(url)
                .post(body)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Content-Type", "application/json")
                .addHeader("Prefer", "return=representation")
                .build()

            android.util.Log.d("SupabaseShare", "开始上传: URL=$url, ShareCode=$shareCode")

            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                android.util.Log.d("SupabaseShare", "上传成功: $shareCode")
                shareCode
            } else {
                val error = response.body?.string() ?: "HTTP ${response.code}"
                android.util.Log.e("SupabaseShare", "上传失败: $error")
                // 抛出具体异常以便 UI 捕获并显示
                throw Exception(error)
            }
        } catch (e: Exception) {
            android.util.Log.e("SupabaseShare", "上传异常", e)
            null
        }
    }

    override suspend fun downloadConfig(shareCode: String): String? = withContext(Dispatchers.IO) {
        if (!configured) return@withContext null
        try {
            val code = shareCode.trim().uppercase()
            if (code.length != 6 || !code.all { it.isUpperCase() || it.isDigit() }) {
                return@withContext null
            }

            val url = "$baseUrl/rest/v1/$TABLE?share_code=eq.$code&limit=1"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                android.util.Log.e("SupabaseShare", "下载失败: HTTP ${response.code}")
                return@withContext null
            }

            val body = response.body?.string() ?: return@withContext null
            val arr = org.json.JSONArray(body)
            if (arr.length() == 0) return@withContext null

            val item = arr.getJSONObject(0)
            val data = item.optString("data", "")

            // 增加下载计数
            incrementDownloadCount(code)

            if (data.isBlank()) return@withContext null
            data
        } catch (e: Exception) {
            android.util.Log.e("SupabaseShare", "下载异常", e)
            null
        }
    }

    private suspend fun incrementDownloadCount(shareCode: String) {
        if (!configured) return
        try {
            val url = "$baseUrl/rest/v1/$TABLE?share_code=eq.$shareCode"
            val getRequest = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()

            val getResponse = client.newCall(getRequest).execute()
            val body = getResponse.body?.string() ?: return
            val arr = org.json.JSONArray(body)
            if (arr.length() == 0) return

            val currentCount = arr.getJSONObject(0).optInt("download_count", 0)
            val count = currentCount + 1

            val updateBody = JSONObject().apply {
                put("download_count", count)
            }.toString().toRequestBody(contentTypeHeader.toMediaType())

            val updateRequest = Request.Builder()
                .url(url)
                .patch(updateBody)
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .addHeader("Content-Type", "application/json")
                .build()

            client.newCall(updateRequest).execute()
        } catch (_: Exception) {
            // 忽略
        }
    }

    override suspend fun listConfigs(): List<CloudService.CloudConfig> = withContext(Dispatchers.IO) {
        if (!configured) return@withContext emptyList()
        try {
            val url = "$baseUrl/rest/v1/$TABLE?order=created_at.desc&limit=$DEFAULT_LIMIT"
            val request = Request.Builder()
                .url(url)
                .get()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()

            val response = client.newCall(request).execute()
            if (!response.isSuccessful) {
                android.util.Log.e("SupabaseShare", "列出失败: HTTP ${response.code}")
                return@withContext emptyList()
            }

            val body = response.body?.string() ?: return@withContext emptyList()
            val arr = org.json.JSONArray(body)

            val result = mutableListOf<CloudService.CloudConfig>()
            for (i in 0 until arr.length()) {
                val item = arr.getJSONObject(i)
                val code = item.optString("share_code", "").takeIf { it.length == 6 } ?: continue
                val data = item.optString("data", "")
                val createdAt = item.optLong("created_at")
                val downloadCount = item.optInt("download_count", 0)

                val preview = data.take(100).replace(Regex("[{}\"\\\\]"), "").take(50)
                val type = when {
                    data.contains("alarm_group") -> ShareDataType.ALARM_GROUP
                    data.contains("check_in_group") -> ShareDataType.CHECK_IN_GROUP
                    else -> ShareDataType.ALARM_GROUP
                }

                result.add(CloudService.CloudConfig(code, createdAt, downloadCount, preview, type))
            }
            result
        } catch (e: Exception) {
            android.util.Log.e("SupabaseShare", "列出异常", e)
            emptyList()
        }
    }

    override suspend fun deleteConfig(shareCode: String): Boolean = withContext(Dispatchers.IO) {
        if (!configured) return@withContext false
        try {
            val url = "$baseUrl/rest/v1/$TABLE?share_code=eq.$shareCode"
            val request = Request.Builder()
                .url(url)
                .delete()
                .addHeader("apikey", supabaseKey)
                .addHeader("Authorization", "Bearer $supabaseKey")
                .build()

            val response = client.newCall(request).execute()
            response.isSuccessful
        } catch (e: Exception) {
            android.util.Log.e("SupabaseShare", "删除异常", e)
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
