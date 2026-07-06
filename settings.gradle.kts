rootProject.name = "vaadin-tab-scope"

include("tab-scope", "testapp")

// Compose in a local Karibu-Testing checkout so we can consume its unreleased 2.7.1-SNAPSHOT
// (the beacon-timing API — KaribuConfig.unloadBeaconTiming). Gradle substitutes any
// com.github.mvysny.kaributesting:* dependency with the local build, regardless of the version
// requested in the catalog. Override the checkout location with -PkaribuTestingDir=/path, or set
// it in ~/.gradle/gradle.properties; defaults to a sibling ../vok/karibu-testing checkout.
//
// This checkout is currently REQUIRED to build: 2.7.1-SNAPSHOT is not published to any Maven
// snapshot repo (the snapshotsOnly repo in the root build only becomes a fallback if/when a
// snapshot is actually deployed). If the path is absent the block is skipped and dependency
// resolution will fail — expected until Karibu-Testing 2.7.1 is released, at which point drop
// this block and the snapshot repo and pin the release version in the catalog.
val karibuTestingDir = file(providers.gradleProperty("karibuTestingDir").getOrElse("../vok/karibu-testing"))
if (karibuTestingDir.isDirectory) {
    includeBuild(karibuTestingDir)
}
