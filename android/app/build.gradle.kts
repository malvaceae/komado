plugins {
    id("com.android.application")
}

dependencies {
    implementation(project(":core"))
    implementation(project(":security"))
    implementation("io.getstream:stream-webrtc-android:1.3.10")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

android {
    namespace = "me.mallows.komado"
    compileSdk = 37
    defaultConfig {
        applicationId = "me.mallows.komado"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
