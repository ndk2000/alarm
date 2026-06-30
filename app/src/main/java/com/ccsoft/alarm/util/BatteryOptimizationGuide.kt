package com.ccsoft.alarm.util

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.PowerManager
import android.provider.Settings

/**
 * Utilities for guiding users to whitelist the app against OEM battery optimization.
 */
object BatteryOptimizationGuide {

    /**
     * Check whether the app has already been exempted from battery optimization.
     */
    fun isBatteryOptimizationDisabled(context: Context): Boolean {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return false
        return pm.isIgnoringBatteryOptimizations(context.packageName)
    }

    /**
     * Open the system page where the user can disable battery optimization for this app,
     * or fall back to the general battery optimization settings page.
     */
    fun openBatteryOptimizationSettings(context: Context) {
        try {
            // Direct intent to ignore battery optimizations for this app
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            // Fallback: open the general battery optimization page
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e2: Exception) {
                // Last resort: open app settings
                openAppSettings(context)
            }
        }
    }

    /**
     * Open the system's app-info page for this app.
     */
    fun openAppSettings(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = android.net.Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            // Give up
        }
    }

    /**
     * Try to open the autostart permission page specific to the device manufacturer.
     * Returns true if a manufacturer-specific page was attempted, false otherwise.
     */
    fun openAutostartSettings(context: Context): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return try {
            when {
                // Xiaomi / MIUI
                manufacturer.contains("xiaomi") -> {
                    val intent = Intent().apply {
                        action = "miui.intent.action.OP_AUTO_START"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        `package` = "com.miui.securitycenter"
                    }
                    context.startActivity(intent)
                    true
                }
                // Huawei / EMUI
                manufacturer.contains("huawei") || manufacturer.contains("honor") -> {
                    val intent = Intent().apply {
                        action = "huawei.intent.action.HSM_BOOT_APP_MANAGER"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                // OPPO / ColorOS
                manufacturer.contains("oppo") -> {
                    val intent = Intent().apply {
                        action = "oppo.intent.action.MANAGE_STARTUP_APPS"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                // VIVO / FuntouchOS
                manufacturer.contains("vivo") -> {
                    val intent = Intent().apply {
                        action = "com.vivo.permissionmanager.BACKGROUND_MANAGER"
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    true
                }
                // Samsung / One UI
                manufacturer.contains("samsung") -> {
                    openAppSettings(context)
                    false
                }
                // Others — can't be sure, just open app settings
                else -> {
                    openAppSettings(context)
                    false
                }
            }
        } catch (_: Exception) {
            // Fallback
            openAppSettings(context)
            false
        }
    }

    /**
     * Check whether the app likely has autostart permission enabled.
     * Uses battery optimization status as a reasonable heuristic since
     * there is no universal Android API to query autostart status.
     * TODO: Add manufacturer-specific autostart checks (Xiaomi miui.autostart, etc.)
     */
    fun isAutostartEnabled(context: Context): Boolean {
        // Battery optimization being disabled is a strong indicator that
        // the user has whitelisted the app, which usually includes autostart.
        return isBatteryOptimizationDisabled(context)
    }

    /**
     * Returns a brief setup tip specific to the device manufacturer.
     */
    fun getManufacturerTip(): String {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return when {
            manufacturer.contains("xiaomi") ->
                "小米: 设置 → 应用设置 → 应用管理 → 本应用 → 省电策略 → 无限制\n" +
                "并开启「自启动」权限"
            manufacturer.contains("huawei") || manufacturer.contains("honor") ->
                "华为: 手机管家 → 应用启动管理 → 本应用 → 关闭「自动管理」→ 手动开启" +
                "\n允许自启动、关联启动、后台活动"
            manufacturer.contains("oppo") ->
                "OPPO: 设置 → 电池 → 耗电管理 → 本应用 → 关闭「自动管理」→ 手动允许所有" +
                "\n并开启「自启动」"
            manufacturer.contains("vivo") ->
                "vivo: 设置 → 电池 → 后台耗电管理 → 本应用 → 允许高耗电" +
                "\ni管家 → 应用管理 → 自启动 → 开启本应用"
            manufacturer.contains("samsung") ->
                "三星: 设置 → 电池 → 后台使用限制 → 未使用的应用 → 关闭" +
                "\n设置 → 应用程序 → 本应用 → 电池 → 不受限制"
            manufacturer.contains("meizu") ->
                "魅族: 设置 → 应用管理 → 本应用 → 权限管理 → 后台管理 → 保持后台运行"
            else ->
                "设置 → 应用 → 本应用 → 电池 → 不受限制（或无限制）\n" +
                "并确保「自启动」权限已开启"
        }
    }
}
