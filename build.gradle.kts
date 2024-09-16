import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

val ktor_version: String by project
val kotlin_version: String by project
val logback_version: String by project
val postgres_version: String by project
val hikari_version: String by project
val flyway_version: String by project
val jackson_version: String by project
val arrow_version: String by project
val microsoft_sdk_version: String by project
val microsoft_azure_version: String by project

plugins {
    kotlin("jvm") version "2.0.20"
    id("io.ktor.plugin") version "2.3.12"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.0.20"
    id("com.diffplug.spotless") version "6.25.0"
}

kotlin {
    jvmToolchain(17)
}

spotless {
    kotlin {
        ktfmt().dropboxStyle()
    }
}

group = "no.nav.delta"
version = "0.0.1"
application {
    mainClass.set("no.nav.delta.ApplicationKt")

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
    testImplementation("io.ktor:ktor-server-tests-jvm:$ktor_version")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit:$kotlin_version")

    //Database
    implementation("org.postgresql:postgresql:$postgres_version")
    implementation("com.zaxxer:HikariCP:$hikari_version")
    implementation("org.flywaydb:flyway-core:$flyway_version")

    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:$jackson_version")
    implementation("com.fasterxml.jackson.module:jackson-module-jaxb-annotations:$jackson_version")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:$jackson_version")

    implementation("io.arrow-kt:arrow-core:$arrow_version")
    implementation("io.arrow-kt:arrow-fx-coroutines:$arrow_version")

    implementation("com.microsoft.graph:microsoft-graph:$microsoft_sdk_version")
    implementation("com.microsoft.azure:msal4j:$microsoft_azure_version")
}