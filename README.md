# GolfTrace 杆头轨迹

Android 自用 App（Kotlin，零第三方依赖）。手机上选/分享挥杆视频 → 自动找挥杆 → 标杆头/握把 → 半自动跟踪 → 轨迹叠加图、3D（x/y/时间）、节奏数据，保存 PNG 到相册 Pictures/杆头轨迹。

构建：`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug`

- Decoder.kt：MediaCodec 硬解，只取 Y 平面降采样；彩色多重曝光；音轨击球声检测。
- ShaftTracker.kt：杆头跟踪（三帧差 → Hough 找杆身 → 离挥杆中心远的一端 → Viterbi 全局最优；9 个候选中心取最优，容忍握把点击误差）。
- Analysis.kt：挥杆候选（静止→上杆→静止→下杆 的节奏）、节奏分段（运动能量 15% 阈值）、握把模板跟踪、报告。
- CameraActivity.kt：Camera2 手动快门/ISO（测光一次后锁定）、竖构图、换镜头/变焦、HEVC 低码率 + 收音、水平仪、DTL/正面 两个机位预设 + 参考画面半透明叠加。
- 离线回放：`GT_RAW=帧.gray GT_W GT_H GT_SEED="帧,x,y" GT_GRIP="帧,x,y" GT_OUT=out.csv ./gradlew testDebugUnitTest --tests '*TrackReplay*'`（帧用 ffmpeg `-vf scale=W:H,format=gray -f rawvideo` 导出）。

已知限制（2026-09-19 用 9/18 素材实测）：
- 正面机位 60fps：上杆、顶点准；下杆中段 2–3 帧杆身糊成一片，位置是近似；只跟到击球后 ~0.05s（之后腿的轮廓会干扰）。
- 背面机位（第一段）杆头在顶点出画面，没法跟。
- 旧 BMD 文件音画不同步（击球声晚 ~1s），击球声只作候选；本 App 录的是同步的。
- 模拟器只能测到 720p/30fps，真机的 60fps、手动快门、多镜头没实测。
