plugins {
    java
    `maven-publish`
    application
}

fun getGitRef(): String = try {
    providers.exec {
        commandLine("git", "rev-parse", "--short", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim().ifEmpty { "unknown" }
} catch (ignored: Throwable) {
    "unknown"
}

fun gradleProperty(name: String): String = providers.gradleProperty(name).get()

val semver = "${gradleProperty("version_major")}." +
    "${gradleProperty("version_minor")}." +
    gradleProperty("version_patch")
val buildNumber = System.getenv("PROMOTED_NUMBER")
    ?: System.getenv("BUILD_NUMBER")
    ?: getGitRef()

version = "$semver+$buildNumber"
group = "li.cil.sedna"

java.toolchain.languageVersion = JavaLanguageVersion.of(21)

repositories {
    exclusiveContent {
        forRepository {
            maven {
                url = uri("https://fnuecke.github.io/maven")
            }
        }
        filter {
            includeModule("li.cil.sedna", "sedna-buildroot")
        }
    }
    mavenCentral()
}

dependencies {
    implementation(libs.log4j.api)
    implementation(libs.log4j.core)

    implementation(libs.picocli)
    implementation(libs.jline)
    implementation(libs.jline.terminal.jna)
    implementation(libs.jna)

    implementation(libs.ceres)
    implementation(libs.sedna)
    implementation(libs.sedna.buildroot)
    implementation(libs.sedna.cpm)

    testImplementation(libs.junit.api)
    testImplementation(libs.junit.params)
    testRuntimeOnly(libs.junit.engine)
    testRuntimeOnly(libs.junit.launcher)
}

publishing {
    publications {
        create<MavenPublication>("mavenJava") {
            artifact(tasks.jar)
        }
    }
    repositories {
        val mavenPath = System.getenv("MAVEN_PATH")
        if (!mavenPath.isNullOrBlank()) {
            maven {
                url = uri(mavenPath)
            }
        }
    }
}

application {
    mainClass = "li.cil.sedna.cli.Main"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
    standardOutput = System.out
}

tasks.test {
    useJUnitPlatform()
}
