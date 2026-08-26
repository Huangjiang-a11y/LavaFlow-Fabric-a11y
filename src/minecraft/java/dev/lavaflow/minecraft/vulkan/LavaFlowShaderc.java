package dev.lavaflow.minecraft.vulkan;

import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.lwjgl.util.shaderc.Shaderc.*;

/** Architecture-independent shaderc bridge used by the LavaFlow Vulkan backend. */
public final class LavaFlowShaderc {
    private LavaFlowShaderc() {}

    public static String load() {
        return getLibrary().getPath();
    }

    static ByteBuffer compile(String source, int kind, String name) {
        long compiler = shaderc_compiler_initialize();
        long options = shaderc_compile_options_initialize();
        if (compiler == 0 || options == 0) {
            if (options != 0) shaderc_compile_options_release(options);
            if (compiler != 0) shaderc_compiler_release(compiler);
            throw new IllegalStateException("Unable to initialize LavaFlow shaderc");
        }

        long result = 0;
        try {
            shaderc_compile_options_set_source_language(options, shaderc_source_language_glsl);
            if (Boolean.getBoolean("lavaflow.dumpDynamicTransforms")) {
                String safe = name.replaceAll("[^a-zA-Z0-9_.-]", "_");
                String suffix = kind == shaderc_fragment_shader ? "_frag" : "_vert";
                boolean hasDt = source.contains("DynamicTransforms");
                System.out.println("[LavaFlow] dump shader name=" + name + " kind=" + suffix
                        + " containsDynamicTransforms=" + hasDt
                        + " uses_gl_VertexID=" + source.contains("gl_VertexID")
                        + " uses_gl_InstanceID=" + source.contains("gl_InstanceID")
                        + " uses_gl_VertexIndex=" + source.contains("gl_VertexIndex"));
                try {
                    java.nio.file.Files.createDirectories(java.nio.file.Paths.get("lavaflow_shaders"));
                    java.nio.file.Files.writeString(
                            java.nio.file.Paths.get("lavaflow_shaders/" + safe + suffix + ".glsl"), source);
                } catch (Exception e) {
                    System.out.println("[LavaFlow] shader dump write failed: " + e);
                }
            }
            shaderc_compile_options_set_target_env(options, shaderc_target_env_vulkan,
                    shaderc_env_version_vulkan_1_1);
            shaderc_compile_options_set_target_spirv(options, shaderc_spirv_version_1_3);
            shaderc_compile_options_set_auto_bind_uniforms(options, true);
            shaderc_compile_options_set_auto_map_locations(options, true);
            shaderc_compile_options_set_generate_debug_info(options);
            shaderc_compile_options_set_optimization_level(options, shaderc_optimization_level_zero);

            // Vulkan GLSL names these gl_VertexIndex/gl_InstanceIndex; Mojang's OpenGL-style vanilla
            // GLSL (every vanilla program and the main-menu panorama) relies on gl_VertexID/gl_InstanceID.
            // Without this shim, vertex/instance indices are computed wrong on Vulkan, corrupting instanced
            // and offset geometry and showing as per-frame flickering of vanilla (non-Sodium) content.
            if (source.contains("gl_VertexID") || source.contains("gl_InstanceID")) {
                String idDefine = "#define gl_VertexID gl_VertexIndex\n#define gl_InstanceID gl_InstanceIndex\n";
                int versionAt = source.indexOf("#version");
                if (versionAt >= 0) {
                    int nl = source.indexOf('\n', versionAt);
                    source = source.substring(0, nl + 1) + idDefine + source.substring(nl + 1);
                } else {
                    source = idDefine + source;
                }
            }

            result = shaderc_compile_into_spv(compiler, source, kind, name, "main", options);
            if (result == 0) throw new IllegalStateException("LavaFlow shaderc returned no result for " + name);

            int status = shaderc_result_get_compilation_status(result);
            if (status != shaderc_compilation_status_success) {
                throw new IllegalStateException("Could not compile " + name + ": "
                        + shaderc_result_get_error_message(result));
            }

            long length = shaderc_result_get_length(result);
            if (length > Integer.MAX_VALUE) throw new IllegalStateException("Shader module is too large: " + name);
            ByteBuffer bytes = shaderc_result_get_bytes(result, length);
            ByteBuffer copy = MemoryUtil.memAlloc((int) length);
            copy.put(bytes).flip();
            return copy;
        } finally {
            if (result != 0) shaderc_result_release(result);
            shaderc_compile_options_release(options);
            shaderc_compiler_release(compiler);
        }
    }
}
