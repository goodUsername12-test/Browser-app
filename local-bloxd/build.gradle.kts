import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) load(FileInputStream(f))
}

val ciKeystorePath: String? = System.getenv("FABLE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val ciKeystoreFile: File? = ciKeystorePath?.let { File(it) }?.takeIf { it.isFile }

android {
    namespace = "com.fable.localbloxd"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fable.localbloxd"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "1.1.0-offline"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20", "-O3", "-Wall", "-Wextra")
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
            storeType = "PKCS12"
        }
        if (ciKeystoreFile != null) {
            create("ci") {
                storeFile = ciKeystoreFile
                storePassword = System.getenv("FABLE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("FABLE_KEY_ALIAS")
                keyPassword = System.getenv("FABLE_KEY_PASSWORD")
            }
        }
        if (signingProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = when {
                signingProps.isNotEmpty() -> signingConfigs.getByName("release")
                ciKeystoreFile != null -> signingConfigs.getByName("ci")
                else -> signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            if (ciKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("ci")
            }
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            val abis = (project.findProperty("fable.abis") as String?)
                ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                ?: listOf("arm64-v8a")
            include(*abis.toTypedArray())
            isUniversalApk = false
        }
    }

    val sdkRoot = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
    val hasNdk = sdkRoot?.let { File(it, "ndk").listFiles()?.any { d -> d.isDirectory } } == true
    val hasCmake = sdkRoot?.let { File(it, "cmake").exists() } == true || File("/usr/bin/cmake").canExecute()
    if (hasNdk && hasCmake) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    androidResources {
        noCompress += listOf("woff2", "glb", "wasm", "ogg")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    implementation(libs.kotlinx.coroutines.android)
    testImplementation(libs.junit)
}

abstract class BuildLocalBloxdRustTask : DefaultTask() {
    @get:InputDirectory
    abstract val rustProjectDir: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun buildRust() {
        val outRoot = outputDir.get().asFile.apply { mkdirs() }
        try {
            val sdk = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT") ?: return
            val ndkDir = File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name } ?: return
            val llvmBin = File(ndkDir, "toolchains/llvm/prebuilt/linux-x86_64/bin")
            val aarchLinker = File(llvmBin, "aarch64-linux-android26-clang")
            val x86Linker = File(llvmBin, "x86_64-linux-android26-clang")

            val home = System.getProperty("user.home").orEmpty()
            val cargoBin = File(home, ".cargo/bin")
            val rustupExe = if (File(cargoBin, "rustup").canExecute()) File(cargoBin, "rustup").absolutePath else "rustup"
            val cargoExe = if (File(cargoBin, "cargo").canExecute()) File(cargoBin, "cargo").absolutePath else "cargo"

            val addProc = ProcessBuilder(rustupExe, "target", "add", "aarch64-linux-android", "x86_64-linux-android")
                .redirectErrorStream(true).start()
            val addLog = addProc.inputStream.bufferedReader().readText()
            val addCode = addProc.waitFor()
            if (addCode != 0) {
                logger.warn("Local Bloxd rustup target add exited $addCode: $addLog")
            }

            val manifest = File(rustProjectDir.get().asFile, "Cargo.toml").absolutePath
            val targets = listOf(
                Triple("aarch64-linux-android", "arm64-v8a", aarchLinker),
                Triple("x86_64-linux-android", "x86_64", x86Linker)
            )
            for ((rustTarget, abi, linker) in targets) {
                if (!linker.canExecute()) {
                    logger.warn("Local Bloxd Rust linker missing for $rustTarget: ${linker.absolutePath}")
                    continue
                }
                val pb = ProcessBuilder(
                    cargoExe, "build", "--manifest-path", manifest, "--release", "--target", rustTarget
                ).redirectErrorStream(true)
                val env = pb.environment()
                env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = aarchLinker.absolutePath
                env["CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"] = x86Linker.absolutePath
                env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
                val proc = pb.start()
                val logOut = proc.inputStream.bufferedReader().readText()
                val exit = proc.waitFor()
                if (exit == 0) {
                    val soFile = File(rustProjectDir.get().asFile, "target/$rustTarget/release/liblocal_bloxd_rust.so")
                    if (soFile.isFile) {
                        val dest = File(File(outRoot, abi).apply { mkdirs() }, "liblocal_bloxd_rust.so")
                        soFile.copyTo(dest, overwrite = true)
                        logger.lifecycle("Built Local Bloxd Rust $abi/liblocal_bloxd_rust.so (${dest.length() / 1024} KB)")
                    } else {
                        logger.warn("Local Bloxd Rust output missing: ${soFile.absolutePath}")
                    }
                } else {
                    logger.warn("Local Bloxd Cargo build failed ($exit) for $rustTarget: $logOut")
                }
            }
        } catch (e: Exception) {
            logger.warn("Local Bloxd Rust build skipped: ${e.message}")
        }
    }
}

val buildLocalBloxdRust by tasks.registering(BuildLocalBloxdRustTask::class) {
    rustProjectDir.set(rootProject.layout.projectDirectory.dir("rust/local_bloxd_rust"))
    outputDir.set(layout.buildDirectory.dir("generated/rustJniLibs"))
}

android.sourceSets.getByName("main").jniLibs.srcDir(buildLocalBloxdRust.flatMap { it.outputDir })

tasks.matching { it.name.startsWith("merge") && it.name.endsWith("JniLibFolders") }.configureEach {
    dependsOn(buildLocalBloxdRust)
}

// Copy arm64-v8a APKs alongside app's APKs via finalizedBy tasks so GitHub Actions
// ALWAYS gets both separate APKs even when assembleDebug/assembleRelease hits Gradle build cache!
val copyLocalBloxdDebugApkToApp by tasks.registering {
    outputs.upToDateWhen { false }
    mustRunAfter(":app:assembleDebug")
    doLast {
        val srcDir = layout.buildDirectory.dir("outputs/apk/debug").get().asFile
        val dstDir = rootProject.file("app/build/outputs/apk/debug").apply { mkdirs() }
        srcDir.listFiles()?.filter { it.extension == "apk" }?.forEach { apk ->
            apk.copyTo(File(dstDir, "z-local-bloxd-${apk.name.removePrefix("local-bloxd-")}"), overwrite = true)
        }
    }
}

val copyLocalBloxdReleaseApkToApp by tasks.registering {
    outputs.upToDateWhen { false }
    mustRunAfter(":app:assembleRelease")
    doLast {
        val srcDir = layout.buildDirectory.dir("outputs/apk/release").get().asFile
        val dstDir = rootProject.file("app/build/outputs/apk/release").apply { mkdirs() }
        srcDir.listFiles()?.filter { it.extension == "apk" }?.forEach { apk ->
            apk.copyTo(File(dstDir, "z-local-bloxd-${apk.name.removePrefix("local-bloxd-")}"), overwrite = true)
        }
    }
}

afterEvaluate {
    val abisProp = (project.findProperty("fable.abis") as String?).orEmpty()
    if (!abisProp.contains("x86_64")) {
        tasks.matching { it.name == "assembleDebug" }.configureEach {
            finalizedBy(copyLocalBloxdDebugApkToApp)
        }
        tasks.matching { it.name == "assembleRelease" }.configureEach {
            finalizedBy(copyLocalBloxdReleaseApkToApp)
        }
    }
}
