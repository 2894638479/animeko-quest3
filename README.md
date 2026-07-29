<div align="center">

# Animeko for Meta Quest 3

Animeko 的 Meta Quest 3 VR 适配版 —— 在虚拟空间中沉浸式追番。

基于 [open-ani/animeko](https://github.com/open-ani/animeko) v6.0.0-beta02。

</div>

## 与原版的区别

本分支是 Animeko 的 Meta Quest 3 专版，在原版基础上增加了：

### VR 特性

- **空间面板**：播放器、番剧详情、弹幕列表等均在独立的 3D 空间面板中渲染
- **手柄交互**：握手柄侧键拖拽面板，摇杆缩放，两只手柄独立控制不同面板
- **空间音频**：声音从虚拟面板位置发出，随面板位置实时变化（基于 Meta Spatial SDK）
- **彩透/背景模式**：支持彩透、纯黑、深蓝、360° 全景图等多种 VR 背景
- **VR 设置页**：彩透开关、空间音频开关、背景模式选择

### 技术栈

- 基于 [open-ani/animeko](https://github.com/open-ani/animeko) main 分支，定期合并上游更新
- [Meta Spatial SDK v0.11.1](https://developers.meta.com/horizon/develop/spatial-sdk) — ECS-based 空间面板管理
- [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) — 跨平台 UI
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) — 共享业务逻辑

## 构建

```bash
# 配置签名（local.properties）
signing_release_storeFile=your.keystore
signing_release_storePassword=xxx
signing_release_keyAlias=xxx
signing_release_keyPassword=xxx

# 可选：配置弹弹play API（local.properties）
ani.dandanplay.app.id=xxx
ani.dandanplay.app.secret=xxx

# 编译 debug 包
./gradlew :app:android:assembleDefaultDebug

# 编译 release 包
./gradlew :app:android:assembleDefaultRelease
```

## 下载

仅支持 Meta Quest 3 设备。暂无发布渠道。

## 上游项目

原项目功能和下载请见 [open-ani/animeko](https://github.com/open-ani/animeko)。

## 许可

本项目与原项目相同，使用 [GNU AGPL v3](LICENSE.txt) 许可证。

全景图资源来自 [Poly Haven](https://polyhaven.com/)（CC0）。