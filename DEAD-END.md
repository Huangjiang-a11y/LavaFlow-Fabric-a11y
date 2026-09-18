# 死路分支:`archive/26.3-dead-end`

> [!WARNING]
> **这条路线走不通,不要基于本分支继续开发。**
> 活的 26.3 移植在 **`port/26.3-fresh`**。

本分支原名 `port/26.3`,于 2026-09-18 改名归档。

## 这个分支当初想做什么

提交历史显示,它试图**通过更换构建工具链**让 26.3 编译通过:

| 提交 | 内容 |
| --- | --- |
| `67eb322` | 压平 source set 到 `src/main`;为 Loom 1.18 简化构建 |
| `5e9c329` | Kotlin DSL 下用字符串形式调用 `modImplementation` |
| `7672dc9` | 修 Loom 1.18 非混淆环境:标准 source set + `modImplementation` |

**这些都不是编译不过的原因。**

## 真正的原因:包名整体搬迁

Minecraft 26.3 把整个 GPU 抽象层从 `com.mojang.blaze3d.*` 搬到了 `com.mojang.renderpearl.*`。

对 26.3 客户端 jar 的 **11383 个 class** 做全量字节码扫描,旧名字的引用数:

| 旧名字 | 26.3 中的引用数 |
| --- | --- |
| `com/mojang/blaze3d/systems/GpuBackend` | **0** |
| `com/mojang/blaze3d/systems/GpuDevice` | **0** |
| `com/mojang/blaze3d/textures/GpuTexture` | **0** |
| `com/mojang/blaze3d/pipeline/RenderPipeline` | **0** |
| `com/mojang/blaze3d/GLFWErrorCapture` | **0** |

`com/mojang/blaze3d/` 现在只剩 **147 个** class(`platform`、`vertex`、`RenderSystem` 等);GPU 抽象层整体迁到 `com/mojang/renderpearl/`,**268 个** class。

主要映射:

| 26.2 及以前 | 26.3 |
| --- | --- |
| `blaze3d.systems.GpuBackend` / `GpuDevice` / `RenderPass` | `renderpearl.api.device.GpuBackend` / `renderpearl.api.device.GpuDevice` / `renderpearl.api.commands.RenderPass` |
| `blaze3d.systems.GpuDeviceBackend` / `RenderPassBackend` | `renderpearl.backend.api.*` |
| `blaze3d.textures.*` / `blaze3d.buffers.*` | `renderpearl.api.textures.*` / `renderpearl.api.buffers.*` |
| `blaze3d.pipeline.*` / `blaze3d.shaders.*` | `renderpearl.api.pipeline.*` |
| `blaze3d.vulkan.*` | `renderpearl.backend.vulkan.*` |
| GLFW(`org.lwjgl.glfw.*`) | SDL(`org.lwjgl.sdl.*`) |

## 为什么换 Loom / 换依赖声明救不了

26.3 **没有混淆** —— 版本 json 里连 `client_mappings` 字段都没有(26.1、26.2 同样如此)。没有映射表,Loom 就不可能把 `renderpearl` 的名字「反映射」回 `blaze3d`:那是 named→named 的纯重命名,任何 Loom 版本都不做这件事。同理,`implementation` 换成 `modImplementation` 与包名毫无关系。

换句话说:**这个分支动的是构建配置,而阻塞点是 API 位置**,两者不相交。

## 正确做法

活分支 `port/26.3-fresh` 的进度:

- `:compileJava`(桌面 smoke app)✅ 通过
- `:compileSodiumStubJava` ✅ 通过
- `:compileMinecraftJava` ❌ 133 个唯一错误 —— import 阶段已完成,剩下的全是 API 语义问题

剩余错误只有 5 类:

1. `GpuTexture` / `GpuBuffer` / `GpuTextureView` / `GpuSampler` 由**类变成接口** → `extends` 改 `implements`,并补全方法
2. 接口新增的必实现方法(`GpuBackend.createDevice(GpuDebugOptions)`、`CommandEncoderBackend.submitRenderPass()`、`GpuDeviceBackend.getTimestampCalibrationOffset()`)
3. `DeviceLimits` / `DeviceFeatures` / `HintsAndWorkarounds` 三个 record 的构造参数变化
4. 已消失的符号:`IntermediaryShaderModule`、`VulkanBindGroupLayout`、`GlslPreprocessor`、`GLFWErrorCapture`
5. 杂项:`getVertexShader` / `getFragmentShader` / `flattenSamplers` 等自定义 shader 抽象需要收窄

## 如果你已经基于本分支做了工作

本分支的全部提交都保留在 `archive/26.3-dead-end` 中,可以 `git cherry-pick`。其中唯一可能有独立价值的是 `build.gradle.kts` 的 Loom 1.18 适配(将来真要升 Loom 时再说),**但它与 26.3 编译无关**。

## 撤销这次改名

```bash
git push origin archive/26.3-dead-end:refs/heads/port/26.3
git push origin --delete archive/26.3-dead-end
```
