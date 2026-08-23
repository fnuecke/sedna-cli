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
    implementation("org.apache.logging.log4j:log4j-api:2.17.0")
    implementation("org.apache.logging.log4j:log4j-core:2.17.0")

    implementation("info.picocli:picocli:4.7.6")
    implementation("org.jline:jline:3.20.0")
    implementation("org.jline:jline-terminal-jna:3.20.0")
    implementation("net.java.dev.jna:jna:5.9.0")

    implementation("li.cil.ceres:ceres:0.0.6")
    implementation("li.cil.sedna:sedna:2.1.0")
    implementation("li.cil.sedna:sedna-buildroot:0.0.9")
    implementation("li.cil.sedna:sedna-cpm:0.0.0")
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
