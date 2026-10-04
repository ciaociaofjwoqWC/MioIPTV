plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.miaiptv"
    compileSdk = 35
    defaultConfig {
        // Stesso id dell'app di prima: la nuova versione la sostituisce.
        applicationId = "com.example.miaiptv"
        minSdk = 21
        targetSdk = 34
        // Su GitHub ogni build ha un numero più alto, così si installa sopra la precedente.
        versionCode = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
        versionName = "2.0." + (System.getenv("GITHUB_RUN_NUMBER") ?: "0")
    }
    // Firma fissa: senza, ogni build di GitHub avrebbe una firma diversa e
    // Android rifiuterebbe di aggiornare l'app ("App non installata").
    signingConfigs {
        create("spij") {
            storeFile = file("spijtv.keystore")
            storePassword = "spijtv123"
            keyAlias = "spijtv"
            keyPassword = "spijtv123"
        }
    }
    buildTypes {
        debug { signingConfig = signingConfigs.getByName("spij") }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("spij")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = false }
}
