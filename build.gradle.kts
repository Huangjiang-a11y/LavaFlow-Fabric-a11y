plugins {
    java
    id("net.fabricmc.fabric-loom") version "1.18-SNAPSHOT"
}

group = "dev.lavaflow"
version = "0.1.0-alpha"

val lwjglVersion = "3.4.1"
val lwjglArch = System.getProperty("os.arch").lowercase()
val lwjglNatives = when {
    System.getProperty("os.name").startsWith("Windows") && lwjglArch in setOf("aarch64", "arm64") -> "natives-windows-arm64"
    System.getProperty("os.name").startsWith("Windows") -> "natives-windows"
    System.getProperty("os.name").startsWith("Mac") && lwjglArch in setOf("aarch64", "arm64") -> "natives-macos-arm64"
    System.getProperty("os.name").startsWith("Mac") -> "natives-macos"
    lwjglArch in setOf("aarch64", "arm64") -> "natives-linux-arm64"
    else -> "natives-linux"
}

repositories {
    mavenCentral()
    maven("https://maven.fabricmc.net/") {
        content { includeGroup("net.fabricmc") }
    }
}

dependencies {
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    // Kotlin DSL 中必须使用字符串形式调用 modImplementation
    "modImplementation"("net.fabricmc:fabric-loader:${property("loader_version")}")

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-glfw")
    implementation("org.lwjgl:lwjgl-shaderc")
    implementation("org.lwjgl:lwjgl-spvc")
    implementation("org.lwjgl:lwjgl-vma")
    implementation("org.lwjgl:lwjgl-vulkan")
    implementation("org.joml:joml:1.10.8")

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

loom {
    fabricModJsonPath = file("src/main/resources/fabric.mod.json")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.jar {
    // Sodium 桩类仅在编译时使用，不打包进最终 jar
    exclude("net/caffeinemc/**")
    from("LICENSE") { into("META-INF") }
    manifest.attributes(
        "Implementation-Title" to "LavaFlow",
        "Implementation-Version" to project.version
    )
}

tasks.test {
    useJUnitPlatform()
}

val mcVersion = property("minecraft_version").toString()
tasks.withType<org.gradle.jvm.tasks.Jar>().configureEach {
    archiveBaseName.set(rootProject.name)
    archiveAppendix.set(mcVersion)
    archiveVersion.set(project.version.toString())
}