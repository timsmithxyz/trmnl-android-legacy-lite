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
