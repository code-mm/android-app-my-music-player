# 个人音乐

私人使用的原生 Android 音乐播放器，包名 **`com.codemm.music.player`**。
Android 8.0（API 26）及以上可安装，使用 Kotlin、Jetpack Compose、Media3 和 WorkManager。

## 使用

1. 在电脑、NAS 或其他设备上准备音频文件的 HTTP / HTTPS 下载直链，将链接制作成二维码。
2. 打开「个人音乐」，点击右上角设置按钮，再选择「扫码下载」并允许相机访问；也可以选择「粘贴链接」。
3. 确认链接，可选填音乐名称，点击「下载」。支持局域网 HTTP 地址、HTTPS 地址和常规重定向。
4. 文件完整下载且通过音轨校验后才加入「我的音乐」，点击曲目即可播放。
5. 通知栏和锁屏媒体控件可控制播放；返回桌面、关闭页面或息屏时，正在播放的音乐由前台服务继续播放。

二维码需包含**音频文件直链**。不解析音乐平台分享页、登录页或 DRM 音频。
支持设备能够解码的 MP3、M4A/AAC、WAV、FLAC、OGG/Opus 等常见格式；不接收视频文件。
单文件上限 512 MB。网络异常最多自动尝试三次，可在页面或下载通知中取消任务。

播放器包含进度拖动、上一首/下一首、列表循环、单曲循环、顺序播放、随机播放、删除音乐和最近播放曲目的恢复。
恢复应用时默认不自动播放。耳机拔出时暂停，其他应用的音频焦点按 Android 系统规则处理。

## 扫一次码批量下载（1.1 起）

右上角「设置 → 扫码下载」同时支持单曲二维码和音乐合集二维码。合集会先显示歌曲清单、总大小、待下载数量和已有歌曲数量，点击「全部下载」后按顺序下载；「粘贴链接」也支持同一个合集地址。

- 每首歌曲校验大小、SHA-256 和实际音轨后才保存，已有相同内容自动跳过。
- 下载记录和通知显示整体进度，可取消；已成功保存的歌曲保留。
- 网络失败最多自动尝试三轮。仍有失败时可点「重试」，或再次扫码继续未完成的歌曲；不会重新传输已入库歌曲。中断时未完成的单个文件从头下载。
- 电脑和手机须在同一局域网，下载期间保持电脑开机且不休眠。完成后可完全离线播放。

本次两个目录生成的合集位于 `artifacts/music-share/`：49 个音频文件按内容去重后为 **44 首、3,356,808,528 字节**（约 3.13 GiB）。`music-qr.png` 为音乐二维码，`music-url.txt` 为同一地址。播放器不导入 LRC、PKL 或系统缓存文件。

电脑端工具与清单协议见 [批量下载说明](docs/music-bundle-format.md)。传输服务只共享复制到该产物目录的音频副本，原音乐目录不改动。该目录、音乐文件和二维码均不加入 Git。

界面采用与 APK 安装助手、LanClip、快享盒一致的浅灰、白色与暖黄视觉。手机显示唱片播放器和分组曲库；宽窗口使用左右双栏。每首歌的更多菜单保留删除入口，所有曲名、时长、进度与播放状态均来自真实音乐库。设计来源见 [界面说明](docs/design/README.md)。

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

## 初始版本验证记录（2026-11-07）

- Debug APK 构建、v2 签名验证通过；包名和中文应用名称已通过 APK 元数据核对。
- JVM：8 项测试执行通过，无失败、无跳过。
- Android 35 模拟器：通过原生 AndroidJUnitRunner 执行 2 项设备测试，结果 `OK (2 tests)`。
- 设备测试验证了真实音频下载、私有文件、去重、两种中断恢复、按 Home 后息屏播放、通知栏暂停和不读取外部媒体。
- Lint：0 个错误，4 条非阻断建议（可分配空间 API 和 SharedPreferences KTX 写法）。
- 本环境的 Gradle 设备发现曾超时；原生测试入口在预编译后通过，不将该 Gradle 任务记为成功。
- 实体相机扫码和不同手机的省电行为尚未实机验证。

## 灰黄界面本机验证（2026-11-07）

- Debug 构建、8 项 JVM 测试、Lint 通过；Lint 0 个错误、18 条非阻断建议。
- 专用模拟器 `my_music_player_phone_android15` 保留原数据覆盖安装；通过新菜单完成真实示例音频下载。
- 已操作播放/暂停、进度拖动、切歌、随机/循环、扫码页面打开与返回，并检查 720×1280 / 字体 1.3 及 1920×1200 双栏布局。
- 此轮是界面与交互验收，未重复初始版本的完整设备测试；实体相机识别、软键盘展开态及厂商电池策略仍需目标真机验证。

## Release 构建与签名

2026-11-07 经用户确认创建本项目专用 Release 密钥，后续版本必须沿用。密钥位于本机 `.signing/music-player-release.p12`，Gradle 从 `.signing/keystore.properties` 读取配置；两者均不进入 Git。迁移构建环境时须安全转移并备份这两份原始文件，不能重新生成密钥。公开证书指纹见 [签名记录](docs/release-signature.json)。缺少配置时 Release 打包会失败。

```sh
./gradlew --no-daemon :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease
```

构建产物为 `app/build/outputs/apk/release/app-release.apk`。Release 与 Debug 的签名不同，不能直接覆盖安装对方；需要保留音乐数据时不要卸载现有 Debug。

## APK Hub 发布记录（2026-11-07）

### 1.1（2）批量下载

- 已发布至 APK Hub `test`：[下载 1.1 Release](http://192.168.2.7:18780/downloads/babb3f75d0ff4ef00a7eaac9411d472f/app.apk)。沿用专用签名，可覆盖升级之前的 1.0 Release。
- 安装包和回执分别位于 `artifacts/releases/1.1-2/`、`artifacts/apk-hub/1.1-2/`；音乐合集二维码位于 `artifacts/music-share/music-qr.png`。
- Debug / Release 的 11 项 JVM 测试通过；Release Lint 为 0 个错误、13 条建议。
- 专用 Android 15 模拟器完成两个目录全部 44 首真实音频的下载，独立计算设备文件 SHA-256 与源清单全部一致，原 3 首音乐保留（共 47 首）。
- 6 项原生设备测试通过，覆盖清单校验、内容去重、失败重试、取消恢复、私有存储、息屏播放和通知栏暂停。旧播放测试已适配 Android 15 的系统媒体按钮；测试结果保存在设备侧后回收，避免 ADB 断连影响日志。
- 实体手机安装、相机扫码和厂商后台策略尚待真机验证；同一二维码的解析及下载入口已通过模拟器粘贴链接流程验证。

### 1.0（1）首次发布

- 已发布「个人音乐」`1.0（1）` Release 至 APK Hub 的 `test` 栏目；包名 `com.codemm.music.player`。
- 本次包含灰黄系列界面、设置菜单中的扫码下载与粘贴链接入口，以及与系列应用一致的黄色音乐文档图标。
- 应用 ID：`android-com-codemm-music-player`；版本 ID：`3700b8c44c3e1fd1f9cdb63ce50763f8`。
- [局域网下载 APK](http://192.168.2.7:18780/downloads/3700b8c44c3e1fd1f9cdb63ce50763f8/app.apk)，手机需与服务处于同一局域网。
- 固定产物：`artifacts/releases/1.0-1/com.codemm.music.player-1.0-release.apk`，11,886,521 字节。
- SHA-256：`92f13332113185ca818ee2dc99977b9545f66c971f1e335dda94dded4257d0b8`。
- Release 构建及 8 项 JVM 测试通过；Lint 为 0 个错误、11 条建议。发布后独立下载的大小、SHA-256、签名及局域网可达性均已验证。
- 回执与二维码保存在 `artifacts/apk-hub/1.0-1/`，产物不进入 Git。本次未以 Release 覆盖专用模拟器内已有的 Debug 数据；实体相机扫码、真机安装和厂商后台省电策略尚未验证。
