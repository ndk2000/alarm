package com.ccsoft.alarm.cloud

import android.content.Context
import android.content.SharedPreferences
import com.ccsoft.alarm.BuildConfig

// ==================== 数据类型枚举 ====================

/** 分享数据类型 */
enum class ShareDataType {
    ALARM_GROUP,        // 闹钟分组
    CHECK_IN_GROUP      // 打卡分组
}

// ==================== 统一云端服务接口 ====================

/** 云端服务通用接口 */
interface CloudService {
    val serviceName: String // "Firebase" | "Supabase"
    val configured: Boolean

    suspend fun checkConnection(): ConnectionStatus

    suspend fun uploadConfig(jsonString: String): String? // 返回分享码
    suspend fun downloadConfig(shareCode: String): String? // 返回 JSON 字符串
    suspend fun listConfigs(): List<CloudConfig>
    suspend fun deleteConfig(shareCode: String): Boolean

    fun buildAlarmConfigJson(groupName: String, alarmsJson: org.json.JSONArray): String
    fun buildCheckInConfigJson(groupName: String, tasksJson: org.json.JSONArray): String
    fun parseCheckInConfig(jsonString: String): Pair<String, org.json.JSONArray>?

    data class CloudConfig(
        val shareCode: String,
        val createdAt: Long,
        val downloadCount: Int,
        val preview: String,
        val type: ShareDataType,
        val isEnabled: Boolean = true
    )

    sealed class ConnectionStatus {
        data object Checking : ConnectionStatus()
        data object Connected : ConnectionStatus()
        data class Error(val message: String) : ConnectionStatus()
        data object NotConfigured : ConnectionStatus()
    }
}

// ==================== 配置键 ====================

object CloudConfigKeys {
    const val PREFS_NAME = "cloud_config"
    const val KEY_SERVICE = "cloud_service" // "firebase" | "supabase"
    
    // SharedPreferences 键名
    const val PREF_SUPABASE_URL = "supabase_url"
    const val PREF_SUPABASE_ANON_KEY = "supabase_anon_key"
    const val PREF_FIREBASE_PROJECT_ID = "firebase_project_id"
    const val PREF_FIREBASE_API_KEY = "firebase_api_key"

    // 默认内置凭据（从 BuildConfig 读取，不再硬编码）
    val DEFAULT_SUPABASE_URL: String get() = BuildConfig.SUPABASE_URL
    val DEFAULT_SUPABASE_ANON_KEY: String get() = BuildConfig.SUPABASE_ANON_KEY
    val DEFAULT_FIREBASE_PROJECT_ID: String get() = BuildConfig.FIREBASE_PROJECT_ID
    val DEFAULT_FIREBASE_API_KEY: String get() = BuildConfig.FIREBASE_API_KEY
}

// ==================== SharedPreferences 帮助 ====================

fun Context.getCloudPrefs(): SharedPreferences = getSharedPreferences(CloudConfigKeys.PREFS_NAME, Context.MODE_PRIVATE)

fun Context.getSelectedService(): String {
    return getCloudPrefs().getString(CloudConfigKeys.KEY_SERVICE, "firebase") ?: "firebase"
}

fun Context.selectService(service: String) {
    getCloudPrefs().edit().putString(CloudConfigKeys.KEY_SERVICE, service).apply()
}

fun Context.getSupabaseUrl(): String {
    return getCloudPrefs().getString(CloudConfigKeys.PREF_SUPABASE_URL, "") ?: ""
}

fun Context.getSupabaseAnonKey(): String {
    return getCloudPrefs().getString(CloudConfigKeys.PREF_SUPABASE_ANON_KEY, "") ?: ""
}

fun Context.setSupabaseCredentials(url: String, anonKey: String) {
    getCloudPrefs().edit()
        .putString(CloudConfigKeys.PREF_SUPABASE_URL, url)
        .putString(CloudConfigKeys.PREF_SUPABASE_ANON_KEY, anonKey)
        .apply()
}

fun Context.getFirebaseProjectId(): String {
    return getCloudPrefs().getString(CloudConfigKeys.PREF_FIREBASE_PROJECT_ID, "") ?: ""
}

fun Context.getFirebaseApiKey(): String {
    return getCloudPrefs().getString(CloudConfigKeys.PREF_FIREBASE_API_KEY, "") ?: ""
}

fun Context.setFirebaseCredentials(projectId: String, apiKey: String) {
    getCloudPrefs().edit()
        .putString(CloudConfigKeys.PREF_FIREBASE_PROJECT_ID, projectId)
        .putString(CloudConfigKeys.PREF_FIREBASE_API_KEY, apiKey)
        .apply()
}

// ==================== 工厂 ====================

fun getService(context: Context): CloudService {
    return when (context.getSelectedService()) {
        "supabase" -> SupabaseShareService(context)
        else -> FirebaseShareService(context)
    }
}
