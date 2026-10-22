import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing secrets live in keystore.properties (never commit it).
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "ir.payam.messenger"
    compileSdk = 36

    defaultConfig {
        applicationId = "ir.payam.messenger"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        // Pre-filled server address on the sign-up screen (`-Ppayam.server=https://chat.example.ir`).
        buildConfigField("String", "DEFAULT_SERVER", "\"${project.findProperty("payam.server") ?: "https://"}\"")
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
    }

    signingConfigs {
        if (keystoreProps.isNotEmpty()) {
            create("release") {
                storeFile = rootProject.file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        debug {
            // Plain http/ws only in debug, for a local test server.
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "true")
            manifestPlaceholders["cleartext"] = "true"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "ALLOW_CLEARTEXT", "false")
            manifestPlaceholders["cleartext"] = "false"
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
        jniLibs.useLegacyPackaging = false
    }
}

// Rust crypto (vodozemac) → src/main/jniLibs/<abi>/libpayamcrypto.so
val cargoBuild by tasks.registering(Exec::class) {
    val crate = rootProject.file("crypto-rs")
    val sdk = Properties().apply { rootProject.file("local.properties").inputStream().use { load(it) } }
        .getProperty("sdk.dir")
    val ndkDir = File(sdk, "ndk").listFiles()?.filter { it.isDirectory }?.maxByOrNull { it.name }
        ?: error("No NDK under $sdk/ndk")
    val cargo = File(System.getProperty("user.home"), ".cargo/bin/cargo").path
    workingDir = crate
    environment("ANDROID_NDK_HOME", ndkDir.path)
    commandLine(
        cargo, "ndk", "--platform", "26",
        "-t", "arm64-v8a", "-t", "armeabi-v7a", "-t", "x86_64",
        "-o", file("src/main/jniLibs").path, "build", "--release",
    )
    inputs.dir(crate.resolve("src"))
    inputs.file(crate.resolve("Cargo.toml"))
    outputs.dir(file("src/main/jniLibs"))
}
tasks.named("preBuild") { dependsOn(cargoBuild) }

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
    implementation(libs.androidx.biometric)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.zxing.core)
    implementation(libs.zxing.embedded) { isTransitive = false }
    testImplementation(libs.junit)
    testImplementation("org.json:json:20240303") // android.jar's org.json is a stub in JVM tests
}
