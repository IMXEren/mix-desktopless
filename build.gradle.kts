import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import com.mikepenz.aboutlibraries.plugin.DuplicateMode
import com.mikepenz.aboutlibraries.plugin.DuplicateRule

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose)
    alias(libs.plugins.about.libraries)
    // Shadow plugin is provided by buildSrc to enable the custom NoticeMergeTransformer.
    // Applied without a version here; the version is pinned in buildSrc/build.gradle.kts.
    id("com.gradleup.shadow")
    application
    `maven-publish`
}

group = "app.morphe"

// ============================================================================
// JVM / Kotlin Configuration
// ============================================================================
kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
        vendor.set(JvmVendorSpec.JETBRAINS)
    }
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// ============================================================================
// Application Entry Point
// ============================================================================
// Shadow JAR reads this for Main-Class manifest attribute.
//
//   No args / double-click  →  GUI (Compose Desktop)
//   With args (terminal)    →  CLI (PicoCLI)
application {
    mainClass.set("app.morphe.MorpheLauncherKt")
}

// ============================================================================
// Repositories
// ============================================================================
repositories {
    mavenLocal()
    mavenCentral()
    google()
    maven { url = uri("https://maven.pkg.jetbrains.space/public/p/compose/dev") }
    maven {
        // A repository must be specified for some reason. "registry" is a dummy.
        url = uri("https://maven.pkg.github.com/MorpheApp/registry")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("GITHUB_ACTOR")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("GITHUB_TOKEN")
        }
    }
    // Obtain baksmali/smali from source builds - https://github.com/iBotPeaches/smali
    // Remove when official smali releases come out again.
    maven { url = uri("https://jitpack.io") }
}

dependencies {
    api(libs.morphe.patcher)
    implementation(libs.arsclib)
    implementation(libs.morphe.library)
    implementation(libs.jadb)
    implementation(libs.picocli)

    // -- Compose Desktop ---------------------------------------------------
    // Platform-independent: single JAR runs on all supported OSes.
    // Skiko auto-detects the OS at runtime and loads the correct native library.
    implementation(compose.desktop.macos_arm64)
    implementation(compose.desktop.macos_x64)
    implementation(compose.desktop.linux_x64)
    implementation(compose.desktop.linux_arm64)
    implementation(compose.desktop.windows_x64)
    implementation(compose.components.resources)
    @Suppress("DEPRECATION")
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)

    // -- Async / Serialization ---------------------------------------------
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.kotlinx.serialization.json)

    // -- Networking (GUI) --------------------------------------------------
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.ktor.client.logging)
    implementation(libs.slf4j.nop)

    // -- DI / Navigation (GUI) ---------------------------------------------
    implementation(platform(libs.koin.bom))
    implementation(libs.koin.core)
    implementation(libs.koin.compose)

    implementation(libs.voyager.navigator)
    implementation(libs.voyager.screenmodel)
    implementation(libs.voyager.koin)
    implementation(libs.voyager.transitions)

    // -- JNA (Windows DWM title bar tinting) -------------------------------
    implementation(libs.jna)
    implementation(libs.jna.platform)

    // -- License attribution UI (About / Licenses screen) -----------------
    implementation(libs.about.libraries.core)
    implementation(libs.about.libraries.m3)

    // -- Testing -----------------------------------------------------------
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.params)
    testImplementation(libs.mockk)
}

aboutLibraries {
    collect {
        configPath = file("aboutlibraries")
    }
    library {
        duplicationMode = DuplicateMode.MERGE
        duplicationRule = DuplicateRule.EXACT
    }
}

// ============================================================================
// Tasks
// ============================================================================
tasks {
    jar {
        manifest {
            attributes(
                "Implementation-Title" to project.name,
                "Implementation-Version" to project.version
            )
        }
    }

    test {
        useJUnitPlatform()
        testLogging {
            events("PASSED", "SKIPPED", "FAILED")
        }
    }

    processResources {
        // Make sure the licenses are generated before the resources are processed
        dependsOn("exportLibraryDefinitions")
        from(layout.buildDirectory.file("generated/aboutLibraries/aboutlibraries.json"))

        // Only expand properties files, not binary files like PNG/ICO
        filesMatching("**/*.properties") {
            expand("projectVersion" to project.version)
        }
        // Bundle the project's NOTICE (GPL 7b/7c) and LICENSE into META-INF so they
        // land in the main JAR before the Shadow merge. Source of truth stays at the
        // repo root — these are copied at build time, not duplicated in source control.
        from(arrayOf(rootProject.file("NOTICE"), rootProject.file("LICENSE"))) {
            into("META-INF")
        }
    }

    // -------------------------------------------------------------------------
    // Shadow JAR — the only distribution artifact
    // -------------------------------------------------------------------------
    shadowJar {
        archiveBaseName.set("mix-desktopless")
        archiveClassifier.set("all")
        exclude(
            "/prebuilt/linux/aapt",
            "/prebuilt/windows/aapt.exe",
            "/prebuilt/*/aapt_*",
        )
        // NOTICE/LICENSE handling:
        //   * Global strategy is EXCLUDE (first-wins) so duplicates at non-transformed
        //     paths — including native libs like libskiko-*.dylib — are deduplicated.
        //     INCLUDE globally would double-pack every colliding resource and bloat the
        //     JAR by tens of MB.
        //   * For META-INF/NOTICE* paths specifically, strategy is flipped to INCLUDE
        //     via filesMatching below so all dep NOTICEs reach NoticeMergeTransformer
        //     (Shadow drops duplicates before transformers run under EXCLUDE — see
        //     ShadowJar.kt Kdoc).
        //   * Root /NOTICE and /LICENSE — our project's files, added below via from().
        //     With EXCLUDE, the first occurrence wins. Dep JARs with root-level NOTICE/
        //     LICENSE lose because our from() block is declared before Shadow processes
        //     dependency configurations.
        //   * META-INF/LICENSE — our GPL LICENSE, placed via processResources so it
        //     lands in the main JAR ahead of dep copies. Dep LICENSE files at unique
        //     paths (META-INF/androidx/**/LICENSE.txt, etc.) are preserved untouched.
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        filesMatching(listOf(
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/NOTICE.md",
        )) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        from(rootProject.file("NOTICE"), rootProject.file("LICENSE"))
        minimize {
            exclude(dependency("org.bouncycastle:.*"))
            exclude(dependency("com.github.REAndroid:ARSCLib"))
            exclude(dependency("app.morphe:morphe-patcher"))
            // Ktor uses ServiceLoader
            exclude(dependency("io.ktor:.*"))
            exclude(dependency("org.slf4j:.*"))
            // Koin uses reflection
            exclude(dependency("io.insert-koin:.*"))
            // Coroutines Swing provides Dispatchers.Main via ServiceLoader
            exclude(dependency("org.jetbrains.kotlinx:kotlinx-coroutines-swing"))
            // JNA uses reflection + native loading for DWM title bar tinting
            exclude(dependency("net.java.dev.jna:.*"))
            // Skiko uses ServiceLoader for native registration. Same class of problem as Ktor / Koin / JNA above.
            exclude(dependency("org.jetbrains.skiko:.*"))
        }

        mergeServiceFiles()

        // Concatenate every META-INF/NOTICE (and .txt/.md variants) from all dep JARs
        // plus our own into a single merged file. Satisfies Apache 2.0 §4(d) which
        // requires preserving attribution NOTICEs of Apache-licensed dependencies.
        //
        // Shadow's built-in ApacheNoticeResourceTransformer hardcodes ASF-branded
        // copyright text that cannot be fully disabled, which would falsely attribute
        // this GPL project to the Apache Software Foundation. NoticeMergeTransformer
        // (in buildSrc) is a minimal verbatim concatenator with no boilerplate.
        transform(NoticeMergeTransformer::class.java)
    }

    // -------------------------------------------------------------------------
    // Shadow CLI JAR — GUI-free artifact (no Compose, Koin, Voyager, JNA, AboutLibraries)
    // -------------------------------------------------------------------------
    // Excluding app/morphe/gui/** is the ONLY surface-specific rule. Shadow minimize
    // handles the rest: any dependency reachable ONLY from excluded GUI classes is
    // automatically stripped. No per-dependency exclude list to rot.
    register<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowCliJar") {
        archiveBaseName.set("mix-desktopless")
        archiveClassifier.set("cli")

        from(sourceSets.main.get().output) {
            // Exclude at INPUT level so minimize never sees GUI class references.
            // If we used the task-level exclude(), minimize would still analyze GUI
            // bytecode and pull in Compose/Koin/Voyager/Skiko as "used" — then the
            // post-minimize filter strips the GUI classes but leaves their deps behind.
            exclude("app/morphe/gui/**")
            exclude("fonts/**")
            exclude("morphe_logo.*")
        }
        configurations = listOf(project.configurations["runtimeClasspath"])

        // Exclude entire GUI-only dependency groups at the JAR level.
        // minimize can't strip native resources (.dylib/.so/.dll).
        // Group patterns use .* suffix because Compose/Skiko use sub-groups
        // (org.jetbrains.compose.desktop, org.jetbrains.skiko, androidx.compose.runtime, etc.)
        dependencies {
            exclude(dependency("org.jetbrains.compose.*:.*"))
            exclude(dependency("org.jetbrains.skiko.*:.*"))
            exclude(dependency("androidx.compose.*:.*"))
            exclude(dependency("io.insert-koin:.*"))
            exclude(dependency("cafe.adriel.voyager:.*"))
            exclude(dependency("net.java.dev.jna:.*"))
            exclude(dependency("com.mikepenz:aboutlibraries.*"))
        }

        exclude(
            // Broken JAR signatures from merged dependency JARs — the JVM
            // rejects the entire JAR if these remain with invalid hashes.
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/INDEX.LIST",
            // Prebuilt aapt binaries (same as main JAR)
            "/prebuilt/linux/aapt",
            "/prebuilt/windows/aapt.exe",
            "/prebuilt/*/aapt_*",
        )
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        filesMatching(listOf(
            "META-INF/NOTICE",
            "META-INF/NOTICE.txt",
            "META-INF/NOTICE.md",
        )) {
            duplicatesStrategy = DuplicatesStrategy.INCLUDE
        }
        from(rootProject.file("NOTICE"), rootProject.file("LICENSE"))
        minimize {
            // Shared deps that use reflection / ServiceLoader.
            // GUI-only deps (Compose, Koin, Voyager, JNA, AboutLibraries) are
            // excluded at the JAR level above; they never reach minimize.
            // kotlinx-coroutines-swing is NOT excluded — no CLI/engine code
            // imports it, so minimize strips it automatically.
            exclude(dependency("org.bouncycastle:.*"))
            exclude(dependency("com.github.REAndroid:ARSCLib"))
            exclude(dependency("app.morphe:morphe-patcher"))
            exclude(dependency("io.ktor:.*"))           // ServiceLoader
            exclude(dependency("org.slf4j:.*"))
        }
        mergeServiceFiles()
        transform(NoticeMergeTransformer::class.java)

        manifest {
            attributes(
                "Main-Class" to "app.morphe.cli.CliMainKt",
                "Implementation-Title" to "${project.name}-cli",
                "Implementation-Version" to project.version,
            )
        }
    }

    distTar {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    distZip {
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    publish {
        dependsOn(shadowJar, named("shadowCliJar"))
    }
}

// ============================================================================
// Publishing
// ============================================================================
// Required by gradle-semantic-release-plugin — the publish task must exist.
publishing {
    repositories {
        mavenLocal()
    }

    publications {
        create<MavenPublication>("morphe-desktop-publication") {
            from(components["java"])
        }
    }
}
