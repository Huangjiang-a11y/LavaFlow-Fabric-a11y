package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.GpuFormat;
import com.mojang.renderpearl.api.pipeline.BindGroupLayout.UniformDescription;
import com.mojang.renderpearl.api.pipeline.BlendFunction;
import com.mojang.renderpearl.api.pipeline.ColorTargetState;
import com.mojang.renderpearl.api.pipeline.CompareOp;
import com.mojang.renderpearl.api.pipeline.DepthStencilState;
import com.mojang.renderpearl.backend.api.BackendRenderPipeline;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import dev.lavaflow.vulkan.VulkanException;

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
    private final int[] descriptorTypes;
    private final int[] sampledIndices;
    private final long[] shaderModules;
    private final int[] shaderStages;
    private final String[] shaderEntryPoints;
    // What each module asks of the device, read out of the words before the module is made.
    private final String[] spirvNotes;
    // The abilities each module declares, kept so a pipeline can be refused before the driver sees it.
    private final int[][] spirvCapabilities;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    // Dynamic-rendering pipelines keyed by the VkFormat of the depth attachment (VK_FORMAT_UNDEFINED = no depth).
    // Dynamic rendering requires the pipeline's depthAttachmentFormat to match the render pass, so the format
    // is part of the cache key rather than a boolean that assumes a fixed format.
    // Parallel arrays rather than a Map keyed by Integer: pipelineFor is consulted on every pipeline bind,
    // the distinct depth formats are a handful, and a boxed key would allocate for every format above the
    // Integer cache — which VK_FORMAT_D24_UNORM_S8_UINT (129) and VK_FORMAT_D32_SFLOAT_S8_UINT (130) both
    // are. Mirrors the legacyRenderPasses/legacyPipelines pair below.
    private int[] dynamicFormats = new int[4];
    private long[] dynamicPipelines = new long[4];
    private int dynamicPipelineCount;
    private long[] legacyRenderPasses = new long[4];
    private long[] legacyPipelines = new long[4];
    private int legacyPipelineCount;
    private final boolean dynamicUniforms;
    private boolean closed;

    private LavaFlowRenderPipeline(LavaFlowDevice device, BackendRenderPipeline.CreateInfo createInfo) {
        this.device = device;
        this.info = createInfo;
        this.entries = buildEntries(createInfo);
        this.descriptorTypes = new int[entries.size()];
        for (int i = 0; i < entries.size(); i++) descriptorTypes[i] = vkDescriptorType(entries.get(i).type());
        this.sampledIndices = buildSampledIndices(entries);
        this.shaderModules = new long[createInfo.shaders().size()];
        this.shaderStages = new int[createInfo.shaders().size()];
        this.shaderEntryPoints = new String[createInfo.shaders().size()];
        this.spirvNotes = new String[createInfo.shaders().size()];
        this.spirvCapabilities = new int[createInfo.shaders().size()][];
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
                spirvNotes[i] = spirvSummary(shader.module().spv());
                spirvCapabilities[i] = readCapabilities(shader.module().spv());
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

    private static int[] buildSampledIndices(List<Entry> entries) {
        int count = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).type() == EntryType.SAMPLED_IMAGE) count++;
        }
        int[] indices = new int[count];
        int at = 0;
        for (int i = 0; i < entries.size(); i++) {
            if (entries.get(i).type() == EntryType.SAMPLED_IMAGE) indices[at++] = i;
        }
        return indices;
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

    /**
     * What a module is, read out of its words before it is handed to the driver.
     *
     * <p>The version word matters here more than it looks: a Vulkan 1.1 device takes modules up
     * to SPIR-V 1.3, and LavaFlowSpirv lowers the word when the frontend emitted a newer one —
     * but lowering the word does not lower the instructions. A module that uses an opcode past
     * 1.3 keeps it, and vkCreateShaderModule accepts it while the first pipeline built from it
     * is where the driver notices. On this one that notice is a null dereference.
     *
     * <p>The counts below are the shape of that question: image writes, atomics and subgroup
     * operations are the instructions that come with capabilities a device may lack.
     */
    /** The abilities a module declares in its OpCapability instructions, in the order it lists them. */
    private static int[] readCapabilities(ByteBuffer module) {
        if (module == null) return new int[0];
        ByteBuffer words = module.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = words.position();
        if (words.remaining() < 20 || words.getInt(start) != 0x07230203) return new int[0];
        IntBuffer ints = words.asIntBuffer();
        int at = start / Integer.BYTES + 5;
        int limit = ints.limit();
        int[] found = new int[16];
        int count = 0;
        while (at < limit) {
            int first = ints.get(at);
            int length = first >>> 16;
            int opcode = first & 0xFFFF;
            if (length <= 0) break;
            if (opcode == 17 && count < found.length) found[count++] = ints.get(at + 1);
            at += length;
        }
        return java.util.Arrays.copyOf(found, count);
    }

    private static String spirvSummary(ByteBuffer module) {
        if (module == null) return "no code";
        ByteBuffer words = module.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        int start = words.position();
        if (words.remaining() < 20 || words.getInt(start) != 0x07230203) return "not SPIR-V";
        int version = words.getInt(start + 4);
        IntBuffer ints = words.asIntBuffer();
        int imageWrite = 0, atomics = 0, subgroups = 0, barriers = 0;
        java.util.LinkedHashSet<Integer> capabilities = new java.util.LinkedHashSet<>();
        int at = start / Integer.BYTES + 5;
        int limit = ints.limit();
        while (at < limit) {
            int first = ints.get(at);
            int length = first >>> 16;
            int opcode = first & 0xFFFF;
            if (length <= 0) break;
            if (opcode == 17) capabilities.add(ints.get(at + 1));
            else if (opcode == 99) imageWrite++;
            else if (opcode >= 227 && opcode <= 242) atomics++;
            else if (opcode >= 333 && opcode <= 366) subgroups++;
            else if (opcode == 224 || opcode == 225) barriers++;
            at += length;
        }
        return "SPIR-V " + (version >>> 16) + '.' + ((version >>> 8) & 0xFF)
                + " imageWrite=" + imageWrite + " atomics=" + atomics
                + " subgroups=" + subgroups + " barriers=" + barriers + " caps=" + capabilities;
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

    long pipelineFor(int depthVkFormat, long renderPass, int passColorTargets) {
        if (closed) throw new IllegalStateException("Pipeline is closed");
        if (device.context().dynamicRendering()) {
            long cached = lookupDynamicPipeline(depthVkFormat);
            return cached != 0 ? cached : createDynamicPipeline(depthVkFormat);
        }
        for (int i = 0; i < legacyPipelineCount; i++) {
            if (legacyRenderPasses[i] == renderPass) return legacyPipelines[i];
        }
        return createLegacyPipeline(depthVkFormat != 0, renderPass, passColorTargets);
    }

    /** The colour attachments this pipeline declares, which a legacy render pass must match. */
    int colorTargetCount() {
        return info.colorTargetStates().size();
    }

    private synchronized long createDynamicPipeline(int depthVkFormat) {
        long cached = lookupDynamicPipeline(depthVkFormat);
        if (cached != 0) return cached;
        long pipeline = createGraphicsPipeline(depthVkFormat, 0, 0);
        if (dynamicPipelineCount == dynamicPipelines.length) {
            int grown = Math.max(4, dynamicPipelineCount * 2);
            dynamicFormats = Arrays.copyOf(dynamicFormats, grown);
            dynamicPipelines = Arrays.copyOf(dynamicPipelines, grown);
        }
        dynamicFormats[dynamicPipelineCount] = depthVkFormat;
        dynamicPipelines[dynamicPipelineCount] = pipeline;
        dynamicPipelineCount++;
        return pipeline;
    }

    private long lookupDynamicPipeline(int depthVkFormat) {
        for (int i = 0; i < dynamicPipelineCount; i++) {
            if (dynamicFormats[i] == depthVkFormat) return dynamicPipelines[i];
        }
        return 0;
    }

    private synchronized long createLegacyPipeline(boolean hasDepth, long renderPass, int passColorTargets) {
        for (int i = 0; i < legacyPipelineCount; i++) {
            if (legacyRenderPasses[i] == renderPass) return legacyPipelines[i];
        }
        // For legacy render passes, depth is a binary yes/no encoded in the render pass object itself;
        // the actual format is implicit in the render pass handle used as the key.
        long pipeline = createGraphicsPipeline(hasDepth ? 1 : 0, renderPass, passColorTargets);
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
    private long createGraphicsPipeline(int depthVkFormat, long renderPass, int passColorTargets) {
        // A way past a pipeline this driver will not build, without a rebuild:
        // -Dlavaflow.skipPipelines=shadow drops every pipeline whose name contains it. The draws
        // that would have used one are dropped too (see LavaFlowRenderPass), so the frame goes on
        // without that pass rather than dying in the driver.
        String skip = System.getProperty("lavaflow.skipPipelines", "");
        if (!skip.isEmpty() && info.name().contains(skip)) {
            System.getLogger("LavaFlow").log(System.Logger.Level.WARNING,
                    "Skipping pipeline {0} (lavaflow.skipPipelines={1}); its draws are dropped",
                    info.name(), skip);
            return 0;
        }
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
                    .cullMode(cullMode(info.cull()))
                    .frontFace(VK_FRONT_FACE_CLOCKWISE).lineWidth(1.0f);

            VkPipelineDepthStencilStateCreateInfo depth = VkPipelineDepthStencilStateCreateInfo.calloc(stack).sType$Default();
            DepthStencilState depthState = info.depthStencilState();
            if (depthVkFormat != 0 && depthState != null) {
                depth.depthTestEnable(true).depthWriteEnable(depthState.writeDepth())
                        .depthCompareOp(depthCompareOp(depthState.depthTest()));
                boolean bias = depthState.depthBiasConstant() != 0 || depthState.depthBiasScaleFactor() != 0;
                raster.depthBiasEnable(bias).depthBiasConstantFactor(depthState.depthBiasConstant())
                        .depthBiasSlopeFactor(depthState.depthBiasScaleFactor());
            }

            List<ColorTargetState> targets = info.colorTargetStates();
            // A legacy render pass fixes how many colour attachments its subpass has, and
            // VkPipelineColorBlendStateCreateInfo has to name exactly that many. Handing a driver a
            // smaller count used to be something it was trusted to reject: Mali dereferences a null
            // instead and takes the process down, which is what a shadow pass with one written target
            // and a pass with two did on a Mali-G76. The extra entries are left unwritten, which
            // writes no channel and blends nothing; a pipeline that names more targets than the pass
            // has keeps its own count, since that is the side the pass cache cannot invent.
            int blendCount = Math.max(targets.size(), passColorTargets);
            VkPipelineColorBlendAttachmentState.Buffer blendAttachments =
                    VkPipelineColorBlendAttachmentState.calloc(blendCount, stack);
            for (int i = 0; i < blendCount; i++) {
                ColorTargetState target = i < targets.size() ? targets.get(i) : null;
                if (target == null) continue;
                VkPipelineColorBlendAttachmentState attachment = blendAttachments.get(i)
                        .colorWriteMask(LavaFlowVk.colorWriteMask(target));
                target.blendFunction().ifPresent(blend -> applyBlend(attachment, blend));
            }
            VkPipelineColorBlendStateCreateInfo blend = VkPipelineColorBlendStateCreateInfo.calloc(stack).sType$Default();
            if (blendCount > 0) blend.pAttachments(blendAttachments);
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
            // Said before the call, not after a failure: this driver's answer to a pipeline it
            // cannot build is a null dereference, so a state field that is wrong takes the log with
            // it. One line per pipeline, built once, and only at creation.
            StringBuilder stageText = new StringBuilder();
            for (int i = 0; i < shaderModules.length; i++) {
                if (i > 0) stageText.append(", ");
                stageText.append(shaderStages[i]).append(':').append(shaderEntryPoints[i])
                        .append("@0x").append(Long.toHexString(shaderModules[i]))
                        .append(" [").append(spirvNotes[i]).append(']');
            }
            StringBuilder vertexText = new StringBuilder();
            for (int i = 0; i < attribDescs.size(); i++) {
                BackendRenderPipeline.CreateInfo.AttribBinding attrib = attribDescs.get(i);
                if (i > 0) vertexText.append(' ');
                vertexText.append(attrib.location()).append(':').append(attrib.bufferSlot())
                        .append('/').append(attrib.format()).append('@').append(attrib.offset());
            }
            StringBuilder blendText = new StringBuilder();
            for (int i = 0; i < blendCount; i++) {
                VkPipelineColorBlendAttachmentState state = blendAttachments.get(i);
                if (i > 0) blendText.append(' ');
                blendText.append(state.blendEnable() ? "on" : "off")
                        .append('/').append(state.colorWriteMask());
            }
            System.getLogger("LavaFlow").log(System.Logger.Level.INFO,
                    "Pipeline {0}: stages=[{1}], topology={2}, polygonMode={3}, cull={4}, frontFace={5}, "
                            + "depthTest={6}, depthWrite={7}, depthCompareOp={8}, depthBias={9}, "
                            + "samples={10}, colorTargets={11}, passColorTargets={12}, blend=[{13}], "
                            + "vertexBindings={14}, vertexAttribs={15}, renderPass=0x{16}, depthVkFormat={17}",
                    info.name(), stageText, assembly.topology(), polygonMode, raster.cullMode(),
                    raster.frontFace(), depth.depthTestEnable(), depth.depthWriteEnable(),
                    depth.depthCompareOp(), raster.depthBiasEnable(), multisample.rasterizationSamples(),
                    targets.size(), passColorTargets, blendText, vertexBufferDescs.size(),
                    vertexText, Long.toHexString(renderPass), depthVkFormat);
            // Asked before the driver is: a module may declare an ability this device did not
            // enable, and this one answers that by dying inside vkCreateGraphicsPipelines. A
            // pipeline that cannot be built is dropped here instead, with the reason in the log,
            // so the rest of the frame still runs. Only abilities LavaFlow controls are checked.
            for (int i = 0; i < spirvCapabilities.length; i++) {
                for (int capability : spirvCapabilities[i]) {
                    if (device.context().supportsSpirvCapability(capability)) continue;
                    System.getLogger("LavaFlow").log(System.Logger.Level.WARNING,
                            "Skipping pipeline {0}: stage {1} declares SPIR-V capability {2}, which"
                                    + " this device does not enable; the draw is dropped and the"
                                    + " session goes on",
                            info.name(), shaderStages[i], capability);
                    return 0;
                }
            }
            LongBuffer out = stack.mallocLong(1);
            check(vkCreateGraphicsPipelines(device.context().device(), 0, create, null, out),
                    "vkCreateGraphicsPipelines for " + info.name()
                            + " (renderPass=" + renderPass + ", depthVkFormat=" + depthVkFormat
                            + ", colorTargets=" + targets.size() + ", shaders=" + shaderModules.length + ")");
            return out.get(0);
        }
    }

    /**
     * The compare operation for a depth test, with the one lever a session that cannot get past
     * a pipeline has: {@code -Dlavaflow.depthCompare=greater} maps the two "pass when nearer"
     * forms onto their "pass when farther" mirror.
     *
     * <p>This is a bisection switch, not a fix. A shadow map that keeps the other half of the
     * window casts a wrong shadow. What it settles is whether this driver dies on the compare
     * operation itself, which is the only field left describing the one pipeline this session
     * cannot build.
     */
    /**
     * The cull mode for a pipeline, with a bisection switch of its own:
     * {@code -Dlavaflow.cull=back} makes every pipeline cull back faces.
     *
     * <p>This exists because of the one combination no successful pipeline in the sessions
     * so far has used: cull mode NONE together with nine vertex attributes. solid has the nine
     * and culls, entity_translucent does not cull and has six. Turning culling on for the
     * nine-attribute shadow pass asks whether that pairing is what the driver dies on.
     */
    private static int cullMode(boolean cull) {
        if ("back".equalsIgnoreCase(System.getProperty("lavaflow.cull", ""))) {
            return VK_CULL_MODE_BACK_BIT;
        }
        return cull ? VK_CULL_MODE_BACK_BIT : VK_CULL_MODE_NONE;
    }

    private static int depthCompareOp(CompareOp op) {
        int mapped = LavaFlowVk.compareOp(op);
        if (!"greater".equalsIgnoreCase(System.getProperty("lavaflow.depthCompare", ""))) {
            return mapped;
        }
        return switch (mapped) {
            case VK_COMPARE_OP_LESS -> VK_COMPARE_OP_GREATER;
            case VK_COMPARE_OP_LESS_OR_EQUAL -> VK_COMPARE_OP_GREATER_OR_EQUAL;
            default -> mapped;
        };
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
        if (result != VK_SUCCESS) throw new IllegalStateException(VulkanException.describe(operation, result));
    }

    List<Entry> entries() { return entries; }
    int descriptorType(int index) { return descriptorTypes[index]; }
    int[] sampledIndices() { return sampledIndices; }
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
        long[] nativeDynamicPipelines = Arrays.copyOf(dynamicPipelines, dynamicPipelineCount);
        dynamicPipelineCount = 0;
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
