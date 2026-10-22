# tv-vibehack

[English](README.md) | **简体中文**

## 定制版（本仓库）

基于 [Gathin23/tv-vibehack](https://github.com/Gathin23/tv-vibehack)（MIT 授权）。本仓库的启动器经过定制——可直接安装的 APK 在 [最新 Release](https://github.com/Mayvin168/tv-vibehack/releases/latest) 里。

### 功能

- **5 图标磨砂玻璃 Dock**——长按图标即可移动位置；布局按每个图标单独保存，重启、切换应用、进程被杀都不会乱。
- **12 张内置壁纸**——随时点顶栏的图片按钮切换；同一个选择弹窗里可以开关缓慢漂移的"动态效果"。
- **网络胶囊随连接适配**——图标和文字跟随当前连接（有线 / Wi-Fi / 离线）。
- **干净观感**——背景不再随应用变色；聚焦高亮为中性白色磨砂；应用大标题更紧凑。
- **三语界面**——跟随系统语言（English / 中文 / Bahasa Indonesia）。
- **手机遥控**——点顶栏的手机图标，扫码后手机浏览器就是触控板遥控器：单指移动指针、双指滚动，还有方向键、音量键和能直接往电视里打字的键盘。需要在电视上开启一次无障碍服务。

### 版本历史

**launcher-v2.1.1 — 2026-10-22**（真机实测后的修复，APK 在 Releases 里）

- 修复轻点点击：节点点击优先，点按手势加长兜底。
- 音量键现在会显示电视音量条；方向键改为推指针，不再走无障碍焦点搜索。

**launcher-v2.1 — 2026-10-22**（APK 在 Releases 里）

- 手机遥控：启动器在家庭局域网里开一个遥控页面（端口 8899），扫码或输入电视上的 4 位配对码连接。
- 触控板 + 屏幕指针（靠无障碍手势）：单指移动、双指滚动、轻点点击；方向键走无障碍焦点，另有返回/主页、音量键，手机键盘打字直接同步进电视的输入框。
- 顶栏新增手机图标，点开是配对弹窗：二维码、地址和服务状态。

**launcher-v2.0 — 2026-10-20**（最终定制版，APK 在 Releases 里）

- Dock:5 个图标，整幅宽度的磨砂长条；拖动排序采用"抓取式"移动模式，每步即时保存（布局永远不会跳回去）。
- 壁纸：12 张内置（星云极光、蓝色波浪、山湖黄昏、极光雪山、黑金丝绸、云雾森林、沙漠银河、都市夜景、夜樱、冰川、熔岩、月光瀑布），三排选择弹窗，动态效果开关；选好的壁纸重启后依然保留。
- 顶栏：壁纸按钮移到网络按钮左边；去掉 Inputs 按钮；网络图标随连接类型切换。
- 去掉随应用变色的环境光晕和大标题的染色衬底；大标题改为 40sp 纯文字。

**你的电视，没有广告。** 通过 ADB 给任何 Android TV / Google TV 瘦身，再换上一个干净的、tvOS 风格的启动器。把提示词交给 Claude Code 或任何编程助手，回答几个关于你用哪些应用的问题，剩下的它会在你的真电视上完成。

| 原系统桌面 | 改造后 |
|---|---|
| ![带广告的 Google TV 原桌面](docs/stock-home.jpg) | ![tvOS 风格启动器](docs/new-home.jpg?v=2) |

一切都可逆：应用包只是禁用、从不卸载，还有一个恢复脚本可以把原系统桌面装回去。

## 快速开始

1. 在电视上：设置 → 设备偏好设置 → 关于 → 在 **版本号（Build）** 上连按 OK 七次 → 返回 → 开发者选项 → 打开 **USB 调试**（如果电视上有"网络/ADB 调试"也一起打开）。让电视保持开机，并和电脑连同一个 Wi-Fi。
2. 在电脑上打开 Claude Code（或你顺手的助手），粘贴 [`PROMPT.md`](PROMPT.md) 里的提示词，并填上你的电视型号。
3. 按提示在电视上点"允许 USB 调试"，然后回答清单问题（你用哪些应用、哪四个放进 Dock）。

就这样。助手会依次备份、瘦身、构建并安装启动器，每一步都用电视截图核对。如果你想自己动手，下面的章节就是各个组成部分。

## 仓库内容

| 路径 | 是什么 |
|---|---|
| [`PROMPT.md`](PROMPT.md) | 提示词。不绑定品牌；助手会自己发现你电视上的臃肿应用。 |
| [`launcher/`](launcher/) | tvOS 风格启动器。在 `app/src/main/res/values/config.xml` 里改你的 Dock、隐藏应用和 Live TV 包名，然后运行 `build-and-install.sh`。 |
| [`tools/tv.py`](tools/tv.py) | 纯 Python 的 ADB 客户端（shell、批处理、push、pull）。在官方 `adb` 被拦的地方也能用。 |
| [`tools/b64push.py`](tools/b64push.py) | 把较大的文件（APK）按 base64 分块上传，带 md5 校验。 |
| [`tools/disable.sh`](tools/disable.sh)、[`tools/restore.sh`](tools/restore.sh) | 应用或撤销一份瘦身清单。 |
| [`examples/`](examples/) | 各型号电视的瘦身清单。欢迎补充你的，见 [CONTRIBUTING.md](CONTRIBUTING.md)。 |

## 启动器

一个小巧的原生 Android TV 桌面（`com.gathin.tvhome`），为 1 GB 内存的电视而造：

- 纯黑背景，像 Apple TV 4K。
- 底部 Dock 是一条浅色圆角长带，放你选的四个应用。上方的展示区会以大字显示当前聚焦的 Dock 应用名，并带一层从其横幅采样来的环境光晕。按方向下键，Dock 上滑，露出其余应用的网格。
- 一块半透明玻璃高亮在图标间弹性滑动。聚焦的图标会浮起并显示名字。
- 右上角磨砂胶囊：**Inputs**（HDMI 端口、AV 和 Live TV 的下拉菜单）、**Wi-Fi**（显示网络名）和 **设置**。左上角是问候语、大号细体时钟和日期，字体为 [Outfit](https://fonts.google.com/specimen/Outfit)。
- 确定性的方向键导航、记住上次聚焦的图标、交错入场动画。无模糊、无视频、无网络、无后台服务。

在 [`launcher/app/src/main/res/values/config.xml`](launcher/app/src/main/res/values/config.xml) 里配置：Dock 顺序、隐藏应用、Live TV 包名和列数。构建并安装：

```bash
TV_HOST=192.168.1.50 launcher/build-and-install.sh
```

需要 JDK 17 和 Android SDK（platform 34、build-tools 34.0.0）。macOS 上：
`brew install openjdk@17 android-commandlinetools && sdkmanager "platforms;android-34" "build-tools;34.0.0"`。

## 手动和电视通信

```bash
python3 -m venv tools/venv && tools/venv/bin/pip install -r tools/requirements.txt
export TV_HOST=192.168.1.50                                   # 你电视的 IP
tools/venv/bin/python -I tools/tv.py --probe                 # 型号、Android 版本、固件
tools/venv/bin/python -I tools/tv.py 'pm list packages'      # 执行一条命令
printf 'cmd1\ncmd2\n' | tools/venv/bin/python -I tools/tv.py --batch
tools/disable.sh examples/xiaomi-mi-tv-4a/debloat.txt        # 应用一份清单
tools/restore.sh examples/xiaomi-mi-tv-4a/debloat.txt        # 撤销它，恢复原桌面
```

### 我们踩过的坑

- **macOS 上 `adb connect` 报 "No route to host"**，而端口明明是开的。近期的 macOS 把 Google 签名的 `adb` 当作独立应用受"本地网络"隐私管控，会静默拦截它。`tools/tv.py` 完全绕开这个二进制。
- **有些电视没有"无线调试"开关**（小米等品牌）。仅 USB 调试也会在 5555 端口开放网络 ADB。
- **串联的 `pm` 命令**（`pm disable a; pm disable b`）可能在第一条之后静默停下。每条命令单独一次 shell 调用；`--batch` 就是这样做的。
- **大文件推送会卡住**（通过 Python 客户端时）。APK 请用 `tools/b64push.py`。
- **务必先装好新启动器再禁用原桌面**，否则按主页键会落到一个出厂测试应用上。
- **第三方启动器**常把换壁纸这种基础功能做成收费项。所以有了 `launcher/`。
- **换桌面后没有屏幕键盘**（在小米上见过）：默认输入法指向一个没装的百度输入法。用下面这条修：
  `tools/tv.py 'ime set com.google.android.inputmethod.latin/com.android.inputmethod.latin.LatinIME'`。

## 支持的电视

任何运行 Android TV 或 Google TV 的设备：小米/Redmi、TCL、海信、索尼、一加、Realme、Nvidia Shield 等大多数品牌。不支持三星 Tizen、LG webOS 和 Amazon Fire TV（系统不同，无法用 ADB 替换桌面）。已测试的型号见 [`examples/`](examples/)。

## 参与贡献

欢迎提交你电视的瘦身清单、改进启动器或打磨提示词。见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 授权

MIT，见 [`LICENSE`](LICENSE)。Outfit 字体采用 SIL 开放字体授权，见 `launcher/FONT-LICENSE-OFL.txt`。
