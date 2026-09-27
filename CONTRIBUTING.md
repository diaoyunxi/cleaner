# 贡献指南

感谢你对 Cleaner 项目的关注！这是一个 Android 文件清理工具，支持重复文件检测和可恢复删除。

## 开发环境

- **IDE：** Android Studio Hedgehog+
- **语言：** Kotlin 1.9+
- **最低 API：** 21（Android 5.0）
- **构建：** Gradle 8.x

## 构建步骤

1. 使用 Android Studio 打开项目
2. 等待 Gradle 同步完成
3. 连接设备或启动模拟器
4. 点击 Run 按钮

## 安全注意事项

- 文件删除操作使用 MediaStore 回收站 API（Android 11+），确保可恢复
- 文件扫描需处理 Storage 权限
- 不上传或收集用户文件信息

## 代码规范

- 遵循 `.editorconfig` 中定义的 Kotlin 代码风格
- 新功能需添加对应的单元测试
- 提交前确保 Gradle 编译通过

## 提交 Pull Request

1. Fork 本仓库并创建功能分支
2. 确保编译通过且测试通过
3. 在真实设备或模拟器上验证
4. 遵循 Conventional Commits 规范提交
