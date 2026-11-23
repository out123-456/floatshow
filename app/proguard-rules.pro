# 本项目全部代码都来自编译期可见的 Kotlin/Compose, 没有反射与 JNI 回调,
# 所以不需要额外 keep 规则; 依赖库自带的 consumer rules 已经覆盖各自需要保留的部分.
#
# 只补一条: 悬浮窗的 TileService 由系统按清单实例化, 名字必须保留.
-keep class com.qiqi.floatshow.service.OverlayTileService { *; }
-keep class com.qiqi.floatshow.service.OverlayService { *; }

# 保留行号信息, 崩溃栈才看得懂; 不保留源文件名(省体积)
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
