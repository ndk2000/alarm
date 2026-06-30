package com.ccsoft.alarm.cloud

import android.content.Context
import android.util.Log
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.Auth
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

object SupabaseManager {
    private const val TAG = "SupabaseManager"
    private var client: SupabaseClient? = null
    
    private const val INTERNAL_DOMAIN = "@droidcloud.live"

    private val _currentUser = MutableStateFlow<String?>(null)
    val currentUser = _currentUser.asStateFlow()

    fun init(context: Context) {
        if (client != null) return

        val url = context.getSupabaseUrl().ifBlank { CloudConfigKeys.DEFAULT_SUPABASE_URL }
        val key = context.getSupabaseAnonKey().ifBlank { CloudConfigKeys.DEFAULT_SUPABASE_ANON_KEY }

        try {
            client = createSupabaseClient(url, key) {
                install(Auth)
                install(Postgrest)
            }
            Log.d(TAG, "Supabase Client Initialized")
            
            // 开启协程监听会话状态变化
            val scope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main + kotlinx.coroutines.Job())
            scope.launch {
                client?.auth?.sessionStatus?.collect { status ->
                    Log.d(TAG, "Session Status Changed: $status")
                    when (status) {
                        is io.github.jan.supabase.auth.status.SessionStatus.Authenticated -> {
                            val email = status.session.user?.email
                            email?.let { 
                                val username = if (it.endsWith(INTERNAL_DOMAIN)) {
                                    it.substringBefore(INTERNAL_DOMAIN)
                                } else it
                                _currentUser.value = username
                                Log.d(TAG, "Authenticated as: $username")
                            }
                        }
                        else -> {
                            if (_currentUser.value != null) {
                                _currentUser.value = null
                                Log.d(TAG, "Session cleared")
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Initialization failed", e)
        }
    }

    /** 内部转换：将纯用户名转为标准邮箱格式，确保服务器接受 */
    private fun getUserEmail(username: String): String {
        val clean = username.trim().lowercase()
        return if (clean.contains("@")) clean else "${clean}${INTERNAL_DOMAIN}"
    }

    /** 内部转换：如果密码少于6位，自动填充前缀，确保满足服务器最小长度要求 */
    private fun getInternalPass(pass: String): String {
        return if (pass.length < 6) "APP_SAFE_${pass}" else pass
    }

    suspend fun signUp(username: String, pass: String): Result<Boolean> {
        if (client == null) return Result.failure(Exception("客户端未就绪"))
        return try {
            val emailStr = getUserEmail(username)
            val passStr = getInternalPass(pass)
            
            client?.auth?.signUpWith(Email) {
                email = emailStr
                password = passStr
            }
            // 自动登录
            signIn(username, pass)
        } catch (e: Exception) {
            Log.e(TAG, "Sign Up Error", e)
            Result.failure(Exception(translateError(e)))
        }
    }

    suspend fun signIn(username: String, pass: String): Result<Boolean> {
        if (client == null) return Result.failure(Exception("客户端未就绪"))
        return try {
            val emailStr = getUserEmail(username)
            val passStr = getInternalPass(pass)

            client?.auth?.signInWith(Email) {
                email = emailStr
                password = passStr
            }
            _currentUser.value = username.trim()
            Result.success(true)
        } catch (e: Exception) {
            Log.e(TAG, "Sign In Error", e)
            Result.failure(Exception(translateError(e)))
        }
    }

    private fun translateError(e: Exception): String {
        val msg = e.message ?: ""
        return when {
            msg.contains("User already registered", ignoreCase = true) -> "该用户名已被注册，请直接登录或换一个"
            msg.contains("Invalid login credentials", ignoreCase = true) -> "用户名或密码错误"
            msg.contains("Email not confirmed", ignoreCase = true) -> "邮箱尚未验证，请查收邮件"
            msg.contains("Password should be at least", ignoreCase = true) -> "密码长度不足"
            msg.contains("rate limit", ignoreCase = true) -> "操作过于频繁，请稍后再试"
            msg.contains("network", ignoreCase = true) -> "网络连接失败，请检查网络"
            msg.contains("invalid claim", ignoreCase = true) -> "登录已过期，请重新登录"
            else -> "操作失败: $msg"
        }
    }

    suspend fun signOut() {
        try {
            client?.auth?.signOut()
            _currentUser.value = null
        } catch (e: Exception) {
            Log.e(TAG, "Sign Out Error", e)
        }
    }
}
