plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseStoreFile = providers.environmentVariable("ANDROID_KEYSTORE_PATH").orNull
val releaseStorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull
val configuredVersionCode = providers.environmentVariable("DICTATE_VERSION_CODE").orNull
    ?.toIntOrNull()
    ?: 1
val configuredVersionName = providers.environmentVariable("DICTATE_VERSION_NAME").orNull
    ?: "0.1.0"

android {
    namespace = "com.joeykot.dictate"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.joeykot.dictate"
        minSdk = 26
        targetSdk = 35
        versionCode = configuredVersionCode
        versionName = configuredVersionName

        ndk {
            abiFilters += setOf("arm64-v8a")
        }
    }

    val releaseSigning = if (
        releaseStoreFile != null &&
        releaseStorePassword != null &&
        releaseKeyAlias != null &&
        releaseKeyPassword != null
    ) {
        signingConfigs.create("release") {
            storeFile = file(releaseStoreFile)
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    } else {
        null
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            signingConfig = releaseSigning
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        jniLibs.useLegacyPackaging = true
        jniLibs.keepDebugSymbols += "**/libffmpeg.so"
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    lint {
        // arm64-only is an explicit first-release product constraint.
        disable += "ChromeOsAbiSupport"
    }
}

val verifyFfmpegBinary by tasks.registering {
    group = "verification"
    description = "Checks that the arm64 FFmpeg CLI binary is present for release packaging."
    inputs.file(layout.projectDirectory.file("src/main/jniLibs/arm64-v8a/libffmpeg.so"))
    doLast {
        val binary = inputs.files.singleFile
        check(binary.isFile && binary.length() > 0L) {
            "Missing ${binary.path}. Run scripts/build-android-ffmpeg.sh before building release."
        }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(verifyFfmpegBinary)
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}
