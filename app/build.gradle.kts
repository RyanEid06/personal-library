import java.io.File
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.google.devtools.ksp)
}

abstract class MemoraBuildFeatures {
    @get:javax.inject.Inject
    abstract val features: org.gradle.api.configuration.BuildFeatures
}

// Reject caching before assigning signing passwords to the Android DSL.
if (providers.environmentVariable("RELEASE_STORE_PASSWORD").isPresent || providers.environmentVariable("RELEASE_KEY_PASSWORD").isPresent) {
    check(!objects.newInstance<MemoraBuildFeatures>().features.configurationCache.requested.getOrElse(false)) {
        "Memora signing requires --no-configuration-cache. Never use build scans or debug logging with signing credentials."
    }
}
val releaseStorePassword = providers.environmentVariable("RELEASE_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("RELEASE_KEY_PASSWORD").orNull
val phoneApks = providers.gradleProperty("memoraPhoneApks").map { it.toBoolean() }.getOrElse(false)
val optimizedTests = providers.gradleProperty("memoraOptimizedTests").map { it.toBoolean() }.getOrElse(false)

abstract class VerifyMemoraReleaseSigning : DefaultTask() {
    @TaskAction
    fun verifyIdentity() {
        val names = listOf("RELEASE_KEYSTORE_PATH", "RELEASE_STORE_PASSWORD", "RELEASE_KEY_ALIAS", "RELEASE_KEY_PASSWORD")
        val values = names.associateWith { System.getenv(it) }
        val missing = names.filter { values[it].isNullOrBlank() }
        check(missing.isEmpty()) { "Memora release signing requires: ${missing.joinToString()}. Use scripts/Build-MemoraRelease.ps1; unsigned official releases are forbidden." }
        check(values["RELEASE_KEY_ALIAS"] == "memora-release") { "Memora release alias must be memora-release." }
        val canonical = File(System.getProperty("user.home"), "Documents/Memora-Signing/memora-release.p12").canonicalFile
        val keystore = File(values.getValue("RELEASE_KEYSTORE_PATH")!!).canonicalFile
        check(keystore == canonical && keystore.isFile) { "Memora release requires the canonical Documents/Memora-Signing/memora-release.p12 file." }
        val hash = MessageDigest.getInstance("SHA-256").digest(keystore.readBytes()).joinToString("") { "%02X".format(it) }
        check(hash == "908E801EE64FAD19978A8D30E3E5951C4A3955BF59154AEA32182F0EF1C2FAD8") { "Memora permanent keystore file hash mismatch." }
        val storePass = values.getValue("RELEASE_STORE_PASSWORD")!!.toCharArray()
        val keyPass = values.getValue("RELEASE_KEY_PASSWORD")!!.toCharArray()
        try {
            val store = KeyStore.getInstance("PKCS12")
            keystore.inputStream().use { store.load(it, storePass) }
            check(store.isKeyEntry("memora-release"))
            check(store.getKey("memora-release", keyPass) is PrivateKey)
            val cert = store.getCertificate("memora-release") as X509Certificate
            cert.checkValidity()
            check((cert.publicKey as RSAPublicKey).modulus.bitLength() == 4096)
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(cert.encoded).joinToString("") { "%02X".format(it) }
            check(fingerprint == "98686D31BFE4370CC30024439B56D9E72DE71C081951D31F5D4FDD31D4EB0598")
        } catch (_: Exception) {
            throw GradleException("Memora permanent signing certificate, alias or password validation failed. No official APK may be published.")
        } finally {
            storePass.fill('\u0000')
            keyPass.fill('\u0000')
        }
    }
}

val verifyMemoraReleaseSigning = tasks.register<VerifyMemoraReleaseSigning>("verifyMemoraReleaseSigning") {
    group = "verification"
    description = "Reject missing credentials or any identity other than the owner's permanent Memora key."
    notCompatibleWithConfigurationCache("Permanent signing credentials must not be persisted.")
}
tasks.configureEach {
    if (name.contains("Release", ignoreCase = true) && name != "verifyMemoraReleaseSigning") {
        dependsOn(verifyMemoraReleaseSigning)
    }
}

android {
    namespace = "com.example"
    compileSdk { version = release(36) { minorApiLevel = 1 } }

    defaultConfig {
        applicationId = "com.personallibrary.app"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        buildConfigField("String", "DEFAULT_BACKEND_URL", "\"https://personal-library-backend-49w7.onrender.com\"")
    }

    signingConfigs {
        create("release") {
            val keystorePath = System.getenv("RELEASE_KEYSTORE_PATH")
            if (!keystorePath.isNullOrBlank()) {
                storeFile = file(keystorePath)
            }
            storeType = "PKCS12"
            storePassword = releaseStorePassword
            keyAlias = System.getenv("RELEASE_KEY_ALIAS")
            keyPassword = releaseKeyPassword
        }
    }

    buildTypes {
        release {
            isCrunchPngs = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            // Standard Android debug signing is automatic
        }
        // Exercises release optimization in CI without giving CI the permanent key.
        // This build is verification-only and must never be published as official.
        create("optimizedCheck") {
            initWith(getByName("release"))
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
            if (optimizedTests) {
                proguardFiles("optimized-test-rules.pro")
            }
        }
    }

    testBuildType = if (optimizedTests) "optimizedCheck" else "debug"

    // Opt-in keeps normal debug/emulator tasks unchanged. The universal output
    // retains all dependency ABIs; the ARM64 split is a standalone phone APK.
    splits {
        abi {
            isEnable = phoneApks
            reset()
            include("arm64-v8a")
            isUniversalApk = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":core"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material.icons.extended)

    implementation(libs.androidx.biometric)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    "ksp"(libs.androidx.room.compiler)
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(libs.coil.compose)
    implementation(libs.coil.gif)
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation("androidx.work:work-testing:2.10.0")
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    androidTestImplementation("androidx.work:work-testing:2.10.0")
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
