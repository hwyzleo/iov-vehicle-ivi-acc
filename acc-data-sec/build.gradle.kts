plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

/** Build variant names shared with the app module (D-010). */
val identityDimension = "identity"
val identitySecFlavor = "identitySec"
val identityLocalFlavor = "identityLocal"

android {
    namespace = "net.hwyz.iov.vehicle.ivi.acc.data.sec"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    /**
     * D-010「构建变体二选一」：设备身份只有一个来源。
     *
     * - `identitySec`：跨进程客户端，令牌由统一密码学服务签发，模块引用 sec-api（AIDL）。
     * - `identityLocal`：迁移期本地实现（Keystore 证明 + CIAM 设备身份 grant），不引用 sec-api，
     *   因此 identityLocal 产物不含 AIDL 契约（D-018 构建门禁）。
     *
     * 两个变体不得运行期并存；app 侧同名 flavor 通过 variant aware 匹配自动选中对应变体。
     */
    flavorDimensions += identityDimension
    productFlavors {
        create(identitySecFlavor) {
            dimension = identityDimension
        }
        create(identityLocalFlavor) {
            dimension = identityDimension
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    api(project(":acc-domain"))

    // Cross-application contract of the unified security service (AIDL + parcelables + constants).
    // Flavour scoped so the identityLocal variant never pulls the AIDL contract into the APK.
    "${identitySecFlavor}Api"(libs.ivi.sec.api)
    // LOCAL implementation only: the migration period grant client reuses the CIAM envelope codec.
    "${identityLocalFlavor}Implementation"(project(":acc-data-cloud"))

    implementation(libs.kotlinx.coroutines.core)
    // The migration period grant client encodes its request body; the codec types travel with it.
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit.jupiter)
    testImplementation(libs.kotlin.test.junit5)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.serialization.json)
    "test${identityLocalFlavor.replaceFirstChar { it.uppercase() }}Implementation"(project(":acc-data-cloud"))
    "test${identitySecFlavor.replaceFirstChar { it.uppercase() }}Implementation"(libs.ivi.sec.api)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
