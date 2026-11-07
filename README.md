# 个人音乐

私人使用的原生 Android 音乐播放器，包名 **`com.codemm.music.player`**。
Android 8.0（API 26）及以上可安装，使用 Kotlin、Jetpack Compose、Media3 和 WorkManager。

## 使用

1. 在电脑、NAS 或其他设备上准备音频文件的 HTTP / HTTPS 下载直链，将链接制作成二维码。
2. 打开「个人音乐」，点击「扫码下载」并允许相机访问；也可以点击「粘贴链接」。
3. 确认链接，可选填音乐名称，点击「下载」。支持局域网 HTTP 地址、HTTPS 地址和常规重定向。
4. 文件完整下载且通过音轨校验后才加入「我的音乐」，点击曲目即可播放。
5. 通知栏和锁屏媒体控件可控制播放；返回桌面、关闭页面或息屏时，正在播放的音乐由前台服务继续播放。

二维码需包含**音频文件直链**。不解析音乐平台分享页、登录页或 DRM 音频。
支持设备能够解码的 MP3、M4A/AAC、WAV、FLAC、OGG/Opus 等常见格式；不接收视频文件。
单文件上限 512 MB。网络异常最多自动尝试三次，可在页面或下载通知中取消任务。

播放器包含进度拖动、上一首/下一首、列表循环、单曲循环、顺序播放、随机播放、删除音乐和最近播放曲目的恢复。
恢复应用时默认不自动播放。耳机拔出时暂停，其他应用的音频焦点按 Android 系统规则处理。

## 文件只在本应用内部

- 音频位于 `filesDir/audio/<UUID>.audio`，索引位于 `filesDir/music-library.json`。
- 不访问 MediaStore，不扫描外部音源，不读取系统下载目录，不申请任何存储或媒体读取权限。
- 不提供文件分享、导出或 FileProvider；普通其他应用无法直接读取这些文件。
- 系统云备份和设备迁移备份均关闭；卸载应用或清除应用数据会删除已下载音乐。
- 下载临时文件使用 `.part`，校验后原子移入音乐库；使用 SHA-256 对相同内容去重。
- MusicLibrary 使用 AtomicFile 保存索引，并可恢复在删除事务中断时暂存的文件。
- MediaSession 只接受本应用及系统信任的媒体控制器；传入的播放 URI 均被忽略，只根据本地音乐库 ID 解析文件。

权限用途：相机用于本地二维码识别，联网用于下载，通知和前台服务用于播放/下载控制，唤醒锁用于本地音乐息屏播放。
没有上传功能、分析 SDK、账户或后端服务。HTTP 明文访问用于私人局域网文件服务器；需要加密传输时请使用 HTTPS。
系统强制停止应用仍会停止播放。部分手机的额外省电限制需要在该手机的应用电池设置中允许后台运行。

## 开发与验证

- JDK 17、Gradle 8.11.1、Android Gradle Plugin 8.9.2
- Kotlin / Compose compiler 2.1.20
- Android SDK Platform 35、Build Tools 35.0.0

```sh
source /workspace/.android-env/env.sh  # 本云环境；本地 Android Studio 无需此行
cd /workspace/android-app-my-music-player
./gradlew --no-daemon :app:assembleDebug :app:testDebugUnitTest :app:lintDebug
```

安装包：`app/build/outputs/apk/debug/app-debug.apk`（调试签名，适合本机安装测试）。
报告：`app/build/reports/tests/testDebugUnitTest/` 和 `app/build/reports/lint-results-debug.html`。

连接 Android 设备后运行：

```sh
./gradlew :app:connectedDebugAndroidTest
./gradlew :app:installDebug
adb shell am start -n com.codemm.music.player/.MainActivity
```

无硬件加速的云模拟器上，Gradle 的设备属性发现可能超时。APK 安装完成后，
可直接使用相同的 AndroidJUnitRunner（成功结果须包含 `OK (2 tests)` 和 `INSTRUMENTATION_CODE: -1`）：

```sh
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest
adb install -r -t app/build/outputs/apk/debug/app-debug.apk
adb install -r -t app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
# 无硬件加速的云模拟器可先预编译，避免冷启动被系统判为超时：
adb shell cmd package compile -m speed -f com.codemm.music.player
adb shell cmd package compile -m speed -f com.codemm.music.player.test
adb shell am instrument -w -r -e timeout_msec 240000 com.codemm.music.player.test/androidx.test.runner.AndroidJUnitRunner
```

JVM 测试覆盖直链校验、重定向、中文文件名、SHA-256、网页拒绝、空文件、已知/未知大小超限、下载中断和取消清理。
设备测试使用本地 HTTP 服务与生成的 WAV 音频，验证真实下载、内容去重、内部存储、索引重载、删除中断恢复、
忽略控制器提供的网络 URI、息屏继续播放和通知栏暂停，以及无外部音源读取权限。
同时覆盖下载在“文件已移入、索引尚未提交”时中断后的重试恢复。
设备测试不会验证实体相机的扫描效果或各手机的省电策略，仍应在目标手机验收。

## 当前验证记录（2026-11-07）

- Debug APK 构建、v2 签名验证通过；包名和中文应用名称已通过 APK 元数据核对。
- JVM：8 项测试执行通过，无失败、无跳过。
- Android 35 模拟器：通过原生 AndroidJUnitRunner 执行 2 项设备测试，结果 `OK (2 tests)`。
- 设备测试验证了真实音频下载、私有文件、去重、两种中断恢复、按 Home 后息屏播放、通知栏暂停和不读取外部媒体。
- Lint：0 个错误，4 条非阻断建议（可分配空间 API 和 SharedPreferences KTX 写法）。
- 本环境的 Gradle 设备发现曾超时；原生测试入口在预编译后通过，不将该 Gradle 任务记为成功。
- 实体相机扫码和不同手机的省电行为尚未实机验证。
