# Vaadin Tab Scope — AGENTS.md

## What this is

A small library giving Vaadin Flow apps **tab-scoped values** and **tab-scoped routes**.
No Spring - pure Servlet. Flow owns the `UI`, which does not survive a page reload, and
`UIInitListener`, which fires once per reload rather than once per browser tab; this project owns
what that leaves missing — a per-tab value store keyed on the browser's `window.name` that survives
reload and navigation, `@TabScoped` routes and layouts cached in it, and the cleanup that decides
when a tab has really gone away.

## Promises

- **A tab is a scope.** Per-tab state that survives reload and navigation in a plain Servlet / Vaadin-Boot app.
- **Works both with and without Spring.** Nothing the library ships may break an app that also has `vaadin-spring` on its classpath.
- **A destroy listener you can hang real cleanup off.** Every graceful teardown fires it — explicit close, tab close, idle timeout alike.

## Design docs

| File | Owns | Loaded |
|---|---|---|
| `README.md` | the pitch, how to wire the library into an app, the user-facing FAQ | — |
| `AGENTS.md` (this) | promises, invariants, the module map, conventions, commands | every turn |
| `design/architecture.md` | how the pieces compose — wiring, threads, the three lifecycle flows; normative | lazy |
| `design/decisions.md` | why this and not that — `D_` entries, FAQ-shaped | lazy |
| `design/research.md` | what Flow and the browsers actually do — `R_` entries, each claim with provenance | lazy |
| `WINDOW-NAME-BROWSER-TESTS.md` | the manual cross-browser test protocol, and the latest run's raw results | lazy |
| `CONTRIBUTING.md` | how to run the tests, the manual-test checklist, the release steps | lazy |
| `CHANGELOG.md` | what changed per released version; doubles as the GitHub release notes | lazy |
| doc comments | what one symbol does and why it is shaped so | at the symbol |

Every fact lives in exactly one of these; the others link to it.

## Invariants

- **The scope map lives on the `VaadinSession`**, never in a static field — session destroy is the cleanup floor that always holds.
- **Every read or mutation of a scope holds the session lock.** The reaper thread hops onto it via `session.access` before touching anything.
- **A UI leaves `Lifecycle.uis` only through its own detach listener.** One that stops counting as live stays in the set. See `D_strict_uis`.
- **The UI-detach listener acts only when `ui.isClosing()`.** A client-requested resync re-fires detach on a UI that is going nowhere. See `D_strict_uis`.
- **Never call a Flow API newer than the supported floor.** `compileOnly` makes that a `NoSuchMethodError` in the consumer's app, not a warning in ours. See `D_compileonly_api_floor`.
- **The library ships no `META-INF/services` file.** One would break every Spring app that has us on the classpath. See `D_no_spi_files`.
- **A cached `@TabScoped` instance is `removeFromTree()`d before reuse.** Flow refuses to move a node between state trees, and every reload is a new UI.
- **An orphaned scope is reaped only after `CLEANUP_DURATION_MS`.** Reaping the moment the live-UI count hits zero kills tabs that are merely reloading. See `D_grace_period`.

## Module map

- `tab-scope` — the published library: the store, the instantiator, the two beacon adapters.
- `testapp` — the Vaadin-Boot demo: both SPI files, the init listener, the beacon servlet, the browser-test harness.

## Conventions

- **Java 21, no Kotlin.** Plain classes; Gradle with the Kotlin DSL and a version catalog.
- **Tests: JUnit 5 + Karibu-Testing**, browserless and Spring-free; `MockBrowser` for the multi-tab and `window.name` cases.
- **A `testapp` view test extends `AbstractAppTest`**, which boots `MockVaadin` with the discovered routes and resets the per-tab counter.
- **Diagnostics go through slf4j-api** at `debug`, never `System.out`; the library pulls in no logging backend.
- **A broken precondition raises `IllegalStateException` naming it** — no session lock, off the UI thread, scope already destroyed.
- **Two production test seams, and no more**: `CLEANUP_DURATION_MS` and `reapScheduler`; a third is a design change, not a test fix.
- **The Maven group id squashes, the Java package stays dotted** — `com.github.mvysny.vaadintabscope:tab-scope`, package `com.github.mvysny.vaadin.tabscope`, after `jdbi-orm-vaadin`.
- **Only `tab-scope` is published**; `testapp` never calls the root build's `ext["publishing"]`.
- **A release is written in `CHANGELOG.md` first**, and the GitHub release notes are that section verbatim.

## Commands

- `./gradlew build` — both modules, all tests, plus the doc tripwires; CI runs it with `-Pvaadin.productionMode` (`.github/workflows/gradle.yml`).
- `./gradlew :tab-scope:test` — library tests only, no frontend build.
- `./gradlew :testapp:test --tests testapp.MainViewTest` — a single test.
- `./gradlew :testapp:run` — the demo on http://localhost:8080; add `-Pvaadin.productionMode` for a production build.
- `./gradlew designTripwires` — the doc-layer checks alone; it runs `design/verify_design_tripwires.sh`, which also works standalone.
- `docker build -t test/vaadin-tab-scope:latest . && docker run --rm -ti -p8080:8080 test/vaadin-tab-scope`

## Skills this project follows

- **Gradle:** bootstrap the wrapper from the official distribution only, and read a dependency's API and sources out of `~/.gradle/caches`; the `gradle` skill has the rest.
- **Javadoc:** a doc comment says what one symbol does and why it is shaped so, and nothing the signature already says; the `writing-javadoc` skill has the levels.

## Maintenance of this file

Loaded every turn; cap 34 KB, a module's own `AGENTS.md` 10 KB. Over it, in this order:
delete what has no home — status, history, class lists, what the code already says; trim
each line to its fact plus one clause and send the explanation home — why →
`design/decisions.md`, how across symbols → `design/architecture.md`, how in one symbol →
its doc comment, what upstream does → `design/research.md`; only then a module's own
`AGENTS.md`, peripheral modules first, never the core. Never paraphrase a lazy entry into a
line here. `design/verify_design_tripwires.sh` checks the caps and the cites.

<sub>Doc layer shaped by the `design-docs` skill.</sub>
