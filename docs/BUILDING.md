# 开发说明

## 依赖

* **播放引擎**：Media3 ExoPlayer + MediaSessionService
* **UI 框架**：Jetpack Compose
* **组件规范**：[Miuix](https://github.com/compose-miuix-ui/miuix) 0.9.4
* **玻璃效果**：[Backdrop](https://github.com/Kyant0/AndroidLiquidGlass) 2.0.0
* **系统要求**：Android 12 / API 31 及以上

播放器读取用户授权的本地目录，不上传视频。网络权限用于加载并缓存关于页中的 GitHub 头像。API 31–32 为部分玻璃效果提供兼容绘制；关于页在支持 MIUI 高级材质的系统使用原生透色效果，其它系统使用着色回退。

## 编译和测试

需要 JDK 21、Android SDK 37。Gradle Wrapper 已固定版本及校验和。

```bash
./gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug :app:assembleDebugAndroidTest
```

本地单元测试覆盖生命周期播放策略、相邻视频预读、列表排序、未看优先与已看判定、收藏顺序和队列编辑。界面测试覆盖首次引导、主题与播放偏好保存、玻璃菜单合成、导航与控件显隐、相邻视频预览及渲染视图复用等交互。

beta 另有多目录迁移、授权异常与扫描取消、文档身份去重、双击点赞与手势冲突、诊断导出及报告内容检查。无需电脑的实际手机测试步骤见 [BETA_TESTING.md](BETA_TESTING.md)。

连接已授权的 API 31 以上设备后运行：

```bash
./gradlew :app:connectedDebugAndroidTest
```

编译界面测试不等同于真机测试通过。实际播放仍需在设备上验证目录授权、前后台切换、连续滑动、收藏变化、倍速、锁屏及损坏文件处理。

## Release 打包

Release 关闭调试，启用 R8 代码压缩和资源裁剪。通过环境变量提供发布签名：

* `NEKO_RELEASE_STORE_FILE`：签名库的绝对路径。
* `NEKO_RELEASE_STORE_PASSWORD`：签名库密码。
* `NEKO_RELEASE_KEY_ALIAS`：密钥别名。
* `NEKO_RELEASE_KEY_PASSWORD`：密钥密码。

```bash
./gradlew :app:assembleRelease :app:lintRelease
```

配置签名后，正式 APK 位于 `app/build/outputs/apk/release/app-release.apk`。未配置签名时生成未签名产物。私钥与密码应保存在仓库外，后续版本必须沿用同一签名才能覆盖升级。
