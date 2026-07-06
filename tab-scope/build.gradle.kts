dependencies {
    // logging: the library logs tab-scope lifecycle events via the SLF4J API.
    implementation(libs.slf4j.api)

    // Vaadin is declared compileOnly so that consumers bring their own Vaadin version;
    // this add-on does not pin one on them.
    compileOnly(libs.vaadin.core)
    compileOnly(libs.jetbrains.annotations)
    // VaadinSession implements jakarta.servlet's HttpSessionBindingListener; the servlet API is
    // 'provided' by Vaadin, so we need it on the compile classpath explicitly.
    compileOnly(libs.jakarta.servlet.api)

    // tests
    testImplementation(libs.vaadin.core)
    // Fast Vaadin unit-testing with Karibu-Testing: https://github.com/mvysny/karibu-testing
    testImplementation(libs.kaributesting)
    testImplementation(libs.junit)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation(libs.slf4j.simple)
}

val publishing = ext["publishing"] as (artifactId: String) -> Unit
publishing("tab-scope")
