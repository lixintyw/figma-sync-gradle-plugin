plugins {
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    // Groovy for JsonSlurper (Gradle ships with Groovy, but we need it at compile time)
    implementation("org.codehaus.groovy:groovy-json:3.0.22")
}
