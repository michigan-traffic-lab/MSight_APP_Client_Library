# MSight Client App Library

This repository contains the MSight client app library built with Kotlin Multiplatform, plus an Android example app used to run and validate the library in Android Studio.

This README is intentionally limited to setup and getting the project running. It does not describe the current sample functionality in the codebase.

## Project Layout

- `shared`: Kotlin Multiplatform library module.
- `androidapp`: Android example app module that depends on `shared`.

## Prerequisites

Use Android Studio as the primary IDE for this project.

Before opening the project, make sure the following are installed:

- Android Studio with the Android SDK tools.
- Android SDK Platform 35.
- At least one Android emulator image, or a physical Android device.
- JDK 17 for Gradle. Android Studio normally manages this for you through the bundled JetBrains Runtime.

Recommended checks in Android Studio:

1. Open `File > Settings > Android SDK` and confirm that Android API Level 35 is installed.
2. Open `File > Settings > Build, Execution, Deployment > Build Tools > Gradle` and confirm Gradle uses a JDK 17 runtime.
3. If `local.properties` is missing or stale, let Android Studio recreate it during project import.

## Open The Project In Android Studio

1. Start Android Studio.
2. Select `Open`.
3. Choose the repository root folder: `msight_app_client`.
4. Wait for Android Studio to import the Gradle build.

When the project opens, Android Studio should detect these modules:

- `shared`
- `androidapp`

## Synchronize With Gradle

Android Studio usually starts synchronization automatically after import. If it does not, or if dependencies change later:

1. Click `Sync Project with Gradle Files` from the toolbar.
2. Wait until the Gradle sync finishes successfully.
3. If prompted to install missing SDK components, accept the installation and sync again.

If sync fails, check these first:

- Android SDK 35 is installed.
- Gradle is using JDK 17.
- Internet access is available for dependency resolution.

You can also verify the project from a terminal with the Gradle wrapper:

```powershell
.\gradlew.bat tasks
```

## Build The Project

For most Android Studio workflows, use the Gradle tool window or the Build menu. The most useful command-line equivalents are below.

Build everything:

```powershell
.\gradlew.bat build
```

Build only the shared library:

```powershell
.\gradlew.bat :shared:build
```

Build the Android example app debug variant:

```powershell
.\gradlew.bat :androidapp:assembleDebug
```

Build the Android example app release variant:

```powershell
.\gradlew.bat :androidapp:assembleRelease
```

Generate installable debug APK output:

```powershell
.\gradlew.bat :androidapp:assembleDebug
```

The generated APK is typically written under:

- `androidapp/build/outputs/apk/debug/`

## Run The Example App In Android Studio

### On An Emulator

1. Open `Tools > Device Manager`.
2. Create a virtual device if you do not already have one.
3. Start the emulator.
4. In the run configuration selector, choose the `androidapp` app configuration.
5. Click `Run`.

Android Studio will build the app, install it on the emulator, and launch it.

Command-line equivalent:

```powershell
.\gradlew.bat :androidapp:installDebug
```

### On A Real Device

1. On the Android device, enable Developer Options.
2. Enable USB debugging.
3. Connect the device to the development machine with USB.
4. Accept the RSA trust prompt on the device if it appears.
5. In Android Studio, select the connected device as the deployment target.
6. Run the `androidapp` configuration.

If Android Studio does not detect the device:

- Reconnect the USB cable.
- Confirm USB debugging is still enabled.
- On Windows, install any required OEM USB driver.
- Verify the device is visible with:

```powershell
adb devices
```

## Test The Project

This repository contains both unit-test and Android instrumentation-test source sets.

### Run Unit Tests

Run all unit tests:

```powershell
.\gradlew.bat test
```

Run unit tests for the shared module only:

```powershell
.\gradlew.bat :shared:test
```

Run unit tests for the Android example app debug variant:

```powershell
.\gradlew.bat :androidapp:testDebugUnitTest
```

You can also run these from Android Studio through the `test` source set or the Gradle tool window.

### Run Instrumentation Tests On An Emulator Or Device

Instrumentation tests require a running emulator or a connected physical Android device.

Run Android instrumentation tests for the example app:

```powershell
.\gradlew.bat :androidapp:connectedDebugAndroidTest
```

Android Studio flow:

1. Start an emulator or connect a device.
2. Open the `androidTest` source set in `androidapp`.
3. Right-click the test class or package.
4. Select `Run`.

## Useful Gradle Commands

List available tasks:

```powershell
.\gradlew.bat tasks
```

Clean the build outputs:

```powershell
.\gradlew.bat clean
```

Rebuild from scratch:

```powershell
.\gradlew.bat clean build
```

Install the debug app on the current emulator or connected device:

```powershell
.\gradlew.bat :androidapp:installDebug
```

Run instrumented tests on the connected target:

```powershell
.\gradlew.bat :androidapp:connectedDebugAndroidTest
```

## Common First-Run Issues

### Gradle Sync Fails

Check the SDK version and Gradle JDK first. For this project, the key requirements are Android SDK 35 and JDK 17 for Gradle.

### No Emulator Available

Create one from `Tools > Device Manager`, then start it before running the app or instrumentation tests.

### Device Not Detected

Use `adb devices` to confirm the device is available, then retry deployment from Android Studio.

## Daily Workflow

1. Open the project in Android Studio.
2. Sync Gradle if prompted.
3. Select the `androidapp` run configuration.
4. Start an emulator or connect a device.
5. Run the app.
6. Run unit tests or instrumentation tests as needed.