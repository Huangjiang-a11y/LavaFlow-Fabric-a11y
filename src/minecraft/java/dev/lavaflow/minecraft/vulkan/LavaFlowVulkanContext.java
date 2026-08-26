package dev.lavaflow.minecraft.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.VK_KHR_SWAPCHAIN_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDynamicRendering.VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRDepthStencilResolve.VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME;
import static org.lwjgl.vulkan.KHRCreateRenderpass2.VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME;
import static org.lwjgl.vulkan.EXTVertexAttributeDivisor.VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/** LavaFlow-owned Vulkan 1.1 instance, device, queues, and allocation policy. */
public final class LavaFlowVulkanContext implements AutoCloseable {
    private final long window;
    private VkInstance instance;
    private VkDebugUtilsMessengerCallbackEXT debugCallback;
    private long debugMessenger;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private VkQueue graphicsQueue;
    private VkQueue presentQueue;
    private int graphicsFamily = -1;
    private int presentFamily = -1;
    private long commandPool;
    private String deviceName;
    private VkPhysicalDeviceProperties properties;
    private long maxMemoryAllocationSize;
    private boolean pushDescriptors;
    private boolean dynamicRendering;
    private boolean fillModeNonSolid;
    private boolean multiDrawIndirect;
    private boolean vertexAttributeDivisor;
    private Set<String> enabledExtensions = Set.of();
    private final Map<LegacyRenderPassKey, Long> legacyRenderPasses = new HashMap<>();
    private final Map<LegacyFramebufferKey, Long> legacyFramebuffers = new HashMap<>();
    private boolean closed;

    private record DeviceCapabilities(boolean swapchain, boolean pushDescriptors, boolean dynamicRendering,
                                      boolean fillModeNonSolid, boolean multiDrawIndirect,
                                      boolean vertexAttributeDivisor, Set<String> extensions) {}

    private static final class LegacyRenderPassKey {
        final int[] colorFormats;
        final int[] colorLoadOps;
        final int depthFormat;
        final int depthLoadOp;
        final int hash;

        LegacyRenderPassKey(int[] colorFormats, int[] colorLoadOps, int depthFormat, int depthLoadOp) {
            this.colorFormats = colorFormats.clone();
            this.colorLoadOps = colorLoadOps.clone();
            this.depthFormat = depthFormat;
            this.depthLoadOp = depthLoadOp;
            this.hash = 31 * (31 * Arrays.hashCode(this.colorFormats) + Arrays.hashCode(this.colorLoadOps))
                    + 31 * depthFormat + depthLoadOp;
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof LegacyRenderPassKey key
                    && depthFormat == key.depthFormat && depthLoadOp == key.depthLoadOp
                    && Arrays.equals(colorFormats, key.colorFormats)
                    && Arrays.equals(colorLoadOps, key.colorLoadOps);
        }
    }

    private static final class LegacyFramebufferKey {
        final long renderPass;
        final long[] views;
        final int width;
        final int height;
        final int hash;

        LegacyFramebufferKey(long renderPass, long[] views, int width, int height) {
            this.renderPass = renderPass;
            this.views = views.clone();
            this.width = width;
            this.height = height;
            this.hash = 31 * (31 * (31 * Long.hashCode(renderPass) + Arrays.hashCode(this.views)) + width) + height;
        }

        boolean contains(long view) {
            for (long candidate : views) if (candidate == view) return true;
            return false;
        }

        @Override public int hashCode() { return hash; }
        @Override public boolean equals(Object other) {
            return other instanceof LegacyFramebufferKey key && renderPass == key.renderPass
                    && width == key.width && height == key.height && Arrays.equals(views, key.views);
        }
    }

    public LavaFlowVulkanContext(long window) {
        if (window == NULL) throw new IllegalArgumentException("window must be a GLFW window");
        this.window = window;
        try {
            createInstance();
            createSurface();
            selectDevice();
            createDevice();
            createCommandPool();
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }

    private void createInstance() {
        if (!GLFWVulkan.glfwVulkanSupported()) throw new IllegalStateException("Vulkan is unavailable");
        PointerBuffer extensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (extensions == null) throw new IllegalStateException("GLFW supplied no Vulkan surface extensions");
        try (MemoryStack stack = stackPush()) {
            boolean validation = Boolean.getBoolean("lavaflow.validation");
            PointerBuffer enabledExtensions = extensions;
            if (validation) {
                enabledExtensions = stack.mallocPointer(extensions.remaining() + 1);
                for (int i = extensions.position(); i < extensions.limit(); i++) {
                    enabledExtensions.put(extensions.get(i));
                }
                enabledExtensions.put(stack.UTF8(EXTDebugUtils.VK_EXT_DEBUG_UTILS_EXTENSION_NAME)).flip();
            }
            VkApplicationInfo app = VkApplicationInfo.calloc(stack).sType$Default()
                    .pApplicationName(stack.UTF8("LavaFlow")).applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8("LavaFlow")).engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK_API_VERSION_1_1);
            VkInstanceCreateInfo info = VkInstanceCreateInfo.calloc(stack).sType$Default()
                    .pApplicationInfo(app).ppEnabledExtensionNames(enabledExtensions);
            PointerBuffer out = stack.mallocPointer(1);
            check(vkCreateInstance(info, null, out), "vkCreateInstance");
            instance = new VkInstance(out.get(0), info);
            if (validation) createDebugMessenger(stack);
        }
    }

    private void createDebugMessenger(MemoryStack stack) {
        debugCallback = VkDebugUtilsMessengerCallbackEXT.create((severity, types, callbackData, userData) -> {
            String message = VkDebugUtilsMessengerCallbackDataEXT.create(callbackData).pMessageString();
            System.err.println("[LavaFlow Vulkan validation] " + message);
            return VK_FALSE;
        });
        VkDebugUtilsMessengerCreateInfoEXT info = VkDebugUtilsMessengerCreateInfoEXT.calloc(stack).sType$Default()
                .messageSeverity(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_GENERAL_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                        | EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback(debugCallback);
        LongBuffer out = stack.mallocLong(1);
        check(EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(instance, info, null, out),
                "vkCreateDebugUtilsMessengerEXT");
        debugMessenger = out.get(0);
    }

    private void createSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer out = stack.mallocLong(1);
            check(GLFWVulkan.glfwCreateWindowSurface(instance, window, null, out), "glfwCreateWindowSurface");
            surface = out.get(0);
        }
    }

    private void selectDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
            int best = Integer.MIN_VALUE;
            DeviceCapabilities selectedCapabilities = null;
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                int[] families = findFamilies(candidate);
                DeviceCapabilities candidateCapabilities = queryCapabilities(candidate);
                if (families[0] < 0 || families[1] < 0 || !candidateCapabilities.swapchain()) continue;
                VkPhysicalDeviceProperties candidateProperties = VkPhysicalDeviceProperties.calloc();
                vkGetPhysicalDeviceProperties(candidate, candidateProperties);
                int api = candidateProperties.apiVersion();
                if (VK_VERSION_MAJOR(api) < 1 || (VK_VERSION_MAJOR(api) == 1 && VK_VERSION_MINOR(api) < 1)) {
                    candidateProperties.free();
                    continue;
                }
                int score = candidateProperties.limits().maxImageDimension2D();
                if (candidateProperties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU) score += 1_000_000;
                if (score > best) {
                    if (properties != null) properties.free();
                    best = score; physicalDevice = candidate; graphicsFamily = families[0]; presentFamily = families[1];
                    properties = candidateProperties; deviceName = properties.deviceNameString();
                    selectedCapabilities = candidateCapabilities;
                } else candidateProperties.free();
            }
            if (selectedCapabilities != null) {
                pushDescriptors = selectedCapabilities.pushDescriptors();
                dynamicRendering = selectedCapabilities.dynamicRendering();
                fillModeNonSolid = selectedCapabilities.fillModeNonSolid();
                multiDrawIndirect = selectedCapabilities.multiDrawIndirect();
                vertexAttributeDivisor = selectedCapabilities.vertexAttributeDivisor();
                enabledExtensions = selectedCapabilities.extensions();
            }
        }
        if (physicalDevice == null) throw new IllegalStateException("No Vulkan 1.1 presentation device found");
        // lavaflow.baselineDevice emulates a device that exposes nothing beyond VK_KHR_swapchain, which is
        // what the ARM64 Android targets report. It exists so the fallback paths can be exercised and
        // profiled on a desktop GPU that would otherwise take every extension path.
        boolean baselineDevice = Boolean.getBoolean("lavaflow.baselineDevice");
        if (baselineDevice || Boolean.getBoolean("lavaflow.forceDescriptorSets")) pushDescriptors = false;
        if (baselineDevice || Boolean.getBoolean("lavaflow.forceLegacyRenderPass")) dynamicRendering = false;


        if (baselineDevice || Boolean.getBoolean("lavaflow.forceNoMultiDrawIndirect")) multiDrawIndirect = false;
        if (baselineDevice || Boolean.getBoolean("lavaflow.forceNoVertexAttributeDivisor")) vertexAttributeDivisor = false;
        if (baselineDevice || Boolean.getBoolean("lavaflow.forceNoFillModeNonSolid")) fillModeNonSolid = false;
        queryVulkan11Properties();
    }

    public long largestDeviceLocalHeapSize() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.calloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);
            long largest = 0;
            for (int i = 0; i < memory.memoryHeapCount(); i++) {
                VkMemoryHeap heap = memory.memoryHeaps(i);
                if ((heap.flags() & VK_MEMORY_HEAP_DEVICE_LOCAL_BIT) != 0) {
                    largest = Math.max(largest, heap.size());
                }
            }
            return largest;
        }
    }

    private boolean forceLegacyRenderPass() {
        return Boolean.getBoolean("lavaflow.baselineDevice") || Boolean.getBoolean("lavaflow.forceLegacyRenderPass");
    }

    private void queryVulkan11Properties() {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceVulkan11Properties vulkan11 = VkPhysicalDeviceVulkan11Properties.calloc(stack)
                    .sType$Default();
            VkPhysicalDeviceProperties2 properties2 = VkPhysicalDeviceProperties2.calloc(stack)
                    .sType$Default().pNext(vulkan11.address());
            vkGetPhysicalDeviceProperties2(physicalDevice, properties2);
            long reported = vulkan11.maxMemoryAllocationSize();
            // Some drivers (e.g. Mali) report 0 here, which per the Vulkan spec means the limit is
            // bounded by the heap rather than a fixed value. Fall back to the largest device-local
            // heap size instead of a fabricated huge ceiling, so allocation sizing stays within what
            // the device can actually satisfy. A 256 GiB ceiling made texture/staging memory land in
            // regions the driver could not honor, causing intermittent texture corruption.
            if (reported <= 0 || reported > (1L << 40)) {
                long heap = largestDeviceLocalHeapSize();
                reported = heap > 0 ? heap : (4L << 30);
            }
            maxMemoryAllocationSize = reported;
        }
    }

    private int[] findFamilies(VkPhysicalDevice candidate) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            VkQueueFamilyProperties.Buffer props = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, props);
            IntBuffer supported = stack.ints(VK_FALSE);
            int graphics = -1, present = -1;
            for (int i = 0; i < props.capacity(); i++) {
                if ((props.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) graphics = i;
                check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, supported), "vkGetPhysicalDeviceSurfaceSupportKHR");
                if (supported.get(0) == VK_TRUE) present = i;
                if (graphics >= 0 && present >= 0) break;
            }
            return new int[]{graphics, present};
        }
    }

    private DeviceCapabilities queryCapabilities(VkPhysicalDevice candidate) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            check(vkEnumerateDeviceExtensionProperties(candidate, (String)null, count, null), "vkEnumerateDeviceExtensionProperties(count)");
            VkExtensionProperties.Buffer extensionProperties = VkExtensionProperties.malloc(count.get(0));
            Set<String> extensions = new HashSet<>(extensionProperties.capacity());
            try {
                check(vkEnumerateDeviceExtensionProperties(candidate, (String)null, count, extensionProperties),
                        "vkEnumerateDeviceExtensionProperties");
                for (int i = 0; i < extensionProperties.capacity(); i++) {
                    extensions.add(extensionProperties.get(i).extensionNameString());
                }
            } finally {
                extensionProperties.free();
            }

            boolean dynamicExtension = extensions.contains(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME)
                    && extensions.contains(VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME)
                    && extensions.contains(VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME);
            boolean divisorExtension = extensions.contains(VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME);
            VkPhysicalDeviceDynamicRenderingFeaturesKHR dynamicFeatures = dynamicExtension
                    ? VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default() : null;
            VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT divisorFeatures = divisorExtension
                    ? VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT.calloc(stack).sType$Default() : null;
            long featureChain = NULL;
            if (dynamicFeatures != null) {
                dynamicFeatures.pNext(featureChain);
                featureChain = dynamicFeatures.address();
            }
            if (divisorFeatures != null) {
                divisorFeatures.pNext(featureChain);
                featureChain = divisorFeatures.address();
            }
            if (featureChain != NULL) {
                VkPhysicalDeviceFeatures2 features2 = VkPhysicalDeviceFeatures2.calloc(stack)
                        .sType$Default().pNext(featureChain);
                org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceFeatures2(candidate, features2);
            }
            VkPhysicalDeviceFeatures core = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(candidate, core);
            return new DeviceCapabilities(
                    extensions.contains(VK_KHR_SWAPCHAIN_EXTENSION_NAME),
                    extensions.contains(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME),
                    dynamicFeatures != null && dynamicFeatures.dynamicRendering(),
                    core.fillModeNonSolid(), core.multiDrawIndirect(),
                    divisorFeatures != null && divisorFeatures.vertexAttributeInstanceRateDivisor(),
                    Set.copyOf(extensions));
        }
    }

    private void createDevice() {
        try (MemoryStack stack = stackPush()) {
            int count = graphicsFamily == presentFamily ? 1 : 2;
            VkDeviceQueueCreateInfo.Buffer queues = VkDeviceQueueCreateInfo.calloc(count, stack);
            queues.get(0).sType$Default().queueFamilyIndex(graphicsFamily).pQueuePriorities(stack.floats(1));
            if (count == 2) queues.get(1).sType$Default().queueFamilyIndex(presentFamily).pQueuePriorities(stack.floats(1));
            VkPhysicalDeviceFeatures supported = VkPhysicalDeviceFeatures.calloc(stack);
            vkGetPhysicalDeviceFeatures(physicalDevice, supported);
            VkPhysicalDeviceFeatures enabled = VkPhysicalDeviceFeatures.calloc(stack)
                    .samplerAnisotropy(supported.samplerAnisotropy())
                    .fillModeNonSolid(fillModeNonSolid)
                    .multiDrawIndirect(multiDrawIndirect);
            long featureChain = NULL;
            if (dynamicRendering && !forceLegacyRenderPass()) {
                featureChain = VkPhysicalDeviceDynamicRenderingFeaturesKHR.calloc(stack).sType$Default()
                        .dynamicRendering(true).pNext(featureChain).address();
            }
            if (vertexAttributeDivisor) {
                featureChain = VkPhysicalDeviceVertexAttributeDivisorFeaturesEXT.calloc(stack).sType$Default()
                        .vertexAttributeInstanceRateDivisor(true).pNext(featureChain).address();
            }
            int extensionCount = 1 + (pushDescriptors ? 1 : 0) + (dynamicRendering ? 3 : 0)
                    + (vertexAttributeDivisor ? 1 : 0);
            PointerBuffer extensionNames = stack.mallocPointer(extensionCount);
            extensionNames.put(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            if (pushDescriptors) extensionNames.put(stack.UTF8(VK_KHR_PUSH_DESCRIPTOR_EXTENSION_NAME));
            if (dynamicRendering && !forceLegacyRenderPass()) {
                extensionNames.put(stack.UTF8(VK_KHR_DYNAMIC_RENDERING_EXTENSION_NAME));
                extensionNames.put(stack.UTF8(VK_KHR_DEPTH_STENCIL_RESOLVE_EXTENSION_NAME));
                extensionNames.put(stack.UTF8(VK_KHR_CREATE_RENDERPASS_2_EXTENSION_NAME));
            }
            if (vertexAttributeDivisor) extensionNames.put(stack.UTF8(VK_EXT_VERTEX_ATTRIBUTE_DIVISOR_EXTENSION_NAME));
            extensionNames.flip();
            VkDeviceCreateInfo info = VkDeviceCreateInfo.calloc(stack).sType$Default().pQueueCreateInfos(queues)
                    .pNext(featureChain).pEnabledFeatures(enabled).ppEnabledExtensionNames(extensionNames);
            PointerBuffer out = stack.mallocPointer(1);
            check(vkCreateDevice(physicalDevice, info, null, out), "vkCreateDevice");
            device = new VkDevice(out.get(0), physicalDevice, info);
            PointerBuffer q = stack.mallocPointer(1);
            vkGetDeviceQueue(device, graphicsFamily, 0, q); graphicsQueue = new VkQueue(q.get(0), device);
            vkGetDeviceQueue(device, presentFamily, 0, q); presentQueue = new VkQueue(q.get(0), device);
        }
    }

    private void createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo info = VkCommandPoolCreateInfo.calloc(stack).sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT).queueFamilyIndex(graphicsFamily);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateCommandPool(device, info, null, out), "vkCreateCommandPool"); commandPool = out.get(0);
        }
    }

    int findMemoryType(int typeBits, int requiredFlags) {
        return findMemoryType(typeBits, requiredFlags, 0);
    }

    /**
     * Selects a memory type, preferring one that also carries {@code preferredFlags}.
     *
     * <p>Several required-flag combinations match more than one heap. Host-visible memory in
     * particular is commonly exposed both uncached, where the CPU writes are write-combined and reads
     * are very slow, and host-cached. Which one a buffer wants depends on whether anything reads it
     * back, so the caller states a preference and falls back to any match.
     */
    int findMemoryType(int typeBits, int requiredFlags, int preferredFlags) {
        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceMemoryProperties memory = VkPhysicalDeviceMemoryProperties.malloc(stack);
            vkGetPhysicalDeviceMemoryProperties(physicalDevice, memory);
            int fallback = -1;
            for (int i = 0; i < memory.memoryTypeCount(); i++) {
                if ((typeBits & (1 << i)) == 0) continue;
                int flags = memory.memoryTypes(i).propertyFlags();
                if ((flags & requiredFlags) != requiredFlags) continue;
                if (preferredFlags != 0 && (flags & preferredFlags) == preferredFlags) return i;
                if (fallback < 0) fallback = i;
            }
            if (fallback >= 0) return fallback;
        }
        throw new IllegalStateException("No compatible Vulkan memory type for flags 0x" + Integer.toHexString(requiredFlags));
    }

    public VkInstance instance() { return instance; }
    public long surface() { return surface; }
    public VkPhysicalDevice physicalDevice() { return physicalDevice; }
    public VkDevice device() { return device; }
    public VkQueue graphicsQueue() { return graphicsQueue; }
    public VkQueue presentQueue() { return presentQueue; }
    public int graphicsFamily() { return graphicsFamily; }
    public int presentFamily() { return presentFamily; }
    public long commandPool() { return commandPool; }
    public String deviceName() { return deviceName; }
    public VkPhysicalDeviceProperties properties() { return properties; }
    public long maxMemoryAllocationSize() { return maxMemoryAllocationSize; }
    boolean pushDescriptors() { return pushDescriptors; }
    boolean dynamicRendering() { return dynamicRendering && !forceLegacyRenderPass(); }
    boolean fillModeNonSolid() { return fillModeNonSolid; }
    boolean multiDrawIndirect() { return multiDrawIndirect; }
    boolean vertexAttributeDivisor() { return vertexAttributeDivisor; }
    Set<String> enabledExtensions() { return enabledExtensions; }

    synchronized long legacyRenderPass(int[] colorFormats, int[] colorLoadOps, int depthFormat, int depthLoadOp) {
        LegacyRenderPassKey key = new LegacyRenderPassKey(colorFormats, colorLoadOps, depthFormat, depthLoadOp);
        Long cached = legacyRenderPasses.get(key);
        if (cached != null) return cached;
        try (MemoryStack stack = stackPush()) {
            int attachmentCount = depthFormat == VK_FORMAT_UNDEFINED ? 0 : 1;
            for (int format : colorFormats) if (format != VK_FORMAT_UNDEFINED) attachmentCount++;
            VkAttachmentDescription.Buffer attachments = VkAttachmentDescription.calloc(attachmentCount, stack);
            VkAttachmentReference.Buffer colorReferences = VkAttachmentReference.calloc(colorFormats.length, stack);
            int attachmentIndex = 0;
            for (int i = 0; i < colorFormats.length; i++) {
                int format = colorFormats[i];
                if (format == VK_FORMAT_UNDEFINED) {
                    colorReferences.get(i).attachment(VK_ATTACHMENT_UNUSED)
                            .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                    continue;
                }
                attachments.get(attachmentIndex).format(format).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(colorLoadOps[i]).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
                colorReferences.get(i).attachment(attachmentIndex++)
                        .layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            }
            VkAttachmentReference depthReference = null;
            if (depthFormat != VK_FORMAT_UNDEFINED) {
                attachments.get(attachmentIndex).format(depthFormat).samples(VK_SAMPLE_COUNT_1_BIT)
                        .loadOp(depthLoadOp).storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                        .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                        .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                        .initialLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL)
                        .finalLayout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
                depthReference = VkAttachmentReference.calloc(stack).attachment(attachmentIndex)
                        .layout(VK_IMAGE_LAYOUT_DEPTH_STENCIL_ATTACHMENT_OPTIMAL);
            }
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0).pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(colorReferences.remaining())
                    .pColorAttachments(colorReferences);
            if (depthReference != null) subpass.get(0).pDepthStencilAttachment(depthReference);
            VkRenderPassCreateInfo info = VkRenderPassCreateInfo.calloc(stack).sType$Default()
                    .pAttachments(attachments).pSubpasses(subpass);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateRenderPass(device, info, null, out), "vkCreateRenderPass(fallback)");
            long renderPass = out.get(0);
            legacyRenderPasses.put(key, renderPass);
            return renderPass;
        }
    }

    synchronized long legacyFramebuffer(long renderPass, long[] views, int width, int height) {
        LegacyFramebufferKey key = new LegacyFramebufferKey(renderPass, views, width, height);
        Long cached = legacyFramebuffers.get(key);
        if (cached != null) return cached;
        try (MemoryStack stack = stackPush()) {
            VkFramebufferCreateInfo info = VkFramebufferCreateInfo.calloc(stack).sType$Default()
                    .renderPass(renderPass).pAttachments(stack.longs(views)).width(width).height(height).layers(1);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateFramebuffer(device, info, null, out), "vkCreateFramebuffer(fallback)");
            long framebuffer = out.get(0);
            legacyFramebuffers.put(key, framebuffer);
            return framebuffer;
        }
    }

    synchronized void releaseLegacyFramebuffers(long view) {
        var iterator = legacyFramebuffers.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<LegacyFramebufferKey, Long> entry = iterator.next();
            if (entry.getKey().contains(view)) {
                vkDestroyFramebuffer(device, entry.getValue(), null);
                iterator.remove();
            }
        }
    }

    @Override public void close() {
        if (closed) return; closed = true;
        if (device != null) vkDeviceWaitIdle(device);
        if (device != null) {
            for (long framebuffer : legacyFramebuffers.values()) vkDestroyFramebuffer(device, framebuffer, null);
            legacyFramebuffers.clear();
            for (long renderPass : legacyRenderPasses.values()) vkDestroyRenderPass(device, renderPass, null);
            legacyRenderPasses.clear();
        }
        if (commandPool != NULL) vkDestroyCommandPool(device, commandPool, null);
        if (device != null) vkDestroyDevice(device, null);
        if (surface != NULL && instance != null) vkDestroySurfaceKHR(instance, surface, null);
        if (debugMessenger != NULL && instance != null) {
            EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, debugMessenger, null);
        }
        if (debugCallback != null) debugCallback.free();
        if (instance != null) vkDestroyInstance(instance, null);
        if (properties != null) properties.free();
        commandPool = surface = debugMessenger = NULL;
        device = null; instance = null; properties = null; debugCallback = null;
    }
}
