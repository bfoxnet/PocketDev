plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
}

android {
    namespace = "com.bfoxnet.pocketdev"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.bfoxnet.pocketdev"
        minSdk = 24
        // targetSdk = 28 is required on Android 10+ (API 29+) so SELinux assigns the process
        // to `untrusted_app_27`, which permits `execve()` and `mmap(PROT_EXEC)` on downloaded
        // ELF binaries (`proot`, `loader`, and Ubuntu 24.04 rootfs binaries) inside `filesDir`.
        // Note: If the app was previously installed with targetSdk = 35, uninstall the existing
        // app from the device before installing so Android's installd updates the SELinux context.
        //noinspection ExpiredTargetSdkVersion
        targetSdk = 28
        versionCode = 1
        versionName = "1.0"
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
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
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)

    implementation(libs.sora.editor)
    implementation(libs.sora.textmate)
    implementation(libs.sora.java)
    implementation(libs.sora.monarch)
    implementation(libs.monarch.language.pack)

    implementation("org.tukaani:xz:1.10")

    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.crashlytics)
    implementation(libs.firebase.analytics)

    coreLibraryDesugaring(libs.desugar)
}
