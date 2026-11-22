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

The demo talks to the real gateway only (no scripted fake):

| BuildConfig | Default | Meaning |
|---|---|---|
| `SGW_BASE_URL` | `https://sgw.openiov.top` | EDD-SGW base URL for both IOV-TSP activation routes and SEC-CIAM token routes |
| `CIAM_CLIENT_ID` | `ivi-capp` | OAuth client registered for the vehicle |
| `DEVICE_KEY_ALIAS` | `ivi-acc-device` | Android keystore alias holding the device key used for `KEY_SIGNATURE` proof |
| `DEMO_VIN` | `LVINACC0000000001` | Local scoping VIN; the cloud authoritatively resolves the VIN from the CIAM device token (`X-Vin` is injected by SGW) |

Prerequisites for a successful run:

1. The device key alias must exist and the device must be registered in VIAM (`POST /api/ivi/auth/v1/token`
   with `KEY_SIGNATURE`). Without it the app fails closed with a recoverable authentication error.
2. `MKT-CMS` must have a published `IVI_ACTIVATION` bundle; otherwise the agreement step shows an
   explicit failure with retry and no consent control (A-001).
3. `SEC-CIAM` must have `ciam.binding-ticket.enabled=true` for the "bind and log in" step.

Debug panel (remaining controls):

- `后排屏`: switches the seat role so the privacy request returns "front seat required".
- `清空本地状态`: clears local guide state and sealed credentials only (no cloud unbind).

The flow itself is real: agreements from TSP, QR session, binding poll, one time login ticket,
CIAM ticket exchange (access 60 min + device bound refresh), silent renewal, receipts and privacy
authorization. The QR area still renders a placeholder instead of an image; phone side scanning is
out of scope.

## Cloud contract implemented (DSN-CR-002 / DSN-CR-003)

| Step | Method and path (relative to `SGW_BASE_URL`) | Notes |
|---|---|---|
| Agreements | `GET /api/vehicle/activation/v1/agreements?language=` | `language` = system Locale, fallback `zh-CN`; both documents required |
| Session | `POST /api/vehicle/activation/v1/session` | Carries the two agreement acceptances; `Idempotency-Key` |
| Result poll | `GET /api/vehicle/activation/v1/session/{sessionId}/result` | `BOUND` + `bindingOperationId` = trustworthy success |
| Skip | `POST /api/vehicle/activation/v1/session/skip` | `reportId` idempotent, VIN level evidence |
| Login ticket | `POST /api/vehicle/activation/v1/session/{sessionId}/login-ticket` | One time, 180 s, never persisted or logged |
| Login exchange | `POST /api/ivi/auth/v1/token` (`binding-ticket`) | Bearer device token + ticket -> access + refresh |
| Renewal | `POST /api/open/oauth/v1/token` (`refresh_token`) | Requires the same device token (device binding) |
| Login receipt | `POST /api/vehicle/activation/v1/session/ivi-login-result` | Carries the agreement evidence of the session snapshot |
| Privacy receipt | `POST /api/vehicle/activation/v1/privacy-authorization` | Items with a monotonic local version |

Business failures arrive as HTTP 200 with a non `000000` code; the adapter reads `code` and maps it
to the five local categories (`401101-401103` -> expired, `401108` -> retryable, others rejected).

## Not verified in this baseline

- Real gateway smoke run: the contract is implemented and covered by MockWebServer tests, but a
  vehicle with a provisioned device certificate has not run against `https://sgw.openiov.top` yet.
- `AndroidKeystoreSecretBox` requires a real Android keystore provider: host unit tests cover the
  store logic with an in-memory box only; keystore behaviour must be verified on device.
- No VSO, CMS, VMD or TSP implementation: the car side never writes those systems.
- Emulator smoke run is executed (`Pixel_3a_API_33_arm64-v8a`: install, launch, initial screen
  rendered, no crash, sanitized `state.transition` logs). A full interactive walkthrough of every
  button, real QR scanning and real account login are not executed.
- Pending confirmation: offline skip policy, privacy item list, record retention and the post
  factory-reset recovery strategy.

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
