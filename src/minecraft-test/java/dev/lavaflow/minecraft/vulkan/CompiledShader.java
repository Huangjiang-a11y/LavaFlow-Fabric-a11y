package dev.lavaflow.minecraft.vulkan;

import com.mojang.renderpearl.api.pipeline.ShaderType;
import com.mojang.renderpearl.api.pipeline.SpvModule;
import com.mojang.renderpearl.util.ShaderCompileException;

import java.nio.ByteBuffer;

/**
 * A compiled shader as the backend sees it while building a pipeline.
 *
 * <p>The backend reads only the module contents and its type during compilation; the caller that
 * owns the module (a test here, standing in for the frontend) releases it once
 * {@code finishCompile()} returns, so closing is a no-op.
 *
 * <p>Shared by the tests that hand the backend a real shader module. It lives at package level so
 * that each test need not carry its own copy.
 */
record CompiledShader(String name, String entryPoint, ByteBuffer spv, ShaderType type)
        implements SpvModule {
    @Override public void close() {}
    @Override public SpvModule.Reflection reflect() throws ShaderCompileException {
        throw new UnsupportedOperationException("reflection is the frontend's job");
    }
    @Override public SpvModule.Reflection getReflectionInfoIfAvailable() { return null; }
}
