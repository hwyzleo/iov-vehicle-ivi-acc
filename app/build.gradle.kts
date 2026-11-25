plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

/** Build variant names, kept in sync with `acc-data-sec` (D-010). */
val identityDimension = "identity"
val identitySecFlavor = "identitySec"
val identityLocalFlavor = "identityLocal"

// APK 产物名：ivi-acc-<variant>.apk（默认会使用模块名 app）。
base {
    archivesName.set("ivi-acc")
}

android {
    namespace = "net.hwyz.iov.vehicle.ivi.acc.demo"
    compileSdk = 34

    /**
     * D-010「构建变体二选一」：设备身份来源在编译期确定，禁止运行期并存。
     *
     * - `identitySec`（默认）：令牌由车端统一密码学服务签发，ACC 不持有、不读取设备密钥；
     * - `identityLocal`：迁移期本地实现，仅为未安装统一服务的设备/仿真台架保留，阶段三删除。
     */
    flavorDimensions += identityDimension
    productFlavors {
        create(identitySecFlavor) {
            dimension = identityDimension
            isDefault = true
        }
        create(identityLocalFlavor) {
            dimension = identityDimension
        }
    }

    defaultConfig {
        applicationId = "net.hwyz.iov.vehicle.ivi.acc.demo"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"

        // Real gateway wiring (DSN-CR-002): the car talks to EDD-SGW only, no scripted fake.
        buildConfigField("String", "SGW_BASE_URL", "\"https://sgw.openiov.top\"")
        buildConfigField("String", "CIAM_CLIENT_ID", "\"ivi-capp\"")
        buildConfigField("String", "DEVICE_KEY_ALIAS", "\"ivi-acc-device\"")
        buildConfigField("String", "DEMO_VIN", "\"LVINACC0000000001\"")
        // 临时联调用设备 SN：非空时覆盖 ro.serialno 的读取结果（见 DemoEnvironment）。
        // 传空字符串即可恢复真实来源：-PdevSerialOverride=
        buildConfigField(
            "String",
            "DEVICE_SN_OVERRIDE",
            "\"${(project.findProperty("devSerialOverride") as String?) ?: "00000005AA00000001"}\"",
        )
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = true
        viewBinding = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":acc-domain"))
    // Device identity seam: the library resolves the matching flavour variant automatically.
    "${identitySecFlavor}Implementation"(project(":acc-data-sec"))
    "${identityLocalFlavor}Implementation"(project(":acc-data-sec"))
    implementation(project(":acc-data-local"))
    implementation(project(":acc-data-cloud"))
    implementation(project(":acc-platform"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.constraintlayout)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    testImplementation(libs.androidx.datastore.preferences)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
}

/**
 * D-018 构建门禁：双 flavor 均可构建，且 identityLocal 运行期依赖不含 sec-api（AIDL 契约）。
 *
 * 用组件图而不是制品解析来判定，避免 AGP 的 artifactType 变体歧义。
 */
val verifyIdentityFlavorMatrix by tasks.registering {
    group = "verification"
    description = "identitySec 引用统一服务契约；identityLocal 不含 AIDL 依赖（D-010 / D-018）。"
    dependsOn("assembleIdentitySecDebug", "assembleIdentityLocalDebug")

    val localModules = configurations.named("identityLocalDebugRuntimeClasspath")
        .map { it.incoming.resolutionResult.allComponents.mapNotNull { c -> c.moduleVersion?.name } }
    val secModules = configurations.named("identitySecDebugRuntimeClasspath")
        .map { it.incoming.resolutionResult.allComponents.mapNotNull { c -> c.moduleVersion?.name } }

    doLast {
        val aidlArtifact = "sec-api"
        val local = localModules.get()
        val sec = secModules.get()
        check(local.none { it == aidlArtifact }) {
            "identityLocal 产物包含 $aidlArtifact（AIDL）依赖"
        }
        check(sec.any { it == aidlArtifact }) {
            "identitySec 产物缺少 $aidlArtifact 依赖：设备令牌必须来自统一安全服务"
        }
        logger.lifecycle("identity flavour matrix OK: identityLocal 无 AIDL 依赖，identitySec 引用统一服务契约")
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
