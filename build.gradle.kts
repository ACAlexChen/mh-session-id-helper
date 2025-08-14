plugins {
  application
  kotlin("jvm") version "2.2.0"
  kotlin("plugin.serialization") version "2.2.0"
  id("com.google.cloud.tools.jib") version "3.4.5"
}

application {
  mainClass.set("net.ac_official.mhSessionIdHelper.MainKt")
}

group = "net.ac-official"
version = "1.1-SNAPSHOT"

repositories {
  mavenCentral()
  gradlePluginPortal()
}

dependencies {
  // https://mvnrepository.com/artifact/io.ktor/ktor-client-core
  implementation("io.ktor:ktor-client-core:3.2.3")
  // https://mvnrepository.com/artifact/io.ktor/ktor-client-cio
  implementation("io.ktor:ktor-client-cio:3.2.3")
  // https://mvnrepository.com/artifact/io.ktor/ktor-client-websockets
  implementation("io.ktor:ktor-client-websockets:3.2.3")
  // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-serialization-json
  implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.9.0")
  // https://mvnrepository.com/artifact/org.jetbrains.kotlinx/kotlinx-coroutines-core
  implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
  // https://mvnrepository.com/artifact/io.ktor/ktor-client-content-negotiation
  implementation("io.ktor:ktor-client-content-negotiation:3.2.3")
  // https://mvnrepository.com/artifact/io.ktor/ktor-serialization-kotlinx-json
  implementation("io.ktor:ktor-serialization-kotlinx-json:3.2.3")
}

tasks.test {
  useJUnitPlatform()
}
kotlin {
  jvmToolchain(22)
}

jib {
  from {
    image = "docker.ac-official.net/temurin-local:22-jre-jammy"
  }
  to {
    image = "mh-session-id-helper"
    tags = setOf("latest", "${project.version}")
  }
  container {
    mainClass = "net.ac_official.mhSessionIdHelper.MainKt"
  }
}
