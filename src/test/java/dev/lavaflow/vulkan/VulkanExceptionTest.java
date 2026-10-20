package dev.lavaflow.vulkan;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

class VulkanExceptionTest {
    @Test
    void describeNamesKnownCodes() {
        assertEquals("vkCreateDevice failed with VkResult -3 (VK_ERROR_INITIALIZATION_FAILED)",
                VulkanException.describe("vkCreateDevice", -3));
        assertEquals("vkAcquireNextImageKHR failed with VkResult -1000001004 (VK_ERROR_OUT_OF_DATE_KHR)",
                VulkanException.describe("vkAcquireNextImageKHR", -1000001004));
        assertEquals("vkQueueSubmit failed with VkResult -4 (VK_ERROR_DEVICE_LOST)",
                VulkanException.describe("vkQueueSubmit", -4));
    }

    @Test
    void describeKeepsUnknownCodesBare() {
        // A driver is free to invent a code; inventing a name for it would be worse than the number.
        assertEquals("vkCreateImage failed with VkResult -2048000000",
                VulkanException.describe("vkCreateImage", -2048000000));
        assertNull(VulkanException.name(-2048000000));
    }

    @Test
    void exceptionCarriesTheResultItReported() {
        VulkanException failure = assertThrows(VulkanException.class,
                () -> VulkanException.check(-8, "vkCreateGraphicsPipelines"));
        assertEquals(-8, failure.result());
        assertEquals("vkCreateGraphicsPipelines failed with VkResult -8 (VK_ERROR_FEATURE_NOT_PRESENT)",
                failure.getMessage());
    }

    @Test
    void checkAcceptsSuccess() {
        VulkanException.check(0, "vkDeviceWaitIdle");
        // VK_INCOMPLETE and VK_SUBOPTIMAL_KHR are positive but not success; check() is for the calls
        // that must have finished, so it still rejects them.
        assertSame(VulkanException.class, assertThrows(VulkanException.class,
                () -> VulkanException.check(1000001003, "vkAcquireNextImageKHR")).getClass());
    }
}
