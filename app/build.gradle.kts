plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.rsrrccn.headcounter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.rsrrccn.headcounter"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    androidResources {
        noCompress += "tflite"
    }
}

// Downloads the person-detection model (about 7 MB) the first time you build
val modelFile = layout.projectDirectory.file("src/main/assets/efficientdet_lite0.tflite").asFile
val downloadModel by tasks.registering {
    doLast {
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            val url = "https://storage.googleapis.com/mediapipe-models/object_detector/" +
                "efficientdet_lite0/float16/1/efficientdet_lite0.tflite"
            uri(url).toURL().openStream().use { input ->
                modelFile.outputStream().use { out -> input.copyTo(out) }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModel) }

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")

    val camerax = "1.4.1"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    // MediaPipe object detector (finds people; model is bundled in the app, works offline)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
