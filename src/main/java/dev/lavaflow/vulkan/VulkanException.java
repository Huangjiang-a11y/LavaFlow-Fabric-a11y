package dev.lavaflow.vulkan;

public final class VulkanException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    private final int result;

    public VulkanException(String operation, int result) {
        super(describe(operation, result));
        this.result = result;
    }

    public int result() {
        return result;
    }

    public static void check(int result, String operation) {
        if (result != 0) {
            throw new VulkanException(operation, result);
        }
    }

    /**
     * Renders a failed call as {@code "vkCreateDevice failed with VkResult -3 (VK_ERROR_INITIALIZATION_FAILED)"}.
     *
     * <p>Only the raw code was interpolated before, which reads fine next to the Vulkan headers and
     * poorly in the one place these messages are actually consumed: a device log sent by someone
     * whose phone is not on your desk. On the mobile GPUs this backend targets, driver refusals are
     * exactly the interesting case — a Mali driver rejecting a feature reports -3 or -8, not a
     * sentence — so the number stays in the text and the name is appended when this build knows it.
     */
    public static String describe(String operation, int result) {
        String name = name(result);
        return name == null
                ? operation + " failed with VkResult " + result
                : operation + " failed with VkResult " + result + " (" + name + ")";
    }

    /**
     * The Vulkan name of a result code, or {@code null} for one this build has no constant for.
     *
     * <p>Only codes this backend can plausibly provoke are listed. Anything else — including a code
     * a newer driver invented — falls back to the bare number instead of being mislabelled.
     */
    public static String name(int result) {
        return switch (result) {
            case 0 -> "VK_SUCCESS";
            case 1 -> "VK_NOT_READY";
            case 2 -> "VK_TIMEOUT";
            case 3 -> "VK_EVENT_SET";
            case 4 -> "VK_EVENT_RESET";
            case 5 -> "VK_INCOMPLETE";
            case -1 -> "VK_ERROR_OUT_OF_HOST_MEMORY";
            case -2 -> "VK_ERROR_OUT_OF_DEVICE_MEMORY";
            case -3 -> "VK_ERROR_INITIALIZATION_FAILED";
            case -4 -> "VK_ERROR_DEVICE_LOST";
            case -5 -> "VK_ERROR_MEMORY_MAP_FAILED";
            case -6 -> "VK_ERROR_LAYER_NOT_PRESENT";
            case -7 -> "VK_ERROR_EXTENSION_NOT_PRESENT";
            case -8 -> "VK_ERROR_FEATURE_NOT_PRESENT";
            case -9 -> "VK_ERROR_INCOMPATIBLE_DRIVER";
            case -10 -> "VK_ERROR_TOO_MANY_OBJECTS";
            case -11 -> "VK_ERROR_FORMAT_NOT_SUPPORTED";
            case -12 -> "VK_ERROR_FRAGMENTED_POOL";
            case -13 -> "VK_ERROR_UNKNOWN";
            case -1000000000 -> "VK_ERROR_SURFACE_LOST_KHR";
            case -1000000001 -> "VK_ERROR_NATIVE_WINDOW_IN_USE_KHR";
            case 1000001003 -> "VK_SUBOPTIMAL_KHR";
            case -1000001004 -> "VK_ERROR_OUT_OF_DATE_KHR";
            case -1000069000 -> "VK_ERROR_OUT_OF_POOL_MEMORY";
            case -1000072003 -> "VK_ERROR_INVALID_EXTERNAL_HANDLE";
            default -> null;
        };
    }
}
