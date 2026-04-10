plugins {
    application
    alias(libs.plugins.jib)
}

group = "net.trollyloki"
version = "1.2.0-experimental"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

repositories {
    mavenCentral()
    mavenLocal() // still needed for jicsit for now
}

dependencies {
    implementation("org.jspecify:jspecify:1.0.0")
    runtimeOnly(libs.logback.classic)

    implementation(libs.jda) {
        exclude(module = "opus-java")
        exclude(module = "tink")
    }
    implementation(libs.jicsit.server)
    implementation(libs.jackson.databind)
}

application {
    mainClass = "net.trollyloki.discit.Discit"
}

jib {
    to {
        image = "trollyloki/discit"
        tags = setOf("$version")
    }
    outputPaths {
        val path = layout.buildDirectory.file("discit-$version-image").get().asFile.path
        tar = "$path.tar"
        digest = "$path.digest"
        imageId = "$path.id"
        imageJson = "$path.json"
    }
}