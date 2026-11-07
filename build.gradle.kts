// Gradle build for the Paper 26.3 version (the merged culling tools use Paper's data components). pom.xml is kept for
// Maven users; both build the same sources.
plugins {
    java
}

group = "TheServer"
version = "1.3.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.3.build.205-stable")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}
