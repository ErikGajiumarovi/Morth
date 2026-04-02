import org.gradle.internal.os.OperatingSystem

plugins {
    kotlin("multiplatform") version "2.3.0"
}

group = "morph"
version = "0.1.0"

repositories {
    mavenCentral()
}

kotlin {
    val hostOs = OperatingSystem.current()
    val hostArch = System.getProperty("os.arch")

    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    val nativeTarget = when {
        hostOs.isWindows -> mingwX64("native")
        hostOs.isLinux -> linuxX64("native")
        hostOs.isMacOsX && hostArch == "aarch64" -> macosArm64("native")
        hostOs.isMacOsX -> macosX64("native")
        else -> error("Unsupported host OS: ${hostOs.name}")
    }

    nativeTarget.binaries {
        executable {
            baseName = "morph"
            entryPoint = "morph.main"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(kotlin("stdlib"))
            implementation("com.squareup.okio:okio:3.10.2")
        }
    }
}
