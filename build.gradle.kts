plugins {
    java
}

group = "dev.dackoro"
version = "1.2.0"

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

// -PcodeOnly: jar without Common/ and Server/ (deploy.ps1 -Dev). The assets then come from the
// "Mob Battle Assets DEV" folder pack, which links to this repo and hot-reloads in game.
val codeOnly = providers.gradleProperty("codeOnly").isPresent

tasks.jar {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    archiveBaseName.set("MobBattle")
    inputs.property("codeOnly", codeOnly)
    from(".") {
        include("manifest.json")
        include("README.md")
        include("LICENSE")
        include("icon-256.png")
        if (!codeOnly) {
            include("Common/**")
            include("Server/**")
        }
    }
}
