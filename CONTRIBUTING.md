# 参与开发与反馈

本分支主要测试 Nikon Zf 的 AP/STA 连接和原图传输。提交问题前请查看 [兼容性说明](docs/COMPATIBILITY.md)。

## 报告问题

请提供应用版本、Android 版本、相机型号和固件、AP/STA 模式、复现步骤、预期与实际结果。可在“关于”中主动导出诊断日志，但公开前请删除本地 IP、文件名、设备标识等个人信息。不要上传照片、Wi-Fi 密码、相机序列号或未经处理的抓包。

蓝牙入口仍是实验功能；蓝牙失败与 AP/STA 失败请分开报告。

## 提交代码

- 使用 Java 17 和仓库中的 Gradle Wrapper。
- 运行 `./gradlew testDebugUnitTest lintDebug assembleDebug`（Windows 使用 `gradlew.bat`）。
- 解析来自相机的数据时限制长度和数量；下载必须保留原始字节。
- 协议和文件分类改动应添加有意义的单元测试。
- 不要提交构建产物、调试签名、设备日志或个人照片。

贡献按 [GPL-3.0-or-later](LICENSE) 授权。本项目基于 [Nikon Pull](https://github.com/noisetta/Nikon-Pull)；请保留上游版权和许可信息。
