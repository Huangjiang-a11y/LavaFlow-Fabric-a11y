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

### SDL 与 Vulkan 库的加载前提（26.3 特有）

SDL 的 Vulkan 入口只有在 SDL 自己加载过 Vulkan 库之后才有意义，`SDL_Vulkan_GetPresentationSupport` 亦然。
26.3 的 `createDevice` 按设计跑在窗口之前，此时没有任何东西能隐式加载它，因此 `createInstance()` 开头显式调用
`SDL_Vulkan_LoadLibrary`（该调用幂等：重复调用、以及窗口已存在后再调，实测均返回 true 且不报错）。

桌面端漏掉它会得到一个**指向错误方向的报错**：库未加载时呈现查询对每个队列族都返回 false，于是每个设备都被拒，
最终抛 `No Vulkan 1.1 device with a graphics queue family and a presentable queue family found`——真因在那句
`SDL_Vulkan_LoadLibrary`，与设备本身是否有可呈现的队列族无关。

**Android 上这一步不起作用**（既不会因此修好，也不会因此变坏）：SDL 的 Android 后端没有实现呈现查询钩子
（`SDL_androidvideo.c` 只挂了 LoadLibrary / UnloadLibrary / GetInstanceExtensions / CreateSurface /
DestroySurface 五个），平台无法回答时的默认是**返回 true**；且带 `SDL_WINDOW_VULKAN` 的窗口创建会隐式加载库。
所以这条修复只对桌面端有意义。

**26.2 走 GLFW，没有这个前提，不要搬运过来。**

`LavaFlowVulkanContextTest` 覆盖了这个前提：把上面那次 `SDL_Vulkan_LoadLibrary` 去掉，它就会以
`No Vulkan 1.1 device with a graphics queue family and a presentable queue family found` 失败。该用例因此把
**窗口刻意排在上下文之后**创建——带 `SDL_WINDOW_VULKAN` 的窗口会隐式加载 Vulkan 库，顺序颠倒就会把这个前提盖掉。

### 与 26.2 的差异：surface 建立时机，以及由此决定的呈现判断

26.2 先建 surface、再选设备，因此能用 `vkGetPhysicalDeviceSurfaceSupportKHR` 逐族问"这个族能否呈现到**这个
surface**"——这是权威答案。**26.3 做不到，原因不在实现而在调用顺序**：Minecraft 的 `GpuBackend.createDevice` 跑在
`createWindow` 之前，设备必须在窗口存在之前建成，而该查询的第一个参数就是 surface。所以 26.3 只能问 SDL 的
`SDL_Vulkan_GetPresentationSupport`，它回答的是"这个族在**该显示**上能否呈现"，粒度是显示而非某个 surface。这是
能问到的最近似问题，不等价于权威答案：理论上存在 SDL 答"可以"、而窗口的 surface 实际不支持的情形；26.2 不受此影响。

队列族分离与上者无关，已与 26.2 对齐：两者现在都允许图形族与呈现族**不是同一个族**——`findFamilies` 分别取两者，
`createDevice` 相应建 1 或 2 条队列，交换链在两者不同时以 concurrent 模式创建，并呈现到 `presentQueue`。此前 26.3
只接受"图形+呈现合一"的族，会把这类设备整个拒掉，那正是"26.2 能用而 26.3 不能用"的那批设备。

*这一处没有自动化用例能覆盖分离路径：lavapipe 只暴露一个"图形+呈现合一"的族，走不到两族不同的分支。*


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

还有两个会让诊断结论失真的 Gradle 行为：`test` / `minecraftTest` 命中增量或构建缓存时（`UP-TO-DATE`、
`FROM-CACHE`）构建照样成功，而用例一个都没跑——所以任何靠 grep 验证层输出下结论的流程都必须带
`--no-build-cache --rerun-tasks`。另外 `minecraftTest` 的输出（含验证层启用行与任何验证消息）在
`build/test-results/minecraftTest/*.xml` 里，不在 gradle 控制台。

其中 `LavaFlowVulkanContextTest` 不是纯逻辑用例：它会真的构造一次 Minecraft 侧上下文，把
`createInstance → selectDevice → createDevice → createSurface` 整条路走一遍，并以 `lavaflow.baselineDevice`
起（五项可选能力俱不可用，即移动端画像）。它需要显示服务器与可用 Vulkan 设备，无 `DISPLAY` 时**自行跳过**，
本地可用 `xvfb-run -a gradle check` 跑。CI 会断言它确实执行了、而非被跳过——因为跳过同样让构建变绿。

Fabric 模组产物输出至：

```text
build/libs/lavaflow-26.3-0.1.0-alpha.jar
```

生成到 `lavaflow-version.txt` 的版本与提交会打进 JAR：设备启动时以一行 INFO 输出（`LavaFlow build <版本> (<提交>)`），并进入 `DeviceInfo` 的 `driverInfo`，因此崩溃报告的 “Graphics Drivers” 一行同样带提交。工作区有未提交改动时提交带 `-dirty` 后缀；环境无 `git` 时退化为 `unknown`。

GitHub Actions 会对推送、拉取请求与手动触发运行构建，然后将 JAR 作为工作流产物发布。工作流执行
`gradle --no-daemon clean check jar installDist`，然后**真跑一次渲染器**：在 lavapipe 上、开着验证层跑 600 帧。

那一步断言的是"验证层确实应答了"（grep 到上面那条 INFO），而**不是**"退出码为 0"——见 smoke 一节，成功时渲染器
什么都不打印。它需要安装三样东西：软件 Vulkan 驱动（`mesa-vulkan-drivers`）、**验证层本身**
（`vulkan-validationlayers`）与虚拟显示（`xvfb`）。漏装验证层时渲染器仍会退出 0，步骤会按设计失败。

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

注意：smoke 成功时**不向 stdout/stderr 输出任何内容**，所选设备与帧数只出现在窗口标题里。因此"退出码为 0"
对一个根本没起来的渲染器同样成立——要把一次运行当真，就开着验证层跑，并确认那条 INFO 出现：

```sh
gradle installDist
JAVA_OPTS=-Dlavaflow.validation=true build/install/lavaflow/bin/lavaflow --frames=600
```

## 验证与诊断

### Vulkan 验证层（`lavaflow.validation`）

加上 `-Dlavaflow.validation=true` 即可让 LavaFlow 请求 Khronos 验证层与 `VK_EXT_debug_utils`。该开关对两个
后端都生效：Minecraft 适配器（`LavaFlowVulkanContext`）与独立渲染器（`LavaFlowRenderer`），两者都把验证层
消息以 `[LavaFlow Vulkan validation]` 前缀打到 stderr。

设计上，**一项无法被满足的请求会被报出来，而不是被丢掉**：

| 情况 | 输出 |
| --- | --- |
| 层与调试扩展均可用 | `INFO: Vulkan validation enabled through the VK_LAYER_KHRONOS_validation layer` |
| 层不可用 | `WARNING: lavaflow.validation is set, but the VK_LAYER_KHRONOS_validation layer is not available, so this run will not be validated.` |
| 调试扩展不可用 | `WARNING: VK_EXT_debug_utils is unavailable, so validation messages have no way of reaching the log` |

**所以"没有验证层消息"并不等于"没有问题"**：要确认它真的在验证，必须看见那条 INFO。曾经有一版实现是静默的——
开关打开、什么都不发生，读起来与"验证通过"完全一样。

Linux 桌面上需要**单独安装层本身**，只装驱动不够：

```sh
sudo apt-get install vulkan-validationlayers
```

**Android 上目前用不了。** 实测环境（见"已实测环境"）里既没有验证层也没有 `VK_EXT_debug_utils`，开关在移动端
只会打印上面那两条 WARNING。要在手机上拿到验证输出，需要自行把验证层按 arm64 装进设备。

验证层的覆盖范围就是这次运行**真正做过的事**：

- 它能覆盖 `createInstance → selectDevice → createDevice → createSurface` 这段：`LavaFlowVulkanContextTest`
  会在验证层下真的走一遍（2026-10 实测：MC 侧 69 个用例全过、0 跳过，**0 条验证消息**）。
- 它**不能**用来判定 descriptor 路径是否 API 干净：独立 smoke 渲染器（`src/main/java/dev/lavaflow/vulkan/`）
  没有任何 descriptor 代码，现有用例也不驱动 `LavaFlowRenderPass.pushDescriptors`。本地要走到那次崩溃
  所在的调用（`vkUpdateDescriptorSets`），只能新写一个真设备用例把 `LavaFlowRenderPipeline` +
  `LavaFlowRenderPass` 跑起来。

### 在桌面上复现移动端画像（`lavaflow.baselineDevice`）

`-Dlavaflow.baselineDevice=true` 把选中的设备描述成"除 `VK_KHR_swapchain` 外什么都没有"的样子：dynamic
rendering、push descriptors、multi-draw indirect、非 solid fill mode、顶点属性除数一并置为不可用。它存在的目的
是在桌面上走通 LavaFlow 为移动端准备的回退路径，而不必每次真机试。

五项各自还有独立的开关（`lavaflow.forceDescriptorSets`、`lavaflow.forceLegacyRenderPass`、
`lavaflow.forceNoMultiDrawIndirect`、`lavaflow.forceNoVertexAttributeDivisor`、`lavaflow.forceNoFillModeNonSolid`），
用于单独隔离一条路径。

实测的 vivo PD1962 / Mali-G76 上报的能力集与此**完全一致**（五项全 false），所以这个开关是那台设备的忠实模拟。

### 资源 churn 的实测基线（`lavaflow.frameStats`）

`-Dlavaflow.frameStats=true` 会让 `FrameStatsMixin` 周期输出一行计数器，用来看"有没有东西在每帧被创建/退役"——
比只看帧率更能定位问题。字段：`sets_per_frame`、`hit_rate`、`invalidations_per_frame`、`barriers`、
`binds_per_frame`、`rebind_ratio`、`pushes_per_frame`、`retired_buffer`、`retired_view`、`retired_sampler`、
`retired_texture`、`partial_clears`、`top_retired_view`。

`top_retired_view` 在条目多于展示数时会附上 `(distinct=N)`，因为这里常见的分布是**平的**（每份资源各退役
一次），只列前几名几乎说不出信息量——见下文第二轮实测。

`retired_texture` 要和 `retired_view` 一起看才能分辨"纹理本身在换"和"只有 view 在换"。`partial_clears`
是 `clearColorAndDepthTextures` 的调用次数——那是 LavaFlow 自己唯一会制造 view churn 的地方（每次调用
造两个 view 并立刻销毁），所以它是"这些 churn 是不是我们自己造成的"这个问题的分子。

`top_retired_view` 曾经**每一份报告都是 `-`**：退役计数在涨（加载期约 21~29 张 view/帧），而 label 表
始终为空。两层原因，都修了：

1. label 为 null 时 `viewRetired` 直接不记账，于是它在最需要它的场景里静默失效。现在纹理通过
   `identity()` 自报家门：有 label 用 label，没有则退化成
   `[unlabeled 1024x512 RGBA8_UNORM mips=1 layers=1 usage=0x4]`。
2. 更深的一层：**那批纹理本来就没有 label**。前端 `FrontendGpuDevice.createTexture(Supplier<String>, …)`
   只在 `isDebuggingEnabled()` 为真时才去解析 label 供应器，否则直接传 null；而这个开关转发给 backend，
   也就是 LavaFlow 的 `LavaFlowDevice.isDebuggingEnabled()`——它当时硬编码返回 false，于是经由前端创建的
   **每一张**纹理都成了匿名对象。现在它跟随 `lavaflow.frameStats`（唯一会读这些名字的诊断）打开，正常
   运行的代价不变。

Mali-G76 设备实测（渲染距离 2 区块，故 165~185 fps 不代表正常视距）：

| 场景 | `retired_buffer` /帧 | `retired_view` /帧 | 解读 |
| --- | --- | --- | --- |
| 启动 / 资源加载 | ~0.2 | **~24.8** | `invalidations_per_frame ≈ 24.85`：一次性资源加载摊在几秒里，不是每帧泄漏（身份已实测，见下） |
| 稳态主菜单 | **~12.2** | 0.00 | `invalidations_per_frame = 0.00`——完全不碰 descriptor 缓存；菜单受 60fps 限制，没有可见代价 |
| 世界内 | ~0.0~0.5 | **0.00** | 不存在持续 churn（`barriers` ≈ 17.5/帧） |

结论：**不要**为菜单那 12 个短命 buffer 做 buffer 回收——它不碰 descriptor 缓存，也换不到可观察的帧率。

同一台设备 2026-10-02 又一次运行（bundle `df99b43`，视距 2→10）复现了上表：加载期
`retired_view=21.18`/帧 配 `invalidations_per_frame=21.26`（同一现象，量级一致），而世界内从 frames≈2757
起 `sets_per_frame=0.0`、`hit_rate=1.000`、`invalidations_per_frame=0.00` 一路保持到 10585 帧。也就是说
descriptor 侧的稳态 churn 已经不存在，剩下的全在加载期。

#### 加载期那批 churn 是什么（2026-10-02 实测）

上表"启动 / 资源加载"那行的"一次性资源加载摊在几秒里"当时是**推断**——因为身份那一栏一直是 `-`。补上
`retired_texture`、`partial_clears` 与兜底身份后，同一台设备（bundle `3fcb594`）的报告第一次答出了身份：

```text
frames=111 ... sets_per_frame=29.6 hit_rate=0.062 invalidations_per_frame=29.11
  binds_per_frame=2.7 pushes_per_frame=31.6 retired_buffer=0.21
  retired_view=29.01 retired_texture=29.01 partial_clears=0.0
  top_retired_view=[unlabeled 16x16 RGBA8_UNORM mips=1 layers=1 usage=0x5]=2266,
                   [unlabeled 8x8 RGBA8_UNORM mips=1 layers=1 usage=0x5]=154,
                   [unlabeled 32x32 RGBA8_UNORM mips=1 layers=1 usage=0x5]=153
```

- `retired_texture` 与 `retired_view` **逐位相等**：每张纹理恰好一个 view，两者同生共死——不是"只有 view
  在换"。
- `partial_clears=0.0`：`clearColorAndDepthTextures` 一次都没被调，**这批 churn 与 LavaFlow 无关**（这正是
  那个计数器存在的意义——用数字排除，而不是通读调用图）。
- `usage=0x5`（`COPY_DST|TEXTURE_BINDING`）、`RGBA8_UNORM`、`mips=1`、`layers=1`、尺寸取自源 PNG：这一组
  参数与 `ReloadableTexture.doLoad` 的字节码**逐项吻合**（`iconst_5`、`RGBA8_UNORM`、
  `NativeImage.getWidth/getHeight`、`iconst_1`、`iconst_1`），而该方法正是**先 `close()` 旧的再建新的**，
  即测到的"先退役再新建"。`SpriteContents$AnimatedTexture` 在同一位置传 `layers = byMipLevel.length`
  （16×16 会是 5），被 `layers=1` 排除；`FontTexture` 与 `PalettedTextureManager$AtlasTexture` 则分别是
  256×256 与 512×512，尺寸对不上。
- 16×16 占 top-3 的绝大多数，正是方块/物品 PNG 的标准尺寸；整个加载窗口约 3200 张，对应"每份纹理资源
  一张"，而资源加载被摊到各帧上，于是表现为 ~29 张/帧而非瞬时一批。

结论：加载期的 view 与 descriptor churn 是 **Minecraft 重载纹理资源**造成的，随加载结束归零（后续每份
报告都是 `retired_view=0.00`、`retired_texture=0.00`），既不是泄漏，也不该由 LavaFlow 回收。注意
`top_retired_view` 一次只列前 3 名，top-3 之外还有约 20% 未列出。

#### 按名字确认（2026-10-02 第二轮，bundle `7f1f48a`）

label 修复上线后，同一台设备的加载期身份从"形状"变成了真实资源名：

```text
frames=132 ... retired_view=24.39 retired_texture=24.39 partial_clears=0.0
top_retired_view=minecraft:missingno=12, minecraft:item/cave_spider_spawn_egg=1,
                 minecraft:block/sniffer_egg_very_cracked_bottom=1
```

逐张 PNG 的资源名（`block/…`、`item/…`），绝大多数恰好 1 次——与上一轮"字节码逐项吻合
`ReloadableTexture.doLoad`"一致，归属由推断变成点名。`minecraft:missingno`（缺失贴图占位）是唯一出现
多次的身份（12 次）。这一轮也暴露了呈现问题：整个区间约 3200 次退役而 top-3 只列出 14 个，因为分布是平的；
`(distinct=N)` 就是为此加的——"2589 个不同身份、各一次"一句话说清"每份资源一张"，top-3 的计数说不清。

#### `partial_clears` 抓到了我们自己路径上的一个来源

同一轮里 `partial_clears` 第一次非零，出现在打开物品栏前后（frames 9629~10663）：

```text
frames=10110 ... retired_view=2.00 invalidations_per_frame=0.00 partial_clears=1.0
top_retired_view=UI items atlas=481, UI items atlas depth=481
```

`partial_clears × 2 == retired_view` 逐位成立，身份是 `UI items atlas` / `UI items atlas depth`：物品栏的
`GuiItemAtlas` 每次重建都清一次 color+depth，而 `clearColorAndDepthTextures` 每次调用正是造两个临时 view
（`finally` 里销毁）——所以这两个 view 是**我们自己**造的，不是 Minecraft 的。代价有限：`invalidations_per_frame
= 0.00`（完全不碰 descriptor 缓存），速率 ≤1 次清屏/帧，只在图集重建时发生。这一条同样是"用数字说清"而不是
"读调用图猜"。

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

- **移动端无法运行验证层**：Android 的 FCL 环境里没有验证层，也没有 `VK_EXT_debug_utils`，因此 `lavaflow.validation` 在手机上只能打印两条 WARNING（见"验证与诊断"）。Mali 专属问题目前只能靠真机日志与桌面上的 `lavaflow.baselineDevice` 夹逼。
- **wireframe 管线会被跳过**：设备不支持非 solid fill mode 时，需要线框填充的管线（如 `minecraft:pipeline/wireframe`）无法创建；LavaFlow 会记录错误并跳过它们，游戏其余部分继续运行。这属于设计内行为，不是缺陷。
- **AsyncParticles 需靠 mixin 兜底**：AsyncParticles 会因自身名字判断而把 LavaFlow 的设备强转成 Mojang 的 `VulkanDevice`，该转换必然失败且发生在静态初始化器里（会拖垮整个游戏）。`AsyncParticlesVulkanBackendMixin` 拦截 `getVkCaps` 并返回其 `VkCommands.Unsupported`，AsyncParticles 随之走 CPU 粒子路径，永远走不到那句强转。
- **GPU 粒子加速不可用的原因是结构性的，与版本或扩展能力无关**：AsyncParticles 的 Vulkan 渲染器要的不是裸 `VkDevice`，而是一个 Mojang `VulkanDevice` 包装对象（`vkDevice()`、`createCommandEncoder()`，其内部类还继承 `VulkanGpuBuffer`）。LavaFlow 的后端是另一套实现，只实现 `GpuDeviceBackend`，拿不出这个对象。因此即使设备支持 Vulkan 1.4 与全部可选扩展，该路径同样走不通。（曾按 Mali-G76 的 Vulkan 1.1 归因于能力不足，那是不对的：在 `device 1.4.x` 的设备上 AsyncParticles 会按 `apiVersion >= 1.3` 直接置 `pushDescriptor`/`synchronization2` 为真，反而会尝试启用。）
- **部分 mixin 属于诊断代码**：`FramerateLimitMixin` 与 `FrameStatsMixin` 由系统属性门控；`TextureAtlasMaxSizeFallbackMixin` 仅在图集尺寸上报为非正值时介入并记一条 WARN，实测该分支未触发。
- **`LavaFlowShaderc.compile()` 已无调用者**：着色器编译归前端后，该类只剩定位 shaderc 动态库的作用。

- **Mali-G76 上出现过一次未解释的原生崩溃**：渲染线程在驱动的 `vkUpdateDescriptorSets` 里 SIGSEGV，
  进程直接死。现状、已排除的嫌疑，以及若再现该做什么，见「状态 → 一次未解释的驱动崩溃」。

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

同一个 JAR（`0.1.0-alpha (64e8b68)`）后来在这台设备上又跑过一轮，JVM 参数带 `-Dlavaflow.validation=true`：
结果证实移动端无法启用验证层（日志里只有那两条 WARNING），其余表现一致——启动、建世界、渲染、退出码 0，无崩溃。

### 一次未解释的驱动崩溃（Mali-G76）

**现象**：渲染线程在**刚进入世界后的 lightmap 绘制**里，于驱动的 `vkUpdateDescriptorSets` 内部触发
`libGLES_mali.so` 原生崩溃（Java 层无异常，进程直接死）。当时构建里含一处"复用 descriptor 写入结构体"
的改动（`4c308d5`：把 `calloc` 出的 `VkWriteDescriptorSet` 与 info 结构体换成跨帧复用的堆缓冲）。

**已撤销**（`20485cb`；26.2 对齐于 `dc20fed`）。撤销后同一设备连续多轮运行干净，其中包括一轮
**反复进出世界 12 次、20888 帧**的压力测试——而"进出世界"正是崩溃发生的那个时刻。

**成因未解释，但已排除一处嫌疑**：复读代码后可以确定，那处改动与该崩溃**没有可解释的因果**。两个版本
交给驱动的字段值完全一致——每次 push 都重设 `sType / dstSet / dstBinding / dstArrayElement /
descriptorCount / descriptorType`，而未被该 entry 类型使用的联合成员在两个版本里都是 NULL（一个是显式
置空，一个是 `calloc` 零初始化）。所以"撤掉它就好了"更可能是巧合，或是分配/时序上的扰动。

处置：

- 那处改动**维持撤销**。理由不是"它是元凶"，而是它没有可验证的收益（实测每帧只省 8~27 次小 `calloc`，
  量级见 `pushes_per_frame`），而当前状态是唯一被真机验证过的状态。
- **当前构建仍有可能再现这个崩溃**。若再现，请保留完整日志（尤其是崩溃前那几帧的上下文）——那比任何
  本地推断都有价值。
- 剩余可疑方向：Mali 驱动在该写入模式上的 bug；或"已销毁的资源句柄仍被写进 descriptor"这类
  use-after-free——后者正是验证层能抓的，但本地没有任何用例能走到那条路径（见「验证与诊断」）。

## 致谢

- 原始项目：[BZLZHH/LavaFlow](https://github.com/BZLZHH/LavaFlow) —— Vulkan 1.1 后端的设计与实现（NeoForge 版）。
- Fabric 移植：[EternityQwQ/LavaFlow-Fabric](https://github.com/EternityQwQ/LavaFlow-Fabric) —— 将后端移植到 Fabric Loader。
- 本 a11y 分支：[Huangjiang-a11y/LavaFlow-Fabric-a11y](https://github.com/Huangjiang-a11y/LavaFlow-Fabric-a11y) —— 面向移动端 Mali GPU 与第三方模组的 Vulkan 兼容性修复。
- 工具链：[Fabric Loader](https://fabricmc.net/)、[Fabric Loom](https://github.com/FabricMC/fabric-loom)、[LWJGL 3](https://www.lwjgl.org/)。

## 许可证

LavaFlow 依据 [MIT License](LICENSE) 分发。Copyright (c) 2026 BZLZHH。
