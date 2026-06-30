package com.ccsoft.alarm.db

import android.content.Context
import android.os.Build
import android.os.Environment
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import com.ccsoft.alarm.util.PreferencesManager
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Database(
    entities = [AlarmGroup::class, Alarm::class, HourlyChime::class, CheckInGroupEntity::class, CheckInTaskEntity::class, CloudShareRecord::class, AlarmRecord::class],
    version = 10,
    exportSchema = true
)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao
    abstract fun checkinDao(): CheckInDao
    abstract fun cloudShareDao(): CloudShareDao
    abstract fun alarmRecordDao(): AlarmRecordDao

    companion object {
        @Volatile
        private var INSTANCE: AlarmDatabase? = null

        // 数据库文件名
        private const val DB_NAME = "alarm_database.db"

        // 旧的内部存储数据库名（Room 默认在 data/data/.../databases/ 下）
        private const val OLD_DB_NAME = "alarm_database"

        // Migration from version 1 to 2 (if needed in the future)
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // Currently no schema changes between v1 and v2
            }
        }

        // Migration from version 2 to 3: no schema changes (only version bump)
        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                // No schema changes between v2 and v3
            }
        }

        // Migration from version 3 to 4: add check-in tables
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `check_in_groups` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `name` TEXT NOT NULL,
                        `isEnabled` INTEGER NOT NULL DEFAULT 0,
                        `ringtonePath` TEXT,
                        `boundAlarmGroupId` INTEGER NOT NULL DEFAULT -1,
                        `createdAt` INTEGER NOT NULL
                    )
                """.trimIndent())
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `check_in_tasks` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `groupId` INTEGER NOT NULL,
                        `name` TEXT NOT NULL,
                        `hour` INTEGER NOT NULL DEFAULT 8,
                        `minute` INTEGER NOT NULL DEFAULT 0,
                        `orderIndex` INTEGER NOT NULL DEFAULT 0,
                        FOREIGN KEY(`groupId`) REFERENCES `check_in_groups`(`id`) ON DELETE CASCADE
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_check_in_tasks_groupId` ON `check_in_tasks`(`groupId`)")
            }
        }

        // Migration from version 4 to 5: add ringtonePath column to check_in_groups
        // Note: MIGRATION_3_4 already creates check_in_groups WITH ringtonePath, so
        // this ALTER TABLE may fail if the column already exists. Catch & ignore that case.
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(database: SupportSQLiteDatabase) {
                try {
                    database.execSQL("ALTER TABLE `check_in_groups` ADD COLUMN `ringtonePath` TEXT DEFAULT NULL")
                } catch (e: Exception) {
                    val msg = e.message?.lowercase() ?: ""
                    if ("duplicate column" in msg) {
                        Log.w("AlarmDB", "MIGRATION_4_5: ringtonePath already exists, skipping")
                    } else {
                        throw e // rethrow unexpected errors
                    }
                }
            }
        }

        // Migration from version 5 to 6: add ringtonePath and useTts columns to check_in_tasks
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `check_in_tasks` ADD COLUMN `ringtonePath` TEXT DEFAULT NULL")
                database.execSQL("ALTER TABLE `check_in_tasks` ADD COLUMN `useTts` INTEGER NOT NULL DEFAULT 0")
            }
        }

        // Migration from version 6 to 7: add cloud_share_records table
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `cloud_share_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `shareCode` TEXT NOT NULL,
                        `groupName` TEXT NOT NULL,
                        `itemCount` INTEGER NOT NULL DEFAULT 0,
                        `shareTime` INTEGER NOT NULL,
                        `groupType` TEXT NOT NULL,
                        `sourceGroupId` INTEGER NOT NULL DEFAULT -1
                    )
                """.trimIndent())
            }
        }

        // Migration from version 7 to 8: add alarm_records table
        val MIGRATION_7_8 = object : Migration(7, 8) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `alarm_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `alarmId` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `scheduledTime` INTEGER NOT NULL,
                        `recordDate` TEXT NOT NULL,
                        `dismissTime` INTEGER,
                        `status` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_records_recordDate` ON `alarm_records`(`recordDate`)")
            }
        }

        // Migration from version 8 to 9: fix alarm_records schema (remove default)
        val MIGRATION_8_9 = object : Migration(8, 9) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("DROP TABLE IF EXISTS `alarm_records`")
                database.execSQL("""
                    CREATE TABLE IF NOT EXISTS `alarm_records` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `alarmId` INTEGER NOT NULL,
                        `label` TEXT NOT NULL,
                        `scheduledTime` INTEGER NOT NULL,
                        `recordDate` TEXT NOT NULL,
                        `dismissTime` INTEGER,
                        `status` TEXT NOT NULL
                    )
                """.trimIndent())
                database.execSQL("CREATE INDEX IF NOT EXISTS `index_alarm_records_recordDate` ON `alarm_records`(`recordDate`)")
            }
        }

        // Migration from version 9 to 10: add ringtoneDurationSecs column to alarms
        val MIGRATION_9_10 = object : Migration(9, 10) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE `alarms` ADD COLUMN `ringtoneDurationSecs` INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun getDatabase(context: Context, scope: CoroutineScope): AlarmDatabase {
            Log.d("AlarmDB", "[1] getDatabase called")
            return INSTANCE ?: synchronized(this) {
                Log.d("AlarmDB", "[2] synchronized block entered")
                try {
                    // 核心修复：优先尝试公共目录（防重装丢失），若无权访问则退回到内部私有目录（保证必能启动）
                    val dbFile = getAccessibleDbFile(context)
                    Log.d("AlarmDB", "[3] Final DB path: ${dbFile.absolutePath}")

                    // 确保父目录存在
                    val parent = dbFile.parentFile
                    if (parent != null && !parent.exists()) {
                        val created = parent.mkdirs()
                        Log.d("AlarmDB", "[4] mkdirs for parent: $created")
                    }

                    // 如果旧的内部数据库存在，迁移到新位置
                    migrateOldDatabase(context, dbFile)
                    Log.d("AlarmDB", "[5] migrateOldDatabase done")

                    Log.d("AlarmDB", "[6] about to call Room.databaseBuilder")
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        AlarmDatabase::class.java,
                        dbFile.absolutePath
                    )
                    .addCallback(AlarmDatabaseCallback(scope))
.addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
                    .fallbackToDestructiveMigration()
                    .build()
                    Log.d("AlarmDB", "[7] Room.build done")
                    INSTANCE = instance
                    instance
                } catch (e: Throwable) {
                    Log.e("AlarmDB", "[CRASH] getDatabase failed", e)
                    // 最后的保险：如果以上都失败，尝试使用最基础的 Room 默认名
                    return@synchronized buildDefaultDatabase(context, scope)
                }
            }
        }

        /** 构建最基础的 Room 默认数据库（作为崩溃后的最终兜底） */
        private fun buildDefaultDatabase(context: Context, scope: CoroutineScope): AlarmDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AlarmDatabase::class.java,
                DB_NAME
            )
            .addCallback(AlarmDatabaseCallback(scope))
            .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6, MIGRATION_6_7, MIGRATION_7_8, MIGRATION_8_9, MIGRATION_9_10)
            .fallbackToDestructiveMigration()
            .build()
        }

        /** 获取可访问的数据库文件路径 */
        private fun getAccessibleDbFile(context: Context): File {
            val prefs = PreferencesManager(context)
            val customDir = prefs.getDatabaseDirPath()
            if (customDir.isNotEmpty()) {
                try {
                    val customDirFile = if (customDir.startsWith("/")) {
                        File(customDir)
                    } else {
                        File(Environment.getExternalStorageDirectory(), customDir)
                    }
                    if (customDirFile.exists() || customDirFile.mkdirs()) {
                        return File(customDirFile, DB_NAME)
                    }
                    Log.w("AlarmDB", "Custom DB dir path exists but cannot be created: ${customDirFile.absolutePath}")
                } catch (e: Exception) {
                    Log.e("AlarmDB", "Custom DB dir access error", e)
                }
            }

            // 1. 尝试 Downloads 目录 (需要 MANAGE_EXTERNAL_STORAGE)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    if (Environment.isExternalStorageManager()) {
                        val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DroidCloudAlarm")
                        if (publicDir.exists() || publicDir.mkdirs()) {
                            return File(publicDir, DB_NAME)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AlarmDB", "External storage access error", e)
                }
            } else {
                // Android 10 以下使用传统外部存储
                val publicDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DroidCloudAlarm")
                if (publicDir.exists() || publicDir.mkdirs()) {
                    return File(publicDir, DB_NAME)
                }
            }

            // 2. 如果无法访问公共目录，则使用 App 内部私有目录（始终可靠）
            return context.getDatabasePath(DB_NAME)
        }

        /** 如果内部存储有旧数据库，迁移到公共目录 */
        private fun migrateOldDatabase(context: Context, newDbFile: File) {
            val oldDbFile = context.getDatabasePath(OLD_DB_NAME)
            // 如果目标文件已经存在，或者源文件不存在，则不执行迁移
            if (newDbFile.exists() || !oldDbFile.exists()) return
            
            // 路径相同（例如由于无权访问公共目录导致 newDbFile 退回到了私有目录）不迁移
            if (newDbFile.absolutePath == oldDbFile.absolutePath) return

            try {
                Log.i("AlarmDB", "Migrating old database to: ${newDbFile.absolutePath}")
                oldDbFile.copyTo(newDbFile, overwrite = false)
                // 迁移 WAL 和 SHM 文件
                val walSource = File(oldDbFile.parent, "${OLD_DB_NAME}-wal")
                if (walSource.exists()) walSource.copyTo(File(newDbFile.parent, "${DB_NAME}-wal"), overwrite = false)
                val shmSource = File(oldDbFile.parent, "${OLD_DB_NAME}-shm")
                if (shmSource.exists()) shmSource.copyTo(File(newDbFile.parent, "${DB_NAME}-shm"), overwrite = false)
                
                // 迁移成功后删除旧文件
                oldDbFile.delete()
                File(oldDbFile.parent, "${OLD_DB_NAME}-wal").delete()
                File(oldDbFile.parent, "${OLD_DB_NAME}-shm").delete()
            } catch (e: Exception) {
                Log.e("AlarmDB", "Migration failed", e)
            }
        }
    }

    private class AlarmDatabaseCallback(
        private val scope: CoroutineScope
    ) : RoomDatabase.Callback() {
        override fun onCreate(db: SupportSQLiteDatabase) {
            super.onCreate(db)
            try {
                // Pre-populate 24-hour hourly chime entries
                for (hr in 0..23) {
                    db.execSQL("INSERT INTO hourly_chimes (hour, isEnabled, useTts, vibrate) VALUES ($hr, 0, 1, 1)")
                }

                // Pre-populate alarm groups with custom IDs
                db.execSQL("INSERT INTO alarm_groups (id, name, isEnabled) VALUES (1, '日常工作日', 1)")
                db.execSQL("INSERT INTO alarm_groups (id, name, isEnabled) VALUES (2, '温馨周末', 1)")

                // Pre-populate default alarms within groups
                db.execSQL("INSERT INTO alarms (groupId, hour, minute, daysOfWeek, isEnabled, label, ringtonePath, vibrate) VALUES (1, 7, 30, '1,2,3,4,5', 1, '早晨起床闹铃', NULL, 1)")
                db.execSQL("INSERT INTO alarms (groupId, hour, minute, daysOfWeek, isEnabled, label, ringtonePath, vibrate) VALUES (1, 8, 30, '1,2,3,4,5', 0, '上班出门提醒', NULL, 1)")
                db.execSQL("INSERT INTO alarms (groupId, hour, minute, daysOfWeek, isEnabled, label, ringtonePath, vibrate) VALUES (2, 9, 0, '6,7', 1, '周末自然醒', NULL, 1)")
            } catch (e: Exception) {
                e.printStackTrace()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }
}
