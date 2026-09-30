# Zf 传图

一款开源 Android 工具，通过局域网 PTP/IP 浏览 Nikon Zf 中的照片，并下载 JPEG、NEF 原始文件。无需账号；支持相机 Wi-Fi 热点（AP）和同一局域网（STA）连接。

> 本项目是基于 [Nikon Pull](https://github.com/noisetta/Nikon-Pull) 开发的非官方分支，不属于尼康，也不包含尼康官方 SDK 或专有源代码。源码沿用 GPL-3.0-or-later 许可，见 [LICENSE](LICENSE)。

## 当前状态

| 功能 | 状态 |
| --- | --- |
| Zf AP 连接、浏览、JPEG/NEF 原图下载 | 已在 Zf 真机和 Android 手机上验证 |
| Zf STA 首次登记、连接、原图下载 | 已在 Zf 真机和 Android 手机上验证；新建 STA 配置后完成首次登记 |
| 相册缓存、日期/格式筛选、长按后滑动批量选择 | 已在 Android 虚拟机验证 |
| 自选保存文件夹 | 已在虚拟机用模拟相机验证；真实存储卡目录仍需复测 |
| 蓝牙配对及 Wi-Fi 唤起 | 实验功能，尚未在 Zf 上连通 |

测试范围和已知限制见 [兼容性说明](docs/COMPATIBILITY.md) 与 [测试记录](docs/TESTING.md)。这仍是实验版，建议先用少量照片确认连接和保存位置。

## 使用

需要 Android 10 或更新版本。在相机上打开“连接到智能设备”中的 Wi-Fi 连接。

1. **AP：**让手机加入相机热点；在应用连接设置里选 AP，地址留空，再点“连接”。
2. **STA：**让相机和手机处于同一 Wi-Fi。建议在 Zf 上新建 STA 配置；在应用中选 STA，首次登记时按界面提示在相机上确认。自动发现失败时，可手动填写相机 IPv4 地址。
3. 连接后浏览相机照片；长按一张进入多选，随后滑动可批量选择。点“下载原图”保存，不重新编码。保存目录可在设置中选择。

AP 下载和 STA 下载均不依赖蓝牙。切到系统相册后如连接中断，返回应用时可重新连接；蓝牙功能仍在实验阶段。

## 构建

使用 Java 17、Android SDK 36 和仓库内的 Gradle Wrapper：

```powershell
.\gradlew.bat testDebugUnitTest lintDebug assembleDebug
```

Linux/macOS 使用 `./gradlew`。调试 APK 位于 `app/build/outputs/apk/debug/app-debug.apk`；构建产物、日志和签名密钥均不提交到 Git。GitHub Actions 产出的调试 APK 使用 CI 临时签名，不能保证覆盖安装本机签名的 APK；公开发布前需要单独配置稳定的发布签名，见 [发布说明](docs/RELEASE.md)。

模拟相机和协议诊断工具位于 [`tools`](tools)，使用方法见 [测试记录](docs/TESTING.md)。

## 隐私与贡献

相机通信在本地网络完成；应用没有账号、广告或开发者运营的服务。诊断日志保存在本机，主动分享前请检查其中的本地地址、文件名和设备信息。详见 [隐私说明](PRIVACY.md)。

问题反馈与代码贡献请看 [CONTRIBUTING.md](CONTRIBUTING.md)。尼康及 Nikon Zf 名称属于其权利人，见 [商标说明](TRADEMARKS.md)。
