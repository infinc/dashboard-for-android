import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

// 署名情報は keystore.properties から読む。ファイルが無ければ release 署名設定を作らない
// （= デバッグ運用のみ。Phase 6 で keystore を作った時点で自動的に有効になる）。
val keystoreProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

/**
 * バージョンは直下の VERSION（1 行。"v1.2.4-20261010" の形）だけで管理する。
 * 設定の「情報」（BuildConfig.VERSION_LABEL）・APK の versionName / versionCode はすべてここから取る。
 * versionCode は先頭の 3 つの数から作る（v1.2.4 → 10204）。上げるときは VERSION を書き換えるだけでよい。
 */
val versionLabel: String = rootProject.file("VERSION").readText().trim().also {
    require(Regex("""v\d+\.\d+\.\d+(-[0-9A-Za-z._-]+)?""").matches(it)) { "VERSION の形が違います: '$it'（例: v1.2.4-20261010）" }
}
val versionNumbers: List<Int> = Regex("""v(\d+)\.(\d+)\.(\d+)""").find(versionLabel)!!.destructured.toList().map(String::toInt)

android {
    namespace = "app.dashboard"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.dashboard"
        minSdk = 24
        // targetSdk 35 固定: Android 16 (API 36) の挙動変更を Phase 2 のゲート通過まで踏まない。
        // 通過後に 36 へ上げ、Phase 2 の合格条件を再度流す。
        targetSdk = 35
        versionCode = versionNumbers[0] * 10000 + versionNumbers[1] * 100 + versionNumbers[2]
        versionName = versionLabel.removePrefix("v")
        buildConfigField("String", "VERSION_LABEL", "\"$versionLabel\"")
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.findByName("release")
        }
        debug {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        // minSdk 24 で java.time 等を使うため
        isCoreLibraryDesugaringEnabled = true
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    sourceSets.named("main") {
        kotlin.srcDir("src/main/kotlin")
    }
    sourceSets.named("test") {
        kotlin.srcDir("src/test/kotlin")
    }
    sourceSets.named("androidTest") {
        kotlin.srcDir("src/androidTest/kotlin")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/INDEX.LIST",
            "META-INF/DEPENDENCIES",
            "META-INF/LICENSE*",
            "META-INF/NOTICE*",
            "META-INF/*.kotlin_module",
            "META-INF/versions/9/previous-compilation-data.bin",
        )
    }

    // 単体テスト（src/test、JVM で動く）と、Compose の UI テスト（src/androidTest、実機・エミュレーターで動く）
    testOptions {
        // android.util.Log などを呼んでも落ちないようにする（値は既定値を返す）
        unitTests.isReturnDefaultValues = true
    }

    lint {
        abortOnError = false
    }
}

// デバッグ版の APK を作るたびに（assembleDebug・Android Studio の「Build APK」）、
// リポジトリの直下に dashboard.apk として写す。端末へ入れるときに build/ の奥を探さなくて済むように。
val apkToRoot = tasks.register("apkToRoot") {
    val apk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")
    val out = rootProject.layout.projectDirectory.file("dashboard.apk")
    inputs.file(apk)
    outputs.file(out)
    doLast { apk.get().asFile.copyTo(out.asFile, overwrite = true) }
}
tasks.matching { it.name == "assembleDebug" }.configureEach { finalizedBy(apkToRoot) }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk.libs)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.client.android)
    implementation(libs.ktor.client.content.negotiation)
    // CalDAV（iCloud カレンダー）の PROPFIND / REPORT 用。Ktor の Android エンジン（HttpURLConnection）は
    // 標準外のメソッドを送れないため、ここだけ OkHttp を使う
    implementation(libs.okhttp)

    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.ktor.client.mock)

    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
