# iov-vehicle-ivi-acc

IVI Account (ACC) head unit activation: agreement gate, activation QR session, skip activation,
account login continuation, first privacy authorization and reliable record compensation.

Delivered baseline: design change **IVI-ACC-DSN-CR-001** (design D-001..D-007) with requirement
**IVI-ACC-REQ-CR-001** (US-001..US-006, INV-001..INV-006, A-001..A-009).

## Environment

| Item | Version |
|---|---|
| Gradle Wrapper | 8.9 |
| Kotlin | 2.0.21 |
| AGP | 8.5.2 |
| JDK | 17 (compile toolchain, Gradle daemon pinned to JDK 17 in `gradle.properties`) |
| Android SDK | compileSdk 34 / targetSdk 34 / minSdk 26 |

The Gradle daemon may run on a newer JDK (for example 21) as long as the compile toolchain stays
at 17. If the local JDK 17 patch level breaks Gradle 8.9, set `org.gradle.java.home` in
`gradle.properties` (or export `JAVA_HOME` of a JDK 21) and keep `jvmToolchain(17)` in the modules.

`local.properties` must contain `sdk.dir=/path/to/Android/sdk` (not committed).

## Modules

```
acc-domain        pure Kotlin/JVM  state machine, coordinator, records, gateways, retry, logging
acc-data-local    Android library DataStore persistence + keystore sealed login material
acc-data-cloud    Android library SGW adapter (transport + codec boundary) and scriptable fakes
acc-platform      Android library account login / desktop privacy / seat role adapters
app-demo          Android app    activation UI and end-to-end integration test
```

Root package: `net.hwyz.iov.vehicle.ivi.acc`. Layering: View -> ViewModel -> Domain; data and
platform implement domain interfaces; the domain has no Android, network or UI dependency.

## Build and test

```bash
export JAVA_HOME=/path/to/jdk-17            # or a JDK 21 for the daemon
./gradlew test                              # all unit, contract and integration tests
./gradlew :app-demo:assembleDebug           # demo APK
```

Test layers:

| Layer | Location | What it proves |
|---|---|---|
| State machine | `acc-domain/.../ActivationReducerTest` | agreement gate, version binding, generation isolation, skip single submit, login gating, privacy separation, restore rules |
| Coordinator | `acc-domain/.../ActivationCoordinatorTest` | serialized event handling, polling/foreground, refresh, retry with stable ids, record compensation, log hygiene |
| Record sync | `acc-domain/.../RecordSyncEngineTest` | bounded backoff, terminal vs retryable, acknowledgement mapping |
| Persistence | `acc-data-local/.../*Test` | atomic privacy commit, scope isolation, restart, sealed material, expired material, Robolectric wiring |
| Cloud contract | `acc-data-cloud/.../SgwActivationCloudPortTest` | MockWebServer: timeout => unknown, 5xx/429 retryable, 401/403 auth rejected, 409 contract rejected, unconfigured codec fails fast, idempotency key |
| Platform | `acc-platform/.../AdaptersTest` | rear seat never prompts, close = NOT_GRANTED, expired material rejected |
| End to end | `app-demo/.../ActivationEndToEndTest` | A-001..A-008 through real DataStore + fakes |

## Demo

```bash
# 1. start an emulator (any device works, landscape is forced by the activity)
~/Library/Android/sdk/emulator/emulator -avd Pixel_3a_API_33_arm64-v8a -no-window -no-audio &
~/Library/Android/sdk/platform-tools/adb wait-for-device

# 2. install and launch
./gradlew :app-demo:installDebug
~/Library/Android/sdk/platform-tools/adb shell am start \
  -n net.hwyz.iov.vehicle.ivi.acc.demo/.ActivationActivity

# 3. observe sanitized state transitions
~/Library/Android/sdk/platform-tools/adb logcat -s IVI-ACC:D
```

Use the debug panel:

- `云端场景`:
  - `协议未维护（获取失败）` (default): startup shows a loading hint, then an explicit fetch error
    with a retry action and no consent control — matching the current cloud reality.
  - `协议可获取`: both documents load with version/revision, `查看` opens the body, and consent is
    gated before the QR can appear.
  - `绑定成功` / `扫码等待` / `结果未知` / `会话过期` / `登录失败` / `跳过超时`: cloud scenarios.
- `后排屏`: switches the seat role so the privacy request returns "front seat required".
- `清空本地状态`: clears local guide state only (no cloud unbind).

The demo uses scriptable fakes (`FakeActivationCloudPort`, `FakeAgreementGateway`,
`FakeAccountLoginAdapter`) and an in-memory secret box. It renders a placeholder instead of a real
QR image; phone side scanning is out of scope.

## Not verified in this baseline

- Cloud payload schema: `UnconfiguredPayloadCodec` refuses every call until the SGW/TSP contract is
  confirmed, so no URL, field name, status code or numeric error code was invented. `SgwTransport`
  routes are configuration.
- `AndroidKeystoreSecretBox` requires a real Android keystore provider: host unit tests cover the
  store logic with an in-memory box only; keystore behaviour must be verified on device.
- No VSO, CMS, VMD or TSP implementation: the car side never writes those systems.
- Emulator smoke run is executed (`Pixel_3a_API_33_arm64-v8a`: install, launch, initial screen
  rendered, no crash, sanitized `state.transition` logs). A full interactive walkthrough of every
  button, real QR scanning and real account login are not executed.
- Pending confirmation: trusted vehicle identity, result channel, skip/offline policy, privacy item
  list, record idempotency/retention and the post factory-reset recovery strategy.

## Troubleshooting

- `SDK location not found`: create `local.properties` with `sdk.dir=/path/to/Android/sdk`
  (it is machine specific and git ignored).
- `PlatformClassLoader cannot be cast to URLClassLoader` or other daemon errors: run Gradle with a
  JDK 17 patch release >= 17.0.2 or a JDK 21 (`export JAVA_HOME=...`), the compile toolchain stays 17.
- Android Studio: set the Gradle JDK (Settings > Build > Build Tools > Gradle) to JDK 17/21 and
  sync; run configurations for `app-demo` appear after the sync.
- `no connected devices`: start an emulator first, `adb devices` must list it as `device`.
- `FAILURE: Build failed with an exception. * What went wrong: 25.0.2`
  (`IllegalArgumentException: 25.0.2`) on Android Studio sync: the Gradle daemon ran on JDK 25
  (`JavaVersion.parse` inside Kotlin 2.0.21 cannot parse it, which also happens with the bundled
  Android Studio JBR 25). This repo pins `org.gradle.java.home` to JDK 17; additionally set
  Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK to JDK 17 or 21.
  Verify with: `./gradlew -q help` (must configure without exceptions).
