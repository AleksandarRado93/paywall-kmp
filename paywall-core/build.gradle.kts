import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlinMultiplatform)
    alias(libs.plugins.androidLibrary)
    `maven-publish`
}

group = "io.github.aleksandarrado93.paywall"
version = "0.2.1"

kotlin {
    @OptIn(ExperimentalKotlinGradlePluginApi::class)
    compilerOptions {
        freeCompilerArgs.add("-Xexpect-actual-classes")
    }

    androidTarget {
        publishLibraryVariants("release")
        @OptIn(ExperimentalKotlinGradlePluginApi::class)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    listOf(
        iosX64(),
        iosArm64(),
        iosSimulatorArm64(),
    ).forEach { iosTarget ->
        iosTarget.binaries.framework {
            baseName = "PaywallCore"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            api(libs.kotlinx.coroutines.core)
            api(libs.androidx.lifecycle.viewmodel)
            api(libs.purchases.kmp.core)
        }
    }
}

android {
    namespace = "io.github.aleksandarrado93.paywall"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.android.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

publishing {
    publications {
        withType<MavenPublication> {
            pom {
                name.set("paywall-core")
                description.set(
                    "Reusable RevenueCat-backed subscription/billing layer for Kotlin " +
                        "Multiplatform apps (Android + iOS). Provides BillingManager, " +
                        "PaywallViewModel, and bridge interfaces for analytics and crash " +
                        "reporting integration.",
                )
                url.set("https://github.com/AleksandarRado93/paywall-kmp")

                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("http://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }

                developers {
                    developer {
                        id.set("AleksandarRado93")
                        name.set("Aleksandar Radovanovic")
                        url.set("https://github.com/AleksandarRado93")
                    }
                }

                scm {
                    connection.set("scm:git:https://github.com/AleksandarRado93/paywall-kmp.git")
                    developerConnection.set("scm:git:ssh://git@github.com/AleksandarRado93/paywall-kmp.git")
                    url.set("https://github.com/AleksandarRado93/paywall-kmp")
                }
            }
        }
    }

    repositories {
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/AleksandarRado93/paywall-kmp")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.token")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
        }
    }
}
