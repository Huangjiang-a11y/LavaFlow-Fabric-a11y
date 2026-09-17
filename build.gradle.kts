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
    // Loom 1.18 会自动注入 Minecraft 依赖，你只需要声明版本
    minecraft("com.mojang:minecraft:${property("minecraft_version")}")

    // 标准依赖，无需 modImplementation
    implementation("net.fabricmc:fabric-loader:${property("loader_version")}")
    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    // ... (其他 LWJGL 依赖保持不变)

    runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
    // ... (其他 natives 依赖保持不变)

    testImplementation(platform("org.junit:junit-bom:5.13.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

loom {
    // 明确指向标准资源目录
    fabricModJsonPath = file("src/main/resources/fabric.mod.json")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.jar {
    // 排除 Sodium 桩类，它们只在编译时使用
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