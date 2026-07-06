plugins {
    alias(libs.plugins.vaadin)
    application
}

dependencies {
    // the tab-scope library being demoed
    implementation(project(":tab-scope"))

    // Vaadin
    implementation(libs.vaadin.core)
    if (!vaadin.effective.productionMode.get()) {
        implementation(libs.vaadin.dev)
    }

    // Vaadin-Boot
    implementation(libs.vaadin.boot)

    implementation(libs.jetbrains.annotations)

    // logging
    // currently we are logging through the SLF4J API to SLF4J-Simple. See src/main/resources/simplelogger.properties file for the logger configuration
    implementation(libs.slf4j.simple)

    // Fast Vaadin unit-testing with Karibu-Testing: https://github.com/mvysny/karibu-testing
    testImplementation(libs.kaributesting)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

application {
    mainClass = "testapp.Main"
}
