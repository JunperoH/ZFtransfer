# 相机兼容性

| 相机 | 连接方式 | 当前结论 |
| --- | --- | --- |
| Nikon Zf，固件 3.00 | Wi-Fi AP | 真机已验证浏览与 JPEG/NEF 原图下载 |
| Nikon Zf，固件 3.00 | Wi-Fi STA | 真机已验证首次登记、浏览与 JPEG/NEF 原图下载；旧配置可能拒绝新的客户端身份 |
| Nikon Zf，固件 3.00 | 蓝牙 | 尚未完成配对与 Wi-Fi 唤起 |
| 其他 Nikon 相机 | AP/STA | 此分支未验证，欢迎提交测试结果 |

上游 [Nikon Pull](https://github.com/noisetta/Nikon-Pull) 针对部分旧款 WMU 相机（包括 D5300）有独立验证记录；这些结果不能自动推断到本分支的当前 UI 和连接流程。

应用使用 PTP/IP 读取设备、存储和对象信息。相机至少需要提供 `GetStorageIDs` (`0x1004`)、`GetObjectHandles` (`0x1007`)、`GetObjectInfo` (`0x1008`) 和 `GetObject` (`0x1009`)；缩略图能力因机型而异。兼容性还取决于相机的 Wi-Fi 登记与会话行为，不能只凭 PTP 操作列表判断。

提交测试结果时请说明相机型号及固件、Android 版本、AP/STA 模式、是否能列出照片、JPEG/RAW 下载结果，以及日志中的错误码。不要公开相机序列号、Wi-Fi 密码、原始照片或未脱敏的日志。
