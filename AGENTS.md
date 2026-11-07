# 本项目模拟器

- 项目标识：`my_music_player`。
- 专用手机 AVD：`my_music_player_phone_android15`。
- 硬件：Pixel 6，1080 × 2400，ARM64，2 GB RAM。
- 系统镜像：`system-images;android-35;google_apis_playstore;arm64-v8a`（Android 15 / API 35）。
- 保持 `hw.keyboard=yes`，启动前运行 `python3 ~/.local/share/android-avd-defaults/ensure_keyboard.py --name my_music_player_phone_android15 --apply`。
- 启动后通过 `adb -s <serial> emu avd name` 确认身份；串口可能变化，不要长期硬编码。
- 应用包名：`com.codemm.music.player`；启动 Activity：`com.codemm.music.player/.MainActivity`。
- 保留可见模拟器窗口，使用本项目专用实例，不复用其他项目正在运行的设备。
- 移动端操作由当前 Codex 决策，ARTEMIS 负责截图/层级观察，ADB 执行动作；无需配置额外模型 API 密钥。

命名规范：`/Users/w/src/w/docs/模拟器命名规范.md`。

## Release 签名

- 用户于 2026-11-07 明确授权首次创建本项目专用 Release 密钥，今后固定沿用。
- 私有文件：`.signing/music-player-release.p12` 与 `.signing/keystore.properties`；alias 为 `music-player-release`。
- 证书 SHA-256：`2243b0256a698f91c9d6d40397bab4f17497c5831786df42826a2832a325f8e7`，公开记录见 `docs/release-signature.json`。
- 不重新生成或替换本项目签名，不用 Debug 签名替代 Release。密钥、密码与 APK 产物不加入源码提交。
- 缺少私有签名配置时，Release 打包应失败；Debug 构建仍可独立运行。
