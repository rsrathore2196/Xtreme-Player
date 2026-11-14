import com.android.build.api.variant.BuildConfigField

plugins {
  alias(libs.plugins.android.test)
  alias(libs.plugins.androidx.baselineprofile)
}

androidComponents.onVariants { variant ->
  // The baseline-profile plugin also creates nonMinifiedBenchmark and
  // benchmarkBenchmark variants; all must address the isolated target.
  val suffix = if (variant.name.endsWith("Benchmark") || variant.name == "benchmark") ".benchmark" else ""
  variant.buildConfigFields?.put("TARGET_PACKAGE", BuildConfigField("String",
    "\"com.aistudio.xtremeplayer.vkmz$suffix\"", "Application exercised by this test variant"))
}

android {
  namespace = "com.example.benchmark"
  compileSdk = 37
  defaultConfig {
    minSdk = 28
    targetSdk = 36
    testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    buildConfigField("String", "TARGET_PACKAGE", "\"com.aistudio.xtremeplayer.vkmz\"")
  }
  buildFeatures { buildConfig = true }
  buildTypes {
    create("benchmark") {
      isDebuggable = true
      matchingFallbacks += listOf("release")
      buildConfigField("String", "TARGET_PACKAGE", "\"com.aistudio.xtremeplayer.vkmz.benchmark\"")
    }
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
