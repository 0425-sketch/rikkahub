import com.android.build.api.dsl.Packaging
import com.android.build.api.variant.BuildConfigField
import com.google.firebase.crashlytics.buildtools.gradle.CrashlyticsExtension
import com.google.gms.googleservices.GoogleServicesTask
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.google.services)
    alias(libs.plugins.firebase.crashlytics)
    alias(libs.plugins.baselineprofile)
}

// An unsigned APK is useful for local compilation checks, but cannot replace an installed app.
// Personal builds must never silently fall back to a newly generated Android Debug key.
val personalUnsigned = providers.gradleProperty("personalUnsigned")
    .map(String::toBooleanStrict).getOrElse(false)
val personalSigningAvailable = providers.environmentVariable("CHATGPT_TEST_KEYSTORE")
    .orNull?.isNotBlank() == true
val verifyPersonalSigning = tasks.register("verifyPersonalSigning") {
    // Capture values inside task configuration; do not retain the Kotlin build-script object.
    val unsignedAllowed = personalUnsigned
    val signerAvailable = personalSigningAvailable
    doLast {
        check(unsignedAllowed || signerAvailable) {
            "Personal APK signing material is unavailable. Restore the existing fixed signer, " +
                "or use -PpersonalUnsigned=true to build an unsigned verification APK that cannot be installed. " +
                "No replacement signing key will be generated."
        }
        if (unsignedAllowed) {
            logger.lifecycle("Personal APK is explicitly UNSIGNED; it cannot be installed or used for an in-place update.")
        }
    }
}

// Keep normal JVM compilation/tests available without a signer; guard only APK/AAB production.
tasks.configureEach {
    if (name in setOf("packageGithubDebug", "packageGithubDebugBundle", "signGithubDebugBundle", "assembleGithubDebug", "bundleGithubDebug")) {
        dependsOn(verifyPersonalSigning)
    }
}

android {
    namespace = "me.rerere.rikkahub"
    compileSdk {
        version = release(37) {
            minorApiLevel = 2
        }
    }

    defaultConfig {
        applicationId = "me.rerere.rikkahub"
        minSdk = 26
        targetSdk = 37
        versionCode = 197
        versionName = "3.3.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    splits {
        abi {
            // AppBundle tasks usually contain "bundle" in their name
            //noinspection WrongGradleMethod
            val isBuildingBundle = gradle.startParameter.taskNames.any { it.lowercase().contains("bundle") }
            isEnable = !isBuildingBundle
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = true
        }
    }

    signingConfigs {
        // Fixed personal signing is restored from durable Actions secrets before release builds.
        // Local compile/test tasks do not need a signer; personal APK creation is guarded above.
        providers.environmentVariable("CHATGPT_TEST_KEYSTORE").orNull?.takeIf(String::isNotBlank)?.let { keystorePath ->
            create("chatgptTest") {
                storeFile = file(keystorePath)
                storePassword = providers.environmentVariable("CHATGPT_TEST_STORE_PASSWORD").get()
                keyAlias = providers.environmentVariable("CHATGPT_TEST_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("CHATGPT_TEST_KEY_PASSWORD").get()
            }
        }
        create("release") {
            val localProperties = Properties()
            val localPropertiesFile = rootProject.file("local.properties")

            if (localPropertiesFile.exists()) {
                localProperties.load(FileInputStream(localPropertiesFile))

                val storeFilePath = localProperties.getProperty("storeFile")
                val storePasswordValue = localProperties.getProperty("storePassword")
                val keyAliasValue = localProperties.getProperty("keyAlias")
                val keyPasswordValue = localProperties.getProperty("keyPassword")

                if (storeFilePath != null && storePasswordValue != null &&
                    keyAliasValue != null && keyPasswordValue != null
                ) {
                    storeFile = file(storeFilePath)
                    storePassword = storePasswordValue
                    keyAlias = keyAliasValue
                    keyPassword = keyPasswordValue
                }
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = true
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
        debug {
            applicationIdSuffix = ".debug"
            // Select signing per variant below so personal builds never create a debug key.
            signingConfig = null
            configure<CrashlyticsExtension> {
                mappingFileUploadEnabled = false
                nativeSymbolUploadEnabled = false
            }
            buildConfigField("String", "VERSION_NAME", "\"${android.defaultConfig.versionName}\"")
            buildConfigField("String", "VERSION_CODE", "\"${android.defaultConfig.versionCode}\"")
        }
    }
    // 按分发渠道区分：github 是 GitHub Release / 每日构建的 APK，play 是上架 Google Play 的 AAB
    // 两个渠道共用同一个 applicationId，Play 版不能自更新，相关入口通过 BuildConfig.IS_PLAY_STORE 关闭
    flavorDimensions += "distribution"
    productFlavors {
        create("github") {
            dimension = "distribution"
            isDefault = true
            buildConfigField("boolean", "IS_PLAY_STORE", "false")
        }
        create("play") {
            dimension = "distribution"
            buildConfigField("boolean", "IS_PLAY_STORE", "true")
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
    sourceSets {
        getByName("androidTest").assets.directories.add("$projectDir/schemas")
        getByName("test").resources.directories.addAll(
            listOf("$projectDir/schemas", "$projectDir/src/androidTest/assets")
        )
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        unitTests.all {
            it.jvmArgs(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "--add-opens=java.base/java.util=ALL-UNNAMED",
                "--add-opens=java.base/java.io=ALL-UNNAMED",
                "--add-opens=java.base/java.net=ALL-UNNAMED",
                "--add-opens=java.base/java.security=ALL-UNNAMED",
                "--add-opens=java.base/java.text=ALL-UNNAMED",
                "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
            )
        }
    }
    androidResources {
        generateLocaleConfig = true
        // 只保留 app 自己翻译过的语言，依赖库（AndroidX、GMS 等）带的其他语言资源不打包
        localeFilters += listOf("en", "ar", "de", "ja", "ko", "ko-rKR", "ru", "zh", "zh-rTW")
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            // BouncyCastle 的 Picnic 后量子签名查找表（约 1.2MB），JSch 用不到
            excludes += "org/bouncycastle/pqc/crypto/picnic/**"
        }
    }
    tasks.withType<KotlinCompile>().configureEach {
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3Api")
        compilerOptions.optIn.add("androidx.compose.material3.ExperimentalMaterial3ExpressiveApi")
        compilerOptions.optIn.add("androidx.compose.material3.adaptive.ExperimentalMaterial3AdaptiveApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalAnimationApi")
        compilerOptions.optIn.add("androidx.compose.animation.ExperimentalSharedTransitionApi")
        compilerOptions.optIn.add("androidx.compose.foundation.ExperimentalFoundationApi")
        compilerOptions.optIn.add("androidx.compose.foundation.layout.ExperimentalLayoutApi")
        compilerOptions.optIn.add("kotlin.uuid.ExperimentalUuidApi")
        compilerOptions.optIn.add("kotlin.time.ExperimentalTime")
        compilerOptions.optIn.add("kotlinx.coroutines.ExperimentalCoroutinesApi")
        compilerOptions.optIn.add("androidx.navigation3.runtime.ExperimentalNavigation3Api")
    }
}

// Personal debug builds do not initialize Firebase or need a Firebase project config.
// Release variants still require their real google-services.json.
tasks.withType<GoogleServicesTask>().configureEach {
    if (name.endsWith("DebugGoogleServices")) {
        enabled = false
    }
}

// 两个渠道的启动路径一样，profile 合并到 src/main 共用，不按变体各存一份
baselineProfile {
    mergeIntoMain = true
}

// baselineprofile 插件按 release 复制出 nonMinifiedRelease 时只关掉 isMinifyEnabled，关不掉 optimization，
// 不手动关的话生成用的包会被 R8 内联、合并类，profile 里记下的是优化后的类和方法
androidComponents {
    onVariants(selector().withBuildType("debug").withFlavor("distribution" to "github")) { variant ->
        // A distinct identity keeps the previously installed app and all of its data intact.
        // Durable signing and two clean restore builds are required before publication.
        variant.applicationId.set("me.rerere.rikkahub.personal")
        variant.outputs.forEach { output ->
            output.versionCode.set(201)
            output.versionName.set("3.3.0-personal.1")
        }
        variant.buildConfigFields?.put("VERSION_NAME", BuildConfigField("String", "\"3.3.0-personal.1\"", null))
        variant.buildConfigFields?.put("VERSION_CODE", BuildConfigField("String", "\"201\"", null))
        if (!personalUnsigned) {
            android.signingConfigs.findByName("chatgptTest")?.let { signingConfig ->
                checkNotNull(variant.signingConfig) { "AGP did not expose signing configuration for the personal APK." }
                    .from(signingConfig)
            }
        }
    }
    onVariants(selector().withBuildType("debug").withFlavor("distribution" to "play")) { variant ->
        checkNotNull(variant.signingConfig) { "AGP did not expose signing configuration for the Play debug APK." }
            .from(android.signingConfigs.getByName("debug"))
    }
    finalizeDsl { extension ->
        extension.buildTypes.findByName("nonMinifiedRelease")?.optimization?.enable = false
    }
}

composeCompiler {
    stabilityConfigurationFiles.add(
        project.layout.projectDirectory.file("compose_compiler_config.conf")
    )
}

tasks.register("buildAll") {
    dependsOn("assembleGithubRelease", "bundlePlayRelease")
    description = "Build GitHub APK and Play AAB"
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// kotlin-reflect 只由 ktor-server-core 传递引入，用到它的只有按配置加载模块、开发模式热重载等用不到的路径。
// 它在 classpath 上时 stdlib 的 Reflection 会 Class.forName 加载 ReflectionFactoryImpl，R8 因此保留整个实现（约 0.7MB dex）
configurations.matching { it.name.endsWith("RuntimeClasspath") }.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-reflect")
}

// Local JVM tests need the desktop native library instead of the Android AAR.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

dependencies {
    implementation("com.nimbusds:nimbus-jose-jwt:10.5")
    implementation(libs.quickjs)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.profileinstaller)
    implementation(libs.termux.terminal.view) {
        // terminal-emulator 由仓库内的 :terminal 模块提供, 不再引入上游 aar
        exclude(group = "com.termux.termux-app", module = "terminal-emulator")
    }
    implementation(libs.guava.listenablefuture)

    // Compose
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material3.adaptive)
    implementation(libs.androidx.material3.adaptive.layout)

    // Navigation 3
    implementation(libs.androidx.navigation3.runtime)
    implementation(libs.androidx.navigation3.ui)
    implementation(libs.androidx.lifecycle.viewmodel.navigation3)
    implementation(libs.androidx.material3.adaptive.navigation3)

    // Firebase
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.analytics)
    implementation(libs.firebase.crashlytics)

    // DataStore
    implementation(libs.androidx.datastore.preferences)

    // EXIF orientation for picked images
    implementation(libs.androidx.exifinterface)

    // Image metadata extractor
    // https://github.com/drewnoakes/metadata-extractor
    implementation(libs.metadata.extractor)

    // Haze (background blur and glass)
    implementation(libs.haze)
    implementation(libs.haze.blur)
    implementation(libs.haze.blur.material3)
    implementation(libs.haze.glass)
    implementation(libs.haze.glass.material3)

    // koin
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.android)
    implementation(libs.koin.compose)
    implementation(libs.koin.androidx.workmanager)

    // okhttp
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)
    implementation(libs.retrofit)
    implementation(libs.retrofit.serialization.json)

    // ktor client
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    // pebble (template engine)
    implementation(libs.pebble)

    // java-diff-utils (unified diff)
    implementation(libs.diffutils)

    // coil
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.coil.okhttp)
    implementation(libs.coil.svg)
    implementation(libs.coil.cache.control)

    // serialization
    implementation(libs.kotlinx.serialization.json)

    // YAML front matter
    implementation(libs.snakeyaml)

    // zxing + CameraX (qrcode scanner)
    implementation(libs.zxing.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.compose)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.room.paging)
    baselineProfile(project(":app:baselineprofile"))
    ksp(libs.androidx.room.compiler)

    // Paging3
    implementation(libs.androidx.paging.runtime)
    implementation(libs.androidx.paging.compose)

    // media3 (video playback)
    implementation(libs.androidx.media3.exoplayer)
    implementation(libs.androidx.media3.ui)

    // Apache Commons Text
    implementation(libs.commons.text)

    // Reorderable (https://github.com/Calvin-LL/Reorderable/)
    implementation(libs.reorderable)

    // icons
    implementation(libs.huge.icons)

    // image viewer
    implementation(libs.image.viewer)

    // JLatexMath
    // https://github.com/re-ovo/bettertext
    implementation(platform(libs.bettertext.bom))
    implementation(libs.bettertext.text)
    implementation(libs.bettertext.markdown)
    implementation(libs.bettertext.latex)
    implementation(libs.bettertext.highlight)

    // mcp
    implementation(libs.modelcontextprotocol.kotlin.sdk)

    // jmDNS (mDNS/Bonjour for .local hostname)
    implementation(libs.jmdns)

    // SLF4J Android binding — routes Ktor/SLF4J logs to logcat
    implementation(libs.slf4j.api)
    implementation(libs.slf4j.android)

    // sqlite-android (requery SQLite for Android)
    implementation(libs.sqlite.android)

    // modules
    implementation(project(":ai"))
    implementation(project(":web"))
    implementation(project(":document"))
    implementation(project(":search"))
    implementation(project(":speech"))
    implementation(project(":mediagen"))
    implementation(project(":common"))
    implementation(project(":material3"))
    implementation(project(":ui"))
    implementation(project(":workspace"))
    implementation(project(":terminal"))
    implementation(project(":oauth"))
    implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.jar", "*.aar"))))

    // Leak Canary
    // debugImplementation(libs.leakcanary.android)

    // tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.robolectric:robolectric:4.18")
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    androidTestImplementation(libs.androidx.room.testing)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)
}
