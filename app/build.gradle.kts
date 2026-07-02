plugins { id("com.android.application") }

android {
  namespace = "com.trmnl.legacylite"
  compileSdk = 34

  defaultConfig {
    applicationId = "com.trmnl.legacylite"
    minSdk = 7
    targetSdk = 34
    versionCode = 1
    versionName = "0.0.1"
  }

  buildTypes {
    release {
      isMinifyEnabled = false
      proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
  }
}

// Android <= 2.1 (API 7) can't read AAPT2's UTF-8 resource string pools, so the
// stock APK crashes in setContentView. This post-processes the built debug APK,
// transcoding the string pools to UTF-16, then realigns and re-signs it.
// Produces app-debug-legacy.apk alongside app-debug.apk -- install that one.
tasks.register<Exec>("legacyPatchDebug") {
  workingDir = rootDir
  commandLine("bash", "tools/build-legacy.sh")
}

afterEvaluate {
  tasks.named("assembleDebug").configure { finalizedBy("legacyPatchDebug") }
}
