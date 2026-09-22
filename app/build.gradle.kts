plugins {
    id("com.android.application")
}

// The release build is R8-obfuscated. The entry class name survives because the
// framework instantiates it from java_init.list; every other member is renamed.
// For a plain, easily patchable build use: gradle assembleDebug
android {
    namespace = "com.fj.mhhook"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.fj.mhhook"
        minSdk = 24
        targetSdk = 34
        versionCode = 3
        versionName = "3.0"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("MH_KEYSTORE") ?: "mh.jks"
            val ksFile = rootProject.file(ksPath)
            if (ksFile.exists()) {
                storeFile = ksFile
                storePassword = System.getenv("MH_STORE_PASS") ?: "123456"
                keyAlias = System.getenv("MH_KEY_ALIAS") ?: "mh"
                keyPassword = System.getenv("MH_KEY_PASS") ?: "123456"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            val rel = signingConfigs.getByName("release")
            signingConfig = if (rel.storeFile != null) rel else signingConfigs.getByName("debug")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }

    lint {
        abortOnError = false
    }
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
