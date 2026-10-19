plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.chotaai.llm.litert"
    compileSdk = 37

    defaultConfig {
        minSdk = 28
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    api(project(":llm:core"))
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.android)
}
