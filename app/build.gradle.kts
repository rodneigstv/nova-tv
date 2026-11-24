import java.util.Properties

plugins {
    id("com.android.application")
}

// APK público (Release do GitHub): gradlew assembleRelease -Ppublico
// Sai sem as chaves do segredos.properties e sem o histórico pessoal da Netflix.
val publico = providers.gradleProperty("publico").isPresent

val segredos = Properties().apply {
    val f = rootProject.file("segredos.properties")
    if (!publico && f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.exemplo.novatv"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.exemplo.novatv"
        minSdk = 28
        targetSdk = 36
        versionCode = 21
        versionName = "1.9"
        buildConfigField("String", "TMDB_KEY", "\"${segredos.getProperty("TMDB_KEY", "")}\"")
        buildConfigField("String", "YT_CLIENT_ID", "\"${segredos.getProperty("YT_CLIENT_ID", "")}\"")
        buildConfigField("String", "YT_CLIENT_SECRET", "\"${segredos.getProperty("YT_CLIENT_SECRET", "")}\"")
        buildConfigField("String", "NETFLIX_PERFIL", "\"${segredos.getProperty("NETFLIX_PERFIL", "")}\"")
    }
    androidResources {
        if (publico) ignoreAssetsPatterns += "!netflix_historico.csv"
    }
    buildFeatures { buildConfig = true }
    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
