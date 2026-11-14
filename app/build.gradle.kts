import java.io.FileInputStream
import java.net.HttpURLConnection
import java.net.URI
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val signingProps = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) load(FileInputStream(f))
}

val ciKeystorePath: String? = System.getenv("FABLE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }
val ciKeystoreFile: File? = ciKeystorePath?.let { File(it) }?.takeIf { it.isFile }

android {
    namespace = "com.fable.browser"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fable.browser"
        minSdk = 26
        targetSdk = 35
        versionCode = 39
        versionName = "1.43.0"

        externalNativeBuild {
            cmake {
                cppFlags("-std=c++20", "-O3", "-Wall", "-Wextra")
            }
        }

        testInstrumentationRunner = "com.fable.browser.HiltTestRunner"
        vectorDrawables { useSupportLibrary = true }
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
            isMinifyEnabled = true
            isShrinkResources = true
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
    buildFeatures {
        compose = true
        buildConfig = true
    }
    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "/META-INF/DEPENDENCIES",
                "/META-INF/INDEX.LIST",
                "/META-INF/LICENSE.md",
                "/META-INF/LICENSE-notice.md",
                "/META-INF/NOTICE.md",
                "/META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/versions/9/previous-compilation-data.bin",
                "DebugProbesKt.bin"
            )
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

abstract class FetchFilterListsTask : DefaultTask() {
    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    @TaskAction
    fun fetch() {
        val dir = outputDir.get().dir("filters").asFile.apply { mkdirs() }
        val lists = listOf(
            "easylist.txt" to "https://easylist.to/easylist/easylist.txt",
            "easyprivacy.txt" to "https://easylist.to/easylist/easyprivacy.txt",
            "adguard_mobile.txt" to "https://filters.adtidy.org/extension/ublock/filters/11.txt",
            "adguard_tracking.txt" to "https://filters.adtidy.org/extension/ublock/filters/3.txt",
            "peterlowe.txt" to "https://pgl.yoyo.org/adservers/serverlist.php?hostformat=adblockplus&showintro=0&mimetype=plaintext"
        )
        if (System.getenv("FABLE_OFFLINE_FILTERS") == "1") return
        lists.forEach { (name, url) ->
            val dest = File(dir, name)
            if (dest.length() > 1024) return@forEach
            val tmp = File(dir, "$name.part")
            try {
                val conn = URI(url).toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 20_000
                conn.readTimeout = 60_000
                conn.instanceFollowRedirects = true
                conn.setRequestProperty("User-Agent", "FableBrowser-Build")
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                conn.disconnect()
                if (tmp.length() > 1024 && !tmp.readText().take(256).trimStart().startsWith("<")) {
                    tmp.copyTo(dest, overwrite = true)
                    logger.lifecycle("Bundled filter list $name (${dest.length() / 1024} KB)")
                } else {
                    logger.warn("Filter list $name looked invalid, skipped")
                }
            } catch (e: Exception) {
                logger.warn("Filter list $name not bundled: ${e.message}")
            } finally {
                tmp.delete()
            }
        }
    }
}

abstract class BuildRustCoreTask : DefaultTask() {
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
            if (!aarchLinker.canExecute() || !x86Linker.canExecute()) return

            val home = System.getProperty("user.home").orEmpty()
            val cargoBin = File(home, ".cargo/bin")
            val rustupExe = if (File(cargoBin, "rustup").canExecute()) File(cargoBin, "rustup").absolutePath else "rustup"
            val cargoExe = if (File(cargoBin, "cargo").canExecute()) File(cargoBin, "cargo").absolutePath else "cargo"

            val targetAdd = ProcessBuilder(rustupExe, "target", "add", "aarch64-linux-android", "x86_64-linux-android")
                .redirectErrorStream(true).start()
            targetAdd.inputStream.bufferedReader().readText()
            if (targetAdd.waitFor() != 0) return

            val manifest = File(rustProjectDir.get().asFile, "Cargo.toml").absolutePath
            val targets = listOf(
                "aarch64-linux-android" to "arm64-v8a",
                "x86_64-linux-android" to "x86_64"
            )
            for ((rustTarget, abi) in targets) {
                val pb = ProcessBuilder(
                    cargoExe, "build", "--manifest-path", manifest, "--release", "--target", rustTarget
                ).redirectErrorStream(true)
                val env = pb.environment()
                env["CARGO_TARGET_AARCH64_LINUX_ANDROID_LINKER"] = aarchLinker.absolutePath
                env["CARGO_TARGET_X86_64_LINUX_ANDROID_LINKER"] = x86Linker.absolutePath
                env["RUSTFLAGS"] = "-C link-arg=-Wl,-z,max-page-size=16384"
                val proc = pb.start()
                val logOut = proc.inputStream.bufferedReader().readText()
                if (proc.waitFor() == 0) {
                    val soFile = File(rustProjectDir.get().asFile, "target/$rustTarget/release/libfable_rust.so")
                    if (soFile.isFile) {
                        val dest = File(File(outRoot, abi).apply { mkdirs() }, "libfable_rust.so")
                        soFile.copyTo(dest, overwrite = true)
                        logger.lifecycle("Built Rust core $abi/libfable_rust.so (${dest.length() / 1024} KB)")
                    }
                } else {
                    logger.warn("Cargo build for $rustTarget failed: $logOut")
                }
            }
        } catch (e: Exception) {
            logger.warn("Rust native core build skipped: ${e.message}")
        }
    }
}

androidComponents {
    onVariants { variant ->
        val capName = variant.name.replaceFirstChar { it.uppercase() }
        val fetch = tasks.register<FetchFilterListsTask>("fetch${capName}FilterLists")
        variant.sources.assets?.addGeneratedSourceDirectory(fetch, FetchFilterListsTask::outputDir)

        val rustTask = tasks.register<BuildRustCoreTask>("build${capName}RustCore") {
            rustProjectDir.set(rootProject.layout.projectDirectory.dir("rust/fable_rust_core"))
        }
        variant.sources.jniLibs?.addGeneratedSourceDirectory(rustTask, BuildRustCoreTask::outputDir)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.navigation.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.geckoview)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.accompanist.permissions)
    implementation(libs.coil.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.hilt.android)
    kspAndroidTest(libs.hilt.compiler)
    androidTestImplementation(libs.hilt.android.testing)

    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
