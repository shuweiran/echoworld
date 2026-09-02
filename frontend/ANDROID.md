# EchoWorld Android APK

Android 包是精简移动端入口，只包含一般模式的文本/Gal 对话核心功能：角色选择、Agent 库、场景启动、实时对话、设置和历史记录。剧本杀、狼人杀、Phaser 2D 地图与 Babylon 3D 不在移动端入口中；桌面/网页构建不受影响。

## 构建

1. 安装 Android Studio、Android SDK（API 35）和 JDK 21，并设置 `ANDROID_HOME` 或 `ANDROID_SDK_ROOT`。
2. 复制 `.env.mobile.example` 为 `.env.mobile.local`，填写手机可访问的后端 HTTPS 地址；不要把密钥写入仓库。
3. 执行：

```powershell
npm install
npm run mobile:apk
```

Debug APK 输出在 `android/app/build/outputs/apk/debug/app-debug.apk`。安装到连接的设备可使用 `adb install -r android/app/build/outputs/apk/debug/app-debug.apk`。

`npm run mobile:build` 只验证移动端前端构建；`npm run mobile:sync` 会把构建产物同步进 Capacitor Android 工程。`mobile:apk` 会自动使用项目本地协作临时目录，规避部分 Windows JDK 21 的 loopback 临时 socket 问题。
