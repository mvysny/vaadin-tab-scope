# Contributing

Thank you so much for making the library better.
Please feel free to open bug reports to discuss new features; PRs are welcome as well :)

## Tests

Uses JUnit + [Karibu-Testing](https://github.com/mvysny/karibu-testing) (browserless Vaadin UI tests, no Spring). Simply run `./gradlew build` to build both modules and run all tests.

- Library tests only (fast, no frontend build): `./gradlew :tab-scope:test`
- A single test: `./gradlew :testapp:test --tests testapp.MainViewTest`

There is no browser/Selenium layer in this repo. The `window.name`-preservation behavior that tab identity depends on is only testable manually across real browsers — see the "Known fragility" notes in [CLAUDE.md](CLAUDE.md) and [INTERNALS.md](INTERNALS.md).

### Manual Tests

Run the demo and exercise tab scoping across reloads and multiple tabs:

1. Run `./gradlew :testapp:run` and open [http://localhost:8080](http://localhost:8080).
2. Reload the page (F5) and confirm the tab-scoped state survives the reload.
3. Open a second browser tab and confirm it gets its own independent scope.
4. Test a production build too: `./gradlew clean build :testapp:run -Pvaadin.productionMode`.
5. Unzip `testapp/build/distributions/testapp.zip`, run `bin/testapp`, and confirm the app comes up on port 8080.
6. Optionally test the Docker image: `docker build -t test/vaadin-tab-scope:latest . && docker run --rm -ti -p8080:8080 test/vaadin-tab-scope`.

For the full cross-browser `window.name`-preservation sweep (the correctness foundation of tab identity — [issue #2](https://github.com/mvysny/vaadin-tab-scope/issues/2)), follow the matrix and record results in [WINDOW-NAME-BROWSER-TESTS.md](WINDOW-NAME-BROWSER-TESTS.md).

# Releasing

Only the `tab-scope` module is published to Maven Central (the `testapp` demo is not). The version lives in the root `build.gradle.kts` under the `allprojects { version = ... }` stanza.

To release the library to Maven Central:

1. Run the full build to make sure everything is green: `./gradlew clean build`
2. Edit `build.gradle.kts` and remove `-SNAPSHOT` from the `version =` stanza, e.g. "0.1"
3. Edit `README.md`: bump the version in the Gradle/Maven install snippets to match the release (if present)
4. Run `./gradlew clean build publish closeAndReleaseStagingRepositories`
5. (Optional) watch [Maven Central Publishing Deployments](https://central.sonatype.com/publishing/deployments) as the deployment is published.
6. Commit with the commit message of simply being the version being released, e.g. "0.1"
7. git tag the commit with the same tag name as the commit message above, e.g. `0.1`
8. `git push`, `git push --tags`
9. Add the `-SNAPSHOT` back to the `version =` while bumping it to the next planned release,
   e.g. 0.2-SNAPSHOT, then commit with the commit message "0.2-SNAPSHOT" and push.
