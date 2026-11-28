import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 正式版簽章資訊放在 release/keystore.properties（不進版本控制，請自行備份金鑰與密碼）
val releaseProps = Properties().apply {
    val f = rootProject.file("release/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.duo.browser"
    compileSdk = 35

    defaultConfig {
        // 2.0.0 起取代 1.x：同一個套件名稱、同一把簽章，直接覆蓋升級（分頁、書籤、設定都保留）
        applicationId = "com.CYT.duobrowser"
        minSdk = 29
        // 刻意停在 35：targetSdk 36 會強制啟用預測式返回，實體 B 鍵/返回鍵就收不到 KEYCODE_BACK
        // （在 itch.io 自行發行，不受 Google Play 的 targetSdk 規定限制）
        targetSdk = 35
        versionCode = 220
        versionName = "2.2.0"
    }

    signingConfigs {
        create("release") {
            if (releaseProps.isNotEmpty()) {
                storeFile = rootProject.file(releaseProps.getProperty("storeFile"))
                storePassword = releaseProps.getProperty("storePassword")
                keyAlias = releaseProps.getProperty("keyAlias")
                keyPassword = releaseProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            // 測試版用不同的套件名稱，可以和正式版同時安裝
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = false
            isDebuggable = false
            signingConfig = if (releaseProps.isNotEmpty()) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
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
    implementation("androidx.webkit:webkit:1.12.1")
}
