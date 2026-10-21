plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "8.3.5"
}

group = "ru.hat1"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
}

dependencies {
    // Paper 1.21.11 API. Resolved from the officially checksum-verified jar in libs/
    // so builds never depend on the (sometimes unreachable) PaperMC repository.
    // SHA-256: c577b181c11a8674310e56c92a91e31c010b7f04c9bd10b91c3be18374401070
    compileOnly(files("libs/paper-api-1.21.11.jar"))
    compileOnly("net.luckperms:api:5.4")

    // Paper API references Adventure types in its signatures, and a local jar does
    // not pull transitive dependencies, so the modules are declared explicitly.
    compileOnly("net.kyori:adventure-api:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-legacy:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-gson:4.26.1")
    compileOnly("net.kyori:adventure-text-serializer-plain:4.26.1")

    // ItemDisplay transformations use org.joml types from the Bukkit API.
    compileOnly("org.joml:joml:1.10.8")

    // Vault is reached through reflection (see EconomyService), so no compile-time
    // dependency is needed. VaultAPI is not published to Maven Central anyway.

    testImplementation(kotlin("test"))
    testImplementation(files("libs/paper-api-1.21.11.jar"))
    testImplementation("net.kyori:adventure-api:4.26.1")
    testImplementation("net.kyori:adventure-text-serializer-legacy:4.26.1")
    testImplementation("net.kyori:adventure-text-serializer-gson:4.26.1")
    testImplementation("net.kyori:adventure-text-serializer-plain:4.26.1")
    testImplementation("org.joml:joml:1.10.8")
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
}

tasks {

    processResources {
        val props = mapOf("version" to project.version)
        inputs.properties(props)
        filesMatching("plugin.yml") {
            expand(props)
        }
    }

    shadowJar {
        archiveClassifier.set("")
        archiveBaseName.set("3DCosmetics")
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
        // Paper API classes require a server runtime; these unit tests cover pure logic.
        systemProperty("net.bytebuddy.experimental", "true")
    }
}
