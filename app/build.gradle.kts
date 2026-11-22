import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Application module for Rsensors.
//
// The launcher activity, the pure core/ logic, the sensor source chain and the
// overlay service. Shizuku supplies the tier-1 dumpsys route (ANDROID.md §6,
// §9); the `api` artifact is the client library and `provider` the binder
// hand-off that the manifest declares.
plugins {
  alias(libs.plugins.android.application)
  alias(libs.plugins.compose.compiler)
}

android {
  namespace = "dev.rsensors.android"
  compileSdk = 36
  defaultConfig {
    applicationId = "dev.rsensors.android"
    minSdk = 26
    targetSdk = 36
    versionCode = 2
    versionName = "0.2.0"
  }

  buildTypes {
    release {
      // T11 (ANDROID.md §2.2, §9): the personal-sideload build is shrunk and
      // obfuscated. R8 must keep the Shizuku UserService package alive; see
      // proguard-rules.pro.
      isMinifyEnabled = true
      isShrinkResources = true
      // PERSONAL SIDELOAD ONLY. The release is signed with the local debug
      // keystore so it installs over the debug app (same package and key) and
      // keeps its data. Replace this with a private release keystore before
      // any distribution; never ship an APK signed with debug.keystore.
      signingConfig = signingConfigs.getByName("debug")
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  buildFeatures {
    compose = true
    buildConfig = false
    // IDumpsysService is the shell-side UserService contract (ANDROID.md §9).
    aidl = true
  }

  packaging {
    resources {
      excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
  }
}

// JVM target 17 without a toolchain: the build runs on whatever JDK Gradle uses
// (>= 17) and emits Java 17 bytecode, so no JDK provisioning plugin is needed.
kotlin {
  compilerOptions {
    jvmTarget.set(JvmTarget.JVM_17)
  }
}

dependencies {
  implementation(platform(libs.androidx.compose.bom))

  implementation(libs.androidx.core.ktx)
  implementation(libs.androidx.lifecycle.runtime.ktx)
  implementation(libs.androidx.activity.compose)
  // MaterialTheme + Text for the launcher activity; version from the BOM.
  implementation(libs.androidx.compose.material3)

  // Tier-1 source: the client API plus the provider that hands the binder over.
  implementation(libs.shizuku.api)
  implementation(libs.shizuku.provider)

  testImplementation(libs.junit)
}
