package com.ccsoft.alarm

import android.Manifest
import android.app.Application
import android.content.Intent
import android.content.pm.ResolveInfo
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.enableEdgeToEdge
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ccsoft.alarm.db.Alarm
import com.ccsoft.alarm.db.AlarmGroup
import com.ccsoft.alarm.db.HourlyChime
import com.ccsoft.alarm.db.CheckInGroupEntity
import com.ccsoft.alarm.db.CheckInTaskEntity
import com.ccsoft.alarm.ui.AlarmViewModel
import com.ccsoft.alarm.ui.theme.Theme
import com.ccsoft.alarm.ui.screens.MainAppShell
import com.ccsoft.alarm.ui.screens.MainAppContent
import com.ccsoft.alarm.alarm.AlarmGuardService
import com.ccsoft.alarm.alarm.AlarmScheduler
import com.ccsoft.alarm.service.ServiceStatusMonitor
import com.ccsoft.alarm.util.PreferencesManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        
        // 强制竖屏
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT

        // 处理初始 Intent
        intent?.let { handleIntent(it) }

        // 后台预生成 24 段报时语音
        lifecycleScope.launch(Dispatchers.IO) {
            com.ccsoft.alarm.alarm.ChimeAudioPreloader.ensure(this@MainActivity)
        }

        // 启动闹钟守护服务（双重保障：确保 App 打开时守护服务一定在运行）
        // 如果守护服务已在运行，startForegroundService 不会重复创建
        AlarmGuardService.start(this)
        android.util.Log.d("MainActivity", "onCreate：已启动 AlarmGuardService")

        // 启动服务状态监控（在状态栏显示各个服务的运行状态）
        val prefs = PreferencesManager(this)
        if (prefs.isServiceStatusMonitorEnabled()) {
            ServiceStatusMonitor.start(this)
            android.util.Log.d("MainActivity", "onCreate：已启动服务状态监控")
        } else {
            android.util.Log.d("MainActivity", "onCreate：服务状态监控已关闭，不启动")
        }

        // 初始化整点报时调度（防止 App 重启后调度丢失导致不响）
        AlarmScheduler.scheduleNextHourlyChime(this)
        android.util.Log.d("MainActivity", "onCreate：已初始化整点报时调度")

        // 初始化 Supabase 登录模块
        com.ccsoft.alarm.cloud.SupabaseManager.init(this)

        setContent {
            val viewModel: AlarmViewModel = viewModel()
            val appTheme by viewModel.appTheme.collectAsState()

            Theme(appTheme = appTheme) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    MainAppShell(viewModel)
                }
            }
        }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        // Intent 处理逻辑，MainAppContent 会通过 LaunchedEffect 观察到 intent 变化
    }
}

private val ShareDebugTag = "ShareDebug"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainAppShellContent(viewModel: AlarmViewModel) {
    val context = LocalContext.current
    val activity = LocalView.current.context as? ComponentActivity
    val scope = rememberCoroutineScope()

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            Toast.makeText(context, context.getString(R.string.permission_granted), Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, context.getString(R.string.permission_denied), Toast.LENGTH_LONG).show()
        }
    }

    val manageStorageLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                Toast.makeText(context, context.getString(R.string.permission_granted), Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(Unit) {
        val permissions = mutableListOf<String>()
        permissions.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permissionLauncher.launch(permissions.toTypedArray())

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !Environment.isExternalStorageManager()) {
            val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                data = Uri.parse("package:${context.packageName}")
            }
            manageStorageLauncher.launch(intent)
        }
        requestIgnoreBatteryOptimizations(context)
    }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openOutputStream(it)?.use { os ->
                        viewModel.exportConfig(os)
                    }
                    launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.export_success), Toast.LENGTH_SHORT).show() }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.export_error, e.message), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { isStream ->
                        if (viewModel.importConfig(isStream)) {
                            viewModel.loadCustomRingtones()
                            viewModel.loadLocalRecordings()
                            launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.import_success), Toast.LENGTH_SHORT).show() }
                        }
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, context.getString(R.string.import_error, e.message), Toast.LENGTH_LONG).show() }
                }
            }
        }
    }

    val importCheckInLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri?.let {
            scope.launch(Dispatchers.IO) {
                try {
                    context.contentResolver.openInputStream(it)?.use { isStream ->
                        viewModel.importSingleCheckInGroup(isStream) {
                            viewModel.loadCustomRingtones()
                            viewModel.loadLocalRecordings()
                            launch(Dispatchers.Main) { Toast.makeText(context, "导入成功", Toast.LENGTH_SHORT).show() }
                        }
                    }
                } catch (e: Exception) {
                    launch(Dispatchers.Main) { Toast.makeText(context, "导入失败: ${e.message}", Toast.LENGTH_SHORT).show() }
                }
            }
        }
    }

    val onShareCheckInGroup: (CheckInGroupEntity) -> Unit = { group ->
        scope.launch(Dispatchers.IO) {
            try {
                if (activity == null) return@launch
                val tempFile = File(context.cacheDir, "checkin_${System.currentTimeMillis()}.zip")
                val tasks = viewModel.checkInTasksMap.value[group.id] ?: emptyList()
                java.io.FileOutputStream(tempFile).use { fos ->
                    viewModel.exportSingleCheckInGroup(group, tasks, fos)
                }
                val shareUri = androidx.core.content.FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", tempFile)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/zip"
                    putExtra(Intent.EXTRA_STREAM, shareUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                activity.startActivity(Intent.createChooser(shareIntent, "分享打卡配置").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Log.e("MainActivity", "分享失败", e)
                launch(Dispatchers.Main) { Toast.makeText(context, "分享失败", Toast.LENGTH_SHORT).show() }
            }
        }
    }

    MainAppContent(
        onExportConfig = { exportLauncher.launch("alarm_backup_${System.currentTimeMillis()}.zip") },
        onImportConfig = { importLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        onImportCheckInGroup = { importCheckInLauncher.launch(arrayOf("application/zip", "application/octet-stream")) },
        onShareCheckInGroup = onShareCheckInGroup
    )
}

private fun requestIgnoreBatteryOptimizations(context: android.content.Context) {
    val pm = context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager
    if (!pm.isIgnoringBatteryOptimizations(context.packageName)) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
                intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                context.startActivity(intent)
            } catch (_: Exception) {}
        }
    }
}

@androidx.compose.ui.tooling.preview.Preview(showBackground = true, backgroundColor = 0xFF121318)
@Composable
fun MainAppPreview() {
    Theme {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            // Preview simplified
            Text("Preview of Main App Content")
        }
    }
}
