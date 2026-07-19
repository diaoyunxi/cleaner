# cleaner

一款 Android 重复文件清理工具，通过三级哈希比对（文件大小 → XXH64 快速哈希 → SHA-256 精确确认）精准定位系统中的重复文件。

## 功能特性

- **三级哈希比对**: 先按文件大小过滤，再用 XXH64 快速初筛，最后 SHA-256 精确确认，兼顾速度与准确性
- **智能自动选择**: 自动保留每组中修改时间最新的文件，标记其余为待删除
- **扫描预览**: 扫描前展示将要扫描的目录列表，确认后开始
- **实时进度**: 扫描过程中实时显示已扫描文件数、重复组数、可释放空间
- **灵活排序**: 支持按文件大小、重复数量、文件名、可释放空间排序
- **手动调整**: 每个文件条目可单独切换删除/保留状态
- **深色模式**: 支持浅色/深色主题切换
- **Material Design 3**: 现代化 UI 设计

## 技术栈

- **语言**: Kotlin
- **最低 SDK**: Android 10 (API 29)
- **目标 SDK**: Android 15 (API 35)
- **UI**: Material Design 3
- **异步**: Kotlin Coroutines + Flow
- **快速哈希**: XXH64 (via lz4 library)
- **精确哈希**: SHA-256

## 使用方法

1. 安装 APK 到 Android 设备
2. 授予「所有文件访问」权限
3. 点击「开始扫描」
4. 预览扫描目录，确认后开始
5. 等待扫描完成，查看结果
6. 可使用「自动选择」保留最新文件
7. 手动调整后点击「删除选中」
8. 确认后批量删除

## 构建方法

```bash
# 环境要求
# - JDK 17+
# - Android SDK 35
# - Android Build Tools 35.0.0

# 设置环境变量
export ANDROID_HOME=/path/to/android-sdk
export JAVA_HOME=/path/to/jdk-17

# 构建 Release APK
./gradlew assembleRelease

# APK 输出路径
# app/build/outputs/apk/release/app-release.apk
```

## 权限说明

| 权限 | 用途 |
|------|------|
| `MANAGE_EXTERNAL_STORAGE` | 读取所有文件以扫描重复 |

## 算法说明

```
文件A ──┐
文件B ──┤  第1层: 大小不同 → 非重复
文件C ──┘  第2层: 大小相同 → XXH64(前64KB) → 不同则非重复
           第3层: XXH64 相同 → SHA-256(完整) → 相同则确认重复
```

## 许可证

MIT License