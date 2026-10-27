plugins {
    id("com.android.library")
}
android {
    namespace = "io.github.muntashirakon.adb"
    compileSdk = libs.versions.android.compileSdk.get().toInt()
    defaultConfig { minSdk = libs.versions.android.minSdk.get().toInt() }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}
dependencies {
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("org.bouncycastle:bcprov-jdk15to18:1.81")
    implementation("com.github.MuntashirAkon.spake2-java:spake2-android:2.2.1")
}
