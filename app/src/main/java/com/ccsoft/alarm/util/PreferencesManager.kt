package com.ccsoft.alarm.util

import android.content.Context
import android.content.SharedPreferences

/**
 * 统一管理所有 SharedPreferences 读写操作。
 * 所有键名集中定义，避免散落在代码中导致重复和不一致。
 */
class PreferencesManager(context: Context) {

    // ==================== SharedPreferences 文件 ====================

    /** 主应用设置 */
    val appSettings: SharedPreferences =
        context.getSharedPreferences(PREFS_APP_SETTINGS, Context.MODE_PRIVATE)

    /** 报时偏好 */
    val chimePrefs: SharedPreferences =
        context.getSharedPreferences(PREFS_CHIME, Context.MODE_PRIVATE)

    // ==================== 键名常量 ====================

    companion object {
        const val PREFS_APP_SETTINGS = "app_settings"
        const val PREFS_CHIME = "chime_prefs"

        // ---- 状态栏/悬浮窗 ----
        const val KEY_STATUS_BAR_X = "status_bar_x"
        const val KEY_STATUS_BAR_Y = "status_bar_y"
        const val KEY_SB_TEXT_COLOR = "sb_text_color"
        const val KEY_SB_BG_COLOR = "sb_bg_color"
        const val KEY_FLOAT_TEXT_COLOR = "float_text_color"
        const val KEY_FLOAT_BG_COLOR = "float_bg_color"
        const val KEY_WIDGET_TEXT_COLOR = "widget_text_color"
        const val KEY_WIDGET_BG_COLOR = "widget_bg_color"
        const val KEY_NEXT_ALARM_WIDGET_TIME_COLOR = "na_widget_time_color"
        const val KEY_NEXT_ALARM_WIDGET_COUNTDOWN_COLOR = "na_widget_countdown_color"
        const val KEY_NEXT_ALARM_WIDGET_LABEL_COLOR = "na_widget_label_color"
        const val KEY_TOPBAR_CLOCK_COLOR = "top_bar_clock_color"
        const val KEY_TOPBAR_CLOCK_BG_COLOR = "top_bar_clock_bg_color"
        const val KEY_SB_FONT_SIZE = "sb_font_size"
        const val KEY_FLOAT_FONT_SIZE = "float_font_size"
        const val KEY_NEXT_ALARM_WIDGET_TIME_SIZE = "na_widget_time_size"
        const val KEY_NEXT_ALARM_WIDGET_COUNTDOWN_SIZE = "na_widget_countdown_size"
        const val KEY_NEXT_ALARM_WIDGET_LABEL_SIZE = "na_widget_label_size"

        // ---- 开关 ----
        const val KEY_TOPBAR_CLOCK_ENABLED = "top_bar_clock_enabled"
        const val KEY_STATUS_BAR_CLOCK_ENABLED = "status_bar_clock_enabled"
        const val KEY_AUTO_UPDATE_ENABLED = "auto_update_enabled"
        const val KEY_HOURLY_CHIME_MASTER_ENABLED = "hourly_chime_master_enabled"
        const val KEY_FLOATING_TIMER_ENABLED = "floating_timer_enabled"
        const val KEY_SERVICE_STATUS_MONITOR_ENABLED = "service_status_monitor_enabled"

        // ---- 样式调节目标 ----
        const val KEY_DPAD_TARGET = "dpad_target"

        // ---- TTS ----
        const val KEY_TTS_ENGINE = "tts_engine"
        const val KEY_TTS_VOICE = "tts_voice"
        const val KEY_TTS_FORMAT = "tts_format"
        const val KEY_TTS_PITCH = "tts_pitch"
        const val KEY_TTS_RATE = "tts_rate"

        // ---- 通用 ----
        const val KEY_APP_THEME = "app_theme"
        const val KEY_APP_LANGUAGE = "app_language"
        const val KEY_DUPLICATE_OFFSET_HOURS = "duplicate_offset_hours"
        const val KEY_DUPLICATE_OFFSET_MINUTES = "duplicate_offset_minutes"

        // ---- 倒计时/计时器 ----
        const val KEY_TIMER_END_MILLIS = "timer_end_millis"
        const val KEY_COUNTDOWN_WARNING_SECONDS = "countdown_warning_seconds"
        const val KEY_COUNTDOWN_WARNING_SOUND_TYPE = "countdown_warning_sound_type"
        const val KEY_COUNTDOWN_WARNING_CUSTOM_PATH = "countdown_warning_custom_path"
        const val KEY_COUNTDOWN_WARNING_TTS_TEXT = "countdown_warning_tts_text"
        const val KEY_TIMER_FINISH_SOUND_TYPE = "timer_finish_sound_type"
        const val KEY_TIMER_FINISH_CUSTOM_PATH = "timer_finish_custom_path"
        const val KEY_TIMER_FINISH_TTS_TEXT = "timer_finish_tts_text"

        // ---- 路径 ----
        const val KEY_RECORDING_PATH = "recording_path"
        const val KEY_DATABASE_DIR_PATH = "database_dir_path"

        // ---- 报时 ----
        const val KEY_CHIME_STYLE = "chime_style"

        // ---- 全屏闹钟参数 ----
        // 日期
        const val KEY_FS_DATE_FONT_SIZE = "fs_date_font_size"
        const val KEY_FS_DATE_COLOR = "fs_date_color"
        const val KEY_FS_DATE_OFFSET_X = "fs_date_offset_x"
        const val KEY_FS_DATE_OFFSET_Y = "fs_date_offset_y"
        const val KEY_FS_DATE_ALIGNMENT = "fs_date_alignment"
        // 星期
        const val KEY_FS_WEEK_FONT_SIZE = "fs_week_font_size"
        const val KEY_FS_WEEK_COLOR = "fs_week_color"
        const val KEY_FS_WEEK_OFFSET_X = "fs_week_offset_x"
        const val KEY_FS_WEEK_OFFSET_Y = "fs_week_offset_y"
        const val KEY_FS_WEEK_ALIGNMENT = "fs_week_alignment"
        // 时间
        const val KEY_FS_TIME_FONT_SIZE = "fs_time_font_size"
        const val KEY_FS_TIME_COLOR = "fs_time_color"
        const val KEY_FS_TIME_OFFSET_X = "fs_time_offset_x"
        const val KEY_FS_TIME_OFFSET_Y = "fs_time_offset_y"
        const val KEY_FS_TIME_ALIGNMENT = "fs_time_alignment"
        // 闹钟时间
        const val KEY_FS_ALARM_TIME_FONT_SIZE = "fs_alarm_time_font_size"
        const val KEY_FS_ALARM_TIME_COLOR = "fs_alarm_time_color"
        const val KEY_FS_ALARM_TIME_OFFSET_X = "fs_alarm_time_offset_x"
        const val KEY_FS_ALARM_TIME_OFFSET_Y = "fs_alarm_time_offset_y"
        const val KEY_FS_ALARM_TIME_ALIGNMENT = "fs_alarm_time_alignment"
        // 闹钟标签
        const val KEY_FS_ALARM_LABEL_FONT_SIZE = "fs_alarm_label_font_size"
        const val KEY_FS_ALARM_LABEL_COLOR = "fs_alarm_label_color"
        const val KEY_FS_ALARM_LABEL_OFFSET_X = "fs_alarm_label_offset_x"
        const val KEY_FS_ALARM_LABEL_OFFSET_Y = "fs_alarm_label_offset_y"
        const val KEY_FS_ALARM_LABEL_ALIGNMENT = "fs_alarm_label_alignment"
        // 分组名称
        const val KEY_FS_GROUP_FONT_SIZE = "fs_group_font_size"
        const val KEY_FS_GROUP_COLOR = "fs_group_color"
        const val KEY_FS_GROUP_OFFSET_X = "fs_group_offset_x"
        const val KEY_FS_GROUP_OFFSET_Y = "fs_group_offset_y"
        const val KEY_FS_GROUP_ALIGNMENT = "fs_group_alignment"
        // 倒计时
        const val KEY_FS_COUNTDOWN_FONT_SIZE = "fs_countdown_font_size"
        const val KEY_FS_COUNTDOWN_COLOR = "fs_countdown_color"
        const val KEY_FS_COUNTDOWN_OFFSET_X = "fs_countdown_offset_x"
        const val KEY_FS_COUNTDOWN_OFFSET_Y = "fs_countdown_offset_y"
        const val KEY_FS_COUNTDOWN_ALIGNMENT = "fs_countdown_alignment"

        // ---- 默认值 ----
        const val DEFAULT_STATUS_BAR_X = 180
        const val DEFAULT_STATUS_BAR_Y = 0
        const val DEFAULT_SB_TEXT_COLOR = 0xFFFFD700.toInt()
        const val DEFAULT_SB_BG_COLOR = 0x00000000
        const val DEFAULT_FLOAT_TEXT_COLOR = 0xFFFFD700.toInt()
        const val DEFAULT_FLOAT_BG_COLOR = 0xCC000000.toInt()
        const val DEFAULT_WIDGET_TEXT_COLOR = 0xFFFFD700.toInt()
        const val DEFAULT_WIDGET_BG_COLOR = 0xCC000000.toInt()
        const val DEFAULT_NA_WIDGET_TIME_COLOR = 0xFFFFD700.toInt()
        const val DEFAULT_NA_WIDGET_COUNTDOWN_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_NA_WIDGET_LABEL_COLOR = 0xCCFFFFFF.toInt()
        const val DEFAULT_TOPBAR_CLOCK_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_TOPBAR_CLOCK_BG_COLOR = 0x00000000
        const val DEFAULT_SB_FONT_SIZE = 14f
        const val DEFAULT_FLOAT_FONT_SIZE = 16f
        const val DEFAULT_NA_WIDGET_TIME_SIZE = 24f
        const val DEFAULT_NA_WIDGET_COUNTDOWN_SIZE = 16f
        const val DEFAULT_NA_WIDGET_LABEL_SIZE = 14f
        const val DEFAULT_TOPBAR_CLOCK_ENABLED = true
        const val DEFAULT_AUTO_UPDATE_ENABLED = true
        const val DEFAULT_HOURLY_CHIME_MASTER_ENABLED = true
        const val DEFAULT_TTS_PITCH = 1.0f
        const val DEFAULT_TTS_RATE = 1.0f
        const val DEFAULT_THEME = 0
        const val DEFAULT_LANGUAGE = "zh"
        const val DEFAULT_DUPLICATE_OFFSET_HOURS = 0
        const val DEFAULT_DUPLICATE_OFFSET_MINUTES = 10
        const val DEFAULT_COUNTDOWN_WARNING_SECONDS = 120
        const val DEFAULT_COUNTDOWN_WARNING_SOUND_TYPE = "tick_tock"
        const val DEFAULT_TIMER_FINISH_SOUND_TYPE = "tick_tock"
        const val DEFAULT_CHIME_STYLE = 0

        // ---- 全屏闹钟默认值 ----
        const val DEFAULT_FS_DATE_FONT_SIZE = 32f
        const val DEFAULT_FS_DATE_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_FS_WEEK_FONT_SIZE = 32f
        const val DEFAULT_FS_WEEK_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_FS_TIME_FONT_SIZE = 32f
        const val DEFAULT_FS_TIME_COLOR = 0xFFFFD700.toInt()
        const val DEFAULT_FS_ALARM_TIME_FONT_SIZE = 56f
        const val DEFAULT_FS_ALARM_TIME_COLOR = 0xFFFFFFFF.toInt()
        const val DEFAULT_FS_ALARM_LABEL_FONT_SIZE = 18f
        const val DEFAULT_FS_ALARM_LABEL_COLOR = 0xCCFFFFFF.toInt()
        const val DEFAULT_FS_GROUP_FONT_SIZE = 14f
        const val DEFAULT_FS_GROUP_COLOR = 0xFF64B5F6.toInt()
        const val DEFAULT_FS_COUNTDOWN_FONT_SIZE = 20f
        const val DEFAULT_FS_COUNTDOWN_COLOR = 0xFFFFD700.toInt()
    }

    // ==================== 颜色读写 ====================

    fun getStatusBarTextColor(): Int = appSettings.getInt(KEY_SB_TEXT_COLOR, DEFAULT_SB_TEXT_COLOR)
    fun setStatusBarTextColor(color: Int) = appSettings.edit().putInt(KEY_SB_TEXT_COLOR, color).apply()
    fun getStatusBarBgColor(): Int = appSettings.getInt(KEY_SB_BG_COLOR, DEFAULT_SB_BG_COLOR)
    fun setStatusBarBgColor(color: Int) = appSettings.edit().putInt(KEY_SB_BG_COLOR, color).apply()

    fun getFloatTextColor(): Int = appSettings.getInt(KEY_FLOAT_TEXT_COLOR, DEFAULT_FLOAT_TEXT_COLOR)
    fun setFloatTextColor(color: Int) = appSettings.edit().putInt(KEY_FLOAT_TEXT_COLOR, color).apply()
    fun getFloatBgColor(): Int = appSettings.getInt(KEY_FLOAT_BG_COLOR, DEFAULT_FLOAT_BG_COLOR)
    fun setFloatBgColor(color: Int) = appSettings.edit().putInt(KEY_FLOAT_BG_COLOR, color).apply()

    fun getWidgetTextColor(): Int = appSettings.getInt(KEY_WIDGET_TEXT_COLOR, DEFAULT_WIDGET_TEXT_COLOR)
    fun setWidgetTextColor(color: Int) = appSettings.edit().putInt(KEY_WIDGET_TEXT_COLOR, color).apply()
    fun getWidgetBgColor(): Int = appSettings.getInt(KEY_WIDGET_BG_COLOR, DEFAULT_WIDGET_BG_COLOR)
    fun setWidgetBgColor(color: Int) = appSettings.edit().putInt(KEY_WIDGET_BG_COLOR, color).apply()

    fun getNextAlarmWidgetTimeColor(): Int = appSettings.getInt(KEY_NEXT_ALARM_WIDGET_TIME_COLOR, DEFAULT_NA_WIDGET_TIME_COLOR)
    fun setNextAlarmWidgetTimeColor(color: Int) = appSettings.edit().putInt(KEY_NEXT_ALARM_WIDGET_TIME_COLOR, color).apply()
    fun getNextAlarmWidgetCountdownColor(): Int = appSettings.getInt(KEY_NEXT_ALARM_WIDGET_COUNTDOWN_COLOR, DEFAULT_NA_WIDGET_COUNTDOWN_COLOR)
    fun setNextAlarmWidgetCountdownColor(color: Int) = appSettings.edit().putInt(KEY_NEXT_ALARM_WIDGET_COUNTDOWN_COLOR, color).apply()
    fun getNextAlarmWidgetLabelColor(): Int = appSettings.getInt(KEY_NEXT_ALARM_WIDGET_LABEL_COLOR, DEFAULT_NA_WIDGET_LABEL_COLOR)
    fun setNextAlarmWidgetLabelColor(color: Int) = appSettings.edit().putInt(KEY_NEXT_ALARM_WIDGET_LABEL_COLOR, color).apply()

    fun getTopBarClockColor(): Int = appSettings.getInt(KEY_TOPBAR_CLOCK_COLOR, DEFAULT_TOPBAR_CLOCK_COLOR)
    fun setTopBarClockColor(color: Int) = appSettings.edit().putInt(KEY_TOPBAR_CLOCK_COLOR, color).apply()
    fun getTopBarClockBgColor(): Int = appSettings.getInt(KEY_TOPBAR_CLOCK_BG_COLOR, DEFAULT_TOPBAR_CLOCK_BG_COLOR)
    fun setTopBarClockBgColor(color: Int) = appSettings.edit().putInt(KEY_TOPBAR_CLOCK_BG_COLOR, color).apply()

    // ---- 统一颜色设置（兼容旧 setColors 分发） ----

    fun getTextColor(component: String): Int = when (component) {
        "sb" -> getStatusBarTextColor()
        "float" -> getFloatTextColor()
        "widget" -> getWidgetTextColor()
        "topbar" -> getTopBarClockColor()
        else -> DEFAULT_SB_TEXT_COLOR
    }

    fun setTextColor(component: String, color: Int) {
        when (component) {
            "sb" -> setStatusBarTextColor(color)
            "float" -> setFloatTextColor(color)
            "widget" -> setWidgetTextColor(color)
            "topbar" -> setTopBarClockColor(color)
            else -> {}
        }
    }

    fun getBgColor(component: String): Int = when (component) {
        "sb" -> getStatusBarBgColor()
        "float" -> getFloatBgColor()
        "widget" -> getWidgetBgColor()
        "topbar" -> getTopBarClockBgColor()
        else -> DEFAULT_SB_BG_COLOR
    }

    fun setBgColor(component: String, color: Int) {
        when (component) {
            "sb" -> setStatusBarBgColor(color)
            "float" -> setFloatBgColor(color)
            "widget" -> setWidgetBgColor(color)
            "topbar" -> setTopBarClockBgColor(color)
            else -> {}
        }
    }

    // ==================== 字体大小 ====================

    fun getStatusBarFontSize(): Float = appSettings.getFloat(KEY_SB_FONT_SIZE, DEFAULT_SB_FONT_SIZE)
    fun setStatusBarFontSize(size: Float) = appSettings.edit().putFloat(KEY_SB_FONT_SIZE, size).apply()
    fun getFloatFontSize(): Float = appSettings.getFloat(KEY_FLOAT_FONT_SIZE, DEFAULT_FLOAT_FONT_SIZE)
    fun setFloatFontSize(size: Float) = appSettings.edit().putFloat(KEY_FLOAT_FONT_SIZE, size).apply()

    fun getNextAlarmWidgetTimeSize(): Float = appSettings.getFloat(KEY_NEXT_ALARM_WIDGET_TIME_SIZE, DEFAULT_NA_WIDGET_TIME_SIZE)
    fun setNextAlarmWidgetTimeSize(size: Float) = appSettings.edit().putFloat(KEY_NEXT_ALARM_WIDGET_TIME_SIZE, size).apply()
    fun getNextAlarmWidgetCountdownSize(): Float = appSettings.getFloat(KEY_NEXT_ALARM_WIDGET_COUNTDOWN_SIZE, DEFAULT_NA_WIDGET_COUNTDOWN_SIZE)
    fun setNextAlarmWidgetCountdownSize(size: Float) = appSettings.edit().putFloat(KEY_NEXT_ALARM_WIDGET_COUNTDOWN_SIZE, size).apply()
    fun getNextAlarmWidgetLabelSize(): Float = appSettings.getFloat(KEY_NEXT_ALARM_WIDGET_LABEL_SIZE, DEFAULT_NA_WIDGET_LABEL_SIZE)
    fun setNextAlarmWidgetLabelSize(size: Float) = appSettings.edit().putFloat(KEY_NEXT_ALARM_WIDGET_LABEL_SIZE, size).apply()

    // ---- 统一字体设置 ----
    fun getFontSize(component: String): Float = when (component) {
        "sb" -> getStatusBarFontSize()
        "float" -> getFloatFontSize()
        else -> DEFAULT_SB_FONT_SIZE
    }

    fun setFontSize(component: String, size: Float) {
        when (component) {
            "sb" -> setStatusBarFontSize(size)
            "float" -> setFloatFontSize(size)
            else -> {}
        }
    }

    // ==================== 状态栏位置 ====================

    fun getStatusBarX(): Int = appSettings.getInt(KEY_STATUS_BAR_X, DEFAULT_STATUS_BAR_X)
    fun setStatusBarX(x: Int) = appSettings.edit().putInt(KEY_STATUS_BAR_X, x).apply()
    fun getStatusBarY(): Int = appSettings.getInt(KEY_STATUS_BAR_Y, DEFAULT_STATUS_BAR_Y)
    fun setStatusBarY(y: Int) = appSettings.edit().putInt(KEY_STATUS_BAR_Y, y).apply()

    // ==================== 开关 ====================

    fun isTopBarClockEnabled(): Boolean = appSettings.getBoolean(KEY_TOPBAR_CLOCK_ENABLED, DEFAULT_TOPBAR_CLOCK_ENABLED)
    fun setTopBarClockEnabled(enabled: Boolean) = appSettings.edit().putBoolean(KEY_TOPBAR_CLOCK_ENABLED, enabled).apply()

    fun isStatusBarClockEnabled(): Boolean = appSettings.getBoolean(KEY_STATUS_BAR_CLOCK_ENABLED, false)
    fun setStatusBarClockEnabled(enabled: Boolean) = appSettings.edit().putBoolean(KEY_STATUS_BAR_CLOCK_ENABLED, enabled).apply()

    fun isAutoUpdateEnabled(): Boolean = appSettings.getBoolean(KEY_AUTO_UPDATE_ENABLED, DEFAULT_AUTO_UPDATE_ENABLED)
    fun setAutoUpdateEnabled(enabled: Boolean) = appSettings.edit().putBoolean(KEY_AUTO_UPDATE_ENABLED, enabled).apply()

    fun isHourlyChimeMasterEnabled(): Boolean = appSettings.getBoolean(KEY_HOURLY_CHIME_MASTER_ENABLED, DEFAULT_HOURLY_CHIME_MASTER_ENABLED)
    fun setHourlyChimeMasterEnabled(enabled: Boolean) = appSettings.edit().putBoolean(KEY_HOURLY_CHIME_MASTER_ENABLED, enabled).apply()

    fun isFloatingTimerEnabled(): Boolean = appSettings.getBoolean(KEY_FLOATING_TIMER_ENABLED, false)
    fun setFloatingTimerEnabled(enabled: Boolean) = appSettings.edit().putBoolean(KEY_FLOATING_TIMER_ENABLED, enabled).apply()

    fun isServiceStatusMonitorEnabled(): Boolean = appSettings.getBoolean(KEY_SERVICE_STATUS_MONITOR_ENABLED, true)
    fun setServiceStatusMonitorEnabled(enabled: Boolean) = appSettings.edit().putBoolean(KEY_SERVICE_STATUS_MONITOR_ENABLED, enabled).apply()

    // ==================== 样式调节目标 ====================

    fun getDpadTarget(): Int = appSettings.getInt(KEY_DPAD_TARGET, 0)
    fun setDpadTarget(target: Int) = appSettings.edit().putInt(KEY_DPAD_TARGET, target).apply()

    // ==================== TTS ====================

    fun getTtsEngine(): String = appSettings.getString(KEY_TTS_ENGINE, "") ?: ""
    fun setTtsEngine(engine: String) = appSettings.edit().putString(KEY_TTS_ENGINE, engine).apply()
    fun getTtsVoice(): String = appSettings.getString(KEY_TTS_VOICE, "") ?: ""
    fun setTtsVoice(voice: String) = appSettings.edit().putString(KEY_TTS_VOICE, voice).apply()
    fun getTtsFormat(): String = appSettings.getString(KEY_TTS_FORMAT, "wav") ?: "wav"
    fun setTtsFormat(format: String) = appSettings.edit().putString(KEY_TTS_FORMAT, format).apply()
    fun getTtsPitch(): Float = appSettings.getFloat(KEY_TTS_PITCH, DEFAULT_TTS_PITCH)
    fun setTtsPitch(pitch: Float) = appSettings.edit().putFloat(KEY_TTS_PITCH, pitch).apply()
    fun getTtsRate(): Float = appSettings.getFloat(KEY_TTS_RATE, DEFAULT_TTS_RATE)
    fun setTtsRate(rate: Float) = appSettings.edit().putFloat(KEY_TTS_RATE, rate).apply()

    // ==================== 通用设置 ====================

    fun getTheme(): Int = appSettings.getInt(KEY_APP_THEME, DEFAULT_THEME)
    fun setTheme(theme: Int) = appSettings.edit().putInt(KEY_APP_THEME, theme).apply()
    fun getLanguage(): String = appSettings.getString(KEY_APP_LANGUAGE, DEFAULT_LANGUAGE) ?: DEFAULT_LANGUAGE
    fun setLanguage(lang: String) = appSettings.edit().putString(KEY_APP_LANGUAGE, lang).apply()
    fun getDuplicateOffsetHours(): Int = appSettings.getInt(KEY_DUPLICATE_OFFSET_HOURS, DEFAULT_DUPLICATE_OFFSET_HOURS)
    fun setDuplicateOffsetHours(hours: Int) = appSettings.edit().putInt(KEY_DUPLICATE_OFFSET_HOURS, hours).apply()
    fun getDuplicateOffsetMinutes(): Int = appSettings.getInt(KEY_DUPLICATE_OFFSET_MINUTES, DEFAULT_DUPLICATE_OFFSET_MINUTES)
    fun setDuplicateOffsetMinutes(minutes: Int) = appSettings.edit().putInt(KEY_DUPLICATE_OFFSET_MINUTES, minutes).apply()

    // ==================== 倒计时/计时器 ====================

    fun getTimerEndMillis(): Long = appSettings.getLong(KEY_TIMER_END_MILLIS, 0L)
    fun setTimerEndMillis(millis: Long) = appSettings.edit().putLong(KEY_TIMER_END_MILLIS, millis).apply()
    fun removeTimerEndMillis() = appSettings.edit().remove(KEY_TIMER_END_MILLIS).apply()

    fun getCountdownWarningSeconds(): Int = appSettings.getInt(KEY_COUNTDOWN_WARNING_SECONDS, DEFAULT_COUNTDOWN_WARNING_SECONDS)
    fun setCountdownWarningSeconds(seconds: Int) = appSettings.edit().putInt(KEY_COUNTDOWN_WARNING_SECONDS, seconds).apply()

    fun getCountdownWarningSoundType(): String = appSettings.getString(KEY_COUNTDOWN_WARNING_SOUND_TYPE, DEFAULT_COUNTDOWN_WARNING_SOUND_TYPE) ?: DEFAULT_COUNTDOWN_WARNING_SOUND_TYPE
    fun setCountdownWarningSoundType(type: String) = appSettings.edit().putString(KEY_COUNTDOWN_WARNING_SOUND_TYPE, type).apply()

    fun getCountdownWarningCustomPath(): String = appSettings.getString(KEY_COUNTDOWN_WARNING_CUSTOM_PATH, "") ?: ""
    fun setCountdownWarningCustomPath(path: String) = appSettings.edit().putString(KEY_COUNTDOWN_WARNING_CUSTOM_PATH, path).apply()

    fun getCountdownWarningTtsText(): String = appSettings.getString(KEY_COUNTDOWN_WARNING_TTS_TEXT, "") ?: ""
    fun setCountdownWarningTtsText(text: String) = appSettings.edit().putString(KEY_COUNTDOWN_WARNING_TTS_TEXT, text).apply()

    fun getTimerFinishSoundType(): String = appSettings.getString(KEY_TIMER_FINISH_SOUND_TYPE, DEFAULT_TIMER_FINISH_SOUND_TYPE) ?: DEFAULT_TIMER_FINISH_SOUND_TYPE
    fun setTimerFinishSoundType(type: String) = appSettings.edit().putString(KEY_TIMER_FINISH_SOUND_TYPE, type).apply()

    fun getTimerFinishCustomPath(): String = appSettings.getString(KEY_TIMER_FINISH_CUSTOM_PATH, "") ?: ""
    fun setTimerFinishCustomPath(path: String) = appSettings.edit().putString(KEY_TIMER_FINISH_CUSTOM_PATH, path).apply()

    fun getTimerFinishTtsText(): String = appSettings.getString(KEY_TIMER_FINISH_TTS_TEXT, "") ?: ""
    fun setTimerFinishTtsText(text: String) = appSettings.edit().putString(KEY_TIMER_FINISH_TTS_TEXT, text).apply()

    // ==================== 路径 ====================

    fun getRecordingPath(): String = appSettings.getString(KEY_RECORDING_PATH, "") ?: ""
    fun setRecordingPath(path: String) = appSettings.edit().putString(KEY_RECORDING_PATH, path).apply()

    fun getDatabaseDirPath(): String = appSettings.getString(KEY_DATABASE_DIR_PATH, "") ?: ""
    fun setDatabaseDirPath(path: String) = appSettings.edit().putString(KEY_DATABASE_DIR_PATH, path).apply()

    // ==================== 报时 ====================

    fun getChimeStyle(): Int = chimePrefs.getInt(KEY_CHIME_STYLE, DEFAULT_CHIME_STYLE)
    fun setChimeStyle(style: Int) = chimePrefs.edit().putInt(KEY_CHIME_STYLE, style).apply()

    // ==================== 全屏闹钟参数 ====================

    // 日期
    fun getFsDateFontSize(): Float = appSettings.getFloat(KEY_FS_DATE_FONT_SIZE, DEFAULT_FS_DATE_FONT_SIZE)
    fun setFsDateFontSize(size: Float) = appSettings.edit().putFloat(KEY_FS_DATE_FONT_SIZE, size).apply()
    fun getFsDateColor(): Int = appSettings.getInt(KEY_FS_DATE_COLOR, DEFAULT_FS_DATE_COLOR)
    fun setFsDateColor(color: Int) = appSettings.edit().putInt(KEY_FS_DATE_COLOR, color).apply()
    fun getFsDateOffsetX(): Float = appSettings.getFloat(KEY_FS_DATE_OFFSET_X, 0f)
    fun setFsDateOffsetX(x: Float) = appSettings.edit().putFloat(KEY_FS_DATE_OFFSET_X, x).apply()
    fun getFsDateOffsetY(): Float = appSettings.getFloat(KEY_FS_DATE_OFFSET_Y, 0f)
    fun setFsDateOffsetY(y: Float) = appSettings.edit().putFloat(KEY_FS_DATE_OFFSET_Y, y).apply()
    fun getFsDateAlignment(): Int = appSettings.getInt(KEY_FS_DATE_ALIGNMENT, 0)
    fun setFsDateAlignment(alignment: Int) = appSettings.edit().putInt(KEY_FS_DATE_ALIGNMENT, alignment).apply()

    // 星期
    fun getFsWeekFontSize(): Float = appSettings.getFloat(KEY_FS_WEEK_FONT_SIZE, DEFAULT_FS_WEEK_FONT_SIZE)
    fun setFsWeekFontSize(size: Float) = appSettings.edit().putFloat(KEY_FS_WEEK_FONT_SIZE, size).apply()
    fun getFsWeekColor(): Int = appSettings.getInt(KEY_FS_WEEK_COLOR, DEFAULT_FS_WEEK_COLOR)
    fun setFsWeekColor(color: Int) = appSettings.edit().putInt(KEY_FS_WEEK_COLOR, color).apply()
    fun getFsWeekOffsetX(): Float = appSettings.getFloat(KEY_FS_WEEK_OFFSET_X, 0f)
    fun setFsWeekOffsetX(x: Float) = appSettings.edit().putFloat(KEY_FS_WEEK_OFFSET_X, x).apply()
    fun getFsWeekOffsetY(): Float = appSettings.getFloat(KEY_FS_WEEK_OFFSET_Y, 0f)
    fun setFsWeekOffsetY(y: Float) = appSettings.edit().putFloat(KEY_FS_WEEK_OFFSET_Y, y).apply()
    fun getFsWeekAlignment(): Int = appSettings.getInt(KEY_FS_WEEK_ALIGNMENT, 0)
    fun setFsWeekAlignment(alignment: Int) = appSettings.edit().putInt(KEY_FS_WEEK_ALIGNMENT, alignment).apply()

    // 时间
    fun getFsTimeFontSize(): Float = appSettings.getFloat(KEY_FS_TIME_FONT_SIZE, DEFAULT_FS_TIME_FONT_SIZE)
    fun setFsTimeFontSize(size: Float) = appSettings.edit().putFloat(KEY_FS_TIME_FONT_SIZE, size).apply()
    fun getFsTimeColor(): Int = appSettings.getInt(KEY_FS_TIME_COLOR, DEFAULT_FS_TIME_COLOR)
    fun setFsTimeColor(color: Int) = appSettings.edit().putInt(KEY_FS_TIME_COLOR, color).apply()
    fun getFsTimeOffsetX(): Float = appSettings.getFloat(KEY_FS_TIME_OFFSET_X, 0f)
    fun setFsTimeOffsetX(x: Float) = appSettings.edit().putFloat(KEY_FS_TIME_OFFSET_X, x).apply()
    fun getFsTimeOffsetY(): Float = appSettings.getFloat(KEY_FS_TIME_OFFSET_Y, 0f)
    fun setFsTimeOffsetY(y: Float) = appSettings.edit().putFloat(KEY_FS_TIME_OFFSET_Y, y).apply()
    fun getFsTimeAlignment(): Int = appSettings.getInt(KEY_FS_TIME_ALIGNMENT, 0)
    fun setFsTimeAlignment(alignment: Int) = appSettings.edit().putInt(KEY_FS_TIME_ALIGNMENT, alignment).apply()

    // 闹钟时间
    fun getFsAlarmTimeFontSize(): Float = appSettings.getFloat(KEY_FS_ALARM_TIME_FONT_SIZE, DEFAULT_FS_ALARM_TIME_FONT_SIZE)
    fun setFsAlarmTimeFontSize(size: Float) = appSettings.edit().putFloat(KEY_FS_ALARM_TIME_FONT_SIZE, size).apply()
    fun getFsAlarmTimeColor(): Int = appSettings.getInt(KEY_FS_ALARM_TIME_COLOR, DEFAULT_FS_ALARM_TIME_COLOR)
    fun setFsAlarmTimeColor(color: Int) = appSettings.edit().putInt(KEY_FS_ALARM_TIME_COLOR, color).apply()
    fun getFsAlarmTimeOffsetX(): Float = appSettings.getFloat(KEY_FS_ALARM_TIME_OFFSET_X, 0f)
    fun setFsAlarmTimeOffsetX(x: Float) = appSettings.edit().putFloat(KEY_FS_ALARM_TIME_OFFSET_X, x).apply()
    fun getFsAlarmTimeOffsetY(): Float = appSettings.getFloat(KEY_FS_ALARM_TIME_OFFSET_Y, 0f)
    fun setFsAlarmTimeOffsetY(y: Float) = appSettings.edit().putFloat(KEY_FS_ALARM_TIME_OFFSET_Y, y).apply()
    fun getFsAlarmTimeAlignment(): Int = appSettings.getInt(KEY_FS_ALARM_TIME_ALIGNMENT, 0)
    fun setFsAlarmTimeAlignment(alignment: Int) = appSettings.edit().putInt(KEY_FS_ALARM_TIME_ALIGNMENT, alignment).apply()

    // 闹钟标签
    fun getFsAlarmLabelFontSize(): Float = appSettings.getFloat(KEY_FS_ALARM_LABEL_FONT_SIZE, DEFAULT_FS_ALARM_LABEL_FONT_SIZE)
    fun setFsAlarmLabelFontSize(size: Float) = appSettings.edit().putFloat(KEY_FS_ALARM_LABEL_FONT_SIZE, size).apply()
    fun getFsAlarmLabelColor(): Int = appSettings.getInt(KEY_FS_ALARM_LABEL_COLOR, DEFAULT_FS_ALARM_LABEL_COLOR)
    fun setFsAlarmLabelColor(color: Int) = appSettings.edit().putInt(KEY_FS_ALARM_LABEL_COLOR, color).apply()
    fun getFsAlarmLabelOffsetX(): Float = appSettings.getFloat(KEY_FS_ALARM_LABEL_OFFSET_X, 0f)
    fun setFsAlarmLabelOffsetX(x: Float) = appSettings.edit().putFloat(KEY_FS_ALARM_LABEL_OFFSET_X, x).apply()
    fun getFsAlarmLabelOffsetY(): Float = appSettings.getFloat(KEY_FS_ALARM_LABEL_OFFSET_Y, 0f)
    fun setFsAlarmLabelOffsetY(y: Float) = appSettings.edit().putFloat(KEY_FS_ALARM_LABEL_OFFSET_Y, y).apply()
    fun getFsAlarmLabelAlignment(): Int = appSettings.getInt(KEY_FS_ALARM_LABEL_ALIGNMENT, 0)
    fun setFsAlarmLabelAlignment(alignment: Int) = appSettings.edit().putInt(KEY_FS_ALARM_LABEL_ALIGNMENT, alignment).apply()

    // 分组名称
    fun getFsGroupFontSize(): Float = appSettings.getFloat(KEY_FS_GROUP_FONT_SIZE, DEFAULT_FS_GROUP_FONT_SIZE)
    fun setFsGroupFontSize(size: Float) = appSettings.edit().putFloat(KEY_FS_GROUP_FONT_SIZE, size).apply()
    fun getFsGroupColor(): Int = appSettings.getInt(KEY_FS_GROUP_COLOR, DEFAULT_FS_GROUP_COLOR)
    fun setFsGroupColor(color: Int) = appSettings.edit().putInt(KEY_FS_GROUP_COLOR, color).apply()
    fun getFsGroupOffsetX(): Float = appSettings.getFloat(KEY_FS_GROUP_OFFSET_X, 0f)
    fun setFsGroupOffsetX(x: Float) = appSettings.edit().putFloat(KEY_FS_GROUP_OFFSET_X, x).apply()
    fun getFsGroupOffsetY(): Float = appSettings.getFloat(KEY_FS_GROUP_OFFSET_Y, 0f)
    fun setFsGroupOffsetY(y: Float) = appSettings.edit().putFloat(KEY_FS_GROUP_OFFSET_Y, y).apply()
    fun getFsGroupAlignment(): Int = appSettings.getInt(KEY_FS_GROUP_ALIGNMENT, 0)
    fun setFsGroupAlignment(alignment: Int) = appSettings.edit().putInt(KEY_FS_GROUP_ALIGNMENT, alignment).apply()

    // 倒计时
    fun getFsCountdownFontSize(): Float = appSettings.getFloat(KEY_FS_COUNTDOWN_FONT_SIZE, DEFAULT_FS_COUNTDOWN_FONT_SIZE)
    fun setFsCountdownFontSize(size: Float) = appSettings.edit().putFloat(KEY_FS_COUNTDOWN_FONT_SIZE, size).apply()
    fun getFsCountdownColor(): Int = appSettings.getInt(KEY_FS_COUNTDOWN_COLOR, DEFAULT_FS_COUNTDOWN_COLOR)
    fun setFsCountdownColor(color: Int) = appSettings.edit().putInt(KEY_FS_COUNTDOWN_COLOR, color).apply()
    fun getFsCountdownOffsetX(): Float = appSettings.getFloat(KEY_FS_COUNTDOWN_OFFSET_X, 0f)
    fun setFsCountdownOffsetX(x: Float) = appSettings.edit().putFloat(KEY_FS_COUNTDOWN_OFFSET_X, x).apply()
    fun getFsCountdownOffsetY(): Float = appSettings.getFloat(KEY_FS_COUNTDOWN_OFFSET_Y, 0f)
    fun setFsCountdownOffsetY(y: Float) = appSettings.edit().putFloat(KEY_FS_COUNTDOWN_OFFSET_Y, y).apply()
    fun getFsCountdownAlignment(): Int = appSettings.getInt(KEY_FS_COUNTDOWN_ALIGNMENT, 0)
    fun setFsCountdownAlignment(alignment: Int) = appSettings.edit().putInt(KEY_FS_COUNTDOWN_ALIGNMENT, alignment).apply()
}
