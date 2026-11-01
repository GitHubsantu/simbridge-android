import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

ksp {
    // Room exports its schema so future migrations can be validated. Commit the generated /schemas folder.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "in.imdevops.simbridge"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "in.imdevops.simbridge"
        minSdk = 26
        targetSdk = 37
        versionCode = 3
        versionName = "1.1.1"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Release signing. The keystore and keystore.properties are git-ignored and must never be committed.
    val keystoreFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystoreFile.exists()) {
            create("release") {
                val props = Properties().apply { keystoreFile.inputStream().use { load(it) } }
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")

                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // R8 is OFF on purpose: it was never tested with Ktor/Room here, and a personal app gains little from it.
            // Turn it on only after testing a minified build on the phone.
            optimization {
                enable = false
            }
            if (keystoreFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    // One build produces three installable files: 32-bit only, 64-bit only, and a universal APK with both.
    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    packaging {
        // cloudflared is executed from the native-library directory, so it must be extracted to disk.
        jniLibs {
            useLegacyPackaging = true
            // Package the checksum-verified upstream binary byte-for-byte (no symbol stripping).
            keepDebugSymbols += "**/libcloudflared.so"
        }
        resources {
            excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.cio)
    implementation(libs.androidx.room.runtime)
    ksp(libs.androidx.room.compiler)
    testImplementation(libs.junit)
    testImplementation(libs.ktor.server.test.host)
    // Real org.json for JVM unit tests (the android.jar one is a stub that throws).
    testImplementation(libs.json)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}

// A release build without the tunnel binary would silently ship a broken app, so refuse to build it.
val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
val checkCloudflaredLibs = tasks.register("checkCloudflaredLibs") {
    val dir = jniLibsDir
    doLast {
        val missing = listOf("armeabi-v7a", "arm64-v8a").filter { !dir.file("$it/libcloudflared.so").asFile.exists() }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "libcloudflared.so is missing for: ${missing.joinToString()}. " +
                        "Run: powershell -ExecutionPolicy Bypass -File tools\\fetch-cloudflared.ps1",
            )
        }
    }
}
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    dependsOn(checkCloudflaredLibs)
}