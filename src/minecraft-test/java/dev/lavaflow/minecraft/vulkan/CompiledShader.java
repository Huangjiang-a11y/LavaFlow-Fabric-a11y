package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.backend.api.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;

import java.nio.ByteBuffer;

/**
 * A compiled shader as the backend sees it while building a pipeline.
 *
 * <p>The backend reads only {@code spv()} and {@code type()} during compilation; the caller that
 * owns the module (a test here, standing in for the frontend) releases it once
 * {@code finishCompile()} returns, so closing is a no-op.
 *
 * <p>Shared by the tests that hand the backend a real shader module. It lives at package level so
 * that each test need not carry its own copy; {@code Reflection} is inherited from
 * {@link SpvModule} rather than imported.
 */
record CompiledShader(ByteBuffer spv, ShaderType type) implements SpvModule {
    @Override public void close() {}
    @Override public Reflection reflect() throws ShaderCompileException {
        throw new UnsupportedOperationException("reflection is the frontend's job");
    }
    @Override public Reflection getReflectionInfoIfAvailable() { return null; }
}
