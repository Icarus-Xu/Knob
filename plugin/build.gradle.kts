plugins {
    // Kotlin 2.1.10 起支持 JDK 25 及更新的版本（AS 的 JBR 25 可用）
    // 版本号统一在 gradle/libs.versions.toml 管理
    alias(libs.plugins.kotlin.jvm)
}

group = "cn.icarus.knob"
version = "1.0.0"

// 编译依赖的 android.jar（仅编译用，不打进 dex）
val androidJar = System.getenv("ANDROID_HOME")?.let { "$it/platforms/android-34/android.jar" }
    ?: "/home/icarus/Android/Sdk/platforms/android-34/android.jar"

// d8 工具路径
val d8Path = System.getenv("ANDROID_HOME")?.let { "$it/build-tools/36.1.0/d8" }
    ?: "/home/icarus/Android/Sdk/build-tools/36.1.0/d8"

dependencies {
    // android.jar：编译 Context 等 Android 类
    compileOnly(files(androidJar))
    // 车机厂商官方车控 SDK（仅编译用，运行时由车机系统提供）
    compileOnly(files("libs/bydauto-openapi.jar"))
}

// 不使用强制 toolchain：让 Kotlin 直接用 Gradle 当前运行的 JDK 编译（本机环境）。
// Kotlin 2.1.10 支持 JDK 17/21/25+。
// 关键：java 和 kotlin 的 JVM-target 必须一致（否则 Gradle 报
// "Inconsistent JVM-target compatibility"）。这里都设为 17 —— 只是字节码目标，
// 不是编译 JDK 版本，所以无论 Gradle 用 JDK 21 还是 25 都能编译。

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// 打包所有类到单个 jar（不含 android.jar，因为 compileOnly）
tasks.register<Jar>("pluginJar") {
    archiveFileName.set("plugin.jar")
    from(sourceSets["main"].output)
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    dependsOn("compileKotlin")
}

// 一步完成 jar → dex（方案 B：Android Studio 里双击 pluginDex 即得 plugin.dex）
tasks.register("pluginDex") {
    group = "knob"
    description = "编译插件并转成 plugin.dex"
    dependsOn("pluginJar")

    doLast {
        val jarFile = file("build/libs/plugin.jar")
        val outDir = file("output")
        outDir.deleteRecursively()
        outDir.mkdirs()

        // 用 d8 把 jar 转成 dex
        val d8 = file(d8Path)
        if (!d8.exists()) {
            throw GradleException("d8 不存在: $d8Path，请检查 ANDROID_HOME")
        }
        val cmd = listOf(
            d8.absolutePath,
            "--output", outDir.absolutePath,
            "--lib", androidJar,
            jarFile.absolutePath
        )
        // d8 是 shell 脚本，靠 JAVA_HOME/PATH 找 java。
        // 这里用 Gradle 当前运行 JVM 的 java，不写死任何 JDK 路径，完全跟随本机环境。
        val javaHome = System.getProperty("java.home") // 例如 .../jbr 或 .../jdk-21
        val javaBin = File(javaHome, "bin").absolutePath
        val pb = ProcessBuilder(cmd)
        val env = pb.environment()
        env["JAVA_HOME"] = javaHome
        env["PATH"] = "$javaBin${File.pathSeparator}${env["PATH"] ?: ""}"
        pb.redirectErrorStream(true)
        val process = pb.start()
        val output = process.inputStream.readBytes().toString(Charsets.UTF_8)
        val exit = process.waitFor()
        if (exit != 0) {
            throw GradleException("d8 转换失败:\n$output")
        }

        // 重命名为 plugin.dex
        val classesDex = file("output/classes.dex")
        if (classesDex.exists()) {
            classesDex.copyTo(file("output/plugin.dex"), overwrite = true)
            println("✅ plugin.dex 生成: ${file("output/plugin.dex").absolutePath}")
        } else {
            throw GradleException("未找到 classes.dex")
        }
    }
}
