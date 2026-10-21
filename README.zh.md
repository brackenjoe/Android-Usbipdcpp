# Android-Usbipdcpp

[English](README.md)

> **关于这个复刻**：这是 [yunsmall/Android-Usbipdcpp](https://github.com/yunsmall/Android-Usbipdcpp)
> 的复刻（fork），2026 年 10 月由 brackenjoe 修改，增加了向主机拨出（连接前与主机双向认证，密钥存于 Android Keystore，指纹验证后 1 小时内可用）和仅监听回环地址，并去掉了相机权限。
> 插入设备时不弹出任何提示：点"绑定"共享设备，Android 每次插入会请求一次 USB 权限。上游 v0.0.11 起的"自动重新绑定曾共享的设备"在设置中，本复刻默认关闭。usbipdcpp 库没有修改。详情见 [README.md](README.md) 的 "About this fork" 一节。

📱🔌 一个 Android USB/IP 服务端应用，通过网络共享 USB 设备，基于一个专门用于创建usbip服务器的库——[usbipdcpp](https://github.com/yunsmall/usbipdcpp)。

## ✨ 功能

- 🔗 从 Android 共享 USB 设备到远程计算机
- 📦 支持多设备
- ⚙️ 可配置端口
- 🌐 中英文界面
- 📋 实时日志

## 📋 要求

| 项目 | 要求 |
|------|------|
| 🤖 Android | 8.1+ (API 28+) |
| 💻 硬件 | USB OTG 支持 |
| 🖥️ 客户端 | USB/IP 客户端（如 Linux 的 `usbip`） |

## 🚀 使用

```
Android 设备                      远程计算机
    │                                │
    │  1. 🔌 连接 USB 设备           │
    │  2. ▶️ 启动服务器              │
    │  3. ✅ 授予 USB 权限           │
    │  4. 📎 绑定设备                │
    │                                │
    │◀──────── 网络 ────────────────▶│
    │                                │
    │                    5. 📋 usbip list -r <ip>
    │                    6. 🔗 usbip attach -r <ip> -b <busid>
```

## 🔨 编译

```bash
# 克隆（含子模块）
git clone --recurse-submodules https://github.com/yunsmall/Android-Usbipdcpp.git

# 如果已克隆但未初始化子模块
git submodule update --init --recursive

# 使用 Android Studio 编译（需要 NDK）
```

## 📄 许可证

[GPL-3.0](LICENSE)
