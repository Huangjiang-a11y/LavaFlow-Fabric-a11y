plugins {
    java
    application
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

group = "dev.lavaflow"
version = "0.1.1-alpha"

val lwjglVersion = "3.4.3"
val lwjglArch = System.getProperty("os.arch").lowercase()
val lwjglNatives = when {
    System.getProperty("os.name").startsWith("Windows") && lwjglArch in setOf("aarch64", "arm64") ->
        "natives-windows-arm64"
    System.getProperty("os.name").startsWith("Windows") -> "natives-windows"
    System.getProperty("os.name").startsWith("Mac") && lwjglArch in setOf("aarch64", "arm64") ->
        "natives-macos-arm64"
    System.getProperty("os.name").startsWith("Mac") -> "natives-macos"
    lwjglArch in setOf("aarch64", "arm64") -> "natives-linux-arm64"
    else -> "natives-linux"
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") {
        content {
            includeGroup("net.fabricmc")
        }
    }
}

dependencies {
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-sdl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-shaderc")
    implementation("org.lwjgl:lwjgl-spvc")
    implementation("org.lwjgl:lwjgl-vma")
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.joml:joml:1.10.8")

    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-sdl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-spvc::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-vma::$lwjglNatives")
    // jemalloc 是通过依赖图带进来的独立模块（LWJGL 的 VKAllocationCallbacks 会用它），
    // 它的原生 jar 因此不在上面的 runtimeOnly 列表里；缺了它，测试起设备时会报
    // "Failed to locate library: libjemalloc.so"。见 minecraftTest 源集处的说明。
    runtimeOnly("org.lwjgl:lwjgl-jemalloc::$lwjglNatives")

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

application {
    mainClass = "dev.lavaflow.smoke.LavaFlowSmoke"
}

// Signature-only stubs for the Sodium classes the compatibility mixins target. Sodium supplies the
// real classes at runtime, so this output is never packaged.
val sodiumStub by sourceSets.creating {
    java.setSrcDirs(listOf("src/sodiumStub/java"))
    resources.setSrcDirs(emptyList<String>())
    // Loom wires the resolved Minecraft classes (net.minecraft:minecraft-merged-deobf) onto the
    // main sourceSet's compileClasspath, so reusing it gives the stubs the MC types they need.
    // (The `minecraft` configuration itself is a non-resolvable bucket, so it can't be used directly.)
    compileClasspath += configurations.compileClasspath.get()
}

val minecraft by sourceSets.creating {
    java.setSrcDirs(listOf("src/minecraft/java"))
    resources.setSrcDirs(listOf("src/minecraft/resources"))
    resources.srcDir("build/generated/lavaflowVersion")
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get() + sodiumStub.output
    runtimeClasspath += output + compileClasspath
}

/** Runs git and returns its trimmed output, or null when git is missing or the command fails. */
fun gitOutput(vararg args: String): String? = try {
    val process = ProcessBuilder(listOf("git") + args)
        .directory(rootDir).redirectErrorStream(true).start()
    // Read before waiting: a full pipe buffer would deadlock the child.
    val output = process.inputStream.bufferedReader().readText().trim()
    if (process.waitFor() == 0) output.ifEmpty { null } else null
} catch (ignored: Exception) {
    null
}

// Build identity, embedded in the jar and reported by LavaFlowVersion at runtime. The commit is
// recorded because the version alone cannot identify a build: it changes only when someone bumps it,
// so two jars built from different commits are otherwise indistinguishable in a device log, leaving
// file timestamps as the only way to tell them apart.
val buildCommit: String = gitOutput("rev-parse", "--short", "HEAD")?.let { commit ->
    if (gitOutput("status", "--porcelain") == null) commit else "$commit-dirty"
} ?: "unknown"

// Writes the build identity to a classpath resource LavaFlowVersion reads at runtime. Needed
// because FML's transforming classloader never populates java.lang.Package version info from the
// jar manifest, so Package.getImplementationVersion() always returns null for a mod's own classes.
val generateLavaFlowVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/lavaflowVersion")
    val outputFile = outputDir.map { it.file("lavaflow-version.txt") }
    inputs.property("version", project.version.toString())
    // Without the commit as an input the task stays up-to-date across commits and keeps embedding
    // whichever revision it happened to run against first.
    inputs.property("commit", buildCommit)
    outputs.dir(outputDir)
    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            // Version on the first line, commit on the second; see LavaFlowVersion.
            writeText(project.version.toString() + "\n" + buildCommit + "\n")
        }
    }
}

tasks.named(minecraft.processResourcesTaskName) {
    dependsOn(generateLavaFlowVersion)
}

configurations[minecraft.implementationConfigurationName].extendsFrom(configurations.implementation.get())

loom {
    mods {
        create("lavaflow") {
            sourceSet(sourceSets["minecraft"])
        }
    }
    fabricModJsonPath = file("src/minecraft/resources/fabric.mod.json")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.named<JavaCompile>(minecraft.compileJavaTaskName) {
    options.release = 25
}

tasks.named<JavaCompile>(sodiumStub.compileJavaTaskName) {
    options.release = 25
}

tasks.jar {
    from(minecraft.output)
    from("LICENSE") {
        into("META-INF")
    }
    manifest.attributes(
        "Implementation-Title" to "LavaFlow",
        "Implementation-Version" to project.version
    )
}

tasks.test {
    useJUnitPlatform()
}

// 最终产物文件名：模组名-游戏版本名-模组版本名 (例如 lavaflow-26.2-0.1.0-alpha.jar)
val mcVersion = property("minecraft_version").toString()
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    archiveBaseName.set(rootProject.name)
    archiveAppendix.set(mcVersion)
    archiveVersion.set(project.version.toString())
}

// Tests that exercise classes in the minecraft sourceSet (Blaze3D API on the classpath).
val minecraftTest by sourceSets.creating {
    java.setSrcDirs(listOf("src/minecraft-test/java"))
    resources.setSrcDirs(emptyList<String>())
    compileClasspath += minecraft.output + minecraft.runtimeClasspath
    runtimeClasspath += output + minecraft.output + minecraft.runtimeClasspath
}

configurations[minecraftTest.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
// 测试要真的起设备、开窗口，就必须能加载 LWJGL 的原生库——而 main 把原生库声明为 runtimeOnly，
// minecraft 源集只从 main 继承了 implementation，于是这些 jar 根本不在测试的 classpath 上。
// 少了它们，LavaFlowVulkanContextTest 会在 new LavaFlowVulkanContext() 处抛 UnsatisfiedLinkError
// （Failed to locate library: libvulkan.so.1）。这个失败还很容易被掩盖：只要 java.io.tmpdir 里
// 留着别处解压过的同版本原生库，LWJGL 就会复用，测试于是"通过"而实际什么都不缺——
// 本仓库就在本地为此多绿了一轮。所以这里显式继承 main 的 runtimeOnly。
configurations[minecraftTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get(), configurations.runtimeOnly.get())

tasks.named<JavaCompile>(minecraftTest.compileJavaTaskName) {
    options.release = 25
}

tasks.register<Test>("minecraftTest") {
    description = "Runs unit tests for classes that depend on the Minecraft sourceSet"
    group = "verification"
    testClassesDirs = minecraftTest.output.classesDirs
    classpath = minecraftTest.runtimeClasspath
    useJUnitPlatform()
    // 计数器和开关一样由系统属性门控，且只在类加载时读一次（LavaFlowFrameStats.ENABLED），
    // 所以只能在这里给：测试方法里再设已经晚了——同一 JVM 里前一个测试可能已经加载过该类。
    // 少了它，读退役计数的断言会恒等于 0 而空过，正是本仓库栽过一次的"测试绿着但什么都没覆盖"。
    systemProperty("lavaflow.frameStats", "true")
}

tasks.named("check") {
    dependsOn("minecraftTest")
}
