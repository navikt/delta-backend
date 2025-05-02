val ktor_version = "3.1.2"
val logback_version = "1.5.18"
val logstash_version = "8.1"
val postgres_version = "42.7.5"
val hikari_version = "6.3.0"
val flyway_version = "11.8.0"
val jackson_version = "2.19.0"
val arrow_version = "2.1.1"
val microsoft_sdk_version = "6.36.0"
val microsoft_azure_version = "1.20.1"

val junit_version = "5.12.2"
val testcontainers_version = "1.21.0"

val appMainClass = "no.nav.delta.ApplicationKt"

plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "2.1.20"
}

kotlin {
    jvmToolchain(21)
}

group = "no.nav.delta"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-call-logging:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-auth-jwt-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-swagger-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-content-negotiation-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-jackson-jvm:$ktor_version")
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm:$ktor_version")
    implementation("io.ktor:ktor-server-netty-jvm:$ktor_version")

    implementation("ch.qos.logback:logback-classic:$logback_version")
    implementation("net.logstash.logback:logstash-logback-encoder:$logstash_version")

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

    testImplementation("org.testcontainers:postgresql:$testcontainers_version")
    testImplementation(platform("org.junit:junit-bom:$junit_version"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks {
    withType<Jar> {
        archiveBaseName.set("app")

        manifest {
            attributes["Main-Class"] = appMainClass
            attributes["Class-Path"] = configurations.runtimeClasspath.get().joinToString(separator = " ") {
                it.name
            }
        }

        doLast {
            configurations.runtimeClasspath.get().forEach {
                val file = File("${layout.buildDirectory.get()}/libs/${it.name}")
                if (!file.exists())
                    it.copyTo(file)
            }
        }
    }

    withType<Wrapper> {
        gradleVersion = "8.13"
    }

    withType<Test> {
        useJUnitPlatform()
        testLogging {
            showExceptions = true
        }
    }
}
