plugins {
    application
}

group = "net.trollyloki"
version = "1.0-SNAPSHOT"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(17)
    }
}

repositories {
    mavenCentral()
    mavenLocal()
}

dependencies {
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