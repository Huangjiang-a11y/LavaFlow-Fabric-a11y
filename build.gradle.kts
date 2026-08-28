plugins {
    java
    application
    id("net.fabricmc.fabric-loom") version "1.17-SNAPSHOT"
}

group = "dev.lavaflow"
version = "0.1.0-alpha"

val lwjglVersion = "3.4.1"
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
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-shaderc")
    implementation("org.lwjgl:lwjgl-spvc")
    implementation("org.lwjgl:lwjgl-vma")
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.joml:joml:1.10.8")

    minecraft("com.mojang:minecraft:${property("minecraft_version")}")
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-shaderc::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-spvc::$lwjglNatives")
    runtimeOnly("org.lwjgl:lwjgl-vma::$lwjglNatives")

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

// Writes the project version to a classpath resource LavaFlowVersion reads at runtime. Needed
// because FML's transforming classloader never populates java.lang.Package version info from the
// jar manifest, so Package.getImplementationVersion() always returns null for a mod's own classes.
val generateLavaFlowVersion by tasks.registering {
    val outputDir = layout.buildDirectory.dir("generated/lavaflowVersion")
    val outputFile = outputDir.map { it.file("lavaflow-version.txt") }
    inputs.property("version", project.version.toString())
    outputs.dir(outputDir)
    doLast {
        outputFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(project.version.toString())
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
configurations[minecraftTest.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

tasks.named<JavaCompile>(minecraftTest.compileJavaTaskName) {
    options.release = 25
}

tasks.register<Test>("minecraftTest") {
    description = "Runs unit tests for classes that depend on the Minecraft sourceSet"
    group = "verification"
    testClassesDirs = minecraftTest.output.classesDirs
    classpath = minecraftTest.runtimeClasspath
    useJUnitPlatform()
}

tasks.named("check") {
    dependsOn("minecraftTest")
}
