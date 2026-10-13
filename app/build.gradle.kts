import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.parcelize")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
    id("androidx.room3")
}

// Upload key for Google Play, read from keystore.properties (never committed).
val keystoreProperties = Properties().apply {
    val file = rootProject.file("keystore.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}
// `-PsignWithDebugKey` builds a sideloadable APK signed like the debug app (updates it in place).
val signWithDebugKey = project.hasProperty("signWithDebugKey")

android {
    namespace = "org.crea.reader"
    compileSdk = 37

    defaultConfig {
        applicationId = "org.crea.reader"
        minSdk = 24
        targetSdk = 37
        versionCode = 26
        versionName = "0.9.8"
        // Address of the AI server (see server/README.md). Empty = AI features do not exist in this build.
        buildConfigField("String", "AI_BASE_URL", "\"${project.findProperty("aiBaseUrl") ?: ""}\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // Every CREA tablet is ARM: the x86 libraries only go into debug builds, for the emulator.
        ndk.abiFilters += listOf("armeabi-v7a", "arm64-v8a")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        viewBinding = true
        compose = true
        buildConfig = true
    }

    signingConfigs {
        if (keystoreProperties.containsKey("storeFile")) {
            create("upload") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            ndk.abiFilters += listOf("x86", "x86_64")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            // Store builds use the upload key; fall back to the debug key only when asked for
            // (-PsignWithDebugKey) or when keystore.properties is missing.
            signingConfig = if (!signWithDebugKey && keystoreProperties.containsKey("storeFile")) {
                signingConfigs.getByName("upload")
            } else {
                signingConfigs.getByName("debug")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources.excludes += "META-INF/*"
    }
}

kotlin {
    compilerOptions {
        languageVersion = org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_4
    }
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")

    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.4.20")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    val readiumVersion = "3.4.0"
    implementation("org.readium.kotlin-toolkit:readium-shared:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-streamer:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-navigator:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-audio:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-navigator-media-tts:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-adapter-exoplayer:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-adapter-pdfium:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-opds:$readiumVersion")
    implementation("org.readium.kotlin-toolkit:readium-lcp:$readiumVersion")

    implementation("androidx.activity:activity-ktx:1.13.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.appcompat:appcompat:1.8.0")
    implementation("androidx.browser:browser:1.10.0")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.compose.animation:animation:1.12.0")
    implementation("androidx.compose.foundation:foundation:1.12.0")
    implementation("androidx.compose.material:material:1.12.0")
    implementation("androidx.compose.material3:material3:1.4.0")
    implementation("androidx.compose.runtime:runtime:1.12.0")
    implementation("androidx.compose.ui:ui:1.12.0")
    implementation("androidx.compose.ui:ui-tooling:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.core:core-ktx:1.19.0")
    implementation("androidx.datastore:datastore-preferences:1.2.1")
    implementation("androidx.fragment:fragment-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-common-java8:2.11.0")
    implementation("androidx.navigation:navigation-fragment-ktx:2.10.0")
    implementation("androidx.navigation:navigation-ui-ktx:2.10.0")
    implementation("androidx.paging:paging-runtime-ktx:3.5.1")
    implementation("androidx.recyclerview:recyclerview:1.4.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.room3:room3-runtime:3.0.2")
    implementation("androidx.viewpager2:viewpager2:1.1.0")
    implementation("androidx.webkit:webkit:1.17.0")
    implementation("androidx.work:work-runtime-ktx:2.11.0")
    implementation("com.google.android.gms:play-services-auth:22.0.0")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.jakewharton.timber:timber:5.0.1")
    implementation("com.squareup.picasso:picasso:2.8")
    implementation("org.jsoup:jsoup:1.23.2")

    implementation("androidx.media3:media3-common-ktx:1.11.0")
    implementation("androidx.media3:media3-session:1.11.0")
    implementation("androidx.media3:media3-exoplayer:1.11.0")

    ksp("androidx.room3:room3-compiler:3.0.2")

    testImplementation("junit:junit:4.13.2")
    // Real SQLite on the JVM, to run the Room migrations against the exported schemas.
    testImplementation("androidx.sqlite:sqlite-bundled-jvm:2.7.0")
}
