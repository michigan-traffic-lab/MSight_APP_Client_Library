import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    androidTarget {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }
    jvm {
        compilations.all {
            compileTaskProvider.configure {
                compilerOptions {
                    jvmTarget.set(JvmTarget.JVM_1_8)
                }
            }
        }
    }
    
    // iOS is not supported yet. To bring it up:
    //  1. Uncomment the XCFramework import at the top of this file and the block below.
    //  2. Add the Ktor Darwin engine to an iosMain source set:
    //         iosMain.dependencies { implementation("io.ktor:ktor-client-darwin:2.3.12") }
    //  3. Create shared/src/iosMain/kotlin/com/msight/app/client/ and implement the two
    //     `expect` declarations the library needs — `createPlatformHttpClient`
    //     (PlatformHttpClient.kt) and `PlatformContext` / `PlatformLocationProvider`
    //     (LocationEmitter.kt), the latter over CLLocationManager.
    // Everything else — protocol handling, MAP geometry, the signal state machine — is already
    // shared in commonMain and needs no per-platform work.
//    val xcf = XCFramework()
//    listOf(
//        iosX64(),
//        iosArm64(),
//        iosSimulatorArm64()
//    ).forEach {
//        it.binaries.framework {
//            baseName = "shared"
//            xcf.add(this)
//            isStatic = true
//        }
//    }
    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.0")
            implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
            implementation("io.ktor:ktor-client-core:2.3.12")
            implementation("io.ktor:ktor-client-websockets:2.3.12")
        }
        androidMain.dependencies {
            implementation("io.ktor:ktor-client-okhttp:2.3.12")
            implementation(libs.play.services.location)
        }
        jvmMain.dependencies {
            implementation("io.ktor:ktor-client-cio:2.3.12")
        }
        commonTest.dependencies {
            implementation(libs.kotlin.test)
        }
    }
}

android {
    namespace = "com.msight.app.client"
    compileSdk = 35
    defaultConfig {
        minSdk = 24
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
}

tasks.register<JavaExec>("runJvmMain") {
    group = "application"
    description = "Run the JVM main class"

    dependsOn("jvmJar")

    classpath(
        tasks.named("jvmJar"),
        kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles
    )

    mainClass.set("com.msight.app.client.MainKt")
}
