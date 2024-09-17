val ktor_version = "2.3.12"
val logback_version = "1.5.8"
val postgres_version = "42.7.4"
val hikari_version = "5.1.0"
val flyway_version = "10.18.0"
val jackson_version = "2.17.2"
val arrow_version = "1.2.4"
val microsoft_sdk_version = "5.65.0"
val microsoft_azure_version = "1.13.9"

val appMainClass = "no.nav.delta.ApplicationKt"

plugins {
    kotlin("jvm") version "2.0.20"
    id("io.ktor.plugin") version "2.3.12"
    id("com.gradleup.shadow") version "8.3.1"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("com.diffplug.spotless") version "6.25.0"
}

kotlin {
    jvmToolchain(21)
}

spotless {
    kotlin {
        ktfmt().dropboxStyle()
    }
}

group = "no.nav.delta"
version = "0.0.1"
application {
    mainClass.set(appMainClass)

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-swagger-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")
    implementation("ch.qos.logback:logback-classic:$logback_version")

    //Database
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    implementation("org.flywaydb:flyway-database-postgresql:$flyway_version")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jackson_version")
    implementation("com.fasterxml.jackson.module:jackson-module-jaxb-annotations:$jackson_version")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jackson_version")

    implementation("io.arrow-kt:arrow-core:$arrow_version")
    implementation("io.arrow-kt:arrow-fx-coroutines:$arrow_version")

    implementation("com.microsoft.graph:microsoft-graph:$microsoft_sdk_version")
    implementation("com.microsoft.azure:msal4j:$microsoft_azure_version")
}