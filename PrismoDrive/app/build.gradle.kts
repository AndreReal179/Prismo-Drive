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

// Atualização automática: repositório do GitHub onde ficam os Releases (dono/repo).
// O workflow preenche sozinho; pode ser trocado pela variável REPO_ATUALIZACAO.
val repoAtualizacao: String = (System.getenv("REPO_ATUALIZACAO") ?: "").trim()
    .ifBlank { (System.getenv("GITHUB_REPOSITORY") ?: "").trim() }

// versionCode sobe sozinho a cada build (minutos desde 01/11/2026), sempre maior que o anterior.
// O workflow envia o mesmo número em VERSION_CODE para escrever no Release.
val codigoVersao: Int = (System.getenv("VERSION_CODE") ?: "").toIntOrNull()
    ?: (100 + (System.currentTimeMillis() / 60000L - 29891520L)).toInt()

android {
    namespace = "com.prismo.drive"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.prismo.drive"
        minSdk = 24
        targetSdk = 34
        // versionName é a versão BASE (mínima). O GitHub completa sozinho: 1.9.2, 1.9.3…
        // Para começar uma versão nova (1.8.1, 1.8.2…), troque só o número aqui.
        versionName = "1.9.5"
        // versionCode é automático (sempre maior que o da v1.6 = 29)
        versionCode = codigoVersao
        System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() }?.let { versionName = it }
        buildConfigField("String", "UPDATE_REPO", "\"$repoAtualizacao\"")
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
    // Conta: "Entrar com Google" nativo (o Google não permite login dentro de WebView)
    implementation("androidx.credentials:credentials:1.3.0")
    implementation("androidx.credentials:credentials-play-services-auth:1.3.0")
    implementation("com.google.android.libraries.identity.googleid:googleid:1.1.1")
}

// Novidades do app: antes de cada build monta assets/novidades.js com a versão atual (NOVIDADES.md),
// as notas de todas as versões já publicadas (Releases do GitHub) e o histórico (novidades/historico.json).
// Assim nenhuma versão antiga some da aba Novidades, mesmo que o workflow não tenha o passo próprio.
val gerarNovidades by tasks.registering(Exec::class) {
    val versao = System.getenv("VERSION_NAME")?.takeIf { it.isNotBlank() } ?: "dev"
    workingDir = rootProject.projectDir
    commandLine("sh", "-c", "python3 novidades/gerar.py \"$versao\" || echo 'novidades: python3 indisponível, mantendo o arquivo atual'")
    isIgnoreExitValue = true
    onlyIf { System.getenv("VERSION_NAME")?.isNotBlank() == true }   // só no GitHub (build com versão)
}
tasks.matching { it.name == "preBuild" }.configureEach { dependsOn(gerarNovidades) }
