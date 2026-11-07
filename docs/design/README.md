# 个人音乐 · 灰黄系列界面

采用用户确认的第 2 版及其右上角设置菜单修订稿：[设计图](approved-player.png)。

## 实现

- 浅灰画布 `#F5F6F8`、白色内容面、深灰 `#333E49` 控件、暖黄 `#FFCE54` 强调，与 APK 安装助手、LanClip、快享盒保持同一系列。
- 使用原生 Compose 布局、文本、Slider、菜单和按钮。唱片是独立透明素材，页面没有使用整张设计图作背景。
- 设置菜单包含扫码下载和粘贴链接；曲目更多菜单包含删除音乐。沿用既有下载、私有曲库和 Media3 播放逻辑。
- 手机纵向滚动；窗口宽度达到 700dp 时改为播放器和曲库双栏。宽窗口内容最大宽度 1080dp。
- 音频信息全部来自实际文件。图中的曲名和时长不是应用内预置演示数据。

## 素材

`player_record.png` 由内置 ImageGen 从已确认设计图提取并重新生成独立唱片素材，保留 alpha 通道。提示要求：只保留正面圆形深灰唱片、同心纹、暖黄中心、深灰音符和白色中心孔；透明背景，不包含文字、面板或控件。原始生成尺寸为 1254×1254。

图标来自 Google 官方 Material Design Icons（Apache 2.0），转换为 VectorDrawable 并由界面统一着色。完整许可和 NOTICE 位于 `app/src/main/assets/licenses/`。

官方源：`https://raw.githubusercontent.com/google/material-design-icons/master/src/`，下列路径均使用 `materialiconsoutlined/24px.svg`：

| 应用资源 | 官方路径 |
| --- | --- |
| ic_play / ic_pause | av/play_arrow / av/pause |
| ic_previous / ic_next | av/skip_previous / av/skip_next |
| ic_shuffle / ic_repeat / ic_repeat_one | av/shuffle / av/repeat / av/repeat_one |
| ic_equalizer | av/equalizer |
| ic_music / ic_scan | image/music_note / image/crop_free |
| ic_settings | action/settings |
| ic_link / ic_more | content/link / navigation/more_vert |

设置、链接、更多图标复用同系列快享盒项目中已保存的上述官方路径资源。

桌面图标采用与其他系列应用一致的白底、暖黄主体和粗深灰轮廓，以音乐文件与双音符表达功能，包含彩色自适应图标和系统单色主题资源，详见 [应用图标说明](launcher-icon.md)。
