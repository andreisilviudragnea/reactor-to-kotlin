plugins {
    id("org.jetbrains.intellij") version "0.4.21"
    java
    kotlin("jvm") version "1.4.0"
}

group = "io.dragnea"
version = "0.1"

intellij {
    version = "IU-LATEST-EAP-SNAPSHOT"

    setPlugins(
            "java",
            "org.jetbrains.kotlin:1.4.0-release-IJ2020.2-1"
    )

    updateSinceUntilBuild = false
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
}

tasks {
    compileKotlin {
        kotlinOptions.jvmTarget = "11"
    }
    compileTestKotlin {
        kotlinOptions.jvmTarget = "11"
    }
    runIde {
        maxHeapSize = "4G"
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation(kotlin("reflect"))
}
