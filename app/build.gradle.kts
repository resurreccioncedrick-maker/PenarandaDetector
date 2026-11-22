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
        noCompress += "onnx"
    }
}

// Downloads the crowd person-detection model (about 12 MB) the first time you build
val modelFile = layout.projectDirectory.file("src/main/assets/crowd_model.onnx").asFile
val downloadModel by tasks.registering {
    doLast {
        if (!modelFile.exists()) {
            modelFile.parentFile.mkdirs()
            val url = "https://github.com/yakhyo/people-tracker/releases/download/weights/yolov8n.onnx"
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

    // Runs the crowd detection model on the phone (offline)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.19.2")

    // Modern look (buttons, cards, dialogs)
    implementation("com.google.android.material:material:1.12.0")
}
