<div align="center">

# Animeko for Meta Quest 3

Animeko 的 Meta Quest 3 VR 适配版 —— 在虚拟空间中沉浸式追番。

基于 [open-ani/animeko](https://github.com/open-ani/animeko) v6.0.0-beta02。

</div>

## 与原版的区别

本分支是 Animeko 的 Meta Quest 3 专版，在原版基础上增加了：

### VR 特性

- **空间面板**：播放器、番剧详情、弹幕列表等均在独立的 3D 空间面板中渲染
- **手柄交互**：握手柄侧键拖拽面板，摇杆缩放，两只手柄独立控制不同面板；操作方向以头部为参照（手向右放大、向前推远），转头不影响
- **空间音频**：声音从虚拟面板位置发出，随面板位置实时变化（基于 Meta Spatial SDK）
- **彩透/背景模式**：支持彩透、纯黑、深蓝、360° 全景图等多种 VR 背景
- **VR 设置页**：彩透开关、空间音频开关、背景模式、3D 转换相关选项

### 3D 视频转换（实验）

- 用 [MiDaS v2.1](https://github.com/isl-org/MiDaS) 深度模型（TFLite / [LiteRT](https://github.com/google-ai-edge/LiteRT)，GPU delegate 加速）**实时**估算每一帧的深度，再通过 DIBR 视差把平面番剧渲染成双目立体画面
- 视频移到一个**独立的全屏双目立体面板**，主面板的文字、控制条、弹幕保持单眼正常显示；视频面板绑定在主面板后方，拖动/缩放主面板时三者一起移动
- **深度与画面严格对齐**：渲染器维护视频环形缓冲，显示与最新深度图对应的那一帧，运动物体的立体形状不会滞后
- 由于深度推理需要约 1~2 帧时间，视频会延迟约 1~2 帧，**音频因此轻微超前（约 30~50ms）**——这是为换取深度同步的可接受代价，听感上基本无感
- 可调项（VR 设置页）：**视差强度**、**深度时间滤波**、**深度固定缩放**、**弹幕前后位置**

### 技术栈

- 基于 [open-ani/animeko](https://github.com/open-ani/animeko) main 分支，定期合并上游更新
- [Meta Spatial SDK v0.11.1](https://developers.meta.com/horizon/develop/spatial-sdk) — ECS-based 空间面板管理
- [Compose Multiplatform](https://www.jetbrains.com/compose-multiplatform/) — 跨平台 UI
- [Kotlin Multiplatform](https://kotlinlang.org/docs/multiplatform.html) — 共享业务逻辑
- [LiteRT (TensorFlow Lite)](https://github.com/google-ai-edge/LiteRT) — 深度模型推理（GPU delegate）

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

> 构建时会从 Meta 的 Maven 仓库下载 Meta Spatial SDK，需要接受 [Meta Platform Technologies SDK 许可](https://developer.oculus.com/licenses/oculussdk/)。

## 下载

仅支持 Meta Quest 3 设备。暂无发布渠道。

## 上游项目

原项目功能和下载请见 [open-ani/animeko](https://github.com/open-ani/animeko)。

## 许可与第三方组件

本项目与原项目相同，使用 [GNU AGPL v3](LICENSE.txt) 许可证。

本仓库内的源码遵循 AGPLv3；仓库内**二进制组件与依赖**的许可如下，均已与本项目的 AGPLv3 兼容（或其自身专有、作为构建期依赖不随仓库分发）：

| 组件 | 用途 | 许可 |
|---|---|---|
| [MiDaS v2.1](https://github.com/isl-org/MiDaS)（`model_opt.tflite`） | 深度估算模型 | MIT |
| [LiteRT / TensorFlow Lite](https://github.com/google-ai-edge/LiteRT) | 深度模型推理运行时 | Apache 2.0 |
| [Meta Spatial SDK](https://developers.meta.com/horizon/develop/spatial-sdk) | VR 空间面板/空间音频 | Meta Platform Technologies SDK（专有，构建期 Maven 依赖，不随仓库分发） |
| [Poly Haven](https://polyhaven.com/) 全景图 | VR 背景资源 | CC0 |

**结论**：MiDaS 模型（MIT）与 LiteRT（Apache 2.0）均为宽松许可，与 AGPLv3 兼容，可直接上传 GitHub 并随仓库分发。Meta Spatial SDK 为 Meta 专有许可，但它只作为构建期二进制依赖（通过 Gradle 从 Meta Maven 下载），**不随本仓库分发**，因此不影响本仓库以 AGPLv3 开源。
