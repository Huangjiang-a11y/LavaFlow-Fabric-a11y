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
│   ├── vulkan/                           # Blaze3D Vulkan 实现（20+ 类）
│   │   ├── LavaFlowDevice.java ...       # 设备、纹理、缓冲、采样器、管线等
│   │   └── LavaFlowVersion.java          # 版本信息
│   ├── mixin/                            # 核心 mixin
│   │   ├── PreferredGraphicsApiMixin.java # 选中 LavaFlow 为后端
│   │   ├── FramerateLimitMixin.java      # 帧率限制插桩（开发期，系统属性门控）
│   │   └── FrameStatsMixin.java          # 帧统计插桩（开发期，系统属性门控）
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
│   └── LavaFlowVkTest.java (等)
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

构建并测试项目：

```sh
gradle --no-daemon clean test jar
```

Fabric 模组产物输出至：

```text
build/libs/lavaflow-26.2-0.1.0-alpha.jar
```

GitHub Actions 会对推送、拉取请求与手动触发运行相同的构建，然后将 JAR 作为工作流产物发布。

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

**本分支与 26.3 在此处的实现必须不同，不要互相搬运。** 差异源自 Mojang 在 26.3 把 `GpuDevice` 从类改成了接口：

| | 本分支（26.2） | 26.3 |
| --- | --- | --- |
| `GpuDevice` | `com.mojang.blaze3d.systems.GpuDevice`，**类**，含 `private final GpuDeviceBackend backend` | `com.mojang.renderpearl.api.device.GpuDevice`，**接口**，无任何字段 |
| 取后端 | 直接 `getDeclaredField("backend")` 可行 | 同样的反射必抛 `NoSuchFieldException`（该分支的守卫曾因此成为空操作） |
| `VulkanDevice` | `com.mojang.blaze3d.vulkan.VulkanDevice` | `com.mojang.renderpearl.backend.vulkan.VulkanDevice` |

## 后续可做

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

## 致谢

- 原始项目：[BZLZHH/LavaFlow](https://github.com/BZLZHH/LavaFlow) —— Vulkan 1.1 后端的设计与实现（NeoForge 版）。
- Fabric 移植：[EternityQwQ/LavaFlow-Fabric](https://github.com/EternityQwQ/LavaFlow-Fabric) —— 将后端移植到 Fabric Loader。
- 本 a11y 分支：[Huangjiang-a11y/LavaFlow-Fabric-a11y](https://github.com/Huangjiang-a11y/LavaFlow-Fabric-a11y) —— 面向移动端 Mali GPU 与第三方模组的 Vulkan 兼容性修复。
- 工具链：[Fabric Loader](https://fabricmc.net/)、[Fabric Loom](https://github.com/FabricMC/fabric-loom)、[LWJGL 3](https://www.lwjgl.org/)。

## 许可证

LavaFlow 依据 [MIT License](LICENSE) 分发。Copyright (c) 2026 BZLZHH。
