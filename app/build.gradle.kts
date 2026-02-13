plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Load keystore properties from local file (not in git)
// If not present or invalid, build will use default debug keystore
val keystorePropertiesFile = rootProject.file("keystore.properties")
val useCustomKeystore = keystorePropertiesFile.exists() && run {
    try {
        val props = java.util.Properties()
        props.load(keystorePropertiesFile.inputStream())
        props.containsKey("storeFile") && props.containsKey("storePassword")
    } catch (e: Exception) {
        false
    }
}

android {
    namespace = "de.koshi.photodream"
    compileSdk = 36

    // Signing config - only if keystore.properties exists and is valid
    if (useCustomKeystore) {
        val keystoreProperties = java.util.Properties().apply {
            load(keystorePropertiesFile.inputStream())
        }
        signingConfigs {
            create("release") {
                storeFile = file(keystoreProperties["storeFile"] as String)
                storePassword = keystoreProperties["storePassword"] as String
                keyAlias = keystoreProperties["keyAlias"] as String
                keyPassword = keystoreProperties["keyPassword"] as String
            }
        }
    }

    defaultConfig {
        applicationId = "de.koshi.photodream"
        minSdk = 23
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        debug {
            if (useCustomKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (useCustomKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.constraintlayout)
    
    // Networking
    implementation(libs.retrofit)
    implementation(libs.retrofit.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)
    
    // Image loading
    implementation(libs.glide)
    
    // HTTP Server (for HA communication)
    implementation(libs.nanohttpd)
    
    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    
    // Lifecycle
    implementation(libs.lifecycle.runtime)
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}