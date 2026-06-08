package com.example.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import java.net.HttpURLConnection
import java.net.URL

/**
 * 网络连接状态检测
 */
object NetworkStatus {
    
    /**
     * 网络连接状态
     */
    sealed class Status {
        object Connected : Status() {
            override fun toString(): String = "已连接网络"
        }
        object NoNetwork : Status() {
            override fun toString(): String = "无网络连接"
        }
        object WiFiRequired : Status() {
            override fun toString(): String = "需要WiFi连接"
        }
        object MobileData : Status() {
            override fun toString(): String = "移动数据连接（建议使用WiFi）"
        }
    }
    
    /**
     * 检测网络状态
     */
    fun checkNetworkStatus(context: Context): NetworkResult {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        
        // 检查是否有网络权限
        if (!hasNetworkPermission(context)) {
            return NetworkResult(
                status = Status.NoNetwork,
                reason = "未获取网络状态权限",
                canRetry = false
            )
        }
        
        // Android 10+ 使用新API
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val network = connectivityManager.activeNetwork
            if (network == null) {
                return NetworkResult(
                    status = Status.NoNetwork,
                    reason = "当前无活动网络连接",
                    canRetry = true
                )
            }
            
            val capabilities = connectivityManager.getNetworkCapabilities(network)
            if (capabilities == null) {
                return NetworkResult(
                    status = Status.NoNetwork,
                    reason = "无法获取网络能力信息",
                    canRetry = true
                )
            }
            
            // 检查WiFi连接
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                var wifiInfoText = "WiFi已连接"
                var isWiFi = true
                var signalStrength = 0
                try {
                    val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
                    val wifiInfo = wifiManager.connectionInfo
                    isWiFi = true
                    signalStrength = wifiInfo.rssi
                    wifiInfoText = "WiFi已连接 (${wifiInfo.ssid?.replace(Regex("\""), "") ?: "未知网络"}, 信号强度: ${wifiInfo.rssi}dBm)"
                } catch (e: SecurityException) {
                    wifiInfoText = "WiFi已连接（WiFi详情权限被拒绝）"
                } catch (e: Exception) {
                    wifiInfoText = "WiFi已连接"
                }
                
                return NetworkResult(
                    status = Status.Connected,
                    reason = wifiInfoText,
                    canRetry = false,
                    isWiFi = isWiFi,
                    signalStrength = signalStrength
                )
            }
            
            // 检查移动数据
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
                return NetworkResult(
                    status = Status.MobileData,
                    reason = "当前使用移动数据，大文件传输建议使用WiFi",
                    canRetry = true,
                    isWiFi = false
                )
            }
            
            // 其他网络类型（Vpn、Ethernet等）
            val transportList = mutableListOf<String>()
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transportList.add("以太网")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) transportList.add("VPN")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transportList.add("蓝牙")
            
            if (transportList.isNotEmpty()) {
                return NetworkResult(
                    status = Status.Connected,
                    reason = "通过 ${transportList.joinToString("、")} 连接",
                    canRetry = false,
                    isWiFi = false
                )
            }
            
            return NetworkResult(
                status = Status.NoNetwork,
                reason = "检测到网络但无法访问互联网",
                canRetry = true
            )
        } else {
            // Android 5.0-9.0 使用旧API
            val networkInfo = connectivityManager.activeNetworkInfo
            if (networkInfo == null || !networkInfo.isConnected) {
                return NetworkResult(
                    status = Status.NoNetwork,
                    reason = "无网络连接",
                    canRetry = true
                )
            }
            
            return if (networkInfo.type == ConnectivityManager.TYPE_WIFI) {
                NetworkResult(
                    status = Status.Connected,
                    reason = "WiFi已连接",
                    canRetry = false,
                    isWiFi = true
                )
            } else if (networkInfo.type == ConnectivityManager.TYPE_MOBILE) {
                NetworkResult(
                    status = Status.MobileData,
                    reason = "当前使用移动数据",
                    canRetry = true,
                    isWiFi = false
                )
            } else {
                NetworkResult(
                    status = Status.Connected,
                    reason = "通过其他网络类型连接",
                    canRetry = false
                )
            }
        }
    }
    
    /**
     * 检查是否有网络状态权限
     */
    private fun hasNetworkPermission(context: Context): Boolean {
        try {
            val permission = android.Manifest.permission.ACCESS_NETWORK_STATE
            val checkResult = android.content.pm.PackageManager.PERMISSION_GRANTED
            return true
        } catch (e: Exception) {
            return false
        }
    }
    
    /**
     * 网络检测结果
     */
    data class NetworkResult(
        val status: Status,
        val reason: String,
        val canRetry: Boolean,
        val isWiFi: Boolean = false,
        val signalStrength: Int = 0
    ) {
        fun getStatusColor(): Int {
            return when (status) {
                Status.Connected -> 0xFF2E7D32.toInt()
                Status.NoNetwork -> 0xFFC62828.toInt()
                Status.WiFiRequired -> 0xFFF57F17.toInt()
                Status.MobileData -> 0xFF0288D1.toInt()
            }
        }
        
        fun getStatusIcon(): String {
            return when (status) {
                Status.Connected -> "✓"
                Status.NoNetwork -> "✗"
                Status.WiFiRequired -> "!"
                Status.MobileData -> "?"
            }
        }
        
        fun getWiFiSignalPercent(): Int {
            if (!isWiFi) return 0
            return maxOf(0, minOf(100, (100 + signalStrength)))
        }
    }
    
    // ==================== 云端服务器连接检测 ====================
    
    /**
     * 检测云端服务器连接状态
     * 检测 Firebase Firestore API 是否可访问
     */
    fun checkCloudServerStatus(context: Context): CloudServerResult {
        return try {
            val networkResult = checkNetworkStatus(context)
            if (networkResult.status == Status.NoNetwork) {
                return CloudServerResult(
                    status = CloudServerStatus.Disconnected,
                    message = "无网络连接，无法访问云端",
                    latencyMs = 0
                )
            }
            
            val startTime = System.currentTimeMillis()
            val urls = listOf(
                "https://firestore.googleapis.com/v1/projects/",
                "https://firestore.googleapis.com"
            )
            
            var lastError: Exception? = null
            for (url in urls) {
                try {
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.requestMethod = "HEAD"
                    conn.connectTimeout = 5000
                    conn.readTimeout = 8000
                    
                    val responseCode = conn.responseCode
                    val latency = System.currentTimeMillis() - startTime
                    
                    if (responseCode == 200 || responseCode == 401 || responseCode == 403 || responseCode == 404) {
                        return CloudServerResult(
                            status = CloudServerStatus.Connected,
                            message = "云端服务器已连接 (延迟: ${latency}ms)",
                            latencyMs = latency.toInt()
                        )
                    }
                } catch (e: Exception) {
                    lastError = e
                    continue
                }
            }
            
            val latency = System.currentTimeMillis() - startTime
            val errorDetail = if (lastError != null) {
                val msg = lastError.message?.trim()
                when {
                    msg.isNullOrBlank() -> lastError::class.simpleName ?: "未知异常"
                    msg.length > 80 -> msg.take(80) + "..."
                    else -> msg
                }
            } else {
                "服务器返回异常响应码"
            }
            
            return CloudServerResult(
                status = CloudServerStatus.Disconnected,
                message = "云端连接失败: $errorDetail",
                latencyMs = latency.toInt(),
                canRetry = true
            )
        } catch (e: Exception) {
            CloudServerResult(
                status = CloudServerStatus.Disconnected,
                message = "云端检测异常: ${e.message?.take(50) ?: "未知错误"}",
                canRetry = true
            )
        }
    }
    
    sealed class CloudServerStatus {
        object Connected : CloudServerStatus()
        object Disconnected : CloudServerStatus()
    }
    
    data class CloudServerResult(
        val status: CloudServerStatus,
        val message: String,
        val latencyMs: Int = 0,
        val canRetry: Boolean = false
    ) {
        fun getStatusColor(): Int {
            return when (status) {
                CloudServerStatus.Connected -> 0xFF2E7D32.toInt()
                CloudServerStatus.Disconnected -> 0xFFC62828.toInt()
            }
        }
        
        fun getStatusIcon(): String {
            return when (status) {
                CloudServerStatus.Connected -> "☁"
                CloudServerStatus.Disconnected -> "✗"
            }
        }
    }
}
