# LavaFlow

> [!CAUTION]
> **100% AI 生成的项目**
>
> LavaFlow 的源代码与文档**100% 由 AI 在人类指导下生成**。请将其视为实验性实现：在依赖它之前，先审计代码并在你自己的硬件上测试。

> [!NOTE]
> 本仓库是 LavaFlow 的 **a11y 分支**（fork 自 [EternityQwQ/LavaFlow-Fabric](https://github.com/EternityQwQ/LavaFlow-Fabric) 的 Fabric 移植版），而后者基于原始项目 [BZLZHH/LavaFlow](https://github.com/BZLZHH/LavaFlow)（NeoForge 版）开发。Vulkan 后端的设计与实现全部归功于原项目。本分支聚焦于**早期移动端GPU与第三方模组的兼容性修复**。

LavaFlow 是 Minecraft Blaze3D API 的实验性 Vulkan 1.1 图形后端。它用 LavaFlow 自有的 LWJGL Vulkan 实现替换了实际执行的 Blaze3D 渲染路径，并且从不调用 OpenGL。

## 兼容性

| 组件 | 目标 |
| --- | --- |
| Minecraft | 26.2 |
| 模组加载器 | Fabric Loader 0.19.3 或更高 |
| Minecraft 运行时 | Java 25 |
| 图形 API | Vulkan 1.1 |
| 桌面 smoke 渲染器 | Java 21 字节码 |
| Vitrail Shaders（可选） | 0.12.0-beta：引擎站到一边，包不绘制（见"Vitrail 兼容性"） |

后端为不具备以下能力的 Vulkan 1.1 设备提供兼容路径：dynamic rendering、synchronization2、push descriptors、multi-draw indirect、非 solid fill mode、顶点属性除数。桌面端与 ARM64 Android 设备均在范围内。实际驱动行为与性能因 GPU 而异。

## 当前功能

- LavaFlow 自有的 Vulkan 实例、呈现设备、队列与交换链
- Blaze3D 纹理、缓冲、采样器、管线、渲染通道与命令编码
- 动态渲染与旧版渲染通道路径
- push-descriptor 与 descriptor-set 路径
- 显式资源生命周期与队列同步
- Vulkan 原生纹理传输、blit、清除与呈现
- 缩放与交换链重建处理
- 最终呈现 blit 时反转目标 Y 坐标
- Sodium 0.9.1 兼容：Sodium 的 Vulkan 地形路径在 LavaFlow 上运行，而非回退到 OpenGL

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
│   ├── LavaFlowBackend.java              # Fabric 适配器，实现 Blaze3D GpuBackend
│   ├── AsyncParticlesCompat.java         # AsyncParticles 兼容（可选模组的守卫逻辑）
│   ├── LavaFlowDevices.java              # "当前设备是不是 LavaFlow 的"（可选模组守卫共用）
│   ├── VitrailCompat.java                # Vitrail 兼容（可选模组的后端判定）
│   ├── vulkan/                           # Blaze3D Vulkan 实现（20+ 类）
│   │   ├── LavaFlowDevice.java ...       # 设备、纹理、缓冲、采样器、管线等
│   │   └── LavaFlowVersion.java          # 版本信息
│   ├── mixin/                            # 核心 mixin
│   │   ├── PreferredGraphicsApiMixin.java # 选中 LavaFlow 为后端
│   │   ├── FramerateLimitMixin.java      # 帧率限制插桩（开发期，系统属性门控）
│   │   ├── FrameStatsMixin.java          # 帧统计插桩（开发期，系统属性门控）
│   │   ├── TextureAtlasMaxSizeDebugMixin.java    # 图集尺寸上限（诊断 + workaround）
│   │   ├── AsyncParticlesVulkanBackendMixin.java # AsyncParticles 兼容（可选模组）
│   │   └── VitrailBackendMixin.java              # Vitrail 兼容（可选模组）
│   └── sodium/                           # Sodium 0.9.1 兼容
│       ├── LavaFlowSodium.java           # 向 Sodium 暴露设备/渲染通道
│       ├── LavaFlowSodiumMixinPlugin.java # mixin 插件
│       └── mixin/
│           ├── DrawBackendMixin.java     # 路由 Sodium 至 Vulkan 路径
│           ├── VKDrawContextMixin.java   # 绑定 LavaFlow 命令缓冲与管线布局
│           ├── GpuDeviceBackendAccessor.java
│           ├── RenderPassBackendAccessor.java
│           └── DrawContextPassAccessor.java # 读取 Blaze3D backend 字段
│
├── minecraft/resources/                 # 模组资源
│   ├── fabric.mod.json
│   ├── lavaflow.mixins.json              # 核心 mixin 配置
│   └── lavaflow-sodium.mixins.json       # Sodium mixin 配置（插件门控）
│
├── sodiumStub/java/net/caffeinemc/       # Sodium 编译期签名 stub（不打包）
│
├── minecraft-test/java/                  # 需要 MC 类的单元测试
│   ├── LavaFlowDevicesTest.java          # 设备判定（可选模组守卫共用）
│   ├── VitrailCompatTest.java            # Vitrail 守卫拒绝什么、刻意不拒绝什么
│   └── vulkan/LavaFlowVkTest.java (等)
│
└── test/java/                            # 纯逻辑单元测试
    └── QueueFamiliesTest.java
```

### Sodium 兼容说明

Sodium 通过检测 Minecraft 自有的 `VulkanDevice` 来选择后端，因此一个外部的 Vulkan 后端原本会被当作 OpenGL，Sodium 会发起 OpenGL 调用。LavaFlow 选中了基于核心 `vkCmdDrawIndexedIndirect` 的 indirect Vulkan 路径（无需任何设备扩展）。

## 构建

前置条件：

- JDK 25
- Vulkan 1.1 loader 与驱动
- Gradle 9.5.1 或兼容版本

Fabric Loom 会在首次构建时自动下载 Minecraft 26.2 客户端。

构建并完整测试项目：

```sh
gradle --no-daemon clean check jar installDist
```

测试分布在两个源集，`check` 会同时运行两者：

| 源集 | 任务 | 内容 |
| --- | --- | --- |
| `src/test` | `test` | 纯逻辑测试，不需要 Minecraft 类 |
| `src/minecraft-test` | `minecraftTest` | 需要 Blaze3D / Minecraft 类的测试 |

注意 `gradle test` **不会**运行 `minecraftTest`——后者挂在 `check` 上。只跑 `test` 会静默跳过覆盖 Vulkan 相关逻辑的那部分测试。

其中 `LavaFlowVulkanContextTest` 不是纯逻辑用例：它会真的建一个窗口并构造一次 Minecraft 侧上下文，把
`createInstance → createSurface → selectDevice → createDevice` 整条路走一遍，并以 `lavaflow.baselineDevice`
起（五项可选能力俱不可用，即移动端画像）。它需要显示服务器与可用 Vulkan 设备，无 `DISPLAY` 时**自行跳过**，
本地可用 `xvfb-run -a gradle check` 跑。CI 会断言它确实执行了、而非被跳过——因为跳过同样让构建变绿。

Fabric 模组产物输出至：

```text
build/libs/lavaflow-26.2-0.1.0-alpha.jar
```

生成到 `lavaflow-version.txt` 的版本与提交会打进 JAR：设备启动时以一行 INFO 输出（`LavaFlow build <版本> (<提交>)`），并进入 `DeviceInfo` 的 `driverInfo`，因此崩溃报告的 “Graphics Drivers” 一行同样带提交。工作区有未提交改动时提交带 `-dirty` 后缀；环境无 `git` 时退化为 `unknown`。

GitHub Actions 会对推送、拉取请求与手动触发运行相同的构建，然后将 JAR 作为工作流产物发布。工作流执行
`gradle --no-daemon clean check jar installDist`，然后**真跑一次渲染器**：在 lavapipe 上、开着验证层跑 600 帧。

那一步断言的是"验证层确实应答了"（grep 到下面那条 INFO），而**不是**"退出码为 0"——见 smoke 一节，成功时渲染器
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

**覆盖边界**：`LavaFlowDescriptorPathTest`（在 `minecraftTest` 里，随 `check` 跑）在真设备上走
`vkUpdateDescriptorSets` 那条路：descriptor set 随 buffer 退役、buffer view 随 buffer 退役、sampled image
随 view/sampler 退役，各自断言**零条**层消息；另有一个 canary 故意写一条规范禁止的写
（`descriptorCount = 0`），断言层确实报了——否则前面那些「零条」对「层根本没在跑」同样成立。
CI 会断言这四个用例确实执行、且没有跳过。

Linux 桌面上需要**单独安装层本身**，只装驱动不够：

```sh
sudo apt-get install vulkan-validationlayers
```

**Android 上目前用不了。** 真机环境里既没有验证层也没有 `VK_EXT_debug_utils`，开关在移动端只会打印上面那两条
WARNING。要在手机上拿到验证输出，需要自行把验证层按 arm64 装进设备。

### 在桌面上复现移动端画像（`lavaflow.baselineDevice`）

`-Dlavaflow.baselineDevice=true` 把选中的设备描述成"除 `VK_KHR_swapchain` 外什么都没有"的样子：dynamic
rendering、push descriptors、multi-draw indirect、非 solid fill mode、顶点属性除数一并置为不可用。它存在的目的
是在桌面上走通 LavaFlow 为移动端准备的回退路径，而不必每次真机试。

另有七个独立开关用于单独隔离一条路径：`lavaflow.forceDescriptorSets`、`lavaflow.forceLegacyRenderPass`、
`lavaflow.forceNoMultiDrawIndirect`、`lavaflow.forceNoVertexAttributeDivisor`、`lavaflow.forceNoFillModeNonSolid`、
`lavaflow.forceNoMultiDrawDirect`、`lavaflow.forceFifo`。

26.3 分支的真机实测显示，vivo PD1962 / Mali-G76 上报的能力集与 `baselineDevice` 置为不可用的那五项**完全一致**
（全为 false），所以这个开关是那台设备的忠实模拟。

## 在 Fabric 上安装

1. 将 `build/libs/lavaflow-26.2-0.1.0-alpha.jar` 复制到 Minecraft 26.2 实例的 `mods` 目录。
2. 选择 Vulkan 作为实例的图形后端。
3. 使用 Java 25 启动 Minecraft。

对于 Android 上的 FCL，典型的模组路径为：

```text
/storage/emulated/0/FCL/.minecraft/versions/26.2-Fabric/mods/
```

具体实例目录可能因启动器配置而异。

## AsyncParticles 兼容性

AsyncParticles 在 `Backends` 的静态初始化器里按后端名分支：名字含 "vulkan" 时调用 `getVkCaps(device)`，而该方法的第一个语句是 `((VulkanDevice) device.backend).vkDevice()`。LavaFlow 把后端名报为 "Vulkan"（好让按名字分支的模组继续工作），但注册进去的后端对象是自己的 `GpuDeviceBackend` 实现，不是 Mojang 的 `VulkanDevice`。这句强转必然抛 `ClassCastException`；又因为它发生在类初始化器里，挂掉的是整个游戏而不只是一个功能。

`AsyncParticlesVulkanBackendMixin` 拦下 `getVkCaps`：当后端不是 Mojang 的 `VulkanDevice` 时，直接返回 AsyncParticles 自己的 `VkCommands.Unsupported`（用反射在它自己的类加载器里构造，以保证类型完全一致）。AsyncParticles 随后报告无 Vulkan GPU 加速并走 CPU 粒子路径，永远走不到那句强转。

判定与构造都在 `AsyncParticlesCompat` 里，mixin 类只留注入器——普通 mixin 成员会被合并进第三方模组的目标类，而且放在外面才够得到测试。`isMojangVulkanDevice` 走 `LavaFlowDevices.backendOf`（两条守卫共用一处；读不到记 WARNING，而不是静默站到一边），`unsupportedVkCaps` 按名字反射构造那个类型，造不出来就返回 `null` 并记 ERROR——那种情况下放行原方法、让它自己报错，比用一个它的代码处理不了的值取消掉好。守卫生效时记一条 INFO：没有它，"生效了"和"AsyncParticles 根本没问过"在日志里长得一模一样。名字核对于 AsyncParticles **26.2.2.9+26.2**：`Backends.getVkCaps(GpuDevice)` 是私有静态方法、第一步是 `((VulkanDevice) device.backend).vkDevice()`；`VkCommands` 是 public abstract class，`Unsupported` 是它的 public 嵌套类、带 public 无参构造。

**本分支与 26.3 在此处的实现必须不同，不要互相搬运。** 差异源自 Mojang 在 26.3 把 `GpuDevice` 从类改成了接口：

| | 本分支（26.2） | 26.3 |
| --- | --- | --- |
| `GpuDevice` | `com.mojang.blaze3d.systems.GpuDevice`，**类**，含 `private final GpuDeviceBackend backend` | `com.mojang.renderpearl.api.device.GpuDevice`，**接口**，无任何字段 |
| 取后端 | 反射，集中在 `LavaFlowDevices.backendOf`（两条守卫共用） | 同样的反射必抛 `NoSuchFieldException`（该分支的守卫曾因此成为空操作），那边走 accessor mixin |
| `VulkanDevice` | `com.mojang.blaze3d.vulkan.VulkanDevice` | `com.mojang.renderpearl.backend.vulkan.VulkanDevice` |

## Vitrail 兼容性

Vitrail Shaders 是把 OptiFine 格式的包跑在**游戏自己那个 Vulkan 后端**上的光影引擎。它靠**名字**决定引擎能不能画：`dev.vitrail.HostReport.otherBackend()` 是 `!UNKNOWN.equals(backend) && !VULKAN.equals(backend)`，那个名字来自 `RenderSystem.tryGetDevice().getDeviceInfo().backendName()`。LavaFlow 把后端名报为 "Vulkan"（好让按名字选 Vulkan 路径的模组继续工作），于是 Vitrail 的门答"能"，它的引擎被打开。

引擎打开之后要的是**原生后端的对象**，不是设备门面：申请设备特性靠包住 `VulkanBackend.createDevice(...)`，取命令缓冲靠 `VulkanCommandEncoder`，写描述符靠手写 `VkWriteDescriptorSet`，读纹理靠 `VulkanGpuTextureView`。LavaFlow 注册的是自己的 `GpuDeviceBackend`，这些对象一个都不存在、特性一个都没申请过。Vitrail 自己那些 `instanceof` 检测（`PackCompute`、`ShadowCompare`）确实答"不是"并在本地退让，但引擎**整体**已经被名字打开了，而半开的引擎比不开更糟：翻译后的着色器带着 Vitrail 自己的 `OfGlobals` uniform 块，填它的那段代码却在永不执行的原生路径上，第一次绘制就死在空槽上（`Missing uniform OfGlobals`，从 `pushDescriptors` 抛出，经 Sodium 的间接批处理抵达）。这个抛法不是 LavaFlow 比原版严：Mojang 自己的 `VulkanRenderPass.pushDescriptors` 遇到空 uniform-buffer 槽同样抛 `IllegalStateException`；两边都在 26.3 客户端上核对过。

`VitrailBackendMixin` 因此只替那**一个**问句回答"是的，另一个后端"（前提是设备确实是 LavaFlow 的），余下的交给 Vitrail 自己那条为"另一个后端"准备的路：包既不读也不画，游戏保持自己的画面，并在日志与聊天里自己说明。这里不复制也不扩展 Vitrail 的任何源码；除了注入器之外什么也不声明——普通 mixin 成员会被合并进第三方模组的目标类。

名字核对于 Vitrail Shaders v0.12.0-beta 的 **26.2 jar**：`dev.vitrail.HostReport` 是类，`otherBackend` 是无参静态方法（26.3 那个 jar 相同）。

**本分支与 26.3 在此处的实现必须不同，不要互相搬运。** 差异与 AsyncParticles 那处同源——Mojang 在 26.3 把 `GpuDevice` 从类改成了接口：

| | 本分支（26.2） | 26.3 |
| --- | --- | --- |
| 取后端 | `GpuDevice` 自身带 `private final GpuDeviceBackend backend`，反射读得到（`LavaFlowDevices.backendOf`） | 字段落到具体类上，只能用 accessor mixin |
| `VulkanBackend` 等 | `com.mojang.blaze3d.vulkan.*` | `com.mojang.renderpearl.backend.vulkan.*` |
| 目标方法 | `dev.vitrail.HostReport#otherBackend`（无参静态），两分支相同 | 同左 |

## 已知限制

- **移动端无法运行验证层**：Android 的 FCL 环境里没有验证层，也没有 `VK_EXT_debug_utils`，因此 `lavaflow.validation` 在手机上只能打印两条 WARNING（见"验证与诊断"）。Mali 专属问题目前只能靠真机日志与桌面上的 `lavaflow.baselineDevice` 夹逼。
- **wireframe 会被降级为实心，而不是被跳过**：设备不支持非 solid fill mode 时，`LavaFlowRenderPipeline` 把线框多边形模式改写为 `VK_POLYGON_MODE_FILL` 后照常创建管线，因此线框/调试渲染表现为实心、**且不报错**。26.3 的行为不同：26.3 的 `DeviceFeatures` 多了 `wireframeFillMode` 字段，由其驱动 Minecraft 主动跳过这些可选管线并打 ERROR。**两分支此处不要互相搬运。**
- **本分支走 GLFW，26.3 走 SDL**：26.3 的 `createInstance()` 必须显式调用 `SDL_Vulkan_LoadLibrary`——它的 `createDevice` 按设计跑在窗口之前，此时无人加载该库，漏掉会让设备查找抛出**指向错误方向**的报错（`No Vulkan 1.1 device with a graphics queue family and a presentable queue family found`，真因是那句 `SDL_Vulkan_LoadLibrary` 没跑到、与设备本身无关）。本分支用 GLFW，没有这个前提，**不要搬运。**
- **AsyncParticles 需靠 mixin 兜底**：把 LavaFlow 的设备强转成 Mojang `VulkanDevice` 必然失败，且发生在静态初始化器里（会拖垮整个游戏）。守卫见上文"AsyncParticles 兼容性"。
- **Vitrail Shaders 在 LavaFlow 上不绘制**：见"Vitrail 兼容性"。它和 GPU 粒子一样是结构性的——引擎要的是原生后端的对象，而不是设备门面。守卫的代价是一个包不被绘制；反过来答错（把别的模组的设备也当成 LavaFlow）会把不该动的画面拿走，所以 `VitrailCompat` 只在设备确实是我们的时才生效，读不出来就答"不知道"。
- **GPU 粒子加速不可用的原因是结构性的**：与版本或扩展能力无关——需要的不是一个裸 `VkDevice`，而是一整套 Mojang 后端对象。即使设备支持 Vulkan 1.4 与全部可选扩展，该路径同样走不通。详见上文。
- **`TextureAtlasMaxSizeDebugMixin` 是诊断兼 workaround**：它在 `TextureAtlas.maxSupportedTextureSize()` 返回时记录原始值、并用反射探测 `GpuDevice` 实际暴露的方法；当该值 **≤ 0** 时改写为 8192。26.3 上的对应 mixin（`TextureAtlasMaxSizeFallbackMixin`）仅在该情形下介入并记一条 WARN，本分支则每次调用都会记录 INFO。底层限制传递修好后可以去掉那次 `setReturnValue`。
- **`FramerateLimitMixin` 与 `FrameStatsMixin` 属于诊断代码**：由系统属性门控（`lavaflow.unlockFramerate`、`lavaflow.frameStats`），默认不生效。

## 后续可做

### 让 Vitrail 在 LavaFlow 上绘制

**结论：与 GPU 粒子同一类结构性工作，守卫只是把代价换成"包不绘制"。**

不只是"把守卫拿掉"。Vitrail 的引擎要的是原生后端的对象，而不是设备门面：申请设备特性靠包住 `VulkanBackend.createDevice(...)`，取命令缓冲靠 `VulkanCommandEncoder`，写描述符靠手写 `VkWriteDescriptorSet`（`PackCompute.pushDescriptors`），读纹理靠 `VulkanGpuTextureView`。这些都在 LavaFlow 只实现 `GpuDeviceBackend` 的边界之外，与 AsyncParticles 的 GPU 粒子一样是结构性的，不是版本或扩展能力问题。要让包真在这里绘制，等于把这套内部对象重新提供出来——那是另一个项目，不是一条兼容守卫。

### 让 AsyncParticles 在 LavaFlow 上启用 GPU 粒子

**结论：可行，但不是 mixin 能解决的，需要自己实现一个渲染器。**

**为什么 patch AsyncParticles 自身的 Vulkan 路径走不通**（三处都是结构性的，不是命名或版本问题）：

1. **`checkcast` 不可能通过。** `com.mojang.blaze3d.vulkan.VulkanDevice` 是 `public class`（且 `implements GpuDeviceBackend`），不是接口；而 `LavaFlowDevice` 的父类已是 `Object`。mixin 能追加接口，不能改父类，所以 `((VulkanDevice) device.backend)` 永远会抛。
2. **即便通过，需要的也不只是 `VkDevice`。** AsyncParticles 绑定的是 Mojang 后端对象上的一整套：`vulkanDevice.createCommandEncoder()` 返回 Mojang 的 `VulkanCommandEncoder`，它还要读其内部状态；其内部类还直接继承 Mojang 的 `VulkanGpuBuffer`（该类在 26.2 侧同样存在）。
3. **造一个 `VulkanDevice` 需要 Mojang 整套后端初始化。** 本分支的构造器要 `ShaderSource`、`VulkanInstance`、`VulkanPhysicalDevice`、`Set<String>`、`VkDevice`、`long`、`CheckpointExtension`——正是 LavaFlow 立项时绕开的那部分。在 LavaFlow 上重建它，等于放弃 LavaFlow 的前提。

**真口子**：`GpuParticleBehavior.createRenderer()` 是 public，返回公开接口 `IParticleRenderer`（13 个方法）。拦它返回一个 LavaFlow 原生实现，比碰 AsyncParticles 的 Vulkan 内部可靠得多——不依赖任何反射。这个口子在 26.2 与 26.3 上一致，可照搬。

| 需要的东西 | 现状 |
| --- | --- |
| `VkDevice` / 队列 | LavaFlow 已有 |
| 内存分配 | 已有（`findMemoryType` + `vkAllocateMemory`） |
| 命令缓冲录制 | 已有（`LavaFlowCommandEncoder`） |
| compute 管线、描述符集、SPIR-V 加载 | 需新写；AsyncParticles 的 `VkCompParticleRenderer` 可直接作蓝本 |
| 与 LavaFlow 帧的同步 | 需新设计 |

最难的是最后一行：`awaitCompute()` 交出的是 device-local 缓冲区，要能被 LavaFlow 的渲染管线**直接消费**——即 LavaFlow 需要接受外部创建的顶点缓冲。AsyncParticles 原实现是把结果交给 MC 自己的渲染器去画，换到 LavaFlow 上等于新设计一条路。工作量数天到一周量级。

**注意**：无论是否实现上述渲染器，`AsyncParticlesVulkanBackendMixin` 的守卫都必须保留——它拦的 `getVkCaps` 在静态初始化器里，与用哪个粒子渲染器无关。

## 状态

LavaFlow 是实验性软件。渲染正确性与性能已在有限的桌面与 ARM64 设备上测试，但 Vulkan 驱动差异可能暴露设备特定问题。测试新构建时请保留一个已知可用的 JAR。

### 已实测环境

| 项 | 值 |
| --- | --- |
| 设备 | 桌面 x86-64 |
| GPU | lavapipe（Mesa 25.0.7，LLVM 19.1.7，软件光栅化） |
| 内容 | 独立渲染器跑满 600 帧，开验证层、零验证消息，退出码 0 |
| 旁证 | CI 在 ubuntu-latest 上执行同一套命令并通过 |

ARM64 Android 的真机实测记录在 26.3 分支的 README（vivo PD1962 / Mali-G76 / Exynos 980，走全部回退路径）；本分支 README 暂无对应的真机记录。

### descriptor 写入结构体的高水位计数缺陷（已修）

26.2 与 26.3 同步：`WriteScratch` 的跨帧复用曾被两地一起撤掉（26.2 `dc20fed` / 26.3 `20485cb`），
现在随修复一起重加（26.3 侧 `9d02f08`）。

缺陷：`writes(count)` 只按 `capacity` 决定是否重建、从不调 `limit`，而 LWJGL 把 buffer 的 `remaining()`
当作 `descriptorWriteCount`——某次推入条数少于此前高水位时，驱动会被告知读那个高水位，多出来的槽位里是
上一次推入留下的 `dstSet` 与 buffer/image 句柄（可能已销毁）。这是 26.3 在 Mali-G76 上那次
`vkUpdateDescriptorSets` 原生崩溃的来源（真机记录见 26.3 的 README）。现在 `writes(count)` 把
`remaining()` 钉到本次条数，`WriteScratchTest` 有 5 条用例，其中一条直接断言"驱动被告知读几条"。


## 致谢

- 原始项目：[BZLZHH/LavaFlow](https://github.com/BZLZHH/LavaFlow) —— Vulkan 1.1 后端的设计与实现（NeoForge 版）。
- Fabric 移植：[EternityQwQ/LavaFlow-Fabric](https://github.com/EternityQwQ/LavaFlow-Fabric) —— 将后端移植到 Fabric Loader。
- 本 a11y 分支：[Huangjiang-a11y/LavaFlow-Fabric-a11y](https://github.com/Huangjiang-a11y/LavaFlow-Fabric-a11y) —— 面向移动端 Mali GPU 与第三方模组的 Vulkan 兼容性修复。
- 工具链：[Fabric Loader](https://fabricmc.net/)、[Fabric Loom](https://github.com/FabricMC/fabric-loom)、[LWJGL 3](https://www.lwjgl.org/)。

## 许可证

LavaFlow 依据 [MIT License](LICENSE) 分发。Copyright (c) 2026 BZLZHH。
