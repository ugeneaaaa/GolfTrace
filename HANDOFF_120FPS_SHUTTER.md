# 交接 Prompt：GolfTrace 120fps 快门控制

请继续调查并修复 GolfTrace 在 OnePlus PLK110 上的 120fps 快门控制。目标是在弱光中明确控制**录制成片的物理曝光时间**，使杆头与双手运动模糊随 1/500 → 1/4000 明显改变。不要只以 UI 设定值或 Camera2 `CaptureResult` 回报值宣布成功。

## 项目与边界

- 仓库：`git@github.com:ugeneaaaa/GolfTrace.git`
- 工作分支：`investigate/120fps-shutter`；从此分支继续，不要直接改 `main`。
- 核心文件：`app/src/main/java/com/eugene/golftrace/CameraActivity.kt`；辅助说明见 `README.md`。
- 手机：OnePlus PLK110，Android 16 / API 36；用 `adb devices` 现场确认设备，不在仓库保存设备序列号。
- Debug 包 `com.eugene.golftrace.debug` 与正式包分开；测试先装 debug，不覆盖正式版。
- 用户最重视可控快门和最终视频的清晰度。当前杆头自动检测不可靠，不要因此转移焦点。用中文简短报告测量证据。

## 已做与已证实

普通会话使用 `CONTROL_AE_MODE_OFF` 和手动 `SENSOR_EXPOSURE_TIME` / `SENSOR_SENSITIVITY`。受限高速会话强制 AE 开启；在 Android 16 支持时尝试 `CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY`，即快门优先、自动 ISO。相机的 `CaptureResult.SENSOR_EXPOSURE_TIME` 会随选择改变：1/500 回报 2 ms，1/2000 回报 0.5 ms。1080p120 文件确实约 120fps。

**但这不证明成片曝光有效。** 同一静止暗场的 1/500 和 1/4000 两段测试视频视觉亮度几乎相同；相同 400×400 区域、3 帧样本的平均 Y 分别约 42.2 和 42.6，ISO UI 均回报 250。先前“真机验证成功”结论已撤回。静止场景亮度不能单独证明运动模糊相同；可能有数字增益或 ISP 补偿。物理运动模糊尚未对照测试。

本机临时对照片（不要提交或上传 Git，路径可能随清理失效）：`/private/tmp/golftrace-exposure-check.p7LdWA/shutter500.mp4` 与 `shutter4000.mp4`；代表帧 `frame500.png`、`frame4000.png`。手机端测试片已删除。

## 下一步建议的验证顺序

1. 检查实际请求、`CaptureResult`、录像预览是否走同一条高帧率 request 序列；记录 `SENSOR_EXPOSURE_TIME`、`SENSOR_SENSITIVITY`、`CONTROL_POST_RAW_SENSITIVITY_BOOST` 等可用字段，不把回报值当物理验证。
2. 用**同样照明、同样镜头、同样移动物体速度**录 1/500 与 1/4000；从编码视频的对应帧量测拖影长度，并保留可复核的裁图。再在普通 60fps 全手动模式做同样对照，作为正控制。
3. 若 120fps 的拖影不变，排查受限高速会话、快门优先请求是否被设备 ISP/HAL 忽略或补偿；选择真正可用的录制模式，不以 UI 读数掩盖限制。
4. 修复后重新构建、装 debug、真机重复 A/B；只有成片拖影有明确差异且帧率正确，才在 README 中写“已验证”。

构建：`JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home ./gradlew assembleDebug`。ADB 路径：`/Users/eugeneyang/Library/Android/sdk/platform-tools/adb`；设备由 `adb devices` 现场选择。不要改系统设置、不要触碰正式包或私人视频，除非用户明确同意。
