plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Keep the actual open-licensed font files and their individual license notices
// inside the APK. The phone never needs a font-service connection.
val spineFontAssets = layout.buildDirectory.dir("generated/spineFontAssets")
val spineFontSources = mapOf(
    "cinzel" to "Cinzel[wght].ttf",
    "ebgaramond" to "EBGaramond[wght].ttf",
    "oswald" to "Oswald[wght].ttf",
    "bebasneue" to "BebasNeue-Regular.ttf",
    "anton" to "Anton-Regular.ttf",
    "caveat" to "Caveat[wght].ttf",
)
val prepareSpineFonts by tasks.registering {
    inputs.property("fontSources", spineFontSources)
    outputs.dir(spineFontAssets)
    doLast {
        val root = spineFontAssets.get().asFile.resolve("spine_fonts")
        root.mkdirs()
        fun fetchFontAsset(family: String, remote: String, target: java.io.File) {
            val encoded = remote.replace("[", "%5B").replace("]", "%5D")
            val url = java.net.URI("https://raw.githubusercontent.com/google/fonts/main/ofl/$family/$encoded").toURL()
            val bytes = url.openConnection().apply {
                connectTimeout = 15000
                readTimeout = 20000
            }.getInputStream().use { it.readBytes() }
            require(bytes.size in 1000..4000000) { "Unexpected font asset size: $family/$remote" }
            if (remote.endsWith(".ttf")) {
                require(bytes.take(4) == listOf(0.toByte(), 1.toByte(), 0.toByte(), 0.toByte())) {
                    "Expected TrueType font: $family"
                }
            } else {
                require(bytes.toString(Charsets.UTF_8).contains("SIL OPEN FONT LICENSE", ignoreCase = true)) {
                    "Missing OFL notice: $family"
                }
            }
            target.writeBytes(bytes)
        }
        spineFontSources.forEach { (family, file) ->
            fetchFontAsset(family, file, root.resolve("$family.ttf"))
            fetchFontAsset(family, "OFL.txt", root.resolve("$family-OFL.txt"))
        }
    }
}

android {
    val releaseKeystorePath = providers.environmentVariable("AUDIOSHELF_KEYSTORE_PATH").orNull
    namespace = "com.mohamed.audioshelf"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.mohamed.audioshelf"
        minSdk = 26
        targetSdk = 35
        versionCode = 57
        versionName = "0.6.10"
    }

    sourceSets.getByName("main").assets.srcDir(spineFontAssets)
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        if (releaseKeystorePath != null) {
            create("release") {
                storeFile = file(releaseKeystorePath)
                storePassword = providers.environmentVariable("AUDIOSHELF_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("AUDIOSHELF_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("AUDIOSHELF_KEY_PASSWORD").get()
            }
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
        release {
            isMinifyEnabled = true
            if (releaseKeystorePath != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

tasks.named("preBuild").configure { dependsOn(prepareSpineFonts) }

dependencies {
    implementation("androidx.browser:browser:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.1")
    implementation("io.coil-kt:coil-compose:2.7.0")
    // Bundled, on-device text recognition for exact cover lettering; no cloud uploads.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.5.1")
    implementation("androidx.media3:media3-session:1.5.1")
    implementation("androidx.media3:media3-ui:1.5.1")
    implementation("androidx.compose.animation:animation")
    implementation("androidx.work:work-runtime-ktx:2.12.0")
    // Full Android libtorrent engine (Java bindings + all supported native ABIs).
    implementation("com.frostwire:jlibtorrent-android:2.0.15.3@aar")
    // Android AARs carry required JNI binaries: JNA's libjnidispatch.so and Vosk's libvosk.so.
    // The plain JNA JAR omits Android native binaries (see official vosk-android-demo).
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    implementation("com.alphacephei:vosk-android:0.3.75@aar")
    implementation("androidx.media3:media3-common:1.5.1")
    implementation("com.google.guava:guava:33.4.0-android")
    implementation("org.jsoup:jsoup:1.18.3")
    testImplementation("junit:junit:4.13.2")
}
