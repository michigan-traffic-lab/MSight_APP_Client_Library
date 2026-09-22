# MSight App Client Library

**The client-side half of [MSight Cloud](https://github.com/michigan-traffic-lab/MSight) — a Kotlin Multiplatform library that puts live roadside intelligence on the devices road users actually carry.**

MSight instruments intersections with cameras, LiDAR and signal-controller feeds, fuses them at the roadside, and processes the result in the cloud. This library is how that reaches a person: a phone in a cup holder, an in-vehicle infotainment (IVI) head unit, a tablet on a bus dashboard, a low-power roadside display. It holds the connection, reports where the device is, and turns what the cloud pushes back into typed events an application can render — without the application ever writing a WebSocket handler, a J2735 parser, or a reconnection loop.

The design goal is that **a mobile application should not have to understand transportation infrastructure to display it.** Ask for the signal facing you and you get red, yellow or green — not a set of J2735 signal groups you must first match against lane geometry.

```
     roadside                      MSight Cloud                       this library
┌──────────────────┐         ┌──────────────────────┐          ┌─────────────────────┐
│ cameras · LiDAR  │  SDSM   │ decode · reassemble  │   WSS    │  MSightClient       │
│ radar · signals  │────────▶│ geo-match · warn     │─────────▶│    ↓                │
│ (MSight_Core)    │  SPaT   │ radius-scoped push   │◀─────────│  events: SharedFlow │
└──────────────────┘         └──────────────────────┘  location└─────────────────────┘
                                                                          ↓
                                                            Android · iOS · IVI · desktop
```

## Table of contents

- [Where this fits in MSight](#where-this-fits-in-msight)
- [What the library does](#what-the-library-does)
- [Repository layout](#repository-layout)
- [Getting started](#getting-started)
- [Using the library](#using-the-library)
- [API reference](#api-reference)
- [MSight Cloud endpoints used](#msight-cloud-endpoints-used)
- [Platform support](#platform-support)
- [The Android example app](#the-android-example-app)
- [Testing](#testing)
- [Troubleshooting](#troubleshooting)
- [Contributing](#contributing)
- [License](#license)

## Where this fits in MSight

MSight is a full-stack open-source platform for roadside intelligence from the [University of Michigan Transportation Research Institute (UMTRI)](https://umtri.umich.edu/). Its repositories divide along the data path:

| Repository | Role |
| --- | --- |
| [MSight](https://github.com/michigan-traffic-lab/MSight) | Entry point; integrates the modules below as submodules |
| [MSight_base](https://github.com/michigan-traffic-lab/MSight_base) | Canonical data abstractions shared by every module |
| [MSight_Vision](https://github.com/michigan-traffic-lab/MSight_Vision) | Camera perception at the roadside |
| [MSight_Core](https://github.com/michigan-traffic-lab/MSight_Core) | Roadside processing graph and edge-to-cloud transport |
| [MSight_Cloud](https://github.com/michigan-traffic-lab/msight-cloud) | Cloud ingestion, processing, APIs and client push |
| **MSight_APP_Client_Library** | **This repository — the client SDK for mobile and in-vehicle devices** |

This library talks **only** to MSight Cloud. It does not connect to roadside devices, run perception, or implement V2X radio protocols; everything it receives has already been decoded, filtered and scoped to the device's location by the cloud. That is deliberate: it means a client needs nothing but an HTTPS connection, which is what makes an ordinary phone a viable endpoint for infrastructure data.

You need a reachable MSight Cloud deployment to use this library. MSight Cloud deploys into your own AWS account via AWS CDK — see its repository for deployment instructions. What you need from it is three values: the deployment's HTTP API URL, an `app_id` registered in its admin console, and a `client_id` of your own choosing.

## What the library does

**Keeps the device connected and located.** A single `MSightClient` discovers the deployment's WebSocket endpoint, connects, and reconnects with exponential backoff for as long as it lives — a vehicle loses connectivity routinely, so a drop is treated as normal rather than as an error to escalate. Meanwhile it reports the device's position at a configurable rate. That reporting is not telemetry: MSight Cloud scopes every push by radius around live client positions, so a client that does not report a position receives nothing.

**Delivers roadside perception.** Decoded SAE J2735 SDSM frames from sensors near the device — every vehicle and pedestrian a roadside sensor can see, with position, speed, heading, classification and bounding box. This is the feed behind cooperative perception: a driver's device learns about a vehicle occluded from their own view.

**Delivers signal state — already interpreted.** Raw J2735 SPaT arrives on two streams: a routine one at about 2 Hz, and a low-latency one that fires only on an actual phase change. Both are available, but most applications want the derived `MSightSignalStateEvent` instead, which answers the question a driver has: *what is my light doing?*

Producing that answer is the most substantial thing in the library. It fetches the intersection's J2735 MAP geometry, flattens the relative lane-node deltas into usable coordinates, infers which movements each signal group governs from lane-neighbour topology, matches the device's position and heading against individual lane segments to identify the approach it is on, and looks up that approach's signal groups in the latest SPaT. Then it does the unglamorous work that makes the result usable on consumer hardware: requiring several consecutive matches before displaying anything, holding the match when GNSS heading drops out at a standstill — precisely when a driver is waiting at a red light and most wants the display — and suppressing the stale frames that would otherwise flash the old colour back after a phase change.

**Delivers warnings.** Safety messages broadcast to clients within a radius of a hazard, carrying a `event_id` so a repeated broadcast of one ongoing hazard can extend an existing alert rather than restarting it.

**Stays out of the way.** No UI, no threading requirements imposed on the host, no service lifecycle. One hot `SharedFlow` of one sealed event hierarchy: collect it once, dispatch on type.

## Repository layout

```
shared/                                 The library — a Kotlin Multiplatform module
  src/commonMain/kotlin/…/client/
    MSightClient.kt                     Entry point, cloud transport, message + MAP parsing
    MSightApproachDetector.kt           Position + geometry + SPaT → the signal facing the driver
    MSightEvent.kt                      The sealed event hierarchy the host collects
    MSightMap.kt                        Intersection geometry model (from J2735 MAP)
    MSightSdsm.kt                       SDSM model (J2735 Sensor Data Sharing Message)
    MSightSpat.kt                       SPaT model (J2735 Signal Phase and Timing)
    MSightTrajectory.kt                 Observed and predicted motion
    LocationEmitter.kt                  expect: platform context + location provider
    PlatformHttpClient.kt               expect: platform HTTP/WebSocket engine
    WarningEmitter.kt                   Synthetic warnings for offline UI development
  src/androidMain/                      Android actuals: OkHttp + fused location provider
  src/jvmMain/                          JVM actuals: CIO + simulated location; desktop smoke test

androidapp/                             Reference Android application ("MSight Shield")
  src/main/java/…/MainActivity.kt       Config screen, live map, signal overlay, warning banners

*.kml                                   Recorded GNSS routes for emulator playback testing
```

The split matters when porting: everything in `commonMain` — all the protocol handling, geometry and state machines — is platform-independent. A new platform needs two small `actual` implementations and nothing else.

## Getting started

### Prerequisites

- **Android Studio**, recent enough to support Android Gradle Plugin 8.8. This is the primary IDE; the Kotlin Multiplatform and Android tooling both come from it.
- **Android SDK Platform 35.**
- **JDK 17** for Gradle. Android Studio's bundled JetBrains Runtime satisfies this.
- An emulator image or a physical Android device.
- Access to an **MSight Cloud** deployment.

Verify in Android Studio:

1. `Settings > Languages & Frameworks > Android SDK` — API Level 35 installed.
2. `Settings > Build, Execution, Deployment > Build Tools > Gradle` — Gradle JDK is 17.
3. Let Android Studio create `local.properties` during import if it is missing. It is gitignored; it only records your SDK path.

### Open and sync

1. Start Android Studio, choose `Open`, and select the repository root.
2. Wait for the Gradle import. Two modules should appear: `shared` and `androidapp`.
3. If sync does not start on its own, click `Sync Project with Gradle Files`.

From a terminal, `./gradlew tasks` (or `.\gradlew.bat tasks` on Windows) confirms the build resolves.

### Add your own Google Maps API key

The example app's map needs one — the library itself does not. Get a key from the [Google Cloud console](https://console.cloud.google.com/google/maps-apis) with the **Maps SDK for Android** enabled, then add it to `local.properties` in the repository root:

```properties
MAPS_API_KEY=your_key_here
```

`local.properties` is gitignored, and [androidapp/build.gradle.kts](androidapp/build.gradle.kts) injects the value into the manifest as a placeholder, so the key never enters version control. CI can supply it as a `MAPS_API_KEY` environment variable instead.

Without a key the build still succeeds and the app still runs — only the map renders blank, which is usually fine for testing the client library itself.

Restrict the key in the Google Cloud console to this application's package name and SHA-1 signing certificate. That limits the damage if it leaks, which for a key shipped inside an APK is a question of when rather than whether.

### Run the example app

Select the `androidapp` run configuration, start an emulator or connect a device, and click `Run`.

The configuration screen starts empty — there is no default deployment, deliberately. Enter your MSight Cloud deployment's URL (the `HttpApiUrl` its CDK deploy printed), an `app_id` registered in its console, and a `client_id`; one is generated for you. **Start** stays disabled until all three are filled in. Grant the location permission when asked — the library will not produce a position without it, and the cloud will not push anything to a device with no position.

Command-line equivalents:

```bash
./gradlew :androidapp:assembleDebug     # build the debug APK
./gradlew :androidapp:installDebug      # install on the current device or emulator
```

The APK lands in `androidapp/build/outputs/apk/debug/`.

### Check a deployment without a device

The JVM target runs the whole client — connection, upload, parsing — against a simulated location, printing every event:

```bash
MSIGHT_CLOUD_URL=https://your-deployment.example.com \
MSIGHT_APP_ID=your-app \
MSIGHT_CLIENT_ID=desktop-test \
./gradlew :shared:runJvmMain
```

`MSIGHT_CLOUD_URL` is required and has no default; run it without one and it prints the usage above rather than failing obscurely. Every setting also accepts a JVM system property (`-Dmsight.cloudUrl=…`) — see [Main.kt](shared/src/jvmMain/kotlin/com/msight/app/client/Main.kt).

This is the fastest way to tell "the deployment is unreachable" apart from "the app is misconfigured".

## Using the library

Add the `shared` module as a dependency. Within this repository:

```kotlin
dependencies {
    implementation(project(":shared"))
}
```

To consume it from another project, include this repository as a Gradle [composite build](https://docs.gradle.org/current/userguide/composite_builds.html) (`includeBuild` in your `settings.gradle.kts`). There are no published artifacts: the `shared` module does not apply `maven-publish`, so there is no `publishToMavenLocal` task, and nothing is published to Maven Central. Adding publication is a small change to [shared/build.gradle.kts](shared/build.gradle.kts) if you would rather depend on a coordinate.

### Minimal integration

```kotlin
import com.msight.app.client.*
import kotlinx.coroutines.launch

// 1. Location permission must already be granted before this point.

// 2. Construct the client. This blocks until connected, so keep it off the main thread.
val client = MSightClient(
    context = applicationContext,                 // a PlatformContext; Context on Android
    config = MSightClientConfig(
        cloudUrl = "https://your-deployment.example.com",
        appId = "your-app",                       // registered in the MSight Cloud console
        clientId = "device-a1b2c3d4",             // unique per device
        roadUserType = MSightRoadUserType.VEHICLE,
        roadUserSubType = "passenger_car",
        deviceType = MSightDeviceType.CELLPHONE,
        locationUpdateFrequencyHz = 1.0
    )
)

// 3. Subscribe before starting — the flow has no replay buffer.
scope.launch {
    client.events.collect { event ->
        when (event) {
            is MSightLocationEvent    -> updateOwnPosition(event.latitude, event.longitude)
            is MSightSdsmEvent        -> drawDetections(event.refPos, event.objects)
            is MSightSignalStateEvent -> showSignal(event.intersectionName, event.straightColor)
            is MSightSimpleWarning    -> alert(event.message)
            else -> Unit
        }
    }
}

// 4. Start reporting location, and opt into derived signal state.
client.start()
client.setSpatEnabled(true)

// 5. On teardown.
client.close()
```

### Things worth knowing before you build on this

- **`start()` is not optional.** Without it the socket is open but the device has no position, so the cloud has nothing to scope its pushes against and sends nothing. A silent client is nearly always a client that was never started, or one whose location permission was denied.
- **The constructor blocks and can throw.** It returns a connected client or an exception — never a half-live object. Call it off the main thread and handle the failure.
- **Callbacks arrive on the library's dispatcher.** Hop to your main thread before touching UI.
- **Events are dropped, not queued, under backpressure.** Emission uses `tryEmit` into a 64-slot buffer so a slow collector cannot stall the network reader. Keep collectors cheap; hand heavy work off.
- **A null `intersectionName` on `MSightSignalStateEvent` means "take the display down".** It is the only take-down signal you get, so treat it as one rather than filtering it out.
- **`appId` must be registered** in the MSight Cloud admin console. An unregistered one connects successfully and receives nothing.
- **`clientId` must be unique per device.** Two live connections sharing one collide in the cloud's connection registry. Generate and persist one per install.
- **An instance is single-use.** After `close()`, construct a new one.

## API reference

### `MSightClient`

| Member | Purpose |
| --- | --- |
| `MSightClient(context, config)` | Constructs and connects. Blocks; throws if the cloud is unreachable. |
| `events: SharedFlow<MSightEvent>` | The single stream of everything the client produces. Hot, no replay. |
| `start()` | Begins location reporting. Required to receive anything. |
| `stop()` | Ends the session; keeps the instance usable via `initialize()`. |
| `close()` | Releases everything. The instance is finished. |
| `initialize()` | Rebuilds the session. Called by the constructor; rarely needed directly. |
| `setSpatEnabled(Boolean)` | Turns derived `MSightSignalStateEvent` production on or off. Off by default. |
| `locationHistory` | The device's own track over the last two minutes. |
| `loadMapsByLocation(lat, lon, radiusMeters)` | Fetches intersection maps near a point. |
| `loadMapsByName(name)` | Fetches one intersection map by name. |

### `MSightClientConfig`

| Field | Notes |
| --- | --- |
| `cloudUrl` | The deployment's HTTP API base URL. Trailing slash tolerated. |
| `appId` | Registered application identifier. Scopes what the cloud sends you. |
| `clientId` | Unique per device within `appId`. |
| `roadUserType` | `VEHICLE`, `VRU`, `OTHER`. Not yet used — see below. |
| `roadUserSubType` | Free text, e.g. `passenger_car`, `transit_bus`, `pedestrian`. Not yet used. |
| `deviceType` | `CELLPHONE`, `TABLET`, `IVI`, `LOW_POWER_DEVICE`, `OTHER`. Not yet used. |
| `locationUpdateFrequencyHz` | Upper bound on upload rate; default `1.0`. `0.0` disables rate limiting. |

The three road-user and device fields are reserved and have no effect today: they are neither transmitted nor read, because MSight Cloud's location-update schema has no field to carry them yet. Only `cloudUrl`, `appId`, `clientId` and `locationUpdateFrequencyHz` affect anything.

They remain required rather than defaulted on purpose. A default would mean every client that never thought about it silently reporting the same class on the day the cloud starts using these — so you are asked once, now, while the answer is in front of you.

### Events

| Event | Source | Carries |
| --- | --- | --- |
| `MSightLocationEvent` | This device | A rate-limited GNSS fix |
| `MSightSdsmEvent` | Roadside sensor, via cloud | One SDSM frame: reference position + detected objects |
| `MSightSpatEvent` | Signal controller, via cloud | Routine SPaT, ~2 Hz |
| `MSightCriticalSpatEvent` | Signal controller, via cloud | Phase-change SPaT, low latency |
| `MSightSignalStateEvent` | **Derived by this library** | The signal colours facing this device |
| `MSightSimpleWarning` | Cloud microservice | A radius-scoped text warning |
| `MSightTwoVehicleConflictEvent` | Contract only | V2V conflict with trajectories — see note |
| `MSightVehicleVRUConflictEvent` | Contract only | Vehicle–VRU conflict with trajectories — see note |

The two conflict events are part of the event contract but are not emitted by any current cloud microservice. `MSightFakeWarnings` produces them so warning UI can be built and tested before a producer exists.

### Other public types

`MSightApproachDetector` exposes its two pure functions — `detectActiveApproach` and `extractArmSignals` — for callers that want to run the geometry themselves, for instance to display signal state for an intersection the device is not approaching. `LocationEmitter` offers the platform position stream on its own, with no cloud connection. `WarningEmitter` is a host-driven warning channel for development.

## MSight Cloud endpoints used

The library uses four routes from MSight Cloud's [public client API](https://github.com/michigan-traffic-lab/msight-cloud#public-client-api), all unauthenticated:

| Route | Used for |
| --- | --- |
| `GET /system/websocket-url` | Discovering the WebSocket endpoint, on every connection attempt |
| `POST /v1/clients/location/update` | Reporting position, so the cloud can scope pushes by radius |
| `GET /v1/maps/search?lat&lon&radius` | Loading intersection geometry for the current area |
| `GET /v1/maps/{name}` | Loading one intersection's geometry by name |

The WebSocket carries `app_id` and `client_id` as query parameters and is push-only — the client never sends a frame. Every push arrives in an envelope, `{app_id, event_id, message, server_timestamp}`, whose `message` is discriminated by a `type` field: `sdsm`, `spat`, `critical_spat`, or the legacy `message_type: msight_simple_warning`. Unrecognised types are logged and skipped, so a cloud deployment can add message types without breaking clients already in the field.

Your deployment serves its own live OpenAPI document at `GET /system/docs`, generated from the schemas that validate its requests. That document, not this table, is the authority on the contract.

Note that these routes are unauthenticated by design, which has a consequence worth stating plainly: anyone who knows your deployment URL and a registered `app_id` can report locations and receive pushes. Treat the deployment URL as a shared secret, and consult MSight Cloud's security notes before exposing a deployment to untrusted clients.

## Platform support

Kotlin Multiplatform is why this library exists in this form. The protocol handling, geometry, MAP parsing and signal state machine — the substantial and hard-to-get-right parts — all live in `commonMain` and are platform-independent. Every platform needs exactly two `actual` implementations:

1. **`createPlatformHttpClient`** ([PlatformHttpClient.kt](shared/src/commonMain/kotlin/com/msight/app/client/PlatformHttpClient.kt)) — a Ktor client on an engine that target supports, with WebSocket support.
2. **`PlatformContext` and `PlatformLocationProvider`** ([LocationEmitter.kt](shared/src/commonMain/kotlin/com/msight/app/client/LocationEmitter.kt)) — a wrapper over the platform's location API that pushes `MSightLocationEvent`s.

Current status:

| Target | Status | Notes |
| --- | --- | --- |
| **Android** | ✅ **Tested** | Supported and field-tested. OkHttp engine, Play services fused location. `minSdk 24`, `compileSdk 35`. Also the path for Android-based IVI head units. |
| **JVM / Desktop** | ⚠️ Partial | Builds and runs, with a *simulated* location source. Intended as a deployment smoke test, not a desktop application platform. A real implementation would need a GNSS or serial NMEA source. |
| **iOS** | 🚧 Coming soon | Targets and an XCFramework block are present but commented out in [shared/build.gradle.kts](shared/build.gradle.kts), with step-by-step notes alongside them. There is no `iosMain` source set yet. Needs a Ktor **Darwin** engine and a `CLLocationManager` wrapper. Uncommenting the XCFramework block is what produces a framework consumable from Xcode; the CocoaPods plugin is in the version catalogue but not applied. |
| **JavaScript** (browser, Node) | 🚧 Coming soon | Untested. Needs the Ktor **Js** engine and a Geolocation API wrapper. A browser client is subject to secure-context and CORS constraints; MSight Cloud's public API is CORS-open. |
| **macOS** (native) | 🚧 Coming soon | Untested. Shares the Darwin engine and `CoreLocation` with iOS, so it largely follows from an iOS port. |
| **Windows / Linux** (native) | 🚧 Coming soon | Untested. Ktor's native engines (WinHttp, Curl) differ in WebSocket support — verify before committing to a target. |
| **Wasm** | ⛔ Not available | Ktor 2.3.x has no `wasmJs` engine. Requires upgrading to Ktor 3.x first. |

"Coming soon" above means the architecture supports it and no work has been done, not that a port is scheduled. Contributions for any of these are welcome — see [Contributing](#contributing).

### Adding a target

1. Declare the target in `shared/build.gradle.kts` and add its Ktor engine to that source set's dependencies.
2. Create `shared/src/<target>Main/kotlin/com/msight/app/client/` and implement the two `actual` declarations above.
3. Build it: `./gradlew :shared:compileKotlin<Target>` — e.g. `compileKotlinJvm`, `compileKotlinIosArm64`, `compileKotlinJs`. (`./gradlew :shared:tasks --all` lists what your target configuration actually produced; Android's is the differently-named `compileDebugKotlinAndroid`.)
4. Verify against a real deployment. Working through the `MSightClient` lifecycle — connect, report a position, receive a push — exercises everything that can differ per platform.

For iOS specifically, `shared/build.gradle.kts` carries the sequence as a comment next to the commented-out `iosX64`/`iosArm64`/`iosSimulatorArm64` and `XCFramework` blocks.

## The Android example app

`androidapp` is a worked example, not a product — but it is a complete one, and it is the reference for how to consume the library.

It has two screens. The first collects the fields of `MSightClientConfig`, with a generated `client_id`. The second is the live view: a Google map following the device, roadside SDSM detections as markers keyed by track id, intersection lane geometry tinted by the current signal phase, a neon signal overlay for the approach the device is on, and full-screen warning banners with a looping alert tone routed as an alarm so it is audible over music and navigation.

Four details in it are worth copying rather than reinventing:

- **Permission before construction.** `requestPermissionsAndStart` parks the config and only constructs the client once the permission result is in, because the location provider assumes permission is already held.
- **Subscribe, then start.** `startPendingClient` launches collection before calling `start()`, on `Dispatchers.IO` because the constructor blocks.
- **Split-frame merging.** One logical SDSM frame can arrive as several messages. Detections within 50 ms of the previous frame's timestamp are merged into the existing marker set instead of replacing it — otherwise each fragment erases what the others delivered.
- **Stable marker identity.** Markers are held in a map keyed by `objectID` and updated in place, so a tracked object keeps its marker across frames rather than flashing as it is destroyed and recreated.

What it is *not* set up to do: run in the background. There is no foreground service, so the client stops when Android suspends the activity. A production driver-facing application needs one, along with a ViewModel in place of the activity-held state this example uses for brevity.

The `.kml` files in the repository root are 1 Hz GNSS tracks approaching the instrumented Huron Parkway / Plymouth Road intersection in Ann Arbor, Michigan, from five directions. Load one into the Android emulator's extended controls (`Location > Routes > Import GPX/KML`) to replay an approach without driving it. Each includes a twenty-second stop with synthetic GNSS noise at the stop bar, which is what exercises the hardest case in the signal state machine: holding the display locked while heading data degrades at a standstill.

## Testing

```bash
./gradlew test                                  # all unit tests
./gradlew :shared:test                          # library only
./gradlew :androidapp:testDebugUnitTest         # example app only
./gradlew :androidapp:connectedDebugAndroidTest # instrumented; needs a device or emulator
```

Be aware of what those commands currently cover: **nothing**. The `shared` module has no tests at all — only the `kotlin-test` dependency, ready for them — and `androidapp` carries just the two template stubs generated with the project. The library's behaviour is verified against live deployments instead.

The parsers, MAP flattening, arm classification and approach detection in `commonMain` are pure functions over JSON strings and data classes, so they are straightforward to test without a device or a cloud connection. That is the most useful contribution available to anyone looking for one.

```bash
./gradlew build                                 # everything
./gradlew :shared:build                         # library, all targets
./gradlew clean build                           # from scratch
```

## Troubleshooting

**Gradle sync fails.** Check the two things that account for most of it: Android SDK 35 installed, and Gradle running on JDK 17.

**The client connects but no events arrive.** In order of likelihood: `start()` was never called; location permission was denied; the `app_id` is not registered in the deployment's console; or the device is genuinely not near an instrumented intersection. The library logs each stage to stdout (Logcat on Android) — `MSightWebSocketConnection connected` confirms the socket, and `MSightLocationUploader failed` points at the location path.

**The constructor throws.** The deployment is unreachable, or `cloudUrl` is wrong. Confirm with `curl {cloudUrl}/system/version`, which should return a `build_id`.

**No signal overlay at an intersection you know is instrumented.** `setSpatEnabled(true)` must be called. Beyond that, approach detection needs a direction of travel. The library trusts the GNSS bearing only above 2 m/s, falls back to the heading frozen at the last clearly-moving fix, and failing that infers one from position history — but only once two fixes are at least 10 m apart, so that GNSS jitter is not mistaken for motion. A device that has been stationary since launch satisfies none of the three. Drive the approach rather than starting the app at the stop bar. The detector logs its accept/reject decision with the distance and angle for every fix, which makes the cause visible.

**Detections appear at right angles to where they are.** You have the SDSM offset axes the wrong way round. The SDSM stream reverses J2735's usual ordering: `offsetX` is metres **north** and `offsetY` is metres **east** — the opposite of MAP lane geometry, which does follow the standard's east/north convention. The library passes the values through unchanged, so this is the consumer's job; `computeObjectLatLon` in the example app gets it right. See [MSightSdsm.kt](shared/src/commonMain/kotlin/com/msight/app/client/MSightSdsm.kt).

**Device not detected by Android Studio.** `adb devices` to confirm; then USB debugging, the cable, and on Windows the OEM USB driver.

**Blank map in the example app.** Your Google Maps API key is missing or invalid. See [Getting started](#add-your-own-google-maps-api-key).

## Contributing

Issues and pull requests are welcome. The most valuable contributions right now, roughly in order:

1. **Platform ports** — iOS first, then JS. Two `actual` implementations each; the architecture is ready.
2. **Unit tests** for the message parsers, MAP flattening, arm classification and approach detection, all of which are pure functions.
3. **A non-Play-services Android location provider**, for IVI head units without Google Play services.
4. **Field validation** at instrumented intersections beyond Ann Arbor.

Please keep protocol handling and geometry in `commonMain` — platform source sets should hold only what genuinely cannot be shared.

## License

BSD 3-Clause. Copyright (c) 2026, University of Michigan Transportation Research Institute (UMTRI), University of Michigan. See [LICENSE](LICENSE).

The same license as the rest of the MSight platform.

## Learn more

- 🌐 [msight.um.city](https://msight.um.city/)
- 📚 [MSight documentation](https://msight-user-docs.readthedocs.io/en/latest/)
- 🧩 [MSight on GitHub](https://github.com/michigan-traffic-lab/MSight)
