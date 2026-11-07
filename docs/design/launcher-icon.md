# 个人音乐 · 系列应用图标

2026-11-07 按用户反馈修订：桌面图标应与 APK 安装助手、LanClip、快享盒看起来属于同一系列。

## 统一规则

- 白色启动器背景，暖黄功能主体，粗深灰轮廓，圆润转角。
- 主体为「音乐文件＋双音符」，沿用其他应用的功能物件式表达，减少装饰和体积质感。
- 页头使用同一个透明前景，背景保持页面底色；播放器里的唱片插图仍沿用已确认的第 2 版界面。
- 不再使用上一轮黄底深色唱片作为应用程序图标。旧稿归档于 `launcher-record-v1.png` 与 `launcher-record-v1.md`。

## 资源与适配

- `app/src/main/res/drawable-nodpi/launcher_mark.png`：内置 ImageGen 生成的透明 RGBA 原图，1254×1254。
- `mipmap-anydpi/ic_launcher.xml`：自适应图标，白色背景和独立前景，`icon` 与 `roundIcon` 共用。
- `launcher_foreground.xml`：四边 15% inset。不透明主体在 108dp 图层中的范围约 42.80×51.91dp，居中留出裁切空间。
- `launcher_monochrome.xml`：音乐文件轮廓与双音符的单色主题版本。
- 规范来源：[Android 自适应图标](https://developer.android.com/develop/ui/compose/system/icon_design_adaptive)。

## 实际参考

- APK 安装助手：`android-app-apk-install/app/src/main/res/mipmap-xxxhdpi/ic_launcher.png`
- LanClip：`android-app-web-lanclip/app/src/main/res/drawable-nodpi/lanclip_logo.png`
- 快享盒：`android-app-kxh/app/src/main/res/drawable-nodpi/kxh_brand_mark.png`

上述三张真实图标均作为图片参考传入内置 ImageGen，未仅按名称猜测风格。

## 生成提示

```text
Create ONE production launcher foreground mark for a fourth app in EXACTLY the same visual family as the THREE provided real app icons. The references are the user's APK installer, LanClip clipboard, and quick-sharing box icons. Your mark is for a private music player. Closely match their bold charcoal outline weight, warm-yellow fill, minimal functional-object composition, rounded line joins, and extremely clear small-size readability.

New subject: an upright MUSIC FILE / SHEET, a simple yellow document with a small folded top-right corner, containing one bold dark DOUBLE-EIGHTH MUSICAL NOTE in the center. This should feel like the music sibling of the provided yellow clipboard and yellow open box. A compact centered pictogram, clean flat design. Charcoal strokes #333E49, main warm yellow #FFCE54, slight lighter yellow on the folded corner only. Maintain the same line weight and graphic grammar as the references. No thin fussy outlines, no gradients, no shadows, no photographic shading, no 3D, no vinyl disc, no headphones, no large dark filled background. No letters, words, badges, decorative stars, or surrounding UI.

Output ONE square 1024x1024 transparent PNG. Draw only the foreground pictogram; DO NOT draw a white tile, rounded-square background, circular colored background, or phone frame. The Android launcher will supply the same WHITE background used by the other apps. Keep the entire pictogram centered, height about 82% of the canvas and width about 62%, with true transparency around it. The folded document silhouette and bold double musical note must remain legible when reduced to a 48px launcher icon. It is essential that placing this next to the three references makes them look designed as one app series.
```

## 本机核验

Debug 构建与 Lint 通过（0 错误、11 条非阻断建议）。在专用 Android 15 模拟器保留数据覆盖安装，实际检查了系统应用列表中的白底新图标、点击启动，以及页头的同款图案；现有三首音乐保留。单色主题资源已打包，本轮未切换系统主题验证其显示。
