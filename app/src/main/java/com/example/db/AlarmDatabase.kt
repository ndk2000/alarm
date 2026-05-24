package com.example.db

import android.content.Context
import android.os.Environment
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.File

@Database(
    entities = [AlarmGroup::class, Alarm::class, HourlyChime::class],
    version = 3,
    exportSchema = true
)
abstract class AlarmDatabase : RoomDatabase() {
    abstract fun alarmDao(): AlarmDao

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

        fun getDatabase(context: Context, scope: CoroutineScope): AlarmDatabase {
            Log.d("AlarmDB", "[1] getDatabase called")
            return INSTANCE ?: synchronized(this) {
                Log.d("AlarmDB", "[2] synchronized block entered")
                try {
                    val dbDir = getPublicDbDir(context)
                    Log.d("AlarmDB", "[3] dbDir=${dbDir.absolutePath}, exists=${dbDir.exists()}")

                    if (!dbDir.exists()) {
                        val created = dbDir.mkdirs()
                        Log.d("AlarmDB", "[4] mkdirs result=$created")
                    }

                    val dbFile = File(dbDir, DB_NAME)
                    Log.d("AlarmDB", "[5] dbFile=${dbFile.absolutePath}")

                    // 如果旧的内部数据库存在，迁移到新位置
                    migrateOldDatabase(context, dbFile)
                    Log.d("AlarmDB", "[6] migrateOldDatabase done")

                    Log.d("AlarmDB", "[7] about to call Room.databaseBuilder")
                    val instance = Room.databaseBuilder(
                        context.applicationContext,
                        AlarmDatabase::class.java,
                        dbFile.absolutePath
                    )
                    .addCallback(AlarmDatabaseCallback(scope))
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .build()
                    Log.d("AlarmDB", "[8] Room.build done")
                    INSTANCE = instance
                    instance
                } catch (e: Throwable) {
                    Log.e("AlarmDB", "[CRASH] getDatabase failed", e)
                    throw e
                }
            }
        }

        /** 获取公共数据库存储目录：Downloads/DroidCloudAlarm/ */
        private fun getPublicDbDir(context: Context): File {
            // Android 10 及以下用 Environment.getExternalStoragePublicDirectory
            // Android 11+ 该 API 已废弃但仍可用（需要 WRITE_EXTERNAL_STORAGE 权限）
            val downloadsDir = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOWNLOADS
            )
            return File(downloadsDir, "DroidCloudAlarm")
        }

        /** 如果内部存储有旧数据库，迁移到公共目录 */
        private fun migrateOldDatabase(context: Context, newDbFile: File) {
            if (newDbFile.exists()) return // 新位置已有数据库，不需要迁移
            try {
                val oldDbPath = context.getDatabasePath(OLD_DB_NAME)
                if (oldDbPath.exists()) {
                    // 迁移主数据库文件
                    oldDbPath.copyTo(newDbFile, overwrite = false)
                    // 迁移 WAL 和 SHM 文件（如果有）
                    val walFile = File(oldDbPath.parent, "${OLD_DB_NAME}-wal")
                    if (walFile.exists()) {
                        walFile.copyTo(File(newDbFile.parent, "${DB_NAME}-wal"), overwrite = false)
                    }
                    val shmFile = File(oldDbPath.parent, "${OLD_DB_NAME}-shm")
                    if (shmFile.exists()) {
                        shmFile.copyTo(File(newDbFile.parent, "${DB_NAME}-shm"), overwrite = false)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace() // 迁移失败不影响新数据库的创建
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
