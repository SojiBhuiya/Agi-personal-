plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.agi.assistant"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.agi.assistant"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

// The app is intentionally built on the Android framework + Kotlin coroutines only.
// This keeps the APK tiny and lets the project be compiled with the offline
// toolchain in scripts/build_apk.sh as well as with Gradle/Android Studio.
dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    // Real org.json for JVM tests (android.jar only ships stubs).
    testImplementation("org.json:json:20231013")
}

// The JVM test suites in src/test are plain `main` functions (no JUnit) so they
// can also run with the offline toolchain via scripts/run_tests.sh.
tasks.register<JavaExec>("coreTests") {
    group = "verification"
    dependsOn("compileDebugUnitTestKotlin")
    val testOut = layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")
    classpath = files(testOut) + files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) +
        configurations.getByName("debugUnitTestRuntimeClasspath")
    mainClass.set("com.agi.assistant.LocalRuleProviderTest")
}
