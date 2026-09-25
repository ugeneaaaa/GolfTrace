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
- `CameraActivity.kt`：录制页。普通档 MediaRecorder（优先 HEVC）；1080p120 高速档在本机主摄走 AE OFF 手动曝光（快门 + ISO）。快门优先模式在本机实测无效，已停用；未验证的高速组合如实标为自动曝光。界面显示相机回报的快门/ISO；镜头按物理 ID 去重
- `PlayerActivity.kt`：独立回看。MediaCodec → Surface；打开优先用时长+帧率建 CFR 时间表；显示 fps（默认 3）；截图 / 连截写入 `Pictures/杆头回看`（连截：每换一帧存一张，同帧不重复）
- `MainActivity.kt` + `Analysis.kt` + `ShaftTracker.kt` + `Decoder.kt`：分析流程与跟踪
- `Views.kt`：轨迹叠加等自定义绘制

## 离线回放（单元测试）

```bash
GT_RAW=帧.gray GT_W GT_H GT_FPS=60 GT_SEED="帧,x,y" GT_GRIP="帧,x,y" GT_OUT=out.csv \
  ./gradlew testDebugUnitTest --tests '*TrackReplay*'
```

帧用 ffmpeg 导出：`-vf scale=W:H,format=gray -f rawvideo`。

## 真机曝光回归（可选）

`app/src/androidTest/.../ExposureProbe.kt` 是只在手动启动时运行的真机探针：固定 ISO，分别以 1/500 和 1/4000 录 1080p 短片，读取 `CaptureResult` 与厂商实际曝光字段，并断言画面亮度必须随快门变化。

```bash
./gradlew assembleDebug assembleDebugAndroidTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb install -r app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk
adb shell am instrument -w -e record true -e assertExposure true \
  com.eugene.golftrace.test/com.eugene.golftrace.ExposureProbe
```

跑到 120fps 且断言失败，就说明快门又没有真正作用到传感器上。探针产物写在应用私有目录 `files/exposure-probe/`（报告 + 短片），不进相册。

## 已知限制

（分析侧，2026-09-24 用正面机位慢动作素材复测；录制侧以真机能力为准）

- 正面慢动作素材：上杆、顶点和可辨认的下杆杆头会绘制；模糊或被遮挡的帧按低置信度留空，不再整段隐藏下杆
- 只跟到击球后约 0.05s，之后腿部轮廓容易被误认成杆身
- 背面机位（第一段）杆头在顶点出画面，没法跟
- 旧 BMD 文件音画不同步（击球声晚 ~1s），击球声只作候选；本 App 录的是同步的
- 部分机型高速档只有真实可用的帧率（例如 OnePlus PLK110 实测 120fps 可用，假 240 已隐藏）
- **120fps 快门已可用（2026-09-25 真机实测）。** OnePlus PLK110 / Android 16 的 1080p120 高速会话里 `CONTROL_AE_PRIORITY_MODE`（快门优先）无效：申请 1/4000 时 `CaptureResult.SENSOR_EXPOSURE_TIME` 回报 0.25ms，但厂商 `org.quic.camera2.properties_sensor.SensorActualExposureTime` 始终是 5ms，1/500 与 1/4000 的成片亮度几乎相同。改用 AE OFF 手动曝光后真实生效：1080p120 编码成片仍是 120fps，同场景 1/500 与 1/4000 的 Y 平均值为 73.2 与 18.7（60fps 对照 75.3 与 19.3）。本机因此不再提供快门优先。
- 手动曝光只在本机主摄（lens id 2）的 1080p120 验证过；其他镜头或机型的高速档按各自能力显示，未验证的一律标为自动曝光。

## 仓库

`git@github.com:ugeneaaaa/GolfTrace.git`
