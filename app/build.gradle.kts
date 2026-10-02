plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "vn.bongdaplus.reader"
    compileSdk = 34

    defaultConfig {
        applicationId = "vn.bongdaplus.reader"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    // Keystore dùng chung cho cả debug + release (file thật do CI giải mã
    // từ secret ANDROID_KEYSTORE_BASE64, local dev tự có file tương ứng).
    val ksFile = rootProject.file("bongdaplus-release.p12")
    val ksPass = System.getenv("KEYSTORE_PASSWORD")
        ?: project.findProperty("KEYSTORE_PASSWORD")?.toString().orEmpty()
    val ksAlias = System.getenv("KEY_ALIAS")
        ?: project.findProperty("KEY_ALIAS")?.toString()?.ifBlank { null } ?: "bongdaplus"
    val ksKeyPass = System.getenv("KEY_PASSWORD")
        ?: project.findProperty("KEY_PASSWORD")?.toString()?.ifBlank { null } ?: ksPass
    val hasKs = ksFile.exists() && ksPass.isNotEmpty()

    signingConfigs {
        create("shared") {
            if (hasKs) {
                storeFile = ksFile
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
                storeType = "PKCS12"
            }
        }
    }

    buildTypes {
        debug {
            if (hasKs) signingConfig = signingConfigs.getByName("shared")
        }
        release {
            if (hasKs) signingConfig = signingConfigs.getByName("shared")
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.material:material") // pull-to-refresh ổn định
    implementation("androidx.navigation:navigation-compose:2.7.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.3")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.webkit:webkit:1.11.0")

    // HTML scraping bongdaplus.vn (không có API công khai)
    implementation("org.jsoup:jsoup:1.17.2")
    // HTTP client (thay transport WebView): cookie đồng bộ với WebView login
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Load ảnh
    implementation("io.coil-kt:coil-compose:2.6.0")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
