import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// import org.jetbrains.kotlin.gradle.plugin.mpp.apple.XCFramework

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
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

    // jvm()

    sourceSets {
        commonMain.dependencies {
            //put your multiplatform dependencies here
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
            implementation(libs.play.services.location)
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

//tasks.register<JavaExec>("runJvmMain") {
//    group = "application"
//    description = "Run the JVM main class"
//
//    dependsOn("jvmJar")
//
//    classpath(
//        tasks.named("jvmJar"),
//        kotlin.targets.getByName("jvm").compilations.getByName("main").runtimeDependencyFiles
//    )
//
//    mainClass.set("com.msight.app.client.MainKt")
//}
