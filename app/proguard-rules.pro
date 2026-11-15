# 保留 Room 生成的实现类
-keep class * extends androidx.room.RoomDatabase
-keep @androidx.room.Entity class *
-keep class xyz.ikuyo.finance.data.local.entity.** { *; }
-keep class xyz.ikuyo.finance.data.local.IkuyoDatabase_Impl { *; }
-dontwarn androidx.room.paging.**

# Kotlin 反射元数据
-keep class kotlin.Metadata { *; }

# Compose 运行时通过反射创建的 ViewModel 工厂要保留构造器
-keepclassmembers class * extends androidx.lifecycle.ViewModel {
    <init>(...);
}
-keepclassmembers class xyz.ikuyo.finance.**$$serializer { *; }

# 崩溃日志保留行号，便于定位；文件名为 SourceFile 不泄露源码结构
-keepattributes SourceFile,LineNumberTable,*Annotation*
-renamesourcefileattribute SourceFile

# 备份/CSV/Excel 都是纯 Kotlin 实现，不依赖反射，无需额外 keep；
# org.json 在 Android 平台由系统提供，R8 只需忽略其内部依赖警告。
-dontwarn org.json.**