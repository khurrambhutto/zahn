import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
}

// llama.cpp is pinned to a release and downloaded once into build/ (not committed).
val llamaCppVersion = providers.gradleProperty("chota.llamaCppVersion").get()
val llamaCppSha256 = providers.gradleProperty("chota.llamaCppSha256").get()
val llamaCppRoot = layout.buildDirectory.dir("llama.cpp")
val llamaCppDir = llamaCppRoot.map { it.dir("llama.cpp-${llamaCppVersion.removePrefix("v")}") }

val fetchLlamaCpp = tasks.register("fetchLlamaCpp") {
    description = "Downloads the pinned llama.cpp source release"
    val archive = llamaCppRoot.map { it.file("llama.cpp-$llamaCppVersion.tar.gz") }
    outputs.dir(llamaCppDir)
    doLast {
        val target = archive.get().asFile
        if (!target.exists()) {
            target.parentFile.mkdirs()
            val url = "https://github.com/ggml-org/llama.cpp/archive/refs/tags/$llamaCppVersion.tar.gz"
            logger.lifecycle("Downloading $url")
            uri(url).toURL().openStream().use { input -> target.outputStream().use { input.copyTo(it) } }
        }
        val actual = MessageDigest.getInstance("SHA-256")
            .digest(target.readBytes()).joinToString("") { b -> "%02x".format(b) }
        if (actual != llamaCppSha256) {
            target.delete()
            throw GradleException("llama.cpp checksum mismatch: expected $llamaCppSha256, got $actual")
        }
        copy {
            from(tarTree(resources.gzip(target)))
            into(llamaCppRoot)
        }
    }
}

// arm64-v8a for phones, x86_64 for the emulator. Pass -Pchota.abis=arm64-v8a to build faster.
val abis = providers.gradleProperty("chota.abis").getOrElse("arm64-v8a,x86_64").split(",")

android {
    namespace = "com.chotaai.llm.llamacpp"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
        ndk { abiFilters += abis }
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DLLAMA_CPP_DIR=${llamaCppDir.get().asFile.absolutePath}",
                    "-DCMAKE_BUILD_TYPE=Release",
                    "-DBUILD_SHARED_LIBS=ON",
                    "-DLLAMA_BUILD_COMMON=ON",
                    "-DLLAMA_BUILD_TESTS=OFF",
                    "-DLLAMA_BUILD_TOOLS=OFF",
                    "-DLLAMA_BUILD_EXAMPLES=OFF",
                    "-DLLAMA_BUILD_SERVER=OFF",
                    "-DLLAMA_BUILD_APP=OFF",
                    "-DLLAMA_OPENSSL=OFF",
                    "-DGGML_NATIVE=OFF",
                    // Build every ARM CPU variant and pick the best one at runtime (dotprod, i8mm, SVE...).
                    "-DGGML_BACKEND_DL=ON",
                    "-DGGML_CPU_ALL_VARIANTS=ON",
                    "-DGGML_LLAMAFILE=OFF",
                )
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.configureEach {
    if (name.startsWith("configureCMake") || name.startsWith("buildCMake")) dependsOn(fetchLlamaCpp)
}

dependencies {
    api(project(":llm:core"))
    implementation(libs.kotlinx.coroutines.android)
}
