# 保留 Room 生成的实现类
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-dontwarn androidx.room.paging.**

# Kotlin 反射元数据
-keep class kotlin.Metadata { *; }