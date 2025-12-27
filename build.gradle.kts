plugins {
    id("java")
    id("io.github.goooler.shadow") version "8.1.7"
}

group = "com.artillexstudios.axvouchers"
version = "1.0.0"

repositories {
    mavenCentral()

    maven("https://repo.artillex-studios.com/releases/")
    maven("https://repo.papermc.io/repository/maven-public/")
    maven("https://repo.extendedclip.com/content/repositories/placeholderapi/")
}

dependencies {
    implementation("com.artillexstudios.axapi:axapi:1.4.830:all")
    implementation("com.zaxxer:HikariCP:7.0.2")
    implementation("dev.triumphteam:triumph-gui:3.1.13")
    compileOnly("com.h2database:h2:2.4.240")
    compileOnly("org.xerial:sqlite-jdbc:3.51.1.0")
    compileOnly("io.papermc.paper:paper-api:1.20.4-R0.1-SNAPSHOT")
    compileOnly("org.slf4j:slf4j-api:2.0.17")
    implementation("commons-io:commons-io:2.21.0")
    compileOnly("org.apache.commons:commons-text:1.15.0")
    implementation("org.apache.commons:commons-math3:3.6.1")
    compileOnly("me.clip:placeholderapi:2.11.7")
    compileOnly("it.unimi.dsi:fastutil:8.5.18")
}

tasks {
    compileJava {
        options.encoding = Charsets.UTF_8.name()
        options.compilerArgs.add("-parameters")
        options.isFork = true
    }

    shadowJar {
        relocate("com.artillexstudios.axapi", "com.artillexstudios.axvouchers.libs.axapi")
    }

    processResources {
        filteringCharset = Charsets.UTF_8.name()

        filesMatching("plugin.yml") {
            expand(mapOf("version" to project.version,))
        }
    }

    build {
        dependsOn(shadowJar)
    }
}
