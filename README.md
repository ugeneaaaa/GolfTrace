# GolfTrace 杆头轨迹

Android 自用 App（Kotlin，**零第三方依赖**）。三个入口互不绑死：

| 入口 | 做什么 |
|------|--------|
| **录制** | Camera2 手动快门 / ISO、显示相机回报的曝光、按机器实际能力列镜头与 分辨率×帧率、水平仪、DTL / 正面机位预设与参考画面叠加 |
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
- `CameraActivity.kt`：录制页。普通档 MediaRecorder（优先 HEVC）；只有普通会话支持 120fps 且支持手动传感器时才提供 120fps 全手动曝光。Android 16 若报告支持快门优先，高速档允许选快门、由系统调 ISO；其余高速档标为自动曝光。界面显示相机回报的快门/ISO；镜头按物理 ID 去重
- `PlayerActivity.kt`：独立回看。MediaCodec → Surface；打开优先用时长+帧率建 CFR 时间表；显示 fps（默认 3）；截图 / 连截写入 `Pictures/杆头回看`（连截：每换一帧存一张，同帧不重复）
- `MainActivity.kt` + `Analysis.kt` + `ShaftTracker.kt` + `Decoder.kt`：分析流程与跟踪
- `Views.kt`：轨迹叠加等自定义绘制

## 离线回放（单元测试）

```bash
GT_RAW=帧.gray GT_W GT_H GT_FPS=60 GT_SEED="帧,x,y" GT_GRIP="帧,x,y" GT_OUT=out.csv \
  ./gradlew testDebugUnitTest --tests '*TrackReplay*'
```

帧用 ffmpeg 导出：`-vf scale=W:H,format=gray -f rawvideo`。

## 已知限制

（分析侧，2026-09-24 用正面机位慢动作素材复测；录制侧以真机能力为准）

- 正面慢动作素材：上杆、顶点和可辨认的下杆杆头会绘制；模糊或被遮挡的帧按低置信度留空，不再整段隐藏下杆
- 只跟到击球后约 0.05s，之后腿部轮廓容易被误认成杆身
- 背面机位（第一段）杆头在顶点出画面，没法跟
- 旧 BMD 文件音画不同步（击球声晚 ~1s），击球声只作候选；本 App 录的是同步的
- 部分机型高速档只有真实可用的帧率（例如 OnePlus PLK110 实测 120fps 可用，假 240 已隐藏）
- **120fps 快门优先尚未通过成片验证。** OnePlus PLK110 / Android 16 的 1080p120 模式中，选择 1/500 和 1/2000 时，`CaptureResult.SENSOR_EXPOSURE_TIME` 分别回报 2 ms 和 0.5 ms。但同一静止暗场分别以 1/500、1/4000 录制的两段视频，代表性区域亮度几乎相同（Y 平均值约 42.2 与 42.6）；是否真的缩短物理曝光、降低运动模糊尚未验证。界面读数是相机回报值，不等于成片效果。

## 仓库

`git@github.com:ugeneaaaa/GolfTrace.git`
