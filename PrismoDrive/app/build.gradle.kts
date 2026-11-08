plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Assinatura: lida das variáveis de ambiente (preenchidas pelos GitHub Secrets no workflow).
// Sem elas, o build de release sai assinado com a chave de debug (só para teste).
val ksPath: String = System.getenv("KEYSTORE_FILE") ?: ""
val ksPass: String = System.getenv("KEYSTORE_PASSWORD") ?: ""
val ksAlias: String = (System.getenv("KEY_ALIAS") ?: "").ifBlank { "prismo" }
val ksKeyPass: String = (System.getenv("KEY_PASSWORD") ?: "").ifBlank { ksPass }
val temAssinatura: Boolean = ksPath.isNotBlank() && file(ksPath).exists() && ksPass.isNotBlank()

android {
    namespace = "com.prismo.drive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prismo.drive"
        minSdk = 24
        targetSdk = 34
        // versionCode precisa ser MAIOR que o do APK instalado (v1.6 = 29)
        versionCode = 30
        versionName = "1.7"
    }

    signingConfigs {
        if (temAssinatura) {
            create("prismo") {
                storeFile = file(ksPath)
                storePassword = ksPass
                keyAlias = ksAlias
                keyPassword = ksKeyPass
                if (ksPath.endsWith(".p12", true) || ksPath.endsWith(".pfx", true)) storeType = "pkcs12"
                enableV1Signing = true
                enableV2Signing = true
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            signingConfig = if (temAssinatura) signingConfigs.getByName("prismo")
                            else signingConfigs.getByName("debug")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        buildConfig = true
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/AL2.0", "META-INF/LGPL2.1")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
}
