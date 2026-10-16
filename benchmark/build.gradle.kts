plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

android {
  namespace = "com.example.benchmark"
  compileSdk = 37
  defaultConfig {
    minSdk = 28
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
  }
  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
  }
  targetProjectPath = ":app"
}

baselineProfile { useConnectedDevices = true }

dependencies {
  implementation(libs.androidx.benchmark.macro)
  implementation(libs.androidx.uiautomator)
  implementation(libs.androidx.runner)
  implementation(libs.androidx.junit)
  implementation(libs.junit)
}

// Keep the requested module entry point while the consumer merges/copies the device output.
tasks.register("generateBaselineProfile") {
  group = "Baseline Profile"
  description = "Collect startup/navigation/player profiles on a connected device and save them to app/src/main."
  dependsOn(":app:generateBaselineProfile")
}
