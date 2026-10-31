plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.guaner.jsonviewer"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.guaner.jsonviewer"
        minSdk = 24
        targetSdk = 34
        versionCode = 7
        versionName = "1.4.1"
    }

    signingConfigs {
        create("release") {
            // 优先读环境变量，其次用工程根目录的 release.jks（不提交仓库）
            val path = System.getenv("JSONVIEWER_KEYSTORE")
                ?: rootProject.file("release.jks").takeIf { it.exists() }?.absolutePath
            if (path != null) {
                storeFile = file(path)
                storePassword = System.getenv("JSONVIEWER_STORE_PASSWORD") ?: "jsonviewer2024"
                keyAlias = System.getenv("JSONVIEWER_KEY_ALIAS") ?: "jsonviewer"
                keyPassword = System.getenv("JSONVIEWER_KEY_PASSWORD") ?: "jsonviewer2024"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 没有密钥时退回 debug 签名，保证 CI 产物可直接安装
            signingConfig = if (signingConfigs.getByName("release").storeFile != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
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
        viewBinding = true
    }
    lint {
        // 发布包不因 lint 提示中断；lint 通过独立任务运行
        checkReleaseBuilds = false
        abortOnError = false
    }
    packaging {
        resources {
            excludes += setOf(
                "/META-INF/{AL2.0,LGPL2.1}",
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE",
                "META-INF/LICENSE.txt",
                "META-INF/NOTICE",
                "META-INF/NOTICE.txt",
                "META-INF/*.kotlin_module"
            )
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.drawerlayout:drawerlayout:1.1.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    implementation("com.fasterxml.jackson.core:jackson-core:2.15.3")
    implementation("com.jayway.jsonpath:json-path:2.8.0")

    // 代码编辑器：自绘增量渲染，几 MB 文本下输入/删除仍然跟手。
    // 0.23.6 是中央仓库上的稳定版（0.24.x 只在快照仓库）
    implementation("io.github.Rosemoe.sora-editor:editor:0.23.6")

    testImplementation("junit:junit:4.13.2")
}
