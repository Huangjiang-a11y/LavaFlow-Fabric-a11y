package dev.lavaflow.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.glfw.GLFWVulkan;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.KHRSurface.*;
import static org.lwjgl.vulkan.KHRSwapchain.*;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.VK_API_VERSION_1_1;

/** Owns LavaFlow's Minecraft-independent Vulkan 1.1 clear-and-present path. */
public final class LavaFlowRenderer implements AutoCloseable {
    private static final int MAX_FRAMES_IN_FLIGHT = 2;
    private static final long FENCE_TIMEOUT_NS = 1_000_000_000L;

    private final long window;
    private VkInstance instance;
    private long surface;
    private VkPhysicalDevice physicalDevice;
    private VkDevice device;
    private QueueFamilies queueFamilies;
    private VkQueue graphicsQueue;
    private VkQueue presentQueue;
    private long commandPool;
    private final SwapchainState swapchain = new SwapchainState();
    private FrameResources[] frames;
    private int frameIndex;
    private volatile boolean framebufferResized;
    private boolean closed;
    private String deviceName;

    public LavaFlowRenderer(long window) {
        if (window == NULL) {
            throw new IllegalArgumentException("window must be a valid GLFW window");
        }
        this.window = window;
        try {
            createInstance();
            createSurface();
            selectPhysicalDevice();
            createDevice();
            createCommandPool();
            createSwapchainResources(NULL);
            createFrameResources();
        } catch (Throwable failure) {
            close();
            throw failure;
        }
    }

    public String deviceName() {
        return deviceName;
    }

    public void markFramebufferResized() {
        framebufferResized = true;
    }

    public void render(float red, float green, float blue) {
        ensureOpen();
        FrameResources frame = frames[frameIndex];
        waitForFence(frame.inFlightFence);
        int acquireResult = vkAcquireNextImageKHR(
                device, swapchain.handle, Long.MAX_VALUE, frame.imageAvailableSemaphore, NULL, frame.imageIndex
        );
        if (acquireResult == VK_ERROR_OUT_OF_DATE_KHR) {
            recreateSwapchain();
            return;
        }
        if (acquireResult != VK_SUCCESS && acquireResult != VK_SUBOPTIMAL_KHR) {
            throw new VulkanException("vkAcquireNextImageKHR", acquireResult);
        }

        int imageIndex = frame.imageIndex.get(0);
        long imageFence = swapchain.imagesInFlight[imageIndex];
        if (imageFence != NULL) {
            waitForFence(imageFence);
        }
        swapchain.imagesInFlight[imageIndex] = frame.inFlightFence;
        frame.signalSemaphore.put(0, swapchain.renderFinishedSemaphores[imageIndex]);
        VulkanException.check(vkResetFences(device, frame.inFlightFence), "vkResetFences");
        VulkanException.check(vkResetCommandBuffer(frame.commandBuffer, 0), "vkResetCommandBuffer");
        recordCommands(frame, imageIndex, red, green, blue);
        VulkanException.check(vkQueueSubmit(graphicsQueue, frame.submit, frame.inFlightFence), "vkQueueSubmit");

        frame.swapchain.put(0, swapchain.handle);
        int presentResult = vkQueuePresentKHR(presentQueue, frame.present);
        if (presentResult == VK_ERROR_OUT_OF_DATE_KHR || presentResult == VK_SUBOPTIMAL_KHR || framebufferResized) {
            framebufferResized = false;
            recreateSwapchain();
        } else if (presentResult != VK_SUCCESS) {
            throw new VulkanException("vkQueuePresentKHR", presentResult);
        }
        frameIndex = (frameIndex + 1) % frames.length;
    }

    private void createInstance() {
        if (!GLFWVulkan.glfwVulkanSupported()) {
            throw new IllegalStateException("GLFW reports that Vulkan is unavailable");
        }
        PointerBuffer extensions = GLFWVulkan.glfwGetRequiredInstanceExtensions();
        if (extensions == null) {
            throw new IllegalStateException("GLFW did not provide Vulkan surface extensions");
        }
        try (MemoryStack stack = stackPush()) {
            VkApplicationInfo applicationInfo = VkApplicationInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationName(stack.UTF8("LavaFlow"))
                    .applicationVersion(VK_MAKE_VERSION(0, 1, 0))
                    .pEngineName(stack.UTF8("LavaFlow"))
                    .engineVersion(VK_MAKE_VERSION(0, 1, 0))
                    .apiVersion(VK_API_VERSION_1_1);
            VkInstanceCreateInfo createInfo = VkInstanceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pApplicationInfo(applicationInfo)
                    .ppEnabledExtensionNames(extensions);
            PointerBuffer handle = stack.mallocPointer(1);
            VulkanException.check(vkCreateInstance(createInfo, null, handle), "vkCreateInstance");
            instance = new VkInstance(handle.get(0), createInfo);
        }
    }

    private void createSurface() {
        try (MemoryStack stack = stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            VulkanException.check(
                    GLFWVulkan.glfwCreateWindowSurface(instance, window, null, handle),
                    "glfwCreateWindowSurface"
            );
            surface = handle.get(0);
        }
    }

    private void selectPhysicalDevice() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            VulkanException.check(vkEnumeratePhysicalDevices(instance, count, null), "vkEnumeratePhysicalDevices(count)");
            if (count.get(0) == 0) {
                throw new IllegalStateException("No Vulkan physical devices found");
            }
            PointerBuffer devices = stack.mallocPointer(count.get(0));
            VulkanException.check(vkEnumeratePhysicalDevices(instance, count, devices), "vkEnumeratePhysicalDevices");
            int bestScore = Integer.MIN_VALUE;
            for (int i = 0; i < devices.capacity(); i++) {
                VkPhysicalDevice candidate = new VkPhysicalDevice(devices.get(i), instance);
                QueueFamilies families = findQueueFamilies(candidate);
                if (!families.isComplete() || !supportsSwapchainExtension(candidate)) {
                    continue;
                }
                try (SwapchainSupport support = querySwapchainSupport(candidate)) {
                    if (support.formats.capacity() == 0 || support.presentModes.length == 0) {
                        continue;
                    }
                }
                VkPhysicalDeviceProperties properties = VkPhysicalDeviceProperties.calloc(stack);
                vkGetPhysicalDeviceProperties(candidate, properties);
                int apiVersion = properties.apiVersion();
                if (VK_VERSION_MAJOR(apiVersion) < 1
                        || (VK_VERSION_MAJOR(apiVersion) == 1 && VK_VERSION_MINOR(apiVersion) < 1)) {
                    continue;
                }
                int score = properties.deviceType() == VK_PHYSICAL_DEVICE_TYPE_DISCRETE_GPU ? 1000 : 0;
                score += properties.limits().maxImageDimension2D();
                if (score > bestScore) {
                    bestScore = score;
                    physicalDevice = candidate;
                    queueFamilies = families;
                    deviceName = properties.deviceNameString();
                }
            }
        }
        if (physicalDevice == null) {
            throw new IllegalStateException("No Vulkan 1.1 device with presentation and VK_KHR_swapchain support found");
        }
    }

    private QueueFamilies findQueueFamilies(VkPhysicalDevice candidate) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, null);
            VkQueueFamilyProperties.Buffer properties = VkQueueFamilyProperties.malloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(candidate, count, properties);
            IntBuffer supported = stack.ints(VK_FALSE);
            int graphics = -1;
            int present = -1;
            for (int i = 0; i < properties.capacity(); i++) {
                if ((properties.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) {
                    graphics = i;
                }
                VulkanException.check(vkGetPhysicalDeviceSurfaceSupportKHR(candidate, i, surface, supported),
                        "vkGetPhysicalDeviceSurfaceSupportKHR");
                if (supported.get(0) == VK_TRUE) {
                    present = i;
                }
                if (graphics >= 0 && present >= 0) {
                    break;
                }
            }
            return new QueueFamilies(graphics, present);
        }
    }

    private boolean supportsSwapchainExtension(VkPhysicalDevice candidate) {
        try (MemoryStack stack = stackPush()) {
            IntBuffer count = stack.ints(0);
            VulkanException.check(vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, null),
                    "vkEnumerateDeviceExtensionProperties(count)");
            VkExtensionProperties.Buffer extensions = VkExtensionProperties.malloc(count.get(0));
            try {
                VulkanException.check(vkEnumerateDeviceExtensionProperties(candidate, (String) null, count, extensions),
                        "vkEnumerateDeviceExtensionProperties");
                for (int i = 0; i < extensions.capacity(); i++) {
                    if (VK_KHR_SWAPCHAIN_EXTENSION_NAME.equals(extensions.get(i).extensionNameString())) {
                        return true;
                    }
                }
                return false;
            } finally {
                extensions.free();
            }
        }
    }

    private SwapchainSupport querySwapchainSupport(VkPhysicalDevice candidate) {
        VkSurfaceCapabilitiesKHR capabilities = VkSurfaceCapabilitiesKHR.calloc();
        try {
            VulkanException.check(vkGetPhysicalDeviceSurfaceCapabilitiesKHR(candidate, surface, capabilities),
                    "vkGetPhysicalDeviceSurfaceCapabilitiesKHR");
            try (MemoryStack stack = stackPush()) {
                IntBuffer count = stack.ints(0);
                VulkanException.check(vkGetPhysicalDeviceSurfaceFormatsKHR(candidate, surface, count, null),
                        "vkGetPhysicalDeviceSurfaceFormatsKHR(count)");
                VkSurfaceFormatKHR.Buffer formats = VkSurfaceFormatKHR.calloc(count.get(0));
                VulkanException.check(vkGetPhysicalDeviceSurfaceFormatsKHR(candidate, surface, count, formats),
                        "vkGetPhysicalDeviceSurfaceFormatsKHR");
                VulkanException.check(vkGetPhysicalDeviceSurfacePresentModesKHR(candidate, surface, count, null),
                        "vkGetPhysicalDeviceSurfacePresentModesKHR(count)");
                IntBuffer modeBuffer = stack.mallocInt(count.get(0));
                VulkanException.check(vkGetPhysicalDeviceSurfacePresentModesKHR(candidate, surface, count, modeBuffer),
                        "vkGetPhysicalDeviceSurfacePresentModesKHR");
                int[] modes = new int[count.get(0)];
                modeBuffer.get(modes);
                return new SwapchainSupport(capabilities, formats, modes);
            }
        } catch (Throwable failure) {
            capabilities.free();
            throw failure;
        }
    }

    private void createDevice() {
        try (MemoryStack stack = stackPush()) {
            int queueCount = queueFamilies.isUnified() ? 1 : 2;
            VkDeviceQueueCreateInfo.Buffer queueInfos = VkDeviceQueueCreateInfo.calloc(queueCount, stack);
            queueInfos.get(0).sType$Default().queueFamilyIndex(queueFamilies.graphics())
                    .pQueuePriorities(stack.floats(1.0f));
            if (!queueFamilies.isUnified()) {
                queueInfos.get(1).sType$Default().queueFamilyIndex(queueFamilies.present())
                        .pQueuePriorities(stack.floats(1.0f));
            }
            PointerBuffer extensions = stack.pointers(stack.UTF8(VK_KHR_SWAPCHAIN_EXTENSION_NAME));
            VkDeviceCreateInfo createInfo = VkDeviceCreateInfo.calloc(stack)
                    .sType$Default()
                    .pQueueCreateInfos(queueInfos)
                    .ppEnabledExtensionNames(extensions);
            PointerBuffer handle = stack.mallocPointer(1);
            VulkanException.check(vkCreateDevice(physicalDevice, createInfo, null, handle), "vkCreateDevice");
            device = new VkDevice(handle.get(0), physicalDevice, createInfo);
            PointerBuffer queue = stack.mallocPointer(1);
            vkGetDeviceQueue(device, queueFamilies.graphics(), 0, queue);
            graphicsQueue = new VkQueue(queue.get(0), device);
            vkGetDeviceQueue(device, queueFamilies.present(), 0, queue);
            presentQueue = new VkQueue(queue.get(0), device);
        }
    }

    private void createCommandPool() {
        try (MemoryStack stack = stackPush()) {
            VkCommandPoolCreateInfo createInfo = VkCommandPoolCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                    .queueFamilyIndex(queueFamilies.graphics());
            LongBuffer handle = stack.mallocLong(1);
            VulkanException.check(vkCreateCommandPool(device, createInfo, null, handle), "vkCreateCommandPool");
            commandPool = handle.get(0);
        }
    }

    private void createSwapchainResources(long oldSwapchain) {
        createSwapchain(oldSwapchain);
        createImageViews();
        createRenderPass();
        createFramebuffers();
    }

    private void createSwapchain(long oldSwapchain) {
        try (SwapchainSupport support = querySwapchainSupport(physicalDevice);
             MemoryStack stack = stackPush()) {
            VkSurfaceFormatKHR format = chooseSurfaceFormat(support.formats);
            int width;
            int height;
            if (support.capabilities.currentExtent().width() != 0xFFFFFFFF) {
                width = support.capabilities.currentExtent().width();
                height = support.capabilities.currentExtent().height();
            } else {
                IntBuffer framebufferWidth = stack.mallocInt(1);
                IntBuffer framebufferHeight = stack.mallocInt(1);
                glfwGetFramebufferSize(window, framebufferWidth, framebufferHeight);
                width = clamp(framebufferWidth.get(0), support.capabilities.minImageExtent().width(),
                        support.capabilities.maxImageExtent().width());
                height = clamp(framebufferHeight.get(0), support.capabilities.minImageExtent().height(),
                        support.capabilities.maxImageExtent().height());
            }
            int imageCount = support.capabilities.minImageCount() + 1;
            if (support.capabilities.maxImageCount() > 0) {
                imageCount = Math.min(imageCount, support.capabilities.maxImageCount());
            }
            VkSwapchainCreateInfoKHR createInfo = VkSwapchainCreateInfoKHR.calloc(stack)
                    .sType$Default()
                    .surface(surface)
                    .minImageCount(imageCount)
                    .imageFormat(format.format())
                    .imageColorSpace(format.colorSpace())
                    .imageExtent(extent -> extent.set(width, height))
                    .imageArrayLayers(1)
                    .imageUsage(VK_IMAGE_USAGE_COLOR_ATTACHMENT_BIT)
                    .preTransform(support.capabilities.currentTransform())
                    .compositeAlpha(chooseCompositeAlpha(support.capabilities.supportedCompositeAlpha()))
                    .presentMode(choosePresentMode(support.presentModes))
                    .clipped(true)
                    .oldSwapchain(oldSwapchain);
            if (queueFamilies.isUnified()) {
                createInfo.imageSharingMode(VK_SHARING_MODE_EXCLUSIVE);
            } else {
                createInfo.imageSharingMode(VK_SHARING_MODE_CONCURRENT)
                        .pQueueFamilyIndices(stack.ints(queueFamilies.graphics(), queueFamilies.present()));
            }
            LongBuffer handle = stack.mallocLong(1);
            VulkanException.check(vkCreateSwapchainKHR(device, createInfo, null, handle), "vkCreateSwapchainKHR");
            swapchain.handle = handle.get(0);
            swapchain.imageFormat = format.format();
            swapchain.width = width;
            swapchain.height = height;
            IntBuffer count = stack.ints(0);
            VulkanException.check(vkGetSwapchainImagesKHR(device, swapchain.handle, count, null),
                    "vkGetSwapchainImagesKHR(count)");
            LongBuffer images = stack.mallocLong(count.get(0));
            VulkanException.check(vkGetSwapchainImagesKHR(device, swapchain.handle, count, images),
                    "vkGetSwapchainImagesKHR");
            swapchain.images = new long[count.get(0)];
            images.get(swapchain.images);
            swapchain.imagesInFlight = new long[swapchain.images.length];
            swapchain.renderFinishedSemaphores = createSemaphores(swapchain.images.length);
        }
    }

    private long[] createSemaphores(int count) {
        long[] semaphores = new long[count];
        try (MemoryStack stack = stackPush()) {
            VkSemaphoreCreateInfo createInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < count; i++) {
                VulkanException.check(vkCreateSemaphore(device, createInfo, null, handle),
                        "vkCreateSemaphore(render finished)");
                semaphores[i] = handle.get(0);
            }
            return semaphores;
        } catch (Throwable failure) {
            for (long semaphore : semaphores) {
                if (semaphore != NULL) {
                    vkDestroySemaphore(device, semaphore, null);
                }
            }
            throw failure;
        }
    }

    private static VkSurfaceFormatKHR chooseSurfaceFormat(VkSurfaceFormatKHR.Buffer formats) {
        for (int i = 0; i < formats.capacity(); i++) {
            VkSurfaceFormatKHR format = formats.get(i);
            if (format.format() == VK_FORMAT_B8G8R8A8_SRGB
                    && format.colorSpace() == VK_COLOR_SPACE_SRGB_NONLINEAR_KHR) {
                return format;
            }
        }
        return formats.get(0);
    }

    private static int choosePresentMode(int[] modes) {
        if (Boolean.getBoolean("lavaflow.forceFifo")) {
            for (int mode : modes) {
                if (mode == VK_PRESENT_MODE_FIFO_KHR || mode == VK_PRESENT_MODE_FIFO_RELAXED_KHR) {
                    return VK_PRESENT_MODE_FIFO_KHR;
                }
            }
            return VK_PRESENT_MODE_FIFO_KHR;
        }
        for (int mode : modes) {
            if (mode == VK_PRESENT_MODE_MAILBOX_KHR) {
                return mode;
            }
        }
        return VK_PRESENT_MODE_FIFO_KHR;
    }

    private static int chooseCompositeAlpha(int supported) {
        int[] preferences = {VK_COMPOSITE_ALPHA_OPAQUE_BIT_KHR, VK_COMPOSITE_ALPHA_PRE_MULTIPLIED_BIT_KHR,
                VK_COMPOSITE_ALPHA_POST_MULTIPLIED_BIT_KHR, VK_COMPOSITE_ALPHA_INHERIT_BIT_KHR};
        for (int preference : preferences) {
            if ((supported & preference) != 0) {
                return preference;
            }
        }
        throw new IllegalStateException("Surface exposes no supported composite alpha mode");
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }

    private void ensureOpen() {
        if (closed) {
            throw new IllegalStateException("LavaFlowRenderer is closed");
        }
    }

    private void createImageViews() {
        swapchain.imageViews = new long[swapchain.images.length];
        try (MemoryStack stack = stackPush()) {
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < swapchain.images.length; i++) {
                VkImageViewCreateInfo createInfo = VkImageViewCreateInfo.calloc(stack)
                        .sType$Default()
                        .image(swapchain.images[i])
                        .viewType(VK_IMAGE_VIEW_TYPE_2D)
                        .format(swapchain.imageFormat);
                createInfo.components().set(
                        VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY,
                        VK_COMPONENT_SWIZZLE_IDENTITY, VK_COMPONENT_SWIZZLE_IDENTITY
                );
                createInfo.subresourceRange()
                        .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .baseMipLevel(0)
                        .levelCount(1)
                        .baseArrayLayer(0)
                        .layerCount(1);
                VulkanException.check(vkCreateImageView(device, createInfo, null, handle), "vkCreateImageView");
                swapchain.imageViews[i] = handle.get(0);
            }
        }
    }

    private void createRenderPass() {
        try (MemoryStack stack = stackPush()) {
            VkAttachmentDescription.Buffer attachment = VkAttachmentDescription.calloc(1, stack);
            attachment.get(0)
                    .format(swapchain.imageFormat)
                    .samples(VK_SAMPLE_COUNT_1_BIT)
                    .loadOp(VK_ATTACHMENT_LOAD_OP_CLEAR)
                    .storeOp(VK_ATTACHMENT_STORE_OP_STORE)
                    .stencilLoadOp(VK_ATTACHMENT_LOAD_OP_DONT_CARE)
                    .stencilStoreOp(VK_ATTACHMENT_STORE_OP_DONT_CARE)
                    .initialLayout(VK_IMAGE_LAYOUT_UNDEFINED)
                    .finalLayout(VK_IMAGE_LAYOUT_PRESENT_SRC_KHR);
            VkAttachmentReference.Buffer colorReference = VkAttachmentReference.calloc(1, stack);
            colorReference.get(0).attachment(0).layout(VK_IMAGE_LAYOUT_COLOR_ATTACHMENT_OPTIMAL);
            VkSubpassDescription.Buffer subpass = VkSubpassDescription.calloc(1, stack);
            subpass.get(0)
                    .pipelineBindPoint(VK_PIPELINE_BIND_POINT_GRAPHICS)
                    .colorAttachmentCount(1)
                    .pColorAttachments(colorReference);
            VkSubpassDependency.Buffer dependency = VkSubpassDependency.calloc(1, stack);
            dependency.get(0)
                    .srcSubpass(VK_SUBPASS_EXTERNAL)
                    .dstSubpass(0)
                    .srcStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstStageMask(VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT)
                    .dstAccessMask(VK_ACCESS_COLOR_ATTACHMENT_WRITE_BIT);
            VkRenderPassCreateInfo createInfo = VkRenderPassCreateInfo.calloc(stack)
                    .sType$Default()
                    .pAttachments(attachment)
                    .pSubpasses(subpass)
                    .pDependencies(dependency);
            LongBuffer handle = stack.mallocLong(1);
            VulkanException.check(vkCreateRenderPass(device, createInfo, null, handle), "vkCreateRenderPass");
            swapchain.renderPass = handle.get(0);
        }
    }

    private void createFramebuffers() {
        swapchain.framebuffers = new long[swapchain.imageViews.length];
        try (MemoryStack stack = stackPush()) {
            LongBuffer attachment = stack.mallocLong(1);
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < swapchain.imageViews.length; i++) {
                attachment.put(0, swapchain.imageViews[i]);
                VkFramebufferCreateInfo createInfo = VkFramebufferCreateInfo.calloc(stack)
                        .sType$Default()
                        .renderPass(swapchain.renderPass)
                        .pAttachments(attachment)
                        .width(swapchain.width)
                        .height(swapchain.height)
                        .layers(1);
                VulkanException.check(vkCreateFramebuffer(device, createInfo, null, handle), "vkCreateFramebuffer");
                swapchain.framebuffers[i] = handle.get(0);
            }
        }
    }

    private void createFrameResources() {
        frames = new FrameResources[MAX_FRAMES_IN_FLIGHT];
        try (MemoryStack stack = stackPush()) {
            VkCommandBufferAllocateInfo allocateInfo = VkCommandBufferAllocateInfo.calloc(stack)
                    .sType$Default()
                    .commandPool(commandPool)
                    .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY)
                    .commandBufferCount(MAX_FRAMES_IN_FLIGHT);
            PointerBuffer commandBuffers = stack.mallocPointer(MAX_FRAMES_IN_FLIGHT);
            VulkanException.check(vkAllocateCommandBuffers(device, allocateInfo, commandBuffers), "vkAllocateCommandBuffers");
            VkSemaphoreCreateInfo semaphoreInfo = VkSemaphoreCreateInfo.calloc(stack).sType$Default();
            VkFenceCreateInfo fenceInfo = VkFenceCreateInfo.calloc(stack)
                    .sType$Default()
                    .flags(VK_FENCE_CREATE_SIGNALED_BIT);
            LongBuffer handle = stack.mallocLong(1);
            for (int i = 0; i < MAX_FRAMES_IN_FLIGHT; i++) {
                VulkanException.check(vkCreateSemaphore(device, semaphoreInfo, null, handle),
                        "vkCreateSemaphore(image available)");
                long imageAvailable = handle.get(0);
                VulkanException.check(vkCreateFence(device, fenceInfo, null, handle), "vkCreateFence");
                frames[i] = new FrameResources(
                        new VkCommandBuffer(commandBuffers.get(i), device),
                        imageAvailable,
                        handle.get(0)
                );
                frames[i].swapchain.put(0, swapchain.handle);
            }
        }
    }

    private void recordCommands(FrameResources frame, int imageIndex, float red, float green, float blue) {
        VulkanException.check(vkBeginCommandBuffer(frame.commandBuffer, frame.commandBegin), "vkBeginCommandBuffer");
        frame.clearValue.get(0).color()
                .float32(0, red)
                .float32(1, green)
                .float32(2, blue)
                .float32(3, 1.0f);
        frame.renderPassBegin
                .renderPass(swapchain.renderPass)
                .framebuffer(swapchain.framebuffers[imageIndex]);
        frame.renderPassBegin.renderArea().offset().set(0, 0);
        frame.renderPassBegin.renderArea().extent().set(swapchain.width, swapchain.height);
        vkCmdBeginRenderPass(frame.commandBuffer, frame.renderPassBegin, VK_SUBPASS_CONTENTS_INLINE);
        vkCmdEndRenderPass(frame.commandBuffer);
        VulkanException.check(vkEndCommandBuffer(frame.commandBuffer), "vkEndCommandBuffer");
    }

    private void waitForFence(long fence) {
        int result;
        do {
            result = vkWaitForFences(device, fence, true, FENCE_TIMEOUT_NS);
        } while (result == VK_TIMEOUT);
        VulkanException.check(result, "vkWaitForFences");
    }

    private void recreateSwapchain() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer width = stack.mallocInt(1);
            IntBuffer height = stack.mallocInt(1);
            do {
                glfwGetFramebufferSize(window, width, height);
                if (width.get(0) == 0 || height.get(0) == 0) {
                    glfwWaitEvents();
                }
            } while (width.get(0) == 0 || height.get(0) == 0);
        }
        VulkanException.check(vkDeviceWaitIdle(device), "vkDeviceWaitIdle");
        long oldSwapchain = swapchain.handle;
        destroySwapchainDependents(false);
        try {
            createSwapchainResources(oldSwapchain);
        } finally {
            vkDestroySwapchainKHR(device, oldSwapchain, null);
        }
        for (FrameResources frame : frames) {
            frame.swapchain.put(0, swapchain.handle);
        }
    }

    private void destroySwapchainDependents(boolean includeSwapchain) {
        for (long framebuffer : swapchain.framebuffers) {
            vkDestroyFramebuffer(device, framebuffer, null);
        }
        swapchain.framebuffers = new long[0];
        if (swapchain.renderPass != NULL) {
            vkDestroyRenderPass(device, swapchain.renderPass, null);
            swapchain.renderPass = NULL;
        }
        for (long imageView : swapchain.imageViews) {
            vkDestroyImageView(device, imageView, null);
        }
        swapchain.imageViews = new long[0];
        for (long semaphore : swapchain.renderFinishedSemaphores) {
            vkDestroySemaphore(device, semaphore, null);
        }
        swapchain.renderFinishedSemaphores = new long[0];
        if (includeSwapchain && swapchain.handle != NULL) {
            vkDestroySwapchainKHR(device, swapchain.handle, null);
            swapchain.handle = NULL;
        }
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        if (device != null) {
            vkDeviceWaitIdle(device);
            if (frames != null) {
                for (FrameResources frame : frames) {
                    if (frame == null) {
                        continue;
                    }
                    vkDestroyFence(device, frame.inFlightFence, null);
                    vkDestroySemaphore(device, frame.imageAvailableSemaphore, null);
                    frame.close();
                }
            }
            destroySwapchainDependents(true);
            if (commandPool != NULL) {
                vkDestroyCommandPool(device, commandPool, null);
            }
            vkDestroyDevice(device, null);
        }
        if (surface != NULL && instance != null) {
            vkDestroySurfaceKHR(instance, surface, null);
        }
        if (instance != null) {
            vkDestroyInstance(instance, null);
        }
    }
}
