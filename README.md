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
acc-data-sec      Android library device identity seam: SEC AIDL client (`identitySec`) + LOCAL impl (`identityLocal`)
acc-platform      Android library account login / desktop privacy / seat role adapters
app               Android app    activation UI and end-to-end integration test
```

Root package: `net.hwyz.iov.vehicle.ivi.acc`. Layering: View -> ViewModel -> Domain; data and
platform implement domain interfaces; the domain has no Android, network or UI dependency.

Device tokens are read through the `DeviceTokenSource` port, and the implementation is chosen at
**build time** (SPEC design D-010「构建变体二选一」, never both at runtime):

| Flavour | Device identity | Holds a device key? |
|---|---|---|
| `identitySec` (default) | `acc-data-sec` AIDL client of `iov-vehicle-ivi-sec`: the service signs the proof and calls CIAM, IVI-ACC only consumes the token | no — no key alias at all |
| `identityLocal` (migration window, deleted in stage three) | `acc-data-sec` LOCAL impl: in-process keystore proof + CIAM device identity grant | yes, `ivi-acc-device` |

Both variants resolve automatically through Gradle variant matching; `identityLocal` builds must not
carry the AIDL contract (`sec-api`), which is enforced by `./gradlew verifyIdentityFlavorMatrix`.

## Build and test

```bash
export JAVA_HOME=/path/to/jdk-17            # or a JDK 21 for the daemon
./gradlew test                          # all unit, contract and integration tests
./gradlew :app:assembleIdentitySecDebug     # demo APK (default identity source, ivi-acc-identitySec-debug.apk)
./gradlew :app:assembleIdentityLocalDebug   # migration window APK (ivi-acc-identityLocal-debug.apk)
./gradlew verifyIdentityFlavorMatrix        # D-018: both flavours compile, identityLocal has no AIDL
./gradlew verifyForbiddenIdentitySources    # A-017: no ANDROID_ID fallback
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
| End to end | `app/.../ActivationEndToEndTest` | A-001..A-008 through real DataStore + fakes |

## Demo

```bash
# 1. start an emulator (any device works, landscape is forced by the activity)
~/Library/Android/sdk/emulator/emulator -avd Pixel_3a_API_33_arm64-v8a -no-window -no-audio &
~/Library/Android/sdk/platform-tools/adb wait-for-device

# 2. install and launch
./gradlew :app:installDebug
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

## DSN-CR-005 additions (device identity, QR rendering, privacy catalogue)

- **Device identity (D-011 / BLK-4).** `DeviceSerialProvider` (acc-domain) is the only source of the
  CIAM `device_id`: the unified security service (`DeviceSecurityStatus.deviceId`, `identitySec`) or a
  device level read only property (`ro.serialno` -> `Build.getSerial()` -> `Build.SERIAL`,
  `identityLocal`). A secure settings identifier is **not** a source and must not come back; an
  unavailable serial fails closed (`CONTRACT_REJECTED` / `device-id-unavailable`, no proof, no CIAM
  call). `KeystoreDeviceProofProvider` and `DeviceProof.deviceId` are non nullable.
  Mechanical gate: `./tools/check-forbidden-identity-source.sh` or
  `./gradlew verifyForbiddenIdentitySources` (also enforced by `ForbiddenIdentitySourceGateTest`).
  The IVI-SEC reader (`iov-vehicle-ivi-sec`) drops the same fallback, so both sides agree on the
  identifier — a mismatch trips CIAM `701329`.
- **Activation QR (D-012).** `ActivationCodePresenter<T>` is the port; `ZxingActivationCodePresenter`
  (acc-platform, `com.google.zxing:core:3.5.3`, Apache-2.0, generation only, no CAMERA permission)
  renders the payload. The payload is exactly the cloud `qrToken` string (no URL scheme, no Base64,
  no trimming). Only the **last response** is rendered: a transport replay re-signs the token and the
  previous bitmap is dropped immediately. Countdown uses the server `expiresAt` and triggers
  `refreshSession()` on expiry (new `flowGeneration` -> new idempotency key -> new code).
- **Privacy authorization catalogue (D-013 / G-1).** Items come from
  `GET /api/vehicle/activation/v1/privacy-authorization/items`
  (`{catalogVersion, items:[{authorizationItem, displayName, required}]}`, `Accept-Language`,
  `Cache-Control: private, max-age=300`) and are reported to
  `POST /api/vehicle/activation/v1/privacy-authorization`
  (`{reportId, catalogVersion, items:[{authorizationItem, state, version}], occurredAt}`).
  `version` is the **local decision sequence** (persisted, +1 per decision), never a timestamp and
  never compared with `catalogVersion`. Unavailable / empty / feature-disabled catalogues block only
  the report: the user still enters the account (`ENTERED_ACCOUNT`, `privacySync = NOT_SYNCED`,
  retry entry offered, no local fallback list).
- **Polling (D-014 / R-13).** Polling runs until the session `expiresAt` (5 s request margin) instead
  of a local cap; "query now" is single flight and never resets the backoff ladder.
- **Central error catalogue (D-015 / R-14).** One table (`ActivationErrorCatalog`) classifies TSP
  `401101-401119`, CIAM `701xxx` and SGW codes; retryable codes can never become terminal, unknown
  codes default to `CONTRACT_REJECTED` and are counted.
- **Login receipts (D-005 / BLK-11 / R-15).** A FAILED receipt is generated at session scope
  (`VS:vin|sessionId`, no account needed), is sent even without `bindingOperationId`, and always
  carries `failureReason`.
- **Token lifecycle (D-016) and seat gate (D-017).** Credential invalidation signs the flow out but
  keeps the cloud binding; a rear seat screen can read the flow yet cannot accept agreements, show a
  scannable code or submit the skip.

## Not verified in this baseline

- Real gateway smoke run: the contract is implemented and covered by MockWebServer tests, but a
  vehicle with a provisioned device certificate has not run against `https://sgw.openiov.top` yet.
- `AndroidKeystoreSecretBox` requires a real Android keystore provider: host unit tests cover the
  store logic with an in-memory box only; keystore behaviour must be verified on device.
- No VSO, CMS, VMD or TSP implementation: the car side never writes those systems.
- Emulator smoke run is executed (`Pixel_3a_API_33_arm64-v8a`: install, launch, initial screen
  rendered, no crash, sanitized `state.transition` logs). A full interactive walkthrough of every
  button, real QR scanning and real account login are not executed.
- DSN-CR-005: the QR payload is proven character by character through an encode/decode round trip,
  but a real camera scan on a head unit and the countdown/auto-refresh loop have not been observed on
  device yet. `ZxingActivationCodePresenter`'s Bitmap path has no Robolectric test (acc-platform does
  not depend on Robolectric).
- Device identity on real hardware: both flavours were launched on a tablet (Xiaomi Pad 8 Pro,
  Android 17). `identitySec` binds `iov-vehicle-ivi-sec` and holds `USE_DEVICE_IDENTITY`, but the
  device is **not provisioned** there, so the run ends with `state=UNKNOWN reason=4` (KEY_UNAVAILABLE)
  and `sec:7` (TOKEN_UNAVAILABLE) — the deterministic "device not provisioned" prompt with no retry
  affordance, which is the designed behaviour. A successful activation still requires VIAM
  provisioning (`deviceId` + certificate) on the security service side.
- Pending confirmation: offline skip policy, privacy item list, record retention and the post
  factory-reset recovery strategy.

## Troubleshooting

- `SDK location not found`: create `local.properties` with `sdk.dir=/path/to/Android/sdk`
  (it is machine specific and git ignored).
- `PlatformClassLoader cannot be cast to URLClassLoader` or other daemon errors: run Gradle with a
  JDK 17 patch release >= 17.0.2 or a JDK 21 (`export JAVA_HOME=...`), the compile toolchain stays 17.
- Android Studio: set the Gradle JDK (Settings > Build > Build Tools > Gradle) to JDK 17/21 and
  sync; run configurations for `app` appear after the sync.
- `no connected devices`: start an emulator first, `adb devices` must list it as `device`.
- `FAILURE: Build failed with an exception. * What went wrong: 25.0.2`
  (`IllegalArgumentException: 25.0.2`) on Android Studio sync: the Gradle daemon ran on JDK 25
  (`JavaVersion.parse` inside Kotlin 2.0.21 cannot parse it, which also happens with the bundled
  Android Studio JBR 25). This repo pins `org.gradle.java.home` to JDK 17; additionally set
  Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK to JDK 17 or 21.
  Verify with: `./gradlew -q help` (must configure without exceptions).
