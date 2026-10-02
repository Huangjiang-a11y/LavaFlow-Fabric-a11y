package dev.lavaflow.minecraft;

import com.mojang.renderpearl.api.device.GpuDevice;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Support code for {@code VitrailBackendMixin}.
 *
 * <p>Kept out of the mixin class on purpose: ordinary members of a {@code @Mixin} class are merged into
 * the target class, and the target here is a third-party mod's {@code HostReport}. Only the injector
 * itself belongs there; everything else lives here, where it is also reachable from tests.
 *
 * <p>Vitrail Shaders is a shader engine that runs OptiFine-format packs on Minecraft's own Vulkan
 * backend. It decides whether it may draw by asking the game which backend it came up on, by name:
 * {@code dev.vitrail.HostReport.otherBackend()} is {@code !UNKNOWN.equals(backend) &&
 * !VULKAN.equals(backend)}, and that name is {@code RenderSystem.tryGetDevice().getDeviceInfo()
 * .backendName()}. LavaFlow reports exactly {@code "Vulkan"} (see {@link
 * dev.lavaflow.minecraft.vulkan.LavaFlowDevice}) so that mods selecting their Vulkan code paths by that
 * string keep working, which means Vitrail's gate says yes and its engine switches on.
 *
 * <p>What the engine then needs is the native backend's own classes rather than the device facade:
 * {@code VulkanBackendMixin} wraps {@code VulkanBackend.createDevice(...)} to enable the device features
 * a pack asks for ({@code vertexPipelineStoresAndAtomics}, {@code shaderStorageImageExtendedFormats},
 * {@code independentBlend}, {@code geometryShader}, the 16 and 8 bit arithmetic), and the rest of the mod
 * reads raw handles out of {@code VulkanRenderPass}, {@code VulkanDevice}, {@code VulkanCommandEncoder}
 * and {@code VulkanGpuTextureView}. LavaFlow registers a {@code GpuDeviceBackend} of its own, so none of
 * those objects exists to wrap and no feature is ever enabled. Vitrail's own {@code instanceof} checks
 * ({@code PackCompute}, {@code ShadowCompare}) do answer "no" and bail out locally, but the engine as a
 * whole was already switched on by the name, and half an engine is worse than none: the translated
 * shaders declare Vitrail's own {@code OfGlobals} uniform block while the code that fills it sits on the
 * native path that never runs, so the first draw fails on the empty slot.
 *
 * <p>That failure is a crash, and it does not come from LavaFlow being stricter than the game. Mojang's
 * own {@code VulkanRenderPass.pushDescriptors} throws {@code IllegalStateException} for an empty
 * uniform-buffer slot as well; both were checked against the 26.3 client.
 *
 * <p>So the guard answers Vitrail's own question the way the rest of its answers already do -- this is not
 * a backend its programs can be drawn on -- and Vitrail takes the path it already has for another
 * backend: the pack is neither read nor drawn, the game keeps its own image, and Vitrail says so itself
 * in its log and in chat. Nothing of Vitrail's source is copied or extended here.
 *
 * <p>Checked against Vitrail Shaders v0.12.0-beta for 26.3: {@code dev.vitrail.HostReport} is the class,
 * {@code otherBackend} is the method, and it is static and takes no arguments. Both are a third-party
 * mod's internals and can change without notice.
 */
public final class VitrailCompat {
    private static final System.Logger LOGGER = System.getLogger("LavaFlow/Vitrail");

    /** Vitrail asks its question every tick and from several screens; the line is worth hearing once. */
    private static final AtomicBoolean REPORTED = new AtomicBoolean();

    private VitrailCompat() {}

    /**
     * Returns whether this session is running on LavaFlow's own backend, in which case Vitrail's engine
     * must not be allowed to draw.
     *
     * <p>No device yet, or a backend that cannot be read, answers {@code false}. That is the same rule
     * Vitrail applies to itself: a backend nobody can name is not one to refuse a pack over, and refusing
     * one there would take the picture away on a device that may well have been Mojang's.
     */
    public static boolean runsOnLavaFlow() {
        return runsOnLavaFlow(LavaFlowDevices.currentDeviceOrNull());
    }

    /** The device-taking form, which is what the tests drive. */
    static boolean runsOnLavaFlow(GpuDevice device) {
        if (!LavaFlowDevices.isLavaFlow(device)) {
            return false;
        }
        if (REPORTED.compareAndSet(false, true)) {
            LOGGER.log(System.Logger.Level.INFO, guardFiredMessage(device));
        }
        return true;
    }

    /**
     * The line logged when the guard takes effect.
     *
     * <p>Built here and handed to the logger as a single argument: the AsyncParticles guard lost its device
     * name to a {@code MessageFormat} quote before, and a line that reads plausibly while saying nothing is
     * worse than none.
     */
    static String guardFiredMessage(GpuDevice device) {
        return "Vitrail Shaders is standing aside: its programs are translated for Minecraft's own Vulkan"
                + " backend and every device feature they ask for is enabled on a device LavaFlow never"
                + " creates, so a pack it was asked for is neither read nor drawn and the game keeps its"
                + " own image. This is Vitrail's own behaviour on a backend it was not written for; the"
                + " device is " + device;
    }
}
