plugins {
    id("org.jetbrains.intellij") version "0.6.5"
    java
    kotlin("jvm") version "1.4.21"
}

group = "io.dragnea"
version = "0.1"

intellij {
    version = "IC-2020.3.2"

    setPlugins(
            "java",
            "org.jetbrains.kotlin:203-1.4.21-release-IJ5981.133"
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
