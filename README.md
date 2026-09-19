# LavaFlow

> [!CAUTION]
> **100% AI 生成的项目**
>
> LavaFlow 的源代码与文档**100% 由 AI 在人类指导下生成**。请将其视为实验性实现：在依赖它之前，先审计代码并在你自己的硬件上测试。

> [!NOTE]
> 本仓库是 LavaFlow 的 **a11y 分支**（fork 自 [EternityQwQ/LavaFlow-Fabric](https://github.com/EternityQwQ/LavaFlow-Fabric) 的 Fabric 移植版），而后者基于原始项目 [BZLZHH/LavaFlow](https://github.com/BZLZHH/LavaFlow)（NeoForge 版）开发。Vulkan 后端的设计与实现全部归功于原项目。本分支聚焦于**早期移动端 GPU 与第三方模组的兼容性修复**。

LavaFlow 是 Minecraft Blaze3D API 的实验性 Vulkan 1.1 图形后端。它用 LavaFlow 自有的 LWJGL Vulkan 实现替换了实际执行的 Blaze3D 渲染路径，并且从不调用 OpenGL。

## 兼容性

| 组件 | 目标 |
| --- | --- |
| Minecraft | 26.3 |
| 模组加载器 | Fabric Loader 0.19.5 或更高 |
| Minecraft 运行时 | Java 25 |
| 图形 API | Vulkan 1.1 |
| Sodium（可选） | 0.9.2（实测版本） |
| 桌面 smoke 渲染器 | Java 21 字节码 |

后端为不具备以下能力的 Vulkan 1.1 设备提供兼容路径：dynamic rendering、synchronization2、push descriptors、multi-draw indirect、非 solid fill mode、顶点属性除数。桌面端与 ARM64 Android 设备均在范围内。实际驱动行为与性能因 GPU 而异。

### 为什么在 Vulkan 1.1 设备上需要 LavaFlow

Minecraft 26.3 自带的 Vulkan 后端在创建管线时**只走 dynamic rendering**：图形管线永远通过 `VkPipelineRenderingCreateInfo`（`VK_KHR_dynamic_rendering`）描述附件，从不设置 `renderPass`。因此在不支持该扩展的设备上，原版 Vulkan 后端无法工作。

LavaFlow 为此提供**旧版渲染通道（legacy render pass）路径**：按颜色/深度附件格式缓存 `VkRenderPass` 与 `VkFramebuffer`，并把管线创建为绑定该 render pass 的传统管线。这条路径是本分支自有的实现，Minecraft 官方后端没有对应物可供参照。

### SPIR-V 版本降级

26.3 把着色器编译从后端移到了前端（`GlslCompiler`），而该前端**硬编码**按 Vulkan 1.2 目标编译：

```java
shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan,
                                       shaderc_env_version_vulkan_1_2);
```

它既不覆盖 SPIR-V 版本，也不感知实际设备，因此 shaderc 按 target env 决定版本，产出 **SPIR-V 1.5** 模块。

设备可接受的 SPIR-V 上限由自身 Vulkan 版本决定：Vulkan 1.1 设备除非启用 `VK_KHR_spirv_1_4`，上限为 **SPIR-V 1.3**（1.2 及以上才是 1.5）。二者冲突时的现象很不直观：`vkCreateShaderModule` 会**成功**接受模块，直到首次 `vkCreateGraphicsPipelines` 才以 `VK_ERROR_INITIALIZATION_FAILED`（`VkResult -3`）失败。

26.2 不会遇到这个问题，因为当时由后端自己编译着色器并固定目标版本（见 `LavaFlowShaderc`：`shaderc_env_version_vulkan_1_1` + `shaderc_spirv_version_1_3`）。26.3 把编译移交前端后，后端不再能设置 shaderc 选项，**模块头部成了唯一可纠正的位置**。

`LavaFlowSpirv` 因此读取模块头部的版本字，并在设备无法接受时**复制**模块、只把该版本字降到设备上限：

- 其余字节（指令、调试信息，以及描述符集布局与管线状态所依赖的 binding / location 修饰）逐字节保留；
- 设备本就能接受时**原样透传**，能力足够的设备因此完全不受影响；
- 上限由 `LavaFlowVulkanContext.maxSpirvVersion()` 按设备 `apiVersion` 推导（1.1 → 1.3，1.2 → 1.5，1.3 → 1.6，1.1 且启用 `VK_KHR_spirv_1_4` → 1.4）。

前提是着色器没有使用高于目标版本的指令——版本字反映的是编译目标环境，而非着色器实际需要的指令；对前端产出的这批 GLSL 成立。若怀疑降级本身有问题，可用 `-Dlavaflow.noSpirvDowngrade=true` 关闭降级做 A/B 对照。

## 当前功能

- LavaFlow 自有的 Vulkan 实例、呈现设备、队列与交换链
- Blaze3D 纹理、缓冲、采样器、管线、渲染通道与命令编码
- 动态渲染与旧版渲染通道路径
- push-descriptor 与 descriptor-set 路径
- 显式资源生命周期与队列同步
- Vulkan 原生纹理传输、blit、清除与呈现
- 缩放与交换链重建处理
- 最终呈现 blit 时反转目标 Y 坐标
- **SPIR-V 版本降级**，使 Vulkan 1.1 设备可执行前端编译出的 SPIR-V 1.5 模块
- Sodium 0.9.2 兼容：Sodium 的 Vulkan 地形路径在 LavaFlow 上运行（`ext_multidraw` / indirect），而非回退到 OpenGL

LavaFlow 使用顺时针前面，且不启用 shaderc 的 invert-Y 选项。这些约定是为了匹配 Minecraft 官方 Vulkan 后端的行为。

## 架构

```text
Minecraft Blaze3D API
        |
LavaFlow Fabric 适配器（dev.lavaflow.minecraft）
        |
LavaFlow Vulkan 后端（dev.lavaflow.vulkan）
        |
Vulkan 1.1
```

面向 Minecraft 的适配器位于 `dev.lavaflow.minecraft`。`dev.lavaflow.vulkan` 下的独立 Vulkan 渲染器不包含任何 Minecraft 类，从而将 Vulkan 资源所有权与批处理策略与 Minecraft 解耦。

### 源码结构

```text
src/
├── main/java/dev/lavaflow/
│   ├── vulkan/                    # 独立 Vulkan 核心，无 Minecraft 类
│   │   ├── LavaFlowRenderer.java         # 渲染器主循环
│   │   ├── FrameResources.java           # 帧资源管理
│   │   ├── QueueFamilies.java            # 队列族查询
│   │   ├── SwapchainState.java           # 交换链状态
│   │   ├── SwapchainSupport.java         # 交换链能力查询
│   │   └── VulkanException.java          # 异常封装
│   └── smoke/
│       └── LavaFlowSmoke.java            # 独立 GLFW + Vulkan 清屏验证
│
├── minecraft/java/dev/lavaflow/minecraft/
│   ├── LavaFlowBackend.java              # 实现 Blaze3D GpuBackend
│   ├── LavaFlowDevices.java              # 判定当前设备是否为 LavaFlow 后端
│   ├── AsyncParticlesCompat.java         # AsyncParticles 兼容（可选模组的反射桥接）
│   ├── MixinPluginUtil.java
│   ├── vulkan/                           # Blaze3D Vulkan 实现
│   │   ├── LavaFlowVulkanContext.java         # 实例/设备/队列/交换链与扩展协商
│   │   ├── LavaFlowDevice.java                # 实现 GpuDeviceBackend
│   │   ├── LavaFlowCommandEncoder.java
│   │   ├── LavaFlowRenderPass.java            # 动态渲染 + 旧版渲染通道路径
│   │   ├── LavaFlowRenderPipeline.java        # 管线布局与图形管线创建
│   │   ├── LavaFlowSpirv.java                 # SPIR-V 版本降级
│   │   ├── LavaFlowShaderc.java               # shaderc 动态库定位（编译已移交前端）
│   │   ├── LavaFlowGpuTexture.java / LavaFlowGpuTextureView.java
│   │   ├── LavaFlowGpuSampler.java / LavaFlowGpuBuffer.java / LavaFlowGpuSurface.java
│   │   ├── LavaFlowDescriptorCache.java / LavaFlowTransientMemory.java
│   │   ├── LavaFlowQueryPool.java / LavaFlowFence.java / LavaFlowFrameStats.java
│   │   ├── LavaFlowVk.java                    # GpuFormat / 枚举到 Vk 常量的映射
│   │   └── LavaFlowVersion.java
│   ├── mixin/                            # 核心 mixin（始终应用）
│   │   ├── PreferredGraphicsApiMixin.java     # 选中 LavaFlow 为后端
│   │   ├── GpuDeviceBackendAccessor.java      # 读取具体类 FrontendGpuDevice 上的 backend 字段
│   │   ├── FramerateLimitMixin.java           # 帧率限制插桩（系统属性门控）
│   │   ├── FrameStatsMixin.java               # 帧统计插桩（系统属性门控）
│   │   ├── TextureAtlasMaxSizeDebugMixin.java # 图集尺寸上报诊断（见"已知限制"）
│   │   └── AsyncParticlesVulkanBackendMixin.java # AsyncParticles 兼容（可选模组）
│   └── sodium/                           # Sodium 兼容
│       ├── LavaFlowSodium.java                # 绘制路径选择
│       ├── LavaFlowSodiumMixinPlugin.java     # 未安装 Sodium 时跳过
│       └── mixin/
│           └── DrawBackendMixin.java          # 路由 Sodium 至其 Vulkan 路径
│
├── minecraft/resources/                  # 模组资源
│   ├── fabric.mod.json
│   ├── lavaflow.mixins.json              # 核心 mixin 配置
│   └── lavaflow-sodium.mixins.json       # Sodium mixin 配置（插件门控）
│
├── sodiumStub/java/net/caffeinemc/       # Sodium 编译期签名 stub（不打包）
│   └── .../device/backend/DrawBackend.java
│
├── minecraft-test/java/                  # 需要 MC / Blaze3D 类的单元测试
│   ├── LavaFlowVkTest.java
│   └── LavaFlowSpirvTest.java
│
└── test/java/                            # 纯逻辑单元测试
    └── QueueFamiliesTest.java
```

`src/sodiumStub` 是**编译期签名 stub**，运行时由真实的 Sodium 提供实现，产物不会被打包。它必须与真实 jar 的形态完全一致：约定了真实类中不存在的成员时，编译依旧通过，直到运行时在 mixin 内部才失败——正因如此，一个 Sodium 早已移除的 `VkCommandBuffer` 字段曾长期未被发现。修改 stub 前请先用 `javap` 核对真实 jar。

### Sodium 兼容说明

Sodium 通过检测 Minecraft 自有的 `VulkanDevice` 来选择绘制路径，因此一个外部的 Vulkan 后端原本会被当作 OpenGL，Sodium 会发起 OpenGL 调用。`DrawBackendMixin` 注入 `DrawBackend.chooseBackend`，在识别出 LavaFlow 设备时直接给出 Vulkan 路径。

Sodium 0.9.2 通过 Blaze3D 渲染通道 API 发起地形绘制（`RenderPass.multiDrawIndexed` / `RenderPass.drawIndexedIndirect`），并自行在管线上声明 push-constant 大小（`RenderPipeline.Builder.withPushConstantSize`）。**LavaFlow 只需让 Sodium 认出自己是 Vulkan 设备**，无需向它交出命令缓冲、管线布局或描述符集——更早的 Sodium 版本需要这些，0.9.2 已不再需要，相关桥接代码随之移除。

设备识别由 `GpuDeviceBackendAccessor` 读取 `backend` 字段完成，该 mixin 声明在**核心**配置中而非 Sodium 配置中，因为需要这个答案的不止 Sodium（见下文 AsyncParticles）。字段位于**具体类** `FrontendGpuDevice` 上（26.3 的 `GpuDevice` 是纯接口，没有字段），因此 mixin 必须指向具体类：指向接口会导致访问器方法根本不被生成，直到有人询问设备类型时才以 `AbstractMethodError` 崩溃。

判定逻辑集中在 `LavaFlowDevices` 里，其中 **`null` 表示"读不到"，不是"不是 LavaFlow"** —— 这两者需要不同的回退，混淆它们正是 AsyncParticles 守卫曾经失效的原因。

绘制路径优先选择 `VK_MULTIDRAW`（`ext_multidraw`，把绘制打包进普通 CPU 数组）；设备不支持 `multiDrawDirectInterleaved` 时退回 `VK_INDIRECT`。

关于 `multiDrawIndexed`：核心 Vulkan 1.1 没有 `VK_EXT_multi_draw`，所以 LavaFlow 从直接内存读取交错命令数组，逐条发出 `vkCmdDrawIndexed`。命令数与 multi-draw 相同，只是没有单条调用。

## 构建

前置条件：

- JDK 25
- Vulkan 1.1 loader 与驱动
- Gradle 9.5.1 或兼容版本

Fabric Loom 会在首次构建时自动下载 Minecraft 26.3 客户端。

构建并完整测试项目：

```sh
gradle --no-daemon clean check jar
```

测试分布在两个源集，`check` 会同时运行两者：

| 源集 | 任务 | 内容 |
| --- | --- | --- |
| `src/test` | `test` | 纯逻辑测试，不需要 Minecraft 类 |
| `src/minecraft-test` | `minecraftTest` | 需要 Blaze3D / Minecraft 类的测试 |

注意 `gradle test` **不会**运行 `minecraftTest`——后者挂在 `check` 上。只跑 `test` 会静默跳过覆盖 Vulkan 相关逻辑的那部分测试。

Fabric 模组产物输出至：

```text
build/libs/lavaflow-26.3-0.1.0-alpha.jar
```

GitHub Actions 会对推送、拉取请求与手动触发运行构建，然后将 JAR 作为工作流产物发布。

## smoke 渲染器

运行独立的 Vulkan smoke 渲染器：

```sh
gradle run
```

有限帧的自动化运行：

```sh
gradle run --args='--frames=120'
```

窗口应通过 Vulkan 持续清屏，并在标题中显示所选 GPU 与帧数。关闭窗口时会演练有序的资源回收。

## 在 Fabric 上安装

1. 将 `build/libs/lavaflow-26.3-0.1.0-alpha.jar` 复制到 Minecraft 26.3 实例的 `mods` 目录。
2. 选择 Vulkan 作为实例的图形后端。
3. 使用 Java 25 启动 Minecraft。

Sodium 为可选依赖，装上可启用 LavaFlow 上的 Vulkan 地形渲染路径。

对于 Android 上的 FCL，典型的模组路径为：

```text
/storage/emulated/0/FCL/.minecraft/versions/26.3-Fabric/mods/
```

具体实例目录可能因启动器配置而异。

## 已知限制

- **wireframe 管线会被跳过**：设备不支持非 solid fill mode 时，需要线框填充的管线（如 `minecraft:pipeline/wireframe`）无法创建；LavaFlow 会记录错误并跳过它们，游戏其余部分继续运行。这属于设计内行为，不是缺陷。
- **描述符缓存失效路径缺失**：`LavaFlowDescriptorCache.invalidateAll()` 目前没有调用者。26.2 由管线缓存清理触发，26.3 改由前端 `PipelineCache` 驱动管线关闭，而管线的 `close()` 不会失效描述符缓存。资源重载后理论上可能命中陈旧条目，尚未观察到实际触发。
- **AsyncParticles 需靠 mixin 兜底**：AsyncParticles 会因自身名字判断而把 LavaFlow 的设备强转成 Mojang 的 `VulkanDevice`，该转换必然失败且发生在静态初始化器里（会拖垮整个游戏）。`AsyncParticlesVulkanBackendMixin` 拦截 `getVkCaps` 并让其走 CPU 粒子路径，因此 AsyncParticles 在 LavaFlow 上**不会启用 GPU 粒子加速**。
- **部分 mixin 属于诊断代码**：`FramerateLimitMixin` 与 `FrameStatsMixin` 由系统属性门控；`TextureAtlasMaxSizeDebugMixin` 用于观测图集尺寸上报，其针对 26.2 的反射探测在 26.3 下已不再命中（`FrontendGpuDevice` 不再暴露 `getMaxTextureSize()` 等方法），当前实际只保留日志输出。
- **`LavaFlowShaderc.compile()` 已无调用者**：着色器编译归前端后，该类只剩定位 shaderc 动态库的作用。

## 状态

LavaFlow 是实验性软件。渲染正确性与性能已在有限的桌面与 ARM64 设备上测试，但 Vulkan 驱动差异可能暴露设备特定问题。测试新构建时请保留一个已知可用的 JAR。

### 已实测环境

以下环境已完整运行（主菜单、资源重载、进入世界、Sodium 地形渲染）：

| 项 | 值 |
| --- | --- |
| 设备 | vivo PD1962（Exynos 980） |
| GPU | ARM Mali-G76，Vulkan 1.1.108，驱动 19.0.0 |
| 设备扩展 | 仅 `VK_KHR_swapchain` |
| 全部可选能力 | 均为 false（dynamic rendering / push descriptors / multi-draw indirect / 非 solid fill / 顶点属性除数） |
| 环境 | FCL 1.3.3.2，Java 25，Android 10（SDK 29） |
| 模组 | Fabric Loader 0.19.5，Sodium 0.9.2+mc26.3 |

该设备会走 LavaFlow 的**全部回退路径**（旧版渲染通道、descriptor-set 而非 push descriptor、逐条 `vkCmdDrawIndexed` 而非 multi-draw），因此这些路径已有真机覆盖。尚未覆盖的是桌面驱动，以及具备上述可选能力的设备。

## 致谢

- 原始项目：[BZLZHH/LavaFlow](https://github.com/BZLZHH/LavaFlow) —— Vulkan 1.1 后端的设计与实现（NeoForge 版）。
- Fabric 移植：[EternityQwQ/LavaFlow-Fabric](https://github.com/EternityQwQ/LavaFlow-Fabric) —— 将后端移植到 Fabric Loader。
- 本 a11y 分支：[Huangjiang-a11y/LavaFlow-Fabric-a11y](https://github.com/Huangjiang-a11y/LavaFlow-Fabric-a11y) —— 面向移动端 Mali GPU 与第三方模组的 Vulkan 兼容性修复。
- 工具链：[Fabric Loader](https://fabricmc.net/)、[Fabric Loom](https://github.com/FabricMC/fabric-loom)、[LWJGL 3](https://www.lwjgl.org/)。

## 许可证

LavaFlow 依据 [MIT License](LICENSE) 分发。Copyright (c) 2026 BZLZHH。
