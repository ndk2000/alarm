package com.ccsoft.alarm.alarm

import android.util.Log
import okhttp3.*
import okio.ByteString
import java.io.ByteArrayOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.*
import java.util.concurrent.TimeUnit

/**
 * Edge-TTS Android 原生客户端 (参考 tts-file-gen 实现)
 */
class EdgeTtsClient private constructor(
    private val text: String,
    private val voice: String,
    private val rate: String,
    private val volume: String,
    private val pitch: String,
    private val outputFormat: String,
    private val boundary: BoundaryType,
    private val listener: EdgeTtsListener?
) {
    companion object {
        private const val TAG = "EdgeTts"
        private const val TRUSTED_CLIENT_TOKEN = "6A5AA1D4EAFF4E9FB37E23D68491D6F4"
        private const val CHROMIUM_FULL_VERSION = "143.0.3650.75"
        private const val CHROMIUM_MAJOR = "143"
        private const val SEC_MS_GEC_VERSION = "1-$CHROMIUM_FULL_VERSION"
        private const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$CHROMIUM_MAJOR.0.0.0 Safari/537.36 Edg/$CHROMIUM_MAJOR.0.0.0"
        private const val ORIGIN = "chrome-extension://jdiccldimpdaibmpdkjnbmckianbfold"
        private const val WSS_BASE =
            "wss://speech.platform.bing.com/consumer/speech/synthesize/readaloud/edge/v1"
        private const val WIN_EPOCH = 11644473600.0
        private const val S_TO_NS = 1e9

        private val client = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .pingInterval(10, TimeUnit.SECONDS)
            .build()
    }

    enum class BoundaryType { WORD, SENTENCE }

    interface EdgeTtsListener {
        fun onAudioData(data: ByteArray) {}
        fun onBoundary(boundary: BoundaryInfo) {}
        fun onComplete(success: Boolean, error: String?) {}
    }

    data class BoundaryInfo(
        val type: String,
        val offset: Long,
        val duration: Long,
        val text: String
    )

    class Builder {
        private var text: String = ""
        private var voice: String = "zh-CN-XiaoxiaoNeural"
        private var rate: String = "+0%"
        private var volume: String = "+0%"
        private var pitch: String = "+0Hz"
        private var outputFormat: String = "audio-24khz-48kbitrate-mono-mp3"
        private var boundary: BoundaryType = BoundaryType.SENTENCE
        private var listener: EdgeTtsListener? = null

        fun text(t: String) = apply { text = t }
        fun voice(v: String) = apply { voice = v }
        fun rate(r: String) = apply { rate = r }
        fun volume(v: String) = apply { volume = v }
        fun pitch(p: String) = apply { pitch = p }
        fun format(f: String) = apply { outputFormat = f }
        fun boundary(b: BoundaryType) = apply { boundary = b }
        fun listener(l: EdgeTtsListener?) = apply { listener = l }
        fun build() = EdgeTtsClient(text, voice, rate, volume, pitch, outputFormat, boundary, listener)
    }

    private var cancelled = false
    private var webSocket: WebSocket? = null

    fun speak() {
        val wssUrl = buildWssUrl()
        Log.d(TAG, "连接 WSS: $wssUrl")

        val request = Request.Builder()
            .url(wssUrl)
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Cookie", "muid=${generateMuid()};")
            .build()

        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            private val audioBuffer = ByteArrayOutputStream()
            private var audioReceived = false

            override fun onOpen(ws: WebSocket, response: Response) {
                if (cancelled) { ws.close(1000, "cancelled"); return }
                
                val timestamp = getTimestamp()
                val configMsg = "X-Timestamp:$timestamp\r\n" +
                        "Content-Type:application/json; charset=utf-8\r\n" +
                        "Path:speech.config\r\n\r\n" +
                        "{\"context\":{\"synthesis\":{\"audio\":{\"metadataoptions\":{" +
                        "\"sentenceBoundaryEnabled\":\"${boundary == BoundaryType.SENTENCE}\"," +
                        "\"wordBoundaryEnabled\":\"${boundary == BoundaryType.WORD}\"}," +
                        "\"outputFormat\":\"$outputFormat\"}}}}"
                ws.send(configMsg)

                val requestId = UUID.randomUUID().toString().replace("-", "")
                val ssml = "X-RequestId:$requestId\r\n" +
                        "Content-Type:application/ssml+xml\r\n" +
                        "X-Timestamp:$timestamp\r\n" +
                        "Path:ssml\r\n\r\n" +
                        "<speak version='1.0' xmlns='http://www.w3.org/2001/10/synthesis' xml:lang='zh-CN'>" +
                        "<voice name='$voice'>" +
                        "<prosody pitch='$pitch' rate='$rate' volume='$volume'>" +
                        escapeXml(text) +
                        "</prosody></voice></speak>"
                ws.send(ssml)
            }

            override fun onMessage(ws: WebSocket, text: String) {
                if (cancelled) return
                if (text.contains("Path:turn.end")) {
                    ws.close(1000, "Done")
                }
            }

            override fun onMessage(ws: WebSocket, bytes: ByteString) {
                if (cancelled) return
                val data = bytes.toByteArray()
                if (data.size < 4) return
                
                val headerLength = ((data[0].toInt() and 0xFF) shl 8) or (data[1].toInt() and 0xFF)
                val audioStart = 2 + headerLength
                if (audioStart < data.size) {
                    val audioData = data.copyOfRange(audioStart, data.size)
                    audioReceived = true
                    audioBuffer.write(audioData)
                    listener?.onAudioData(audioData)
                }
            }

            override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                ws.close(1000, null)
            }

            override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                if (!cancelled) {
                    listener?.onComplete(audioReceived && audioBuffer.size() > 0, null)
                }
            }

            override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                if (cancelled) return
                Log.e(TAG, "WebSocket 失败: ${t.message}, Response=$response", t)
                listener?.onComplete(false, t.message ?: "未知网络错误")
            }
        })
    }

    private fun buildWssUrl(): String {
        val gec = generateSecMsGec()
        val connectionId = UUID.randomUUID().toString().replace("-", "")
        return "$WSS_BASE?TrustedClientToken=$TRUSTED_CLIENT_TOKEN&Sec-MS-GEC=$gec&Sec-MS-GEC-Version=$SEC_MS_GEC_VERSION&ConnectionId=$connectionId"
    }

    private fun generateSecMsGec(): String {
        var ticks = System.currentTimeMillis() / 1000.0
        ticks += WIN_EPOCH
        ticks -= ticks % 300
        ticks *= S_TO_NS / 100
        val strToHash = String.format("%.0f", ticks) + TRUSTED_CLIENT_TOKEN
        val digest = MessageDigest.getInstance("SHA-256").digest(strToHash.toByteArray(Charsets.US_ASCII))
        return digest.joinToString("") { "%02X".format(it) }
    }

    private fun generateMuid(): String {
        val bytes = ByteArray(16)
        java.security.SecureRandom().nextBytes(bytes)
        return bytes.joinToString("") { "%02X".format(it) }
    }

    private fun getTimestamp(): String {
        val sdf = SimpleDateFormat("EEE MMM dd yyyy HH:mm:ss 'GMT+0000 (Coordinated Universal Time)'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return sdf.format(Date())
    }

    private fun escapeXml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    fun cancel() {
        cancelled = true
        webSocket?.close(1000, "User cancelled")
    }
}
