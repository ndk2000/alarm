package com.example.tts

import android.util.Log
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import kotlin.coroutines.suspendCoroutine

/**
 * 微软 Azure TTS 客户端
 *
 * 使用 Microsoft 的认知服务语音合成 REST API。
 * 支持多种语言和发音人，中文推荐使用 zh-CN-XiaoxiaoNeural（晓晓，自然女声）。
 *
 * 免费额度：每月 50 万字符（F0 层）
 * 注册地址：https://portal.azure.com → 创建语音服务
 */
class MicrosoftTtsClient {
    private val TAG = "MicrosoftTtsClient"
    private val ACCESS_TOKEN_URL = "https://%s.api.cognitive.microsoft.com/sts/v1.0/issuetoken"
    private val TTS_URL = "https://%s.tts.speech.microsoft.com/cognitiveservices/v1"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    private var cachedToken: String = ""
    private var tokenExpireTime: Long = 0L

    /**
     * 获取访问令牌（自动缓存，过期前自动刷新）
     */
    suspend fun getAccessToken(subscriptionKey: String, region: String): Result<String> {
        if (cachedToken.isNotEmpty() && System.currentTimeMillis() < tokenExpireTime) {
            return Result.success(cachedToken)
        }

        return suspendCoroutine { cont ->
            val request = Request.Builder()
                .url(ACCESS_TOKEN_URL.format(region))
                .header("Ocp-Apim-Subscription-Key", subscriptionKey)
                .post(RequestBody.create(null, ""))
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
                            if (body.isNotEmpty() && !body.contains("error")) {
                                cachedToken = body
                                tokenExpireTime = System.currentTimeMillis() + 540_000L // 9 分钟（实际 10 分钟）
                                Log.d(TAG, "获取 token 成功，${body.length} 字节")
                                cont.resume(Result.success(body))
                            } else {
                                Log.e(TAG, "获取 token 失败: $body")
                                cont.resume(Result.failure(Exception("获取 token 失败: $body")))
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
     * @param text 要合成的文本（中文）
     * @param token 有效的 access token
     * @param region Azure 区域（如 eastasia、chinanorth2、japaneast）
     * @param voiceName 发音人名称，如 zh-CN-XiaoxiaoNeural（晓晓）、zh-CN-YunyangNeural（云扬）
     * @return Result 包含 MP3 音频的字节数组
     */
    suspend fun synthesize(
        text: String,
        token: String,
        region: String = "eastasia",
        voiceName: String = "zh-CN-XiaoxiaoNeural"
    ): Result<ByteArray> {
        return suspendCoroutine { cont ->
            val ssml = buildSsml(text, voiceName)
            val requestBody = RequestBody.create("application/ssml+xml".toMediaTypeOrNull(), ssml)

            val request = Request.Builder()
                .url(TTS_URL.format(region))
                .header("Authorization", "Bearer $token")
                .header("Content-Type", "application/ssml+xml")
                .header("X-Microsoft-OutputFormat", "audio-16khz-128kbitrate-mono-mp3")
                .post(requestBody)
                .build()

            client.newCall(request).enqueue(object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    Log.e(TAG, "语音合成网络失败: ${e.message}")
                    cont.resume(Result.failure(e))
                }

                override fun onResponse(call: Call, response: Response) {
                    response.use {
                        try {
                            val audioData = it.body?.bytes()
                            if (audioData != null && audioData.isNotEmpty()) {
                                Log.d(TAG, "合成成功，音频大小: ${audioData.size} 字节")
                                cont.resume(Result.success(audioData))
                            } else {
                                val errBody = it.body?.string() ?: "空响应"
                                Log.e(TAG, "合成失败: $errBody")
                                cont.resume(Result.failure(Exception("合成失败: $errBody")))
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

    private fun buildSsml(text: String, voiceName: String): String {
        return """<speak version='1.0' xml:lang='zh-CN'>
    <voice name='$voiceName'>
        $text
    </voice>
</speak>"""
    }

    fun getVoiceList(): List<Pair<String, String>> {
        return listOf(
            "zh-CN-XiaoxiaoNeural" to "晓晓（温柔女声）",
            "zh-CN-YunxiNeural" to "云希（阳光男声）",
            "zh-CN-YunyangNeural" to "云扬（新闻男声）",
            "zh-CN-XiaochenNeural" to "晓辰（活泼女声）",
            "zh-CN-XiaohanNeural" to "晓涵（温暖女声）",
            "zh-CN-XiaomengNeural" to "晓梦（亲切女声）",
            "zh-CN-XiaomoNeural" to "晓墨（知性女声）",
            "zh-CN-XiaoqiuNeural" to "晓秋（清脆女声）",
            "zh-CN-XiaoruiNeural" to "晓睿（成熟女声）",
            "zh-CN-XiaoshuangNeural" to "晓双（元气女声）"
        )
    }
}
