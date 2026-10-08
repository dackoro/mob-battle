plugins {
    java
}

group = "dev.dackoro"
version = "0.0.1"

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

repositories {
    mavenCentral()
    maven {
        name = "hytale"
        url = uri("https://maven.hytale.com/release")
    }
}

// Compile against a local HytaleServer.jar (e.g. the pre-release patchline) with
// -PhytaleServerJar=<path>; otherwise use the release artifact from Maven.
val hytaleServerJar = providers.gradleProperty("hytaleServerJar").orNull

dependencies {
    if (hytaleServerJar != null) {
        compileOnly(files(hytaleServerJar))
    } else {
        compileOnly("com.hypixel.hytale:Server:+")
    }
}

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("MobBattle")
    from(".") {
        include("manifest.json")
        include("README.md")
        include("Common/**")
        include("Server/**")
    }
}
