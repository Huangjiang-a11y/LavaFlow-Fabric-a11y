package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.buffers.GpuBuffer;
import com.mojang.renderpearl.api.textures.AddressMode;
import com.mojang.renderpearl.api.textures.FilterMode;
import com.mojang.renderpearl.api.textures.GpuTexture;
import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkDescriptorBufferInfo;
import org.lwjgl.vulkan.VkDescriptorImageInfo;
import org.lwjgl.vulkan.VkDescriptorSetLayoutBinding;
import org.lwjgl.vulkan.VkDescriptorSetLayoutCreateInfo;
import org.lwjgl.vulkan.VkDevice;
import org.lwjgl.vulkan.VkWriteDescriptorSet;

import java.nio.LongBuffer;
import java.util.Map;
import java.util.OptionalDouble;

import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.displayAvailable;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.exposeVulkanLoaderToLwjgl;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.restore;
import static dev.lavaflow.minecraft.vulkan.LavaFlowTestSupport.setSwitches;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.lwjgl.sdl.SDLInit.SDL_INIT_VIDEO;
import static org.lwjgl.sdl.SDLInit.SDL_Init;
import static org.lwjgl.sdl.SDLInit.SDL_Quit;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Drives the descriptor path — the one path the validation layer could not previously reach.
 *
 * <p>Every other minecraft-test case stops short of it. The smoke renderer has no descriptor code,
 * {@code LavaFlowDescriptorCacheTest} only covers {@code Key}'s equals/hashCode/copy semantics, and
 * {@code LavaFlowVulkanContextTest} stops at {@code createSurface}. So descriptor writes were the one
 * part of the backend that nothing exercised, on any machine.
 *
 * <p>That is exactly where the unexplained Mali-G76 crash happened: the render thread died inside
 * {@code vkUpdateDescriptorSets} with no Java-level exception, right after entering a world. The
 * build that crashed was reverted, but re-reading the code showed the reverted change handed the
 * driver identical field values, so the revert is not evidence of a cause — the crash may still
 * reproduce. Two candidates remain: a driver bug in that write pattern, or a use-after-free where a
 * destroyed resource's handle is still written into a descriptor. The second is the kind the
 * validation layer catches, which is why it is worth reaching at all.
 *
 * <p>These tests therefore do the writing themselves, on a real device, with the layer watching:
 * they allocate sets, call {@code vkUpdateDescriptorSets} with the same struct shape
 * {@code LavaFlowRenderPass.buildWrites} produces, then destroy the resource the set points at and
 * assert the cache stops handing that set out. A stale set surviving into a later frame is the
 * use-after-free candidate, stated as an assertion rather than a suspicion.
 *
 * <p>{@code lavaflow.baselineDevice} is set deliberately: it forces push descriptors off, so the
 * descriptor-set path is the one taken on every machine rather than depending on what the local
 * driver happens to support — and it is the same path the Mali device took, since that device
 * reports the same capability picture.
 *
 * <p>Needs a display server and a usable Vulkan device: without a {@code DISPLAY} the test skips,
 * as the context test does (locally {@code xvfb-run}; in CI a software driver is installed).
 */
class LavaFlowDescriptorPathTest {

    /** 256 bytes: one R32G32B32A32 texel is 16, so this is a legal texel buffer range too. */
    private static final long BUFFER_SIZE = 256L;

    @Test
    void descriptorSetsAreWrittenOnARealDeviceAndRetireWithTheirBuffer() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.baselineDevice", "true",
                "lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        long layout = 0L;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            LavaFlowVulkanContext context = device.context();
            VkDevice vkDevice = context.device();

            assertFalse(context.pushDescriptors(),
                    "baselineDevice 应关闭 push descriptors，本用例要覆盖的正是另一条路径");

            LavaFlowDescriptorCache cache = device.descriptorCache();
            layout = createSetLayout(vkDevice, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);

            LavaFlowGpuBuffer first = new LavaFlowGpuBuffer(device, GpuBuffer.USAGE_UNIFORM, BUFFER_SIZE);
            LavaFlowGpuBuffer second = new LavaFlowGpuBuffer(device, GpuBuffer.USAGE_UNIFORM, BUFFER_SIZE);
            long firstHandle = first.handle();
            long secondHandle = second.handle();

            LavaFlowDescriptorCache.Key firstKey = new LavaFlowDescriptorCache.Key(layout, new long[]{firstHandle});
            LavaFlowDescriptorCache.Key secondKey = new LavaFlowDescriptorCache.Key(layout, new long[]{secondHandle});

            // The write below is the call the Mali crash ended in.
            long firstSet = cache.allocateAndStore(firstKey, layout, new long[]{firstHandle});
            assertNotEquals(0L, firstSet, "缓存未分配出 descriptor set");
            writeUniform(vkDevice, firstSet, firstHandle, BUFFER_SIZE);

            long secondSet = cache.allocateAndStore(secondKey, layout, new long[]{secondHandle});
            assertNotEquals(0L, secondSet, "缓存未分配出第二个 descriptor set");
            assertNotEquals(firstSet, secondSet, "不同的资源组合应得到不同的 set");
            writeUniform(vkDevice, secondSet, secondHandle, BUFFER_SIZE);

            assertEquals(firstSet, cache.lookup(firstKey), "同一组绑定应复用同一个 set，而不是每次新建");

            // Only the entries naming the destroyed resource retire; the rest must survive. Both are
            // asserted, because a cache that retires everything on any destruction would look correct
            // from the first assertion alone while throwing away the reuse it exists for.
            first.close();
            assertEquals(0L, cache.lookup(firstKey), "被销毁资源的 set 不得再被交出");
            assertEquals(secondSet, cache.lookup(secondKey), "与销毁无关的 set 必须留下");
            device.completePending();

            // Layout handles are recyclable: reloading shaders closes every pipeline, and a pipeline
            // compiled afterwards can be handed a layout handle that was just released. So a layout
            // going away has to retire the entries built for it too, or a reload would be handed a set
            // whose layout no longer exists.
            cache.invalidate(layout);
            assertEquals(0L, cache.lookup(secondKey), "布局退役后，按它建的 set 不得再被交出");
            device.completePending();

            second.close();
            device.completePending();

            assertNoValidationFindings(context);
        } finally {
            if (device != null) {
                // Retire the layout before destroying it, so a failure part-way cannot leave a set
                // referencing a destroyed layout and bury the real failure under validation noise.
                if (layout != 0L) device.descriptorCache().invalidate(layout);
                device.completePending();
                if (layout != 0L) vkDestroyDescriptorSetLayout(device.context().device(), layout, null);
                device.close();
            }
            SDL_Quit();
            restore(saved);
        }
    }

    @Test
    void bufferViewsAreCreatedOnARealDeviceAndRetireWithTheirBuffer() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.baselineDevice", "true",
                "lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        long layout = 0L;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            LavaFlowVulkanContext context = device.context();
            VkDevice vkDevice = context.device();
            LavaFlowDescriptorCache cache = device.descriptorCache();
            layout = createSetLayout(vkDevice, VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER);

            LavaFlowGpuBuffer buffer = new LavaFlowGpuBuffer(device, GpuBuffer.USAGE_UNIFORM_TEXEL_BUFFER, BUFFER_SIZE);
            long handle = buffer.handle();

            // A buffer view is the other Vulkan object this cache owns, and buildWrites puts its handle
            // straight into a descriptor for TEXEL_BUFFER bindings — so it carries the same stale-handle
            // risk as a set, and is created against a real driver here for the same reason.
            long view = cache.bufferView(handle, VK_FORMAT_R32G32B32A32_SFLOAT, 0L, BUFFER_SIZE);
            assertNotEquals(0L, view, "缓存未创建出 buffer view");
            assertEquals(view, cache.bufferView(handle, VK_FORMAT_R32G32B32A32_SFLOAT, 0L, BUFFER_SIZE),
                    "同一 buffer 区间应复用同一个 view");

            LavaFlowDescriptorCache.Key key = new LavaFlowDescriptorCache.Key(layout, new long[]{handle});
            long set = cache.allocateAndStore(key, layout, new long[]{handle});
            assertNotEquals(0L, set, "缓存未分配出 texel buffer 的 descriptor set");
            writeTexelBuffer(vkDevice, set, view);

            buffer.close();
            assertEquals(0L, cache.lookup(key), "buffer 销毁后，写有它 view 的 set 不得再被交出");
            device.completePending();

            assertNoValidationFindings(context);
        } finally {
            if (device != null) {
                if (layout != 0L) device.descriptorCache().invalidate(layout);
                device.completePending();
                if (layout != 0L) vkDestroyDescriptorSetLayout(device.context().device(), layout, null);
                device.close();
            }
            SDL_Quit();
            restore(saved);
        }
    }

    @Test
    void sampledImageDescriptorsRetireWithTheirViewAndTheirSampler() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.baselineDevice", "true",
                "lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        long layout = 0L;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            LavaFlowVulkanContext context = device.context();
            VkDevice vkDevice = context.device();
            LavaFlowDescriptorCache cache = device.descriptorCache();
            layout = createSetLayout(vkDevice, VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER);

            // This is the binding the crash stack was actually in: lightmap drawing binds a sampled
            // image, not a uniform buffer. A sampled image contributes two handles to a key — the view
            // and the sampler — either of which going stale is the same class of bug as the buffer case.
            LavaFlowGpuTexture texture = (LavaFlowGpuTexture) device.createTexture("descriptor path test",
                    GpuTexture.USAGE_TEXTURE_BINDING, GpuFormat.RGBA8_UNORM, 16, 16, 1, 1);
            // No command buffer is involved here, so the layout is stated rather than transitioned.
            // buildWrites refuses to write a sampled image that is not in GENERAL, so this is the
            // state it would see in production by the time a descriptor is pushed.
            texture.layout(VK_IMAGE_LAYOUT_GENERAL);
            LavaFlowGpuTextureView firstView = (LavaFlowGpuTextureView) device.createTextureView(texture);
            LavaFlowGpuTextureView secondView = (LavaFlowGpuTextureView) device.createTextureView(texture);
            LavaFlowGpuSampler sampler = (LavaFlowGpuSampler) device.createSampler(
                    AddressMode.CLAMP_TO_EDGE, AddressMode.CLAMP_TO_EDGE,
                    FilterMode.NEAREST, FilterMode.NEAREST, 1, OptionalDouble.empty());

            LavaFlowDescriptorCache.Key firstKey = new LavaFlowDescriptorCache.Key(layout,
                    new long[]{firstView.handle(), sampler.handle()});
            LavaFlowDescriptorCache.Key secondKey = new LavaFlowDescriptorCache.Key(layout,
                    new long[]{secondView.handle(), sampler.handle()});

            long firstSet = cache.allocateAndStore(firstKey, layout,
                    new long[]{firstView.handle(), sampler.handle()});
            assertNotEquals(0L, firstSet, "缓存未分配出 sampled image 的 descriptor set");
            writeSampledImage(vkDevice, firstSet, firstView.handle(), sampler.handle());

            long secondSet = cache.allocateAndStore(secondKey, layout,
                    new long[]{secondView.handle(), sampler.handle()});
            assertNotEquals(0L, secondSet, "缓存未分配出第二个 sampled image 的 descriptor set");
            writeSampledImage(vkDevice, secondSet, secondView.handle(), sampler.handle());

            // The view dying retires the entry naming it, and only that one — the other set shares the
            // sampler and must survive, or the sampler's own retirement below would prove nothing.
            firstView.close();
            assertEquals(0L, cache.lookup(firstKey), "texture view 销毁后，写有它的 set 不得再被交出");
            assertEquals(secondSet, cache.lookup(secondKey), "只换了 view 的 set 必须留下");
            device.completePending();

            sampler.close();
            assertEquals(0L, cache.lookup(secondKey), "sampler 销毁后，写有它的 set 不得再被交出");
            device.completePending();

            secondView.close();
            texture.close();
            device.completePending();

            assertNoValidationFindings(context);
        } finally {
            if (device != null) {
                if (layout != 0L) device.descriptorCache().invalidate(layout);
                device.completePending();
                if (layout != 0L) vkDestroyDescriptorSetLayout(device.context().device(), layout, null);
                device.close();
            }
            SDL_Quit();
            restore(saved);
        }
    }

    /**
     * The canary: it writes a buffer handle whose buffer has already been destroyed, and asserts the
     * layer says so.
     *
     * <p>Without it every "no findings" assertion in this class would be satisfied just as well by a
     * layer that never spoke — the failure mode this whole file exists to rule out, and one this
     * repository has already hit once (a test that passed while covering nothing because a stale
     * native library happened to be found elsewhere).
     *
     * <p>It also pins down what the Mali crash's second candidate looks like from here. For a stale
     * handle the layer reports
     * {@code vkUpdateDescriptorSets(): pDescriptorWrites[0].pBufferInfo[0].buffer Invalid VkBuffer
     * Object ...} — the very call the crash died in, reached with a handle to something already
     * freed. If that candidate is what happens on the device, this is the shape of the evidence, and
     * a build that reaches it goes red here instead of surviving to a tombstone.
     */
    @Test
    void theValidationLayerReportsAStaleHandle() throws Exception {
        assumeTrue(displayAvailable(), "需要显示服务器才能初始化 SDL 视频子系统（本地可用 xvfb-run）");
        Map<String, String> saved = setSwitches(Map.of(
                "lavaflow.baselineDevice", "true",
                "lavaflow.validation", "true"));
        LavaFlowDevice device = null;
        long layout = 0L;
        try {
            assumeTrue(SDL_Init(SDL_INIT_VIDEO), "SDL 视频子系统初始化失败");
            exposeVulkanLoaderToLwjgl();
            device = new LavaFlowDevice();
            LavaFlowVulkanContext context = device.context();
            // Skipped rather than passed when the layer is missing — the same shape as the context
            // test's display check, and CI asserts that nothing in this class was skipped. That is
            // what keeps the "no findings" assertions above from being true for the wrong reason: a
            // layer that never spoke satisfies them just as well.
            assumeTrue(context.validationEnabled(),
                    "需要验证层（vulkan-validationlayers 或 Vulkan SDK）才能断言已销毁的句柄会被报出来");
            VkDevice vkDevice = context.device();
            LavaFlowDescriptorCache cache = device.descriptorCache();
            layout = createSetLayout(vkDevice, VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER);

            // Deliberately the use-after-free the cache is built to prevent: take the handle, destroy
            // the buffer for real, then write that handle anyway.
            LavaFlowGpuBuffer doomed = new LavaFlowGpuBuffer(device, GpuBuffer.USAGE_UNIFORM, BUFFER_SIZE);
            long staleHandle = doomed.handle();
            doomed.close();
            device.completePending();

            long set = cache.allocateAndStore(new LavaFlowDescriptorCache.Key(layout, new long[]{staleHandle}),
                    layout, new long[]{staleHandle});
            writeUniform(vkDevice, set, staleHandle, BUFFER_SIZE);

            assertFalse(context.validationMessages().isEmpty(),
                    "验证层对已销毁句柄没有报错，所以本类其余用例的'无 findings'断言是空转的");
        } finally {
            if (device != null) {
                if (layout != 0L) device.descriptorCache().invalidate(layout);
                device.completePending();
                if (layout != 0L) vkDestroyDescriptorSetLayout(device.context().device(), layout, null);
                device.close();
            }
            SDL_Quit();
            restore(saved);
        }
    }

    /** A one-binding set layout whose binding 0 has the given descriptor type. */
    private static long createSetLayout(VkDevice device, int descriptorType) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(1, stack);
            bindings.get(0).binding(0).descriptorType(descriptorType).descriptorCount(1)
                    .stageFlags(VK_SHADER_STAGE_VERTEX_BIT);
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack)
                    .sType$Default().pBindings(bindings);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device, info, null, out), "vkCreateDescriptorSetLayout");
            return out.get(0);
        }
    }

    /**
     * The write {@code buildWrites} produces for a single uniform buffer, field for field: the same
     * {@code sType}, in the same order, with the offset left at zero as the non-dynamic path does.
     */
    private static void writeUniform(VkDevice device, long set, long buffer, long range) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorBufferInfo.Buffer info = VkDescriptorBufferInfo.calloc(1, stack)
                    .buffer(buffer).offset(0L).range(range);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).dstArrayElement(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER)
                    .pBufferInfo(info);
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /** The same call for a sampled image, whose sampler and view go in a third union member. */
    private static void writeSampledImage(VkDevice device, long set, long view, long sampler) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorImageInfo.Buffer imageInfo = VkDescriptorImageInfo.calloc(1, stack)
                    .sampler(sampler).imageView(view).imageLayout(VK_IMAGE_LAYOUT_GENERAL);
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).dstArrayElement(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER)
                    .pImageInfo(imageInfo);
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /** The same call for a texel buffer, whose view handle goes in a separate union member. */
    private static void writeTexelBuffer(VkDevice device, long set, long view) {
        try (MemoryStack stack = stackPush()) {
            VkWriteDescriptorSet.Buffer writes = VkWriteDescriptorSet.calloc(1, stack);
            writes.get(0).sType$Default().dstSet(set).dstBinding(0).dstArrayElement(0)
                    .descriptorCount(1).descriptorType(VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER)
                    .pTexelBufferView(stack.longs(view));
            vkUpdateDescriptorSets(device, writes, null);
        }
    }

    /**
     * Asserts the layer answered and had nothing to say about any of the above.
     *
     * <p>The whole device lifetime inside the test is covered, not only the descriptor writes — which
     * is what turned up a 1.2 structure used against a 1.1 instance in the context constructor the
     * first time this ran. Findings from code outside this class are still findings.
     *
     * <p>Where the layer is missing the assertion has nothing to stand on, so it says so rather than
     * passing vacuously — a green run that validated nothing is the failure mode this whole test
     * exists to remove. CI installs the layer and asserts separately that it answered.
     */
    private static void assertNoValidationFindings(LavaFlowVulkanContext context) {
        if (!context.validationEnabled()) {
            System.err.println("[LavaFlow] lavaflow.validation was requested but the layer is not "
                    + "installed, so the descriptor writes above could not be checked against it");
            return;
        }
        assertEquals(0, context.validationMessages().size(),
                () -> "this test produced validation findings: " + context.validationMessages());
    }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) {
            throw new IllegalStateException(operation + " failed with VkResult " + result);
        }
    }
}
