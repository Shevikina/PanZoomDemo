plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.shevikina.panzoomdemo"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.shevikina.panzoomdemo"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    // Логи внутри кадра включаются сборкой, а не правкой кода: в debug они нужны,
    // на release идут замеры, и лишние строки в кадре там только мешают.
    // Разовое исключение: ./gradlew :app:assembleRelease -Ppanzoom.hotLogs=true
    val hotLogsOverride = providers.gradleProperty("panzoom.hotLogs").map(String::toBoolean)

    buildTypes {
        debug {
            buildConfigField("boolean", "HOT_LOGS", hotLogsOverride.getOrElse(true).toString())
        }
        release {
            buildConfigField("boolean", "HOT_LOGS", hotLogsOverride.getOrElse(false).toString())
            // Ресурсы не ужимаем: ассеты демо и так лежат в assets, а лишний шаг перед
            // докладом только добавляет риск.
            // optimization { enable = true } из AGP 9 требует флага android.r8.gradual.support,
            // это другая, постепенная схема включения R8; нам нужен обычный шринк.
            isMinifyEnabled = true
            // Без proguardFiles R8 отработал бы вообще без дефолтных правил AGP.
            // Проверка, что он реально отработал: после assembleRelease должен появиться
            // app/build/outputs/mapping/release/mapping.txt.
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Debug-подпись для локальных замеров: без signingConfig assembleRelease
            // даёт -unsigned.apk, который adb install откажется ставить.
            // Для публикации это не годится, см. запрет на сборку release на CI ниже.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG нужен проверкам контракта, а BuildConfig.HOT_LOGS - логам
        // внутри кадра: и то, и другое не должно стоить ничего в release.
        buildConfig = true
    }
}

// release подписан отладочным ключом и годится только для локальных замеров. Чтобы такой
// артефакт не уехал из CI, запрещаем собирать его там. Проверка на уровне задачи, а не блока
// buildTypes: иначе на CI падала бы конфигурация проекта, то есть и обычный :app:check.
val isCi = providers.environmentVariable("CI").isPresent
tasks.matching { it.name == "assembleRelease" || it.name == "bundleRelease" }.configureEach {
    // Значение снимаем в локальную переменную на этапе конфигурации: иначе действие задачи
    // утащило бы за собой ссылку на скрипт, а её configuration cache сериализовать не умеет.
    val failOnCi = isCi
    doFirst {
        check(!failOnCi) { "release подписан debug-ключом и собирается только локально" }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // ViewModel
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)

    // JankStats
    implementation(libs.androidx.metrics.performance)

    // DI
    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.navigation.compose)
    ksp(libs.hilt.compiler)
}
