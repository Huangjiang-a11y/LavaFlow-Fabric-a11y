package dev.lavaflow.vulkan;

import org.lwjgl.PointerBuffer;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkClearValue;
import org.lwjgl.vulkan.VkPresentInfoKHR;
import org.lwjgl.vulkan.VkRenderPassBeginInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.nio.IntBuffer;
import java.nio.LongBuffer;

import static org.lwjgl.system.MemoryUtil.*;
import static org.lwjgl.vulkan.VK10.*;

final class FrameResources implements AutoCloseable {
    final VkCommandBuffer commandBuffer;
    final long imageAvailableSemaphore;
    final long inFlightFence;
    final IntBuffer imageIndex = memAllocInt(1);
    final IntBuffer waitStage = memAllocInt(1);
    final LongBuffer waitSemaphore = memAllocLong(1);
    final LongBuffer signalSemaphore = memAllocLong(1);
    final LongBuffer swapchain = memAllocLong(1);
    final PointerBuffer commandBuffers = memAllocPointer(1);
    final VkCommandBufferBeginInfo commandBegin = VkCommandBufferBeginInfo.calloc();
    final VkClearValue.Buffer clearValue = VkClearValue.calloc(1);
    final VkRenderPassBeginInfo renderPassBegin = VkRenderPassBeginInfo.calloc();
    final VkSubmitInfo submit = VkSubmitInfo.calloc();
    final VkPresentInfoKHR present = VkPresentInfoKHR.calloc();

    FrameResources(
            VkCommandBuffer commandBuffer,
            long imageAvailableSemaphore,
            long inFlightFence
    ) {
        this.commandBuffer = commandBuffer;
        this.imageAvailableSemaphore = imageAvailableSemaphore;
        this.inFlightFence = inFlightFence;

        waitStage.put(0, VK_PIPELINE_STAGE_COLOR_ATTACHMENT_OUTPUT_BIT);
        waitSemaphore.put(0, imageAvailableSemaphore);
        commandBuffers.put(0, commandBuffer.address());
        commandBegin.sType$Default();
        renderPassBegin.sType$Default().pClearValues(clearValue);
        submit.sType$Default()
                .waitSemaphoreCount(1)
                .pWaitSemaphores(waitSemaphore)
                .pWaitDstStageMask(waitStage)
                .pCommandBuffers(commandBuffers)
                .pSignalSemaphores(signalSemaphore);
        present.sType$Default()
                .pWaitSemaphores(signalSemaphore)
                .swapchainCount(1)
                .pSwapchains(swapchain)
                .pImageIndices(imageIndex);
    }

    @Override
    public void close() {
        present.free();
        submit.free();
        renderPassBegin.free();
        clearValue.free();
        commandBegin.free();
        memFree(commandBuffers);
        memFree(swapchain);
        memFree(signalSemaphore);
        memFree(waitSemaphore);
        memFree(waitStage);
        memFree(imageIndex);
    }
}
