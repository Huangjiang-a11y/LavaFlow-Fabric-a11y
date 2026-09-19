package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/**
 * LavaFlow-owned shader modules, descriptor layout, pipeline layout, and compatible graphics pipelines.
 *
 * <p>26.3 hands the backend a fully resolved {@link BackendRenderPipeline.CreateInfo}. The frontend has
 * already compiled and reflected the SPIR-V, rewritten the shader interfaces and assigned the descriptor
 * slots, so this class only turns that description into Vulkan objects. In particular nothing here may
 * reorder the uniforms: slot {@code i} in the descriptor set layout — and therefore the index handed to
 * {@code RenderPassBackend.setUniform(int, Object)} — is exactly {@code CreateInfo.uniforms()} order.
 * 26.2 could reorder because it rewrote the SPIR-V itself through IntermediaryShaderModule; that mechanism
 * no longer exists and must not be reintroduced.
 */
final class LavaFlowRenderPipeline implements BackendRenderPipeline {
    enum EntryType { UNIFORM_BUFFER, SAMPLED_IMAGE, TEXEL_BUFFER }
    record Entry(EntryType type, String name, GpuFormat texelFormat) {}

    final LavaFlowDevice device;
    /**
     * Retained for the graphics pipelines that are created lazily, on the first draw that needs them.
     * Only the geometry and fixed-function accessors are read after construction: {@code shaders()} is
     * consumed while building the shader modules below, and the frontend closes those modules as soon as
     * compilation finishes, so it must never be touched again.
     */
    private final BackendRenderPipeline.CreateInfo info;
    private final List<Entry> entries;
    private final long[] shaderModules;
    private final int[] shaderStages;
    private final String[] shaderEntryPoints;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    // Dynamic-rendering pipelines keyed by the VkFormat of the depth attachment (VK_FORMAT_UNDEFINED = no depth).
    // Dynamic rendering requires the pipeline's depthAttachmentFormat to match the render pass, so the format
    // is part of the cache key rather than a boolean that assumes a fixed format.
    private final Map<Integer, Long> dynamicPipelines = new HashMap<>();
    private long[] legacyRenderPasses = new long[4];
    private long[] legacyPipelines = new long[4];
    private int legacyPipelineCount;
    private final boolean dynamicUniforms;
    private boolean closed;

    private LavaFlowRenderPipeline(LavaFlowDevice device, BackendRenderPipeline.CreateInfo createInfo) {
        this.device = device;
        this.info = createInfo;
        this.entries = buildEntries(createInfo);
        this.shaderModules = new long[createInfo.shaders().size()];
        this.shaderStages = new int[createInfo.shaders().size()];
        this.shaderEntryPoints = new String[createInfo.shaders().size()];
        int uniformCount = 0;
        for (Entry entry : entries) {
            if (entry.type() == EntryType.UNIFORM_BUFFER) uniformCount++;
        }
        // Push descriptor sets may not contain dynamic uniform buffers, so dynamic offsets are only
        // used on the descriptor-set path, and only while within the device's dynamic-buffer limit.
        this.dynamicUniforms = !device.context().pushDescriptors()
                && uniformCount <= device.context().properties().limits().maxDescriptorSetUniformBuffersDynamic();

        long createdSetLayout = 0, createdPipelineLayout = 0;
        int createdModules = 0;
        try {
            for (int i = 0; i < shaderModules.length; i++) {
                BackendRenderPipeline.CreateInfo.Shader shader = createInfo.shaders().get(i);
                shaderModules[i] = createShaderModule(shader.module().spv());
                createdModules = i + 1;
                shaderStages[i] = LavaFlowVk.stage(shader.module().type());
                shaderEntryPoints[i] = shader.entryPoint();
            }
            createdSetLayout = createDescriptorSetLayout(entries);
            createdPipelineLayout = createPipelineLayout(createdSetLayout);
        } catch (Throwable failure) {
            VkDevice vkDevice = device.context().device();
            if (createdPipelineLayout != 0) vkDestroyPipelineLayout(vkDevice, createdPipelineLayout, null);
            if (createdSetLayout != 0) vkDestroyDescriptorSetLayout(vkDevice, createdSetLayout, null);
            for (int i = 0; i < createdModules; i++) vkDestroyShaderModule(vkDevice, shaderModules[i], null);
            throw new IllegalStateException("Failed to compile LavaFlow pipeline " + createInfo.name(), failure);
        }
        descriptorSetLayout = createdSetLayout;
        pipelineLayout = createdPipelineLayout;
    }

    /**
     * Turns a frontend-resolved pipeline description into a LavaFlow pipeline.
     *
     * <p>Called from {@link LavaFlowDevice#compilePipeline}, which is where the frontend's
     * {@code Pending} contract expects the work: the SPIR-V in {@code createInfo.shaders()} is still valid
     * at this point and is released by the frontend once compilation returns.
     */
    static LavaFlowRenderPipeline compile(LavaFlowDevice device, BackendRenderPipeline.CreateInfo createInfo) {
        return new LavaFlowRenderPipeline(device, createInfo);
    }

    /**
     * One entry per {@code createInfo.uniforms()}, in that exact order.
     *
     * <p>No grouping, no deduplication and no reordering: the frontend derived this order from the SPIR-V
     * reflection and uses the same indices to address uniforms, so a different order here would bind
     * descriptors to the wrong slots.
     */
    private static List<Entry> buildEntries(BackendRenderPipeline.CreateInfo createInfo) {
        List<UniformDescription> uniforms = createInfo.uniforms();
        List<Entry> result = new ArrayList<>(uniforms.size());
        for (UniformDescription uniform : uniforms) {
            EntryType type = switch (uniform.type()) {
                case UNIFORM_BUFFER -> EntryType.UNIFORM_BUFFER;
                case COMBINED_IMAGE_SAMPLER -> EntryType.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> EntryType.TEXEL_BUFFER;
            };
            result.add(new Entry(type, uniform.name(), uniform.gpuFormat()));
        }
        return List.copyOf(result);
    }

    private long createShaderModule(ByteBuffer spirv) {
        try (MemoryStack stack = stackPush()) {
            // The frontend compiles for Vulkan 1.2 regardless of the device in use, so a 1.1 device is
            // handed SPIR-V modules it cannot consume. See LavaFlowSpirv for why the header is the only
            // place this can be corrected; the buffer is returned unchanged when the device accepts it.
            ByteBuffer code = LavaFlowSpirv.downlevel(stack, spirv, device.context().maxSpirvVersion());
            VkShaderModuleCreateInfo create = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(code);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateShaderModule(device.context().device(), create, null, out), "vkCreateShaderModule");
            return out.get(0);
        }
    }

    private long createDescriptorSetLayout(List<Entry> entries) {
        try (MemoryStack stack = stackPush()) {
            VkDescriptorSetLayoutBinding.Buffer bindings = VkDescriptorSetLayoutBinding.calloc(entries.size(), stack);
            for (int i = 0; i < entries.size(); i++) {
                bindings.get(i).binding(i).descriptorCount(1).descriptorType(vkDescriptorType(entries.get(i).type))
                        .stageFlags(VK_SHADER_STAGE_VERTEX_BIT | VK_SHADER_STAGE_FRAGMENT_BIT);
            }
            VkDescriptorSetLayoutCreateInfo info = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default()
                    .flags(device.context().pushDescriptors()
                            ? VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR : 0);
            if (!entries.isEmpty()) info.pBindings(bindings);
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateDescriptorSetLayout(device.context().device(), info, null, out), "vkCreateDescriptorSetLayout");
            return out.get(0);
        }
    }

    private long createPipelineLayout(long setLayout) {
        try (MemoryStack stack = stackPush()) {
            VkPipelineLayoutCreateInfo info = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout));
            // 26.3 resolves this on the frontend from the pipeline's declared size and validates it
            // against the shader's own push_constant reflection, so the backend never has to work it out
            // from the shader source (which is how the pre-26.3 backend did it, via a Sodium-installed
            // provider that no longer exists).
            int pushConstantSize = this.info.pushConstantsSize();
            if (pushConstantSize > 0) {
                int maxPushConstantSize = device.context().properties().limits().maxPushConstantsSize();
                if (pushConstantSize > maxPushConstantSize) {
                    throw new IllegalStateException("Pipeline " + this.info.name() + " needs "
                            + pushConstantSize + " push-constant bytes but the device allows only "
                            + maxPushConstantSize);
                }
                info.pPushConstantRanges(VkPushConstantRange.calloc(1, stack)
                        .offset(0).size(pushConstantSize).stageFlags(VK_SHADER_STAGE_ALL));
            }
            LongBuffer out = stack.mallocLong(1);
            check(vkCreatePipelineLayout(device.context().device(), info, null, out), "vkCreatePipelineLayout");
            return out.get(0);
        }
    }

    /**
     * Returns the pipeline to use for a draw in the given render pass.
     *
     * @param depthVkFormat the VkFormat of the depth attachment, or {@code VK_FORMAT_UNDEFINED} (0)
     *                      when the pass has no depth attachment. For dynamic rendering this is part
     *                      of the pipeline's compile-time state, so it must match the actual attachment
     *                      format the render pass uses.
     * @param renderPass    the legacy render pass handle, or {@code 0} for dynamic rendering.
     */
    long pipelineFor(int depthVkFormat, long renderPass) {
        if (closed) throw new IllegalStateException("Pipeline is closed");
        if (device.context().dynamicRendering()) {
            Long cached = dynamicPipelines.get(depthVkFormat);
            return cached != null ? cached : createDynamicPipeline(depthVkFormat);
        }
        for (int i = 0; i < legacyPipelineCount; i++) {
            if (legacyRenderPasses[i] == renderPass) return legacyPipelines[i];
        }
        return createLegacyPipeline(depthVkFormat != 0, renderPass);
    }

    private synchronized long createDynamicPipeline(int depthVkFormat) {
        Long cached = dynamicPipelines.get(depthVkFormat);
        if (cached != null) return cached;
        long pipeline = createGraphicsPipeline(depthVkFormat, 0);
        dynamicPipelines.put(depthVkFormat, pipeline);
        return pipeline;
    }

    private synchronized long createLegacyPipeline(boolean hasDepth, long renderPass) {
        for (int i = 0; i < legacyPipelineCount; i++) {
            if (legacyRenderPasses[i] == renderPass) return legacyPipelines[i];
        }
        // For legacy render passes, depth is a binary yes/no encoded in the render pass object itself;
        // the actual format is implicit in the render pass handle used as the key.
        long pipeline = createGraphicsPipeline(hasDepth ? 1 : 0, renderPass);
        if (legacyPipelineCount == legacyRenderPasses.length) {
            legacyRenderPasses = Arrays.copyOf(legacyRenderPasses, legacyPipelineCount * 2);
            legacyPipelines = Arrays.copyOf(legacyPipelines, legacyPipelineCount * 2);
        }
        legacyRenderPasses[legacyPipelineCount] = renderPass;
        legacyPipelines[legacyPipelineCount] = pipeline;
        legacyPipelineCount++;
        return pipeline;
    }

    /**
     * Creates one graphics pipeline.
     *
     * @param depthVkFormat for dynamic rendering: the VkFormat of the depth attachment, or
     *                      {@code VK_FORMAT_UNDEFINED} (0) for no depth. For legacy render passes:
     *                      {@code 0} for no depth, any non-zero sentinel for "has depth" (the actual
     *                      format is already encoded in the render pass object).
     * @param renderPass    legacy render pass handle, or {@code 0} for dynamic rendering.
     */
    private long createGraphicsPipeline(int depthVkFormat, long renderPass) {
        try (MemoryStack stack = stackPush()) {
            List<BackendRenderPipeline.CreateInfo.VertexBuffer> vertexBufferDescs = info.vertexBuffers();
            List<BackendRenderPipeline.CreateInfo.AttribBinding> attribDescs = info.attribBindings();
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(shaderModules.length, stack);
            for (int i = 0; i < shaderModules.length; i++) {
                stages.get(i).sType$Default().stage(shaderStages[i]).module(shaderModules[i])
                        .pName(stack.UTF8(shaderEntryPoints[i]));
            }
            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(vertexBufferDescs.size(), stack);
            int divisorCount = 0;
            if (device.context().vertexAttributeDivisor()) {
                for (BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer : vertexBufferDescs) {
                    if (vertexBuffer.stepRate() > 1) divisorCount++;
                }
            }
            VkVertexInputBindingDivisorDescriptionEXT.Buffer divisors =
                    VkVertexInputBindingDivisorDescriptionEXT.calloc(divisorCount, stack);
            int divisorPosition = 0;
            for (int i = 0; i < vertexBufferDescs.size(); i++) {
                BackendRenderPipeline.CreateInfo.VertexBuffer vertexBuffer = vertexBufferDescs.get(i);
                bindings.get(i).binding(vertexBuffer.bufferSlot()).stride(vertexBuffer.stride())
                        .inputRate(vertexBuffer.stepRate() > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
                // A step rate of 1 is the instancing default, so only a larger rate needs a divisor.
                if (vertexBuffer.stepRate() > 1 && device.context().vertexAttributeDivisor()) {
                    divisors.get(divisorPosition++).binding(vertexBuffer.bufferSlot()).divisor(vertexBuffer.stepRate());
                }
            }
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(attribDescs.size(), stack);
            for (int i = 0; i < attribDescs.size(); i++) {
                BackendRenderPipeline.CreateInfo.AttribBinding attrib = attribDescs.get(i);
                attributes.get(i).location(attrib.location()).binding(attrib.bufferSlot())
                        .format(LavaFlowVk.format(attrib.format())).offset(attrib.offset());
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (!vertexBufferDescs.isEmpty()) vertexInput.pVertexBindingDescriptions(bindings);
            if (!attribDescs.isEmpty()) vertexInput.pVertexAttributeDescriptions(attributes);
            if (divisorCount != 0) {
                VkPipelineVertexInputDivisorStateCreateInfoEXT divisorState =
                        VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack).sType$Default()
                                .pVertexBindingDivisors(divisors);
                vertexInput.pNext(divisorState.address());
            }
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(LavaFlowVk.topology(info.primitiveTopology())).primitiveRestartEnable(false);
            int polygonMode = LavaFlowVk.polygonMode(info.polygonMode());
            if (polygonMode != VK_POLYGON_MODE_FILL && !device.context().fillModeNonSolid()) {
                polygonMode = VK_POLYGON_MODE_FILL;
            }
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(polygonMode)
                    .cullMode(info.cull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f);

            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            DepthStencilState depthState = info.depthStencilState();
            if (depthVkFormat != 0 && depthState != null) {
                depth.depthTestEnable(true).depthWriteEnable(depthState.writeDepth())
                        .depthCompareOp(LavaFlowVk.compareOp(depthState.depthTest()));
                boolean bias = depthState.depthBiasConstant() != 0 || depthState.depthBiasScaleFactor() != 0;
                raster.depthBiasEnable(bias).depthBiasConstantFactor(depthState.depthBiasConstant())
                        .depthBiasSlopeFactor(depthState.depthBiasScaleFactor());
            }

            List<ColorTargetState> targets = info.colorTargetStates();
            VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(targets.size(), stack);
            for (int i = 0; i < targets.size(); i++) {
                ColorTargetState target = targets.get(i);
                if (target == null) continue;
                VkPipelineColorBlendAttachmentState attachment = blendAttachments.get(i)
                        .colorWriteMask(LavaFlowVk.colorWriteMask(target));
                target.blendFunction().ifPresent(blend -> applyBlend(attachment, blend));
            }
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            if (!targets.isEmpty()) blend.pAttachments(blendAttachments);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            IntBuffer colorFormats = stack.mallocInt(targets.size());
            for (int i = 0; i < targets.size(); i++) {
                colorFormats.put(i, targets.get(i) == null ? VK_FORMAT_UNDEFINED
                        : LavaFlowVk.format(targets.get(i).format()));
            }
            VkGraphicsPipelineCreateInfo.Buffer create = VkGraphicsPipelineCreateInfo.calloc(1, stack).sType$Default()
                    .pStages(stages).pVertexInputState(vertexInput).pInputAssemblyState(assembly)
                    .pRasterizationState(raster).pDepthStencilState(depth).pColorBlendState(blend)
                    .pViewportState(viewport).pMultisampleState(multisample).pDynamicState(dynamic)
                    .layout(pipelineLayout);
            if (device.context().dynamicRendering()) {
                VkPipelineRenderingCreateInfoKHR rendering = VkPipelineRenderingCreateInfoKHR.calloc(stack)
                        .sType$Default().pColorAttachmentFormats(colorFormats)
                        .depthAttachmentFormat(depthVkFormat);
                create.pNext(rendering);
            } else {
                create.renderPass(renderPass).subpass(0);
            }
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device.context().device(), 0, create, null, out),
                    "vkCreateGraphicsPipelines for " + info.name()
                            + " (renderPass=" + renderPass + ", depthVkFormat=" + depthVkFormat
                            + ", colorTargets=" + targets.size() + ", shaders=" + shaderModules.length + ")");
            return out.get(0);
        }
    }

    private static void applyBlend(VkPipelineColorBlendAttachmentState attachment, BlendFunction blend) {
        attachment.blendEnable(true)
                .srcColorBlendFactor(LavaFlowVk.blendFactor(blend.color().sourceFactor()))
                .dstColorBlendFactor(LavaFlowVk.blendFactor(blend.color().destFactor()))
                .colorBlendOp(LavaFlowVk.blendOp(blend.color().op()))
                .srcAlphaBlendFactor(LavaFlowVk.blendFactor(blend.alpha().sourceFactor()))
                .dstAlphaBlendFactor(LavaFlowVk.blendFactor(blend.alpha().destFactor()))
                .alphaBlendOp(LavaFlowVk.blendOp(blend.alpha().op()));
    }

    /**
     * The Vulkan descriptor type backing {@code type} in this pipeline's layout.
     *
     * <p>On the descriptor-set path uniform buffers are dynamic: their byte offset is supplied at
     * bind time instead of being written into the set, so a set stays reusable across draws that
     * only move within a buffer — which is how Blaze3D delivers per-draw uniforms.
     */
    int vkDescriptorType(EntryType type) {
        return switch (type) {
            case UNIFORM_BUFFER -> dynamicUniforms
                    ? VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC : VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            case SAMPLED_IMAGE -> VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
            case TEXEL_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_TEXEL_BUFFER;
        };
    }

    boolean dynamicUniforms() { return dynamicUniforms; }

    private static void check(int result, String operation) {
        if (result != VK_SUCCESS) throw new IllegalStateException(operation + " failed with VkResult " + result);
    }

    List<Entry> entries() { return entries; }
    long descriptorSetLayout() { return descriptorSetLayout; }
    long pipelineLayout() { return pipelineLayout; }
    @Override public boolean isClosed() { return closed; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        VkDevice vkDevice = device.context().device();
        // Cached descriptor sets are keyed on this layout's handle, so they must be retired along with
        // it. A pipeline compiled later can be handed the same handle, and an entry left behind would
        // answer a lookup for a layout it was never built for instead of missing the cache.
        device.invalidateDescriptorCache(descriptorSetLayout);
        long[] nativeDynamicPipelines = dynamicPipelines.values().stream().mapToLong(Long::longValue).toArray();
        dynamicPipelines.clear();
        long[] nativeLegacyPipelines = Arrays.copyOf(legacyPipelines, legacyPipelineCount);
        legacyPipelineCount = 0;
        device.defer(() -> {
            for (long pipeline : nativeDynamicPipelines) vkDestroyPipeline(vkDevice, pipeline, null);
            for (long pipeline : nativeLegacyPipelines) vkDestroyPipeline(vkDevice, pipeline, null);
            vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);
            for (long module : shaderModules) vkDestroyShaderModule(vkDevice, module, null);
        });
    }
}
