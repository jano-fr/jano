plugins { id("com.android.application"); id("org.jetbrains.kotlin.android"); id("org.jetbrains.kotlin.plugin.compose") }
android {
 namespace = "fr.listes.beta"
 compileSdk = 35
 defaultConfig { applicationId = "fr.listes.filtre"; minSdk = 26; targetSdk = 35; versionCode = 17; versionName = "0.7.0-beta.1" }
 buildFeatures { compose = true }
 compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
 kotlinOptions { jvmTarget = "17" }
}
dependencies {
 implementation(platform("androidx.compose:compose-bom:2024.12.01"))
 implementation("androidx.activity:activity-compose:1.9.3")
 implementation("androidx.compose.material3:material3")
 implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
 implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
 implementation("com.squareup.okhttp3:okhttp:4.12.0")
 testImplementation("junit:junit:4.13.2")
 testImplementation("org.json:json:20240303")
}
