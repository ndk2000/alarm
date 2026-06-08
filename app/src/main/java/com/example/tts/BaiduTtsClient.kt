package com.example.tts

import android.util.Log
import okhttp3.*
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 百度语音合成 REST API 客户端
 * 
 * 使用百度在线语音合成服务，支持多种中文发音人。
 * 免费额度：每日 500,000 次调用。
 * 
 * 使用前需要在 https://console.bce.baidu.com/ 注册应用获取 API Key 和 Secret Key。
 */
class BaiduTtsClient {
    private val TAG = "BaiduTtsClient"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    private var accessToken: String = ""
    private var tokenExpireTime: Long = 0L

    /**
     * 获取百度 API 访问令牌（自动缓存，过期前 1 分钟刷新）
     */
    suspend fun getAccessToken(apiKey: String, secretKey: String): Result<String> {
        if (accessToken.isNotEmpty() && System.currentTimeMillis() < tokenExpireTime) {
            Log.d(TAG, "使用缓存的 accessToken")
            return Result.success(accessToken)
        }

        Log.d(TAG, "请求新的 accessToken")

        return suspendCoroutine { cont ->
            val formBody = FormBody.Builder()
                .add("grant_type", "client_credentials")
                .add("client_id", apiKey)
                .add("client_secret", secretKey)
                .build()

            val request = Request.Builder()
                .url("https://aip.baidubce.com/oauth/2.0/token")
                .post(formBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "获取 token 网络失败: ${e.message}")
                    cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val body = it.body?.string() ?: ""
                            val json = JSONObject(body)
                            if (json.has("access_token")) {
                                accessToken = json.getString("access_token")
                                val expiresIn = json.optLong("expires_in", 2592000L)
                                // 提前 60 秒过期以避免边界情况
                                tokenExpireTime = System.currentTimeMillis() + (expiresIn * 1000) - 60000
                                Log.d(TAG, "获取 token 成功，有效期 ${expiresIn}s")
                                cont.resume(Result.success(accessToken))
                            } else {
                                val errMsg = json.optString("error_description", body)
                                Log.e(TAG, "获取 token 失败: $errMsg")
                                cont.resume(Result.failure(Exception("获取 token 失败: $errMsg")))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析 token 响应异常: ${e.message}")
                            cont.resume(Result.failure(e))
                        }
                    }
                }
            })
        }
    }

    /**
     * 语音合成
     *
     * @param text 要合成的文本（中文，建议单次不超过 1024 字节）
     * @param token 有效的 access token
     * @param voice 发音人 ID: 0=标准女声, 1=标准男声, 3=度逍遥, 4=度丫丫, 5003=度小鹿, 5118=度小媛
     * @param speed 语速 0-15（默认 5）
     * @param pitch 音调 0-15（默认 5）
     * @param volume 音量 0-15（默认 5）
     * @return Result 包含 MP3 音频的字节数组
     */
    suspend fun synthesize(
        text: String,
        token: String,
        voice: Int = 0,
        speed: Int = 5,
        pitch: Int = 5,
        volume: Int = 5
    ): Result<ByteArray> {
        return suspendCoroutine { cont ->
            val formBody = FormBody.Builder()
                .add("tex", text)
                .add("tok", token)
                .add("cuid", "alarm_android_app")
                .add("ctp", "1")
                .add("lan", "zh")
                .add("spd", speed.toString())
                .add("pit", pitch.toString())
                .add("vol", volume.toString())
                .add("per", voice.toString())
                .add("aue", "6") // 6=MP3, 3=WAV
                .build()

            val request = Request.Builder()
                .url("https://tsn.baidu.com/text2audio")
                .post(formBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "语音合成网络失败: ${e.message}")
                    cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val contentType = it.header("Content-Type", "")
                            // 成功时返回音频数据（Content-Type: audio/mp3 等）
                            if (contentType!!.contains("audio") || contentType.contains("octet-stream")) {
                                val audioData = it.body?.bytes()
                                if (audioData != null && audioData.isNotEmpty()) {
                                    Log.d(TAG, "合成成功，音频大小: ${audioData.size} 字节")
                                    cont.resume(Result.success(audioData))
                                } else {
                                    cont.resume(Result.failure(Exception("音频数据为空")))
                                }
                            } else {
                                // 失败时返回 JSON 格式错误信息
                                val errorBody = it.body?.string() ?: "未知错误"
                                Log.e(TAG, "合成失败: $errorBody")
                                cont.resume(Result.failure(Exception("合成失败: $errorBody")))
                            }
                        } catch (e: Exception) {
                            Log.e(TAG, "解析合成响应异常: ${e.message}")
                            cont.resume(Result.failure(e))
                        }
                    }
                }
            })
        }
    }

    /**
     * 获取支持的发音人列表
     */
    fun getVoiceList(): List<Pair<Int, String>> {
        return listOf(
            0 to "标准女声",
            1 to "标准男声",
            3 to "度逍遥（情感男声）",
            4 to "度丫丫（童声）",
            5003 to "度小鹿（温柔女声）",
            5118 to "度小媛（甜美女声）"
        )
    }
}
