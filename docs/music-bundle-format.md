# 音乐合集与局域网传输

## 电脑端生成

`scripts/music_share.py prepare` 递归扫描指定目录，只选音频扩展名（MP3、FLAC、M4A、WAV、AAC、OGG、Opus、AMR、AIFF）。它计算 SHA-256 去重，将唯一音频复制到专用产物目录，并生成清单、随机地址和二维码。原文件不修改，服务不共享整个目录或提供文件列表。

```sh
python3 scripts/music_share.py prepare \
  --directories /Users/w/Documents/音乐 /Users/w/Documents/mp3 \
  --host 192.168.2.7 --out artifacts/music-share
```

二维码优先使用本机 `qrcode` 模块；未安装时复用已运行的本机 APK Hub 二维码接口，无需发送音乐到外部服务。

前台运行传输服务：

```sh
python3 scripts/music_share.py serve --state artifacts/music-share/server-state.json
```

本次已通过 LaunchAgent 启动后台服务 `com.codemm.music-share`，配置保存在项目 `artifacts/music-share/com.codemm.music-share.plist`。它在当前登录会话中保持运行，退出登录或重启后需要重新启动；没有安装开机自启动项。电脑 IP 改变或要更新音乐时，重新生成清单并重载服务，使用新二维码。

停止本次后台服务（已下载的歌曲不受影响）：

```sh
launchctl bootout gui/$(id -u)/com.codemm.music-share
```

再次启动：

```sh
launchctl bootstrap gui/$(id -u) /Users/w/src/codex-cloud/android-app-my-music-player/artifacts/music-share/com.codemm.music-share.plist
```

服务占用 TCP 18781。请保持 Mac 开机且不休眠；手机连接同一局域网。随机分享地址可访问对应合集，请只分享给需要下载的人。服务仅匹配清单地址与已登记音频地址，其他路径返回 404；文件改变会返回 409，需重新生成清单。

## 清单协议

二维码为以 `.music.json` 结尾的 HTTP(S) 地址，客户端识别后获取并展示预览。单首直链逻辑保持兼容。

```json
{
  "format": "personal-music-bundle",
  "version": 1,
  "title": "我的音乐合集",
  "tracks": [
    {
      "title": "音乐名称",
      "url": "http://192.168.2.7:18781/s/随机标识/audio/文件哈希",
      "size": 123456,
      "sha256": "64位十六进制SHA256"
    }
  ]
}
```

清单最多 1 MiB、1000 首，每首不超过 512 MiB。所有音频地址须与清单同源；清单读取不跟随重定向。重复 SHA-256 合并，名称最长 160 字符。客户端保存已确认的清单到应用内部，由一个 WorkManager 前台任务顺序下载；任务重启或重试时按已入库 SHA-256 跳过完成的歌曲。当前不支持字节级断点续传，未完成的单个文件会重下。

每个下载先核对预期长度和 SHA-256，再通过 Android MediaExtractor 验证实际音轨。临时文件在失败或取消时删除；已经保存的歌曲保留。单个文件失败不阻塞其他歌曲，最终记录包含失败数量及部分歌曲名，并提供重试按钮。
