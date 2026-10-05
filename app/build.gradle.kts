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
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        // BuildConfig.DEBUG нужен проверкам контракта, а BuildConfig.HOT_LOGS - логам
        // внутри кадра: и то, и другое не должно стоить ничего в release.
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Держатели состояния логируют через android.util.Log; в юнит-тестах это заглушка,
            // которая без флага бросает исключение вместо возврата значения по умолчанию.
            isReturnDefaultValues = true
        }
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

/**
 * Проверяет, что слой не импортирует то, что ему запрещено.
 *
 * Типизированная задача с inputs/outputs, а не doLast в скрипте: иначе проверка гоняется
 * на каждый прогон check и не кешируется.
 */
@CacheableTask
abstract class CheckLayerPurityTask : DefaultTask() {

    /** Каталоги и отдельные файлы слоя. Коллекция, а не один каталог: слой presentation
     *  живёт не только в своём пакете, composition root лежит в корневом. */
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    /** Регулярное выражение по строке импорта. */
    @get:Input
    abstract val forbiddenImports: Property<String>

    /** Файлы, которым нарушение разрешено осознанно. Имена без пути. */
    @get:Input
    abstract val allowedFiles: SetProperty<String>

    @get:OutputFile
    abstract val report: RegularFileProperty

    @TaskAction
    fun check() {
        val regex = Regex(forbiddenImports.get())
        val allowed = allowedFiles.get()
        val offenders = sources.asFileTree.asSequence()
            .filter { it.isFile && it.extension == "kt" && it.name !in allowed }
            .flatMap { file ->
                file.readLines().asSequence().mapIndexedNotNull { index, line ->
                    if (regex.containsMatchIn(line.trim())) {
                        "${file.name}:${index + 1}: ${line.trim()}"
                    } else {
                        null
                    }
                }
            }
            .toList()
        val reportFile = report.get().asFile
        reportFile.parentFile.mkdirs()
        reportFile.writeText(offenders.joinToString("\n"))
        if (offenders.isNotEmpty()) {
            error("Слой загрязнён зависимостями:\n" + offenders.joinToString("\n"))
        }
    }
}

fun layerDir(layer: String) = layout.projectDirectory.dir("src/main/java/com/shevikina/panzoomdemo/$layer")

// Корневой пакет: Application и Activity. Это composition root, по смыслу presentation, но лежит
// вне его пакета. Шаблон `*.kt` не спускается в подкаталоги, поэтому берёт только эти файлы -
// и автоматически подхватит новые, если в корне что-то появится.
val rootPackageSources = fileTree("src/main/java/com/shevikina/panzoomdemo") { include("*.kt") }

// Ядро не должно зависеть от платформы: проверяем это сборкой, а не договорённостями.
val checkDomainPurity by tasks.registering(CheckLayerPurityTask::class) {
    group = "verification"
    description = "Проверяет, что domain-слой не зависит от Android, Compose и других слоёв"
    sources.from(layerDir("domain"))
    forbiddenImports.set("^import (android|androidx|com\\.shevikina\\.panzoomdemo\\.(data|presentation|core))\\b")
    report.set(layout.buildDirectory.file("reports/layer-purity/domain.txt"))
}

// Presentation не знает о data. Исключение ровно одно и документировано в самом файле.
// Корневой пакет проверяется здесь же: Activity и Application - тоже presentation, и запрет
// на data распространяется и на них.
val checkPresentationPurity by tasks.registering(CheckLayerPurityTask::class) {
    group = "verification"
    description = "Проверяет, что presentation и корневой пакет не импортируют data"
    sources.from(layerDir("presentation"), rootPackageSources)
    forbiddenImports.set("^import com\\.shevikina\\.panzoomdemo\\.data\\b")
    allowedFiles.set(setOf("DecodedImageKtx.kt"))
    report.set(layout.buildDirectory.file("reports/layer-purity/presentation.txt"))
}

tasks.named("check") {
    dependsOn(checkDomainPurity, checkPresentationPurity)
}

// Один JDK на весь проект вместо трёх (Java 11 в compileOptions, JDK 21 в тестах, JBR из
// Android Studio на сборке). JDK 21, а не 25: Robolectric лезет рефлексией в internals java.io
// и на 25 не стартует. Нужную версию Gradle скачает сам.
kotlin {
    jvmToolchain(21)
}

tasks.withType<Test>().configureEach {
    // Robolectric читает internals JDK рефлексией, с JDK 17+ модуль закрыт по умолчанию.
    jvmArgs(
        "--add-opens=java.base/java.io=ALL-UNNAMED",
        "--add-exports=java.base/jdk.internal.access=ALL-UNNAMED",
    )
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

    // Tests
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    // Robolectric нужен единственному тесту: маппер TileRect <-> android.graphics.Rect
    testImplementation(libs.robolectric)
}
