plugins {
    `java-gradle-plugin`
    `java-library`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.codehaus.groovy:groovy-json:3.0.22")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

gradlePlugin {
    plugins {
        create("figmaSync") {
            id = "com.figma.sync"
            implementationClass = "com.figma.sync.FigmaSyncPlugin"
            displayName = "Figma Sync Plugin"
            description = "Auto-sync Figma design assets (icons, colors, fonts, Lottie) to Android resources during Gradle build"
        }
    }
}
