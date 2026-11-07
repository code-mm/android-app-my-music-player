# 个人音乐 · 应用图标

2026-11-07：以深灰唱片、暖黄中心与双音符重新设计桌面图标，页头同步使用同一图案。

- 原始图案：`app/src/main/res/drawable-nodpi/launcher_mark.png`，1254×1254 RGBA，真实透明背景；由内置 ImageGen 生成并保留原始像素。
- 自适应图标：`mipmap-anydpi/ic_launcher.xml`。项目最低系统为 Android 8.0，因此直接使用 adaptive-icon；背景为 `#FFCE54`，前景采用四边 15% inset。
- 不透明主体范围约 63.36dp，位于 108dp 图层中央的 66dp 安全范围内。系统自行应用圆形或其他遮罩。尺寸依据：[Android 官方规范](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。
- `launcher_monochrome.xml` 为系统主题图标准备了简化唱片轮廓与音符。原有通知图标保持独立。
- Manifest 的 `icon`、`roundIcon` 均指向新自适应图标；页头使用同一个彩色原图，曲目行的小音符保留。

## 生成提示

```text
Create ONE final Android launcher foreground emblem for the app “个人音乐”, matching the attached gray/yellow music-player visual identity. This is a redesigned app ICON, not a mockup or a detailed illustration. Output square 1024x1024 with a genuinely TRANSPARENT background.

Design a bold flat charcoal vinyl record with a warm yellow circular center label and a very clear thick charcoal double-eighth musical note on the label. A tiny simple center spindle detail is optional only if it does not interfere with reading the note. Strong geometric silhouette, clean mathematically circular outer edge, no more than two subtle concentric groove lines. Palette strictly graphite #333E49 and warm yellow #FFCE54. A faint warm white highlight is acceptable but no gradients, no 3D, no shadow, no texture, no gloss. Prefer graphic simplicity and contrast so the symbol reads at 48 pixels. The attached artwork is STYLE / SUBJECT inspiration; remove its detailed realistic grooves and shading.

The record emblem must be centered and fit fully inside an approximately 86%-of-canvas diameter circle, leaving transparent margins all around. No rounded-square tile, no opaque background, no badge, no text or letters, no phone, no interface, no multiple alternatives. It will be placed on a warm-yellow Android adaptive-icon background by native resources. The outer dark circle should remain clearly distinct from that yellow background.
```

## 本机核验

- Debug 干净构建与 Lint 通过，0 错误、11 条非阻断建议。
- 在 `my_music_player_phone_android15` 保留数据覆盖安装，系统应用列表已显示新图标；点击图标可启动应用，页头显示同一彩色图案，三首现有音乐仍在。
- 已打包单色主题图层；本轮没有切换启动器主题，单色主题外观尚未作设备实测。
