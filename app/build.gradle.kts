plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pkgmod"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.a9gcbgj.nina"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures {
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt"
            )
        }
    }
}

dependencies {
    implementation("com.android.tools.build:apksig:8.5.2")
    testImplementation("junit:junit:4.13.2")
}

// 首次构建时自动生成签名用的 keystore，改包产物都用它签名。
val signingKeystore = layout.projectDirectory.file("src/main/assets/signing.p12").asFile
val signingAlias = "pkgmod"
val signingStorePass = "pkgmod2019"

tasks.register<Exec>("generateSigningKeystore") {
    onlyIf { !signingKeystore.exists() }
    val keytool = File(System.getProperty("java.home") ?: "", "bin/keytool")
    doFirst {
        signingKeystore.parentFile?.mkdirs()
    }
    commandLine(
        keytool.absolutePath,
        "-genkeypair",
        "-keystore", signingKeystore.absolutePath,
        "-storetype", "PKCS12",
        "-alias", signingAlias,
        "-keyalg", "RSA",
        "-keysize", "2048",
        "-validity", "10000",
        "-storepass", signingStorePass,
        "-keypass", signingStorePass,
        "-dname", "CN=PkgMod, O=PkgMod, C=CN"
    )
    outputs.file(signingKeystore)
}

tasks.matching { it.name == "preBuild" }.configureEach {
    dependsOn("generateSigningKeystore")
}
