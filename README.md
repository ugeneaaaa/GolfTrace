# GolfTrace 杆头轨迹

Android 自用 App（Kotlin，**零第三方依赖**）。三个入口互不绑死：

| 入口 | 做什么 |
|------|--------|
| **录制** | Camera2 手动快门 / ISO、按机器实际能力列镜头与 分辨率×帧率、水平仪、DTL / 正面机位预设与参考画面叠加 |
| **回看** | 独立逐帧播放（与分析无关）：显示 fps 慢放、时间线、截图 / 连截、双指放大 |
| **杆头分析** | 选/分享挥杆视频 → 自动找挥杆 → 标杆头/握把 → 半自动跟踪 → 轨迹叠加、节奏，PNG 存到 `Pictures/杆头轨迹` |

版本见 `app/build.gradle.kts` 的 `versionName`（应用内「关于」同此）。

## 构建

```bash
JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug
```

真机安装（示例）：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 主要文件

- `HomeActivity.kt`：首页三入口 + 关于
- `CameraActivity.kt`：录制页。普通档 MediaRecorder（优先 HEVC）；高速档走厂商 CamcorderProfile / 会话面，避免假 240fps；镜头按物理 ID 去重
- `HighSpeedRecorder.kt`：高速自建编码器备选（MediaRecorder 录制面进不了高速会话时）
- `PlayerActivity.kt`：独立回看。MediaCodec → Surface；打开优先用时长+帧率建 CFR 时间表；显示 fps（默认 3）；截图 / 连截写入 `Pictures/杆头回看`（连截：每换一帧存一张，同帧不重复）
- `MainActivity.kt` + `Analysis.kt` + `ShaftTracker.kt` + `Decoder.kt`：分析流程与跟踪
- `Views.kt`：轨迹叠加等自定义绘制

## 离线回放（单元测试）

```bash
GT_RAW=帧.gray GT_W GT_H GT_SEED="帧,x,y" GT_GRIP="帧,x,y" GT_OUT=out.csv \
  ./gradlew testDebugUnitTest --tests '*TrackReplay*'
```

帧用 ffmpeg 导出：`-vf scale=W:H,format=gray -f rawvideo`。

## 已知限制

（分析侧，2026-09-19 用 9/18 素材实测；录制侧以真机能力为准）

- 正面机位 60fps：上杆、顶点准；下杆中段 2–3 帧杆身糊成一片，位置是近似；只跟到击球后 ~0.05s（之后腿的轮廓会干扰）
- 背面机位（第一段）杆头在顶点出画面，没法跟
- 旧 BMD 文件音画不同步（击球声晚 ~1s），击球声只作候选；本 App 录的是同步的
- 部分机型高速档只有真实可用的帧率（例如 OnePlus PLK110 实测 120fps 可用，假 240 已隐藏）

## 仓库

`git@github.com:ugeneaaaa/GolfTrace.git`
