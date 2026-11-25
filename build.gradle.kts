// Root build file: shared plugin versions only, no per-module build here.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

/**
 * A-017 mechanical gate (IVI-ACC-DSN-CR-005 D-011 / BLK-4).
 *
 * Fails the build when a secure settings device identifier appears anywhere in the IVI-ACC sources,
 * so it can never come back as a "fallback" identity source. CI runs `verifyForbiddenIdentitySources`
 * (or the script directly) before packaging.
 */
tasks.register<Exec>("verifyForbiddenIdentitySources") {
    group = "verification"
    description = "A-017: no secure settings identifier may be used as a device identity source."
    commandLine("bash", "tools/check-forbidden-identity-source.sh")
    workingDir = projectDir
}

/**
 * D-018 构建门禁（IVI-ACC-DSN-CR-005 / CR-004 D-010）：设备身份双 flavor 必须同时可构建，
 * 且 identityLocal 产物不得携带统一服务的 AIDL 契约（sec-api）。
 *
 * 真正的校验在 `:app:verifyIdentityFlavorMatrix` 内执行（需要 app 的 variant 配置）。
 */
tasks.register("verifyIdentityFlavorMatrix") {
    group = "verification"
    description = "Builds both identity flavours and asserts identityLocal carries no sec-api (AIDL)."
    dependsOn(":app:verifyIdentityFlavorMatrix")
}
