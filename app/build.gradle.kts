plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.chevstrap.rbx"

    compileSdk {
        version = release(37) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.chevstrap.extended"

        minSdk = 26
        targetSdk = 37

        versionCode = 201
        versionName = "2.1"
    }

    //buildFeatures {
    //    buildConfig = true
    //}
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(projects.gateway)
    implementation(projects.common)
}