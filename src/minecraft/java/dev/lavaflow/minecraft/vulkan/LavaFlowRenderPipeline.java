package dev.lavaflow.minecraft.vulkan;

import com.mojang.blaze3d.pipeline.*;
import com.mojang.blaze3d.preprocessor.GlslPreprocessor;
import com.mojang.blaze3d.shaders.ShaderSource;
import com.mojang.blaze3d.shaders.ShaderType;
import com.mojang.blaze3d.shaders.UniformType;
import com.mojang.blaze3d.vertex.VertexFormat;
import com.mojang.blaze3d.vertex.VertexFormatElement;
import com.mojang.blaze3d.vulkan.VulkanBindGroupLayout;
import com.mojang.blaze3d.vulkan.glsl.IntermediaryShaderModule;
import com.mojang.blaze3d.vulkan.glsl.ShaderCompileException;
import net.minecraft.client.renderer.ShaderDefines;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.*;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.*;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.util.shaderc.Shaderc.*;
import static org.lwjgl.vulkan.KHRPushDescriptor.VK_DESCRIPTOR_SET_LAYOUT_CREATE_PUSH_DESCRIPTOR_BIT_KHR;
import static org.lwjgl.vulkan.VK10.*;

/** LavaFlow-owned shader modules, descriptor layout, pipeline layout, and compatible graphics pipelines. */
final class LavaFlowRenderPipeline implements CompiledRenderPipeline, AutoCloseable {
    enum EntryType { UNIFORM_BUFFER, SAMPLED_IMAGE, TEXEL_BUFFER }
    record Entry(EntryType type, String name, com.mojang.blaze3d.GpuFormat texelFormat) {}

    final LavaFlowDevice device;
    final RenderPipeline info;
    final ShaderSource source;
    private final List<Entry> entries;
    private final Map<String, Integer> entryIndices;
    private final long vertexModule;
    private final long fragmentModule;
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

    LavaFlowRenderPipeline(LavaFlowDevice device, RenderPipeline info, ShaderSource source) {
        this.device = device;
        this.info = info;
        this.source = source;
        this.entries = buildEntries(info);
        this.entryIndices = buildEntryIndices(entries);
        int uniformCount = 0;
        for (Entry entry : entries) {
            if (entry.type() == EntryType.UNIFORM_BUFFER) uniformCount++;
        }
        // Push descriptor sets may not contain dynamic uniform buffers, so dynamic offsets are only
        // used on the descriptor-set path, and only while within the device's dynamic-buffer limit.
        this.dynamicUniforms = !device.context().pushDescriptors()
                && uniformCount <= device.context().properties().limits().maxDescriptorSetUniformBuffersDynamic();

        long createdVertex = 0, createdFragment = 0, createdSetLayout = 0, createdPipelineLayout = 0;
        try {
            String vertexText = shaderText(device, source, info, ShaderType.VERTEX);
            String fragmentText = shaderText(device, source, info, ShaderType.FRAGMENT);
            try (IntermediaryShaderModule vertex = compileIntermediary(
                    info.getVertexShader().toDebugFileName(), vertexText, ShaderType.VERTEX);
                 IntermediaryShaderModule fragment = compileIntermediary(
                         info.getFragmentShader().toDebugFileName(), fragmentText, ShaderType.FRAGMENT)) {
                List<VulkanBindGroupLayout.Entry> rebindEntries = rebindEntries(entries);
                vertex.rebind(vertexNames(info), rebindEntries);
                fragment.rebind(recordNames(vertex.outputs()), rebindEntries);
                createdVertex = createShaderModule(vertex.spirv());
                createdFragment = createShaderModule(fragment.spirv());
            }
            createdSetLayout = createDescriptorSetLayout(entries);
            createdPipelineLayout = createPipelineLayout(createdSetLayout);
        } catch (Throwable failure) {
            VkDevice vkDevice = device.context().device();
            if (createdPipelineLayout != 0) vkDestroyPipelineLayout(vkDevice, createdPipelineLayout, null);
            if (createdSetLayout != 0) vkDestroyDescriptorSetLayout(vkDevice, createdSetLayout, null);
            if (createdFragment != 0) vkDestroyShaderModule(vkDevice, createdFragment, null);
            if (createdVertex != 0) vkDestroyShaderModule(vkDevice, createdVertex, null);
            throw new IllegalStateException("Failed to compile LavaFlow pipeline " + info.getLocation(), failure);
        }
        vertexModule = createdVertex;
        fragmentModule = createdFragment;
        descriptorSetLayout = createdSetLayout;
        pipelineLayout = createdPipelineLayout;
    }

    private static IntermediaryShaderModule compileIntermediary(String name, String source, ShaderType type)
            throws ShaderCompileException {
        ShaderDefines globals = ShaderDefines.builder()
                .define("gl_VertexID", "gl_VertexIndex")
                .define("gl_InstanceID", "gl_InstanceIndex")
                .build();
        String shaderSource = GlslPreprocessor.injectDefines(source, globals);
        ByteBuffer copy;
        try {
            copy = LavaFlowShaderc.compile(shaderSource,
                    type == ShaderType.FRAGMENT ? shaderc_fragment_shader : shaderc_vertex_shader,
                    name);
        } catch (IllegalStateException failure) {
            throw new ShaderCompileException(failure.getMessage());
        }
        try {
            return IntermediaryShaderModule.createFromSpirv(name, copy);
        } catch (Throwable failure) {
            MemoryUtil.memFree(copy);
            throw failure;
        }
    }

    private static String shaderText(LavaFlowDevice device, ShaderSource source, RenderPipeline info, ShaderType type) {
        var id = type == ShaderType.VERTEX ? info.getVertexShader() : info.getFragmentShader();
        String text = device.shaderText(id, type, source);
        if (text == null) throw new IllegalStateException("Missing " + type.getName() + " shader " + id);
        return GlslPreprocessor.injectDefines(text, info.getShaderDefines());
    }

    private static List<Entry> buildEntries(RenderPipeline info) {
        LinkedHashMap<String, Entry> result = new LinkedHashMap<>();
        for (BindGroupLayout.UniformDescription uniform : BindGroupLayout.flattenUniforms(info.getBindGroupLayouts())) {
            EntryType type = uniform.type() == UniformType.TEXEL_BUFFER ? EntryType.TEXEL_BUFFER : EntryType.UNIFORM_BUFFER;
            result.putIfAbsent(uniform.name(), new Entry(type, uniform.name(), uniform.gpuFormat()));
        }
        for (String sampler : BindGroupLayout.flattenSamplers(info.getBindGroupLayouts())) {
            result.putIfAbsent(sampler, new Entry(EntryType.SAMPLED_IMAGE, sampler, null));
        }
        return List.copyOf(result.values());
    }

    private static Map<String, Integer> buildEntryIndices(List<Entry> entries) {
        Map<String, Integer> result = new HashMap<>(Math.max(4, entries.size() * 2));
        for (int i = 0; i < entries.size(); i++) result.put(entries.get(i).name(), i);
        return result;
    }

    private static List<VulkanBindGroupLayout.Entry> rebindEntries(List<Entry> entries) {
        List<VulkanBindGroupLayout.Entry> result = new ArrayList<>(entries.size());
        for (Entry entry : entries) {
            VulkanBindGroupLayout.VulkanBindGroupEntryType type = switch (entry.type) {
                case UNIFORM_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.UNIFORM_BUFFER;
                case SAMPLED_IMAGE -> VulkanBindGroupLayout.VulkanBindGroupEntryType.SAMPLED_IMAGE;
                case TEXEL_BUFFER -> VulkanBindGroupLayout.VulkanBindGroupEntryType.TEXEL_BUFFER;
            };
            result.add(new VulkanBindGroupLayout.Entry(type, entry.name, entry.texelFormat));
        }
        return result;
    }

    private static List<String> vertexNames(RenderPipeline info) {
        List<String> names = new ArrayList<>();
        for (VertexFormat format : info.getVertexFormatBindings()) {
            if (format == null) continue;
            for (VertexFormatElement element : format.getElements()) names.add(element.name());
        }
        return names;
    }

    private static List<String> recordNames(List<?> records) {
        List<String> result = new ArrayList<>(records.size());
        for (Object record : records) {
            try {
                Method name = record.getClass().getMethod("name");
                name.setAccessible(true);
                result.add((String) name.invoke(record));
            } catch (ReflectiveOperationException failure) {
                throw new IllegalStateException("Unable to read SPIR-V interface variable", failure);
            }
        }
        return result;
    }

    private long createShaderModule(ByteBuffer spirv) {
        try (MemoryStack stack = stackPush()) {
            VkShaderModuleCreateInfo info = VkShaderModuleCreateInfo.calloc(stack).sType$Default()
                    .pCode(spirv.duplicate());
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateShaderModule(device.context().device(), info, null, out), "vkCreateShaderModule");
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
            int pushConstantSize = LavaFlowPushConstants.sizeFor(this.info);
            if (pushConstantSize > 0) {
                int maxPushConstantSize = device.context().properties().limits().maxPushConstantsSize();
                if (pushConstantSize > maxPushConstantSize) {
                    throw new IllegalStateException("Pipeline " + this.info.getLocation() + " needs "
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
            ByteBuffer main = stack.UTF8("main");
            VkPipelineShaderStageCreateInfo.Buffer stages = VkPipelineShaderStageCreateInfo.calloc(2, stack);
            stages.get(0).sType$Default().stage(VK_SHADER_STAGE_VERTEX_BIT).module(vertexModule).pName(main);
            stages.get(1).sType$Default().stage(VK_SHADER_STAGE_FRAGMENT_BIT).module(fragmentModule).pName(main);

            VertexFormat[] formats = info.getVertexFormatBindings();
            int attributeCount = 0, bindingCount = 0;
            for (VertexFormat format : formats) if (format != null) {
                bindingCount++;
                attributeCount += format.getElements().size();
            }
            VkVertexInputBindingDescription.Buffer bindings = VkVertexInputBindingDescription.calloc(bindingCount, stack);
            VkVertexInputAttributeDescription.Buffer attributes = VkVertexInputAttributeDescription.calloc(attributeCount, stack);
            int divisorCount = 0;
            if (device.context().vertexAttributeDivisor()) {
                for (VertexFormat format : formats) {
                    if (format != null && format.getStepRate() > 1) divisorCount++;
                }
            }
            VkVertexInputBindingDivisorDescriptionEXT.Buffer divisors =
                    VkVertexInputBindingDivisorDescriptionEXT.calloc(divisorCount, stack);
            int bindingPosition = 0, location = 0;
            int divisorPosition = 0;
            for (int slot = 0; slot < formats.length; slot++) {
                VertexFormat format = formats[slot];
                if (format == null) continue;
                bindings.get(bindingPosition++).binding(slot).stride(format.getVertexSize())
                        .inputRate(format.getStepRate() > 0 ? VK_VERTEX_INPUT_RATE_INSTANCE : VK_VERTEX_INPUT_RATE_VERTEX);
                if (format.getStepRate() > 1 && device.context().vertexAttributeDivisor()) {
                    divisors.get(divisorPosition++).binding(slot).divisor(format.getStepRate());
                }
                for (VertexFormatElement element : format.getElements()) {
                    attributes.get(location).location(location).binding(slot).format(LavaFlowVk.format(element.format()))
                            .offset(element.offset());
                    location++;
                }
            }
            VkPipelineVertexInputStateCreateInfo vertexInput = VkPipelineVertexInputStateCreateInfo.calloc(stack).sType$Default();
            if (bindingCount != 0) vertexInput.pVertexBindingDescriptions(bindings);
            if (attributeCount != 0) vertexInput.pVertexAttributeDescriptions(attributes);
            if (divisorCount != 0) {
                VkPipelineVertexInputDivisorStateCreateInfoEXT divisorState =
                        VkPipelineVertexInputDivisorStateCreateInfoEXT.calloc(stack).sType$Default()
                                .pVertexBindingDivisors(divisors);
                vertexInput.pNext(divisorState.address());
            }
            VkPipelineInputAssemblyStateCreateInfo assembly = VkPipelineInputAssemblyStateCreateInfo.calloc(stack).sType$Default()
                    .topology(LavaFlowVk.topology(info.getPrimitiveTopology())).primitiveRestartEnable(false);
            int polygonMode = LavaFlowVk.polygonMode(info.getPolygonMode());
            if (polygonMode != VK_POLYGON_MODE_FILL && !device.context().fillModeNonSolid()) {
                polygonMode = VK_POLYGON_MODE_FILL;
            }
            VkPipelineRasterizationStateCreateInfo raster = VkPipelineRasterizationStateCreateInfo.calloc(stack).sType$Default()
                    .polygonMode(polygonMode)
                    .cullMode(info.isCull() ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE)
                    .frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f);

            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            DepthStencilState depthState = info.getDepthStencilState();
            if (depthVkFormat != 0 && depthState != null) {
                depth.depthTestEnable(true).depthWriteEnable(depthState.writeDepth())
                        .depthCompareOp(LavaFlowVk.compareOp(depthState.depthTest()));
                boolean bias = depthState.depthBiasConstant() != 0 || depthState.depthBiasScaleFactor() != 0;
                raster.depthBiasEnable(bias).depthBiasConstantFactor(depthState.depthBiasConstant())
                        .depthBiasSlopeFactor(depthState.depthBiasScaleFactor());
            }

            ColorTargetState[] targets = info.getColorTargetStates();
            VkPipelineColorBlendAttachmentState.Buffer blendAttachments = VkPipelineColorBlendAttachmentState.calloc(targets.length, stack);
            for (int i = 0; i < targets.length; i++) {
                ColorTargetState target = targets[i];
                if (target == null) continue;
                VkPipelineColorBlendAttachmentState attachment = blendAttachments.get(i)
                        .colorWriteMask(LavaFlowVk.colorWriteMask(target));
                target.blendFunction().ifPresent(blend -> applyBlend(attachment, blend));
            }
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            if (targets.length != 0) blend.pAttachments(blendAttachments);
            VkPipelineViewportStateCreateInfo viewport = VkPipelineViewportStateCreateInfo.calloc(stack).sType$Default()
                    .viewportCount(1).scissorCount(1);
            VkPipelineMultisampleStateCreateInfo multisample = VkPipelineMultisampleStateCreateInfo.calloc(stack).sType$Default()
                    .rasterizationSamples(VK_SAMPLE_COUNT_1_BIT);
            VkPipelineDynamicStateCreateInfo dynamic = VkPipelineDynamicStateCreateInfo.calloc(stack).sType$Default()
                    .pDynamicStates(stack.ints(VK_DYNAMIC_STATE_VIEWPORT, VK_DYNAMIC_STATE_SCISSOR));
            IntBuffer colorFormats = stack.mallocInt(targets.length);
            for (int i = 0; i < targets.length; i++) {
                colorFormats.put(i, targets[i] == null ? VK_FORMAT_UNDEFINED
                        : LavaFlowVk.format(targets[i].format()));
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
            check(vkCreateGraphicsPipelines(device.context().device(), 0, create, null, out), "vkCreateGraphicsPipelines");
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
    int bindingIndex(String name) {
        Integer index = entryIndices.get(name);
        return index == null ? -1 : index;
    }
    long descriptorSetLayout() { return descriptorSetLayout; }
    long pipelineLayout() { return pipelineLayout; }
    @Override public boolean isValid() { return !closed && vertexModule != 0 && fragmentModule != 0; }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        VkDevice vkDevice = device.context().device();
        long[] nativeDynamicPipelines = dynamicPipelines.values().stream().mapToLong(Long::longValue).toArray();
        dynamicPipelines.clear();
        long[] nativeLegacyPipelines = Arrays.copyOf(legacyPipelines, legacyPipelineCount);
        legacyPipelineCount = 0;
        device.defer(() -> {
            for (long pipeline : nativeDynamicPipelines) vkDestroyPipeline(vkDevice, pipeline, null);
            for (long pipeline : nativeLegacyPipelines) vkDestroyPipeline(vkDevice, pipeline, null);
            vkDestroyPipelineLayout(vkDevice, pipelineLayout, null);
            vkDestroyDescriptorSetLayout(vkDevice, descriptorSetLayout, null);
            vkDestroyShaderModule(vkDevice, fragmentModule, null);
            vkDestroyShaderModule(vkDevice, vertexModule, null);
        });
    }
}
