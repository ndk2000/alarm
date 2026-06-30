# ========== Room 数据库 ==========
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep @androidx.room.Dao interface *
-keep class androidx.room.** { *; }
-dontwarn androidx.room.paging.**

# ========== Compose ==========
-keep class androidx.compose.** { *; }

# ========== Moshi (JSON 序列化) ==========
-keep class com.squareup.moshi.** { *; }
-keep @com.squareup.moshi.JsonClass class *
-keepclassmembers class * {
    @com.squareup.moshi.Json <fields>;
}
-keepclassmembers @com.squareup.moshi.JsonClass class * extends java.lang.Enum {
    <fields>;
}
-keep,allowobfuscation,allowshrinking class kotlin.Metadata

# ========== OkHttp ==========
-keep class okhttp3.** { *; }
-dontwarn okhttp3.**
-dontwarn okio.**

# ========== Supabase ==========
-keep class io.github.jan.supabase.** { *; }
-keep class io.ktor.** { *; }
-dontwarn io.ktor.**

# ========== ZXing (二维码) ==========
-keep class com.google.zxing.** { *; }

# ========== 数据实体（JSON 反序列化需要） ==========
-keep class com.ccsoft.alarm.db.** { *; }
-keep class com.ccsoft.alarm.cloud.** { *; }

# ========== BuildConfig ==========
-keep class com.ccsoft.alarm.BuildConfig { *; }

# ========== 通用规则 ==========
-keepattributes Signature
-keepattributes *Annotation*
-keepattributes SourceFile,LineNumberTable
-keep class kotlin.Metadata { *; }
-keep class kotlinx.coroutines.** { *; }

# ========== Firebase (如使用) ==========
-keep class com.google.firebase.** { *; }
-dontwarn com.google.firebase.**
