# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project purpose

Tab-scoped values and tab-scoped routes for Vaadin Flow (Vaadin 24/25), without Spring — pure Servlet + Vaadin Boot. The project exists to work around [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468): unlike Vaadin 8's `UI`, a Vaadin Flow `UI` does not survive page reload, and `UIInitListener` fires multiple times per browser tab. README.md is the user-facing kick-start; **INTERNALS.md holds the investigated facts and design rationale**; ideas/ holds forward-looking proposals.

This is not a starter template — the `TabScope` / `TabScopedRouteInstantiator` / `TabScoped` trio is the product.

### Two-module layout

- **`tab-scope/`** — the reusable library, published to Maven Central as `com.github.mvysny.vaadintabscope:tab-scope`. Package `com.github.mvysny.vaadin.tabscope`. Pure Java, `compileOnly(vaadin-core)`, logs via slf4j-api. **Ships no `META-INF/services` files** — see the SPI note below.
- **`testapp/`** — the runnable Vaadin-Boot demo. Package `testapp`. Owns the two SPI registration files and `ApplicationServiceInitListener`.

**Why the library ships no SPI files:** Vaadin resolves exactly one `InstantiatorFactory`, and Spring registers its own; shipping ours would break Spring apps. So both `META-INF/services` files (the `InstantiatorFactory` and the `VaadinServiceInitListener`) live in `testapp`, and each consuming app registers them itself. This keeps a future `tab-scope-spring` module possible. See `ideas/two-project-split.md`.

## Commands

Build (all modules): `./gradlew build`
Run (dev, hotswap via Vaadin dev server): `./gradlew :testapp:run` — app on http://localhost:8080
Production build: `./gradlew build -Pvaadin.productionMode`
Run library tests only (fast, no frontend): `./gradlew :tab-scope:test`
Run a single test: `./gradlew :testapp:test --tests testapp.MainViewTest`
Docker: `docker build -t test/vaadin-tab-scope:latest . && docker run --rm -ti -p8080:8080 test/vaadin-tab-scope`

Java 21 (pure Java, no Kotlin), Gradle (Kotlin DSL), JUnit, Karibu-Testing for UI tests (no browser, no Spring). Root `build.gradle.kts` holds shared config + the reusable `ext["publishing"]` Maven Central setup; only `tab-scope` invokes it.

## Architecture

Three pieces implement tab scoping; changes to one usually require thinking about the others:

1. **`TabScope`** — holds per-tab state keyed by `ExtendedClientDetails.windowName`. The scope map lives on `VaadinSession` under attribute `"tab-scopes"`. A single `TabScope` may transiently have 0 or 2 UIs attached (during page reload the old UI detaches before the new one attaches), so orphan detection uses a **60-second grace period** (`CLEANUP_DURATION_MS`) rather than killing the scope the moment UI count hits 0. Do not shorten this without considering reload races.

2. **`TabScopedRouteInstantiator`** (library; the app registers it via `META-INF/services/com.vaadin.flow.di.InstantiatorFactory` — the library does not ship that file) — intercepts route/layout instantiation. For classes annotated `@TabScoped`, it caches the instance in `TabScope.getValues()` and calls `element.removeFromTree()` before returning, which is required to avoid *"Can't move a node from one state tree to another"* when Vaadin reattaches a cached component to a new UI.

3. **`ApplicationServiceInitListener`** (in `testapp`, registered via `META-INF/services/com.vaadin.flow.server.VaadinServiceInitListener`) — calls the library's `TabScope.setup(...)` exactly once, in the tab-init callback, to seed values. The callback runs **before** any route/layout is constructed for that tab; this ordering is not enforced in code — it relies on Vaadin deferring navigation until `ExtendedClientDetails` is fetched. Fragile but currently unavoidable (see INTERNALS.md, "Ordering").

### Known fragility: `window.name`

Tab identity depends on the browser preserving `window.name` across navigation. Some browsers (notably Safari 18.3.1 with dev tools closed) do **not** preserve it when typing a URL or clicking a bookmark — these arrive as a new tab scope. See [vaadin/flow#21141](https://github.com/vaadin/flow/issues/21141) and INTERNALS.md ("Tab identity fragility") before proposing changes that rely on tab identity.

### Cleanup is deliberately partial

Tab scopes are **not** removed when the browser tab closes — only when the whole session is destroyed, or when a scope is observed orphaned for >60s during another request. This mirrors `vaadin-spring`'s `VaadinRouteScope` behavior. The cleanup mechanics — and why the grace-period timer can't be removed (the unload-beacon race on non-`@PreserveOnRefresh` reloads) — are documented in INTERNALS.md ("Cleanup" → "Why the timer is necessary"). Revisit that before changing cleanup.

## Testing

**testapp:** `AbstractAppTest` spins up `MockVaadin` with auto-discovered routes and resets `ApplicationServiceInitListener.counter` so counter-dependent assertions are deterministic. New view tests should extend it.

**tab-scope:** `TabScopeTest` exercises the library in isolation — its `src/test/resources/META-INF/services` ships test-only SPI wiring (the `InstantiatorFactory` + a `TestInitListener`) plus demo routes, so the library's lifecycle/orphan-cleanup and `@TabScoped` caching are tested without the app. `TabScopeReloadTimingTest` additionally drives all three F5 unload-beacon orderings (`EAGER`/`LATE`/`NEVER`) plus the `@PreserveOnRefresh` path via `KaribuConfig.unloadBeaconTiming` — see INTERNALS.md ("Reproducing the unload-beacon reload orderings"). `TabScopedRoutingTest` covers `@TabScoped` caching across navigation (routes and layouts); `TabScopeLifecycleTest` covers the reaping/session-destroy branches (it shrinks the package-private `TabScope.CLEANUP_DURATION_MS` — the one test seam — to exercise orphan reaping). `MultiTabTest` and `TabIdentityTest` use Karibu's `MockBrowser` to test multi-tab isolation and `window.name`-change-on-reload; `reapInactiveUIs()` drives lost-beacon UI cleanup. These need Karibu-Testing 2.7.1+ (`MockBrowser`, `KaribuConfig.windowName`, `MockVaadin.reapInactiveUIs()`).

There is no browser/Selenium layer in this repo — the `window.name`-preservation behavior described above is only testable manually across real browsers.
