plugins {
    id("com.android.application")
}

android {
    namespace = "me.mallows.komado.probe"
    compileSdk = 37

    defaultConfig {
        applicationId = "me.mallows.komado.probe"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("io.getstream:stream-webrtc-android:1.3.10")
}
