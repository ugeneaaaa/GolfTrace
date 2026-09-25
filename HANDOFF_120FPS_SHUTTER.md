# 120fps 快门问题：已定位并修复

2026-09-25 在 OnePlus PLK110 / Android 16 上完成。用户的问题是：1080p120 档选 1/500 和 1/4000，画面和拖影看上去完全一样，等于快门不可控。

## 根因

之前以为“高速档只能用相机自己的自动曝光”，所以对高速会话用了 Android 16 的 AE 快门优先（`CONTROL_AE_PRIORITY_MODE_SENSOR_EXPOSURE_TIME_PRIORITY`）。这条路径在本机是假的：

- 申请 1/4000 时，`CaptureResult.SENSOR_EXPOSURE_TIME` 回报 0.25ms，界面读数看着正常；
- 但厂商字段 `org.quic.camera2.properties_sensor.SensorActualExposureTime` 始终是 5ms，即传感器从未按请求缩短曝光；
- 同一暗场 1/500 与 1/4000 的预览亮度都是 117 左右，几乎不动。

所以旧实现里“数字变了、画面没变”。快门优先的控制请求被 HAL 接受了，但没有作用到传感器上。

## 修复

高速会话改成和普通档一样走 `CONTROL_AE_MODE_OFF` 手动曝光（`SENSOR_EXPOSURE_TIME` + `SENSOR_SENSITIVITY` + `SENSOR_FRAME_DURATION`），不再依赖快门优先。同时在本机停用快门优先，未验证的高速组合如实标为自动曝光。

`CameraActivity.kt` 的 `canSetManualExposure()` 限定为 PLK110 + Android 16 + 主摄（lens id 2）+ 1080p120，即唯一实测通过的组合；`priorityModeIgnoredOnThisDevice()` 记录为什么本机不给快门优先。

## 证据

| 场景 | 请求 | 成片实际 | 编码成片亮度 YAVG |
|---|---|---|---|
| 旧 · 快门优先 120fps | 1/4000 | 传感器 5ms | 117.2（1/500 也是 117.2） |
| 新 · AE OFF 手动 120fps | 1/4000 | 传感器 0.25ms | 18.7 |
| 新 · AE OFF 手动 120fps | 1/500 | 传感器 2ms | 73.2 |
| 新 · AE OFF 手动 60fps（对照） | 1/4000 | 传感器 0.25ms | 19.3 |
| 新 · AE OFF 手动 60fps（对照） | 1/500 | 传感器 2ms | 75.3 |

新旧 120fps 成片都是 120fps（523 帧 / 4.37s），即修快门没有把帧率打回去。亮度数据取自编码后的 mp4，不是预览读数。

## 复现与回归

`app/src/androidTest/.../ExposureProbe.kt` 是手动启动的真机探针：固定 ISO，分别用 1/500 与 1/4000 录 1080p 短片，读回报值和厂商实际曝光字段，并断言画面亮度必须随快门变化。命令见 `README.md` 的「真机曝光回归」。

用来复现这个 bug 的写法是先跑断言看到它失败（120fps 两次亮度都是 117 左右），修完再跑必须通过。只比较界面读数会复现不了这个 bug —— 读数一直是对的。

## 尚未验证 / 已知边界

- 手动曝光只在本机主摄（lens id 2）的 1080p120 验证过。其他镜头或机型的高速档没有对照测试，一律如实显示为自动曝光。
- 静止暗场的亮度差已证明曝光时间真的不同，但没有用移动物体量过拖影长度。要确认杆头更清晰，最直接的办法是拍一段挥杆：1/2000 以上应该明显更锐。
- 快门优先的代码路径仍然保留给其他设备，因为只在 PLK110 上证实无效。

## 手机现状

- 只保留一个应用：`com.eugene.golftrace` 0.2.1（debug 与 release 共用同一 applicationId，不再有 `.debug` 双开）。
- 旧的 `.debug` 安装包与设置备份在本机临时目录 `/private/tmp/golftrace-device-backup.WyENX8/`，不进仓库。
