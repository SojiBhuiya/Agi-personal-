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

    // Release signing is configured ONLY from the environment (CI decodes the keystore secret to a
    // temp file). Nothing is read from the repository; when the variables are absent the release
    // build type is simply left unsigned, so local/debug builds are unaffected. Values are never logged.
    val releaseKeystore = System.getenv("AGI_RELEASE_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let { file(it) }
    val releaseSigningAvailable = releaseKeystore?.isFile == true &&
        !System.getenv("AGI_RELEASE_STORE_PASSWORD").isNullOrEmpty() &&
        !System.getenv("AGI_RELEASE_KEY_ALIAS").isNullOrEmpty()
    if (releaseSigningAvailable) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("AGI_RELEASE_STORE_PASSWORD")
                keyAlias = System.getenv("AGI_RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("AGI_RELEASE_KEY_PASSWORD")?.takeIf { it.isNotEmpty() }
                    ?: System.getenv("AGI_RELEASE_STORE_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningAvailable) signingConfig = signingConfigs.getByName("release")
        }
    }
    lint {
        // Lint runs in CI as a report; it must not block a signed release build on advisory findings.
        abortOnError = false
        checkReleaseBuilds = true
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
val jvmTestClasspath by lazy {
    files(layout.buildDirectory.dir("tmp/kotlin-classes/debugUnitTest")) +
        files(layout.buildDirectory.dir("tmp/kotlin-classes/debug")) +
        configurations.getByName("debugUnitTestRuntimeClasspath")
}
// Self-contained suites (each exits non-zero on failure). ProviderWireTest needs the mock AI
// server from scripts/mock_ai_server.py and is run by providerWireTest below.
val selfContainedSuites = listOf(
    "LocalRuleProviderTest", "AgentLoopTest", "UpdateCheckerTest", "UpdateUiPolicyTest",
    "ApkDownloaderTest", "InstallFlowTest", "AutoCheckTest",
)
val suiteTasks = selfContainedSuites.map { suite ->
    tasks.register<JavaExec>("run$suite") {
        group = "verification"
        description = "Runs com.agi.assistant.$suite (plain main-function suite)."
        dependsOn("compileDebugUnitTestKotlin")
        classpath = jvmTestClasspath
        mainClass.set("com.agi.assistant.$suite")
    }
}
tasks.register<JavaExec>("providerWireTest") {
    group = "verification"
    description = "Runs ProviderWireTest against a mock server: -PmockUrl=http://127.0.0.1:8089 -PmockLog=/tmp/mock_ai_last.json"
    dependsOn("compileDebugUnitTestKotlin")
    classpath = jvmTestClasspath
    mainClass.set("com.agi.assistant.ProviderWireTest")
    args(
        (project.findProperty("mockUrl") as String?) ?: "http://127.0.0.1:8089",
        (project.findProperty("mockLog") as String?) ?: "/tmp/mock_ai_last.json",
    )
}
tasks.register("coreTests") {
    group = "verification"
    description = "Runs every self-contained JVM suite (same set as scripts/run_tests.sh minus the wire test)."
    dependsOn(suiteTasks)
}
