# floatshow

悬浮显示: 用 Android 悬浮窗在其它应用之上显示内容.

当前是骨架阶段, 包含两层:

- **准星层**: 屏幕中心叠加准星, 支持自定义图片 / 大小 / 偏移 / 不透明度, 点击穿透.
- **面板层**: 可拖动的悬浮面板, 拖标题栏移动, 右上角关闭. 内容槽目前是占位, 后续接图片 / Markdown.

主界面只负责权限与参数设置, 显示全部由前台服务完成.

## 技术栈

- Kotlin + Jetpack Compose, Material 3 (含动态取色)
- 单 Activity + 前台服务 (foregroundServiceType=specialUse)
- 最低 Android 8.0 (API 26), 编译 API 34

## 构建

本地 (Termux / AndroidIDE 等 arm64 环境) 需要在 `~/.gradle/gradle.properties` 里指定 aapt2:

```properties
android.aapt2FromMavenOverride=/data/data/com.termux/files/usr/bin/aapt2
android.enableAapt2Daemon=false
```

然后 `./gradlew :app:assembleDebug`.

GitHub Actions 会在每次 push 到 main 时构建 debug APK, 产物在 Actions 的 Artifacts 里.

## 许可

CC0 1.0 (公有领域). 图标来自 Google Material Symbols (Apache-2.0).
