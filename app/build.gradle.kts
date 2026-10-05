import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingProperties = Properties().apply {
    val localProperties = rootProject.file("local.properties")
    if (localProperties.isFile) {
        localProperties.inputStream().use(::load)
    }
}

fun signingValue(propertyName: String, environmentName: String): String? =
    signingProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
        ?: System.getenv(environmentName)?.takeIf { it.isNotBlank() }

val releaseKeystoreFile = file("config/release.keystore")
val releaseStorePassword = signingValue("RELEASE_STORE_PASSWORD", "KEYSTORE_PASS")
val releaseKeyAlias = signingValue("RELEASE_KEY_ALIAS", "ALIAS_NAME")
val releaseKeyPassword = signingValue("RELEASE_KEY_PASSWORD", "ALIAS_PASS")
val hasReleaseSigning = releaseKeystoreFile.isFile &&
    releaseStorePassword != null && releaseKeyAlias != null && releaseKeyPassword != null

android {
    namespace = "com.kirikira.camdiss"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.kirikira.camdiss"
        minSdk = 30
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }

    val persistentSigning = if (hasReleaseSigning) {
        signingConfigs.create("persistent") {
            storeFile = releaseKeystoreFile
            storePassword = releaseStorePassword
            keyAlias = releaseKeyAlias
            keyPassword = releaseKeyPassword
            enableV3Signing = true
            enableV4Signing = true
        }
    } else {
        null
    }

    buildTypes {
        debug {
            persistentSigning?.let { signingConfig = it }
        }
        release {
            persistentSigning?.let { signingConfig = it }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources {
            excludes += setOf("META-INF/DEPENDENCIES", "META-INF/NOTICE*", "META-INF/LICENSE*")
        }
    }
}

gradle.taskGraph.whenReady {
    val releaseTaskRequested = allTasks.any {
        it.path.startsWith(":app:") && it.name.contains("Release")
    }
    if (releaseTaskRequested && !hasReleaseSigning) {
        throw GradleException(
            "Release signing material is missing. Restore app/config/release.keystore and RELEASE_* properties before building a release."
        )
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.compose.material3:material3:1.4.0")

    implementation("com.github.MuntashirAkon:libadb-android:3.1.1") {
        exclude(group = "org.bouncycastle", module = "bcprov-jdk15to18")
    }
    implementation("org.conscrypt:conscrypt-android:2.5.3")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.86")
}
