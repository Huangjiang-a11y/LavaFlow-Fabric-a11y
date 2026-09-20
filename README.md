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
- 描述符集与所属布局绑定生命周期：重载着色器后不会命中为已释放布局建成的条目

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
│   │   └── LavaFlowVersion.java               # 构建标识（版本 + 提交）
│   ├── mixin/                            # 核心 mixin（始终应用）
│   │   ├── PreferredGraphicsApiMixin.java        # 选中 LavaFlow 为后端
│   │   ├── GpuDeviceBackendAccessor.java         # 读取具体类 FrontendGpuDevice 上的 backend 字段
│   │   ├── FramerateLimitMixin.java              # 帧率限制插桩（系统属性门控）
│   │   ├── FrameStatsMixin.java                  # 帧统计插桩（系统属性门控）
│   │   ├── TextureAtlasMaxSizeFallbackMixin.java # 图集尺寸异常时的兜底（见"已知限制"）
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

生成到 `lavaflow-version.txt` 的版本与提交会打进 JAR：设备启动时以一行 INFO 输出（`LavaFlow build <版本> (<提交>)`），并进入 `DeviceInfo` 的 `driverInfo`，因此崩溃报告的 “Graphics Drivers” 一行同样带提交。工作区有未提交改动时提交带 `-dirty` 后缀；环境无 `git` 时退化为 `unknown`。

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
- **AsyncParticles 需靠 mixin 兜底**：AsyncParticles 会因自身名字判断而把 LavaFlow 的设备强转成 Mojang 的 `VulkanDevice`，该转换必然失败且发生在静态初始化器里（会拖垮整个游戏）。`AsyncParticlesVulkanBackendMixin` 拦截 `getVkCaps` 并返回其 `VkCommands.Unsupported`，AsyncParticles 随之走 CPU 粒子路径，永远走不到那句强转。
- **GPU 粒子加速不可用的原因是结构性的，与版本或扩展能力无关**：AsyncParticles 的 Vulkan 渲染器要的不是裸 `VkDevice`，而是一个 Mojang `VulkanDevice` 包装对象（`vkDevice()`、`createCommandEncoder()`，其内部类还继承 `VulkanGpuBuffer`）。LavaFlow 的后端是另一套实现，只实现 `GpuDeviceBackend`，拿不出这个对象。因此即使设备支持 Vulkan 1.4 与全部可选扩展，该路径同样走不通。（曾按 Mali-G76 的 Vulkan 1.1 归因于能力不足，那是不对的：在 `device 1.4.x` 的设备上 AsyncParticles 会按 `apiVersion >= 1.3` 直接置 `pushDescriptor`/`synchronization2` 为真，反而会尝试启用。）
- **部分 mixin 属于诊断代码**：`FramerateLimitMixin` 与 `FrameStatsMixin` 由系统属性门控；`TextureAtlasMaxSizeFallbackMixin` 仅在图集尺寸上报为非正值时介入并记一条 WARN，实测该分支未触发。
- **`LavaFlowShaderc.compile()` 已无调用者**：着色器编译归前端后，该类只剩定位 shaderc 动态库的作用。

## 后续可做

### 让 AsyncParticles 在 LavaFlow 上启用 GPU 粒子

**结论：可行，但不是 mixin 能解决的，需要自己实现一个渲染器。**

**为什么 patch AsyncParticles 自身的 Vulkan 路径走不通**（三处都是结构性的，不是命名或版本问题）：

1. **`checkcast` 不可能通过。** `com.mojang.renderpearl.backend.vulkan.VulkanDevice` 是 `public class`（且 `implements GpuDeviceBackend`），不是接口；而 `LavaFlowDevice` 的父类已是 `Object`。mixin 能追加接口，不能改父类，所以 `((VulkanDevice) device.backend)` 永远会抛。
2. **即便通过，需要的也不只是 `VkDevice`。** AsyncParticles 绑定的是 Mojang 后端对象上的一整套：`vulkanDevice.createCommandEncoder()` 返回 Mojang 的 `VulkanCommandEncoder`，还要读它的 `currentSubmitIndex` 字段；其 `SubmitSlot$1` 更是直接 `extends VulkanGpuBuffer`。
3. **造一个 `VulkanDevice` 需要 Mojang 整套后端初始化。** 构造器要 `VulkanInstance`、`VulkanPhysicalDevice`、`FeatureSet`、`CheckpointExtension`——正是 LavaFlow 立项时绕开的那部分。在 LavaFlow 上重建它，等于放弃 LavaFlow 的前提。

（另有一处不匹配：Mojang 后端用 VMA 分配器，LavaFlow 用 `vkAllocateMemory` 自行管理，而 AsyncParticles 是按 Mojang 侧约定写的。）

**真口子**：`GpuParticleBehavior.createRenderer()` 是 public，返回公开接口 `IParticleRenderer`（13 个方法）。拦它返回一个 LavaFlow 原生实现，比碰 AsyncParticles 的 Vulkan 内部可靠得多——不依赖任何反射，也不受其内部改名影响。

| 需要的东西 | 现状 |
| --- | --- |
| `VkDevice` / 队列 | LavaFlow 已有 |
| 内存分配 | 已有（`findMemoryType` + `vkAllocateMemory`） |
| 命令缓冲录制 | 已有（`LavaFlowCommandEncoder`） |
| compute 管线、描述符集、SPIR-V 加载 | 需新写；AsyncParticles 的 `VkCompParticleRenderer` 可直接作蓝本（一个主类 + 两个 slot 嵌套类） |
| 与 LavaFlow 帧的同步 | 需新设计 |

最难的是最后一行：`awaitCompute()` 交出的是 device-local 缓冲区，要能被 LavaFlow 的渲染管线**直接消费**——即 LavaFlow 需要接受外部创建的顶点缓冲。AsyncParticles 原实现是把结果交给 MC 自己的渲染器去画，换到 LavaFlow 上等于新设计一条路。

**工作量**：数天到一周量级。

**注意**：无论是否实现上述渲染器，`AsyncParticlesVulkanBackendMixin` 的守卫都必须保留——它拦的 `Backends.getVkCaps` 在静态初始化器里，与用哪个粒子渲染器无关。

*以上依据 AsyncParticles 26.3.2.0-alpha.3+26.3 与 26.3 的 `VulkanDevice` 字节码核对。*

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

该设备会走 LavaFlow 的**全部回退路径**（旧版渲染通道、descriptor-set 而非 push descriptor、逐条 `vkCmdDrawIndexed` 而非 multi-draw），因此这些路径已有真机覆盖。尚未覆盖的是桌面驱动，以及具备上述可选能力的设备。 资源重载（连续三轮）在该设备上跑通，管线关闭→描述符集失效的路径每轮都会走到。

## 致谢

- 原始项目：[BZLZHH/LavaFlow](https://github.com/BZLZHH/LavaFlow) —— Vulkan 1.1 后端的设计与实现（NeoForge 版）。
- Fabric 移植：[EternityQwQ/LavaFlow-Fabric](https://github.com/EternityQwQ/LavaFlow-Fabric) —— 将后端移植到 Fabric Loader。
- 本 a11y 分支：[Huangjiang-a11y/LavaFlow-Fabric-a11y](https://github.com/Huangjiang-a11y/LavaFlow-Fabric-a11y) —— 面向移动端 Mali GPU 与第三方模组的 Vulkan 兼容性修复。
- 工具链：[Fabric Loader](https://fabricmc.net/)、[Fabric Loom](https://github.com/FabricMC/fabric-loom)、[LWJGL 3](https://www.lwjgl.org/)。

## 许可证

LavaFlow 依据 [MIT License](LICENSE) 分发。Copyright (c) 2026 BZLZHH。
