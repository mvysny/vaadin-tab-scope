import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    java
    `maven-publish`
    signing
    id("io.github.gradle-nexus.publish-plugin") version "2.0.0"
}

defaultTasks("clean", "build")

allprojects {
    group = "com.github.mvysny.vaadintabscope"
    version = "0.1-SNAPSHOT"

    repositories {
        mavenCentral()
        // Forward-looking fallback source for Karibu-Testing 2.7.1-SNAPSHOT: only helps if a
        // snapshot is actually deployed here — today none is, so the local composite build in
        // settings.gradle.kts is what resolves it. Remove once 2.7.1 is on Maven Central.
        maven("https://central.sonatype.com/repository/maven-snapshots/") {
            mavenContent { snapshotsOnly() }
        }
    }
}

subprojects {
    apply {
        plugin("java")
        plugin("maven-publish")
        plugin("org.gradle.signing")
    }

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    // creates a reusable function which configures proper deployment to Maven Central.
    // Only the library module calls this; testapp never does, so nothing tries to publish the demo.
    ext["publishing"] = { artifactId: String ->
        java {
            withJavadocJar()
            withSourcesJar()
        }

        tasks.withType<Javadoc> {
            isFailOnError = false
        }

        publishing {
            publications {
                create("mavenJava", MavenPublication::class.java).apply {
                    groupId = project.group.toString()
                    this.artifactId = artifactId
                    version = project.version.toString()
                    pom {
                        description = "Vaadin Flow: tab-scoped values and routes, without Spring"
                        name = artifactId
                        url = "https://github.com/mvysny/vaadin-tab-scope"
                        licenses {
                            license {
                                name = "The MIT License"
                                url = "https://opensource.org/licenses/MIT"
                                distribution = "repo"
                            }
                        }
                        developers {
                            developer {
                                id = "mavi"
                                name = "Martin Vysny"
                                email = "martin@vysny.me"
                            }
                        }
                        scm {
                            url = "https://github.com/mvysny/vaadin-tab-scope"
                        }
                    }
                    from(components["java"])
                }
            }
        }

        signing {
            sign(publishing.publications["mavenJava"])
        }
    }

    tasks.withType<Test> {
        useJUnitPlatform()
        testLogging {
            // to see the stacktraces of failed tests in the CI console.
            exceptionFormat = TestExceptionFormat.FULL
            showCauses = true
            showStackTraces = true
            showStandardStreams = true
        }
    }
}

nexusPublishing {
    repositories {
        // see https://central.sonatype.org/publish/publish-portal-ossrh-staging-api/#configuration
        sonatype {
            nexusUrl.set(uri("https://ossrh-staging-api.central.sonatype.com/service/local/"))
            snapshotRepositoryUrl.set(uri("https://central.sonatype.com/repository/maven-snapshots/"))
        }
    }
}
