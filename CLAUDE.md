# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project purpose

Demo of tab-scoped values and tab-scoped routes for Vaadin Flow (Vaadin 24/25), without Spring — pure Servlet + Vaadin Boot. The project exists to work around [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468): unlike Vaadin 8's `UI`, a Vaadin Flow `UI` does not survive page reload, and `UIInitListener` fires multiple times per browser tab. README.md is the user-facing kick-start; **INTERNALS.md holds the investigated facts and design rationale**; ideas/ holds forward-looking proposals.

This is not a starter template — the `TabScope` / `TabScopedRouteInstantiator` / `TabScoped` trio is the product.

## Commands

Build: `./gradlew build`
Run (dev, hotswap via Vaadin dev server): `./gradlew run` — app on http://localhost:8080
Production build: `./gradlew build -Pvaadin.productionMode`
Run a single test: `./gradlew test --tests com.vaadin.starter.skeleton.MainViewTest`
Docker: `docker build -t test/vaadin-tab-scope-example:latest . && docker run --rm -ti -p8080:8080 test/vaadin-tab-scope-example`

Java 21, Gradle (Kotlin DSL), JUnit, Karibu-Testing for UI tests (no browser, no Spring).

## Architecture

Three pieces implement tab scoping; changes to one usually require thinking about the others:

1. **`TabScope`** — holds per-tab state keyed by `ExtendedClientDetails.windowName`. The scope map lives on `VaadinSession` under attribute `"tab-scopes"`. A single `TabScope` may transiently have 0 or 2 UIs attached (during page reload the old UI detaches before the new one attaches), so orphan detection uses a **60-second grace period** (`CLEANUP_DURATION_MS`) rather than killing the scope the moment UI count hits 0. Do not shorten this without considering reload races.

2. **`TabScopedRouteInstantiator`** (registered via `META-INF/services/com.vaadin.flow.di.InstantiatorFactory`) — intercepts route/layout instantiation. For classes annotated `@TabScoped`, it caches the instance in `TabScope.getValues()` and calls `element.removeFromTree()` before returning, which is required to avoid *"Can't move a node from one state tree to another"* when Vaadin reattaches a cached component to a new UI.

3. **`ApplicationServiceInitListener`** (registered via `META-INF/services/com.vaadin.flow.server.VaadinServiceInitListener`) — calls `TabScope.setup(...)` exactly once, in the tab-init callback, to seed values. The callback runs **before** any route/layout is constructed for that tab; this ordering is not enforced in code — it relies on Vaadin deferring navigation until `ExtendedClientDetails` is fetched. Fragile but currently unavoidable (see INTERNALS.md, "Ordering").

### Known fragility: `window.name`

Tab identity depends on the browser preserving `window.name` across navigation. Some browsers (notably Safari 18.3.1 with dev tools closed) do **not** preserve it when typing a URL or clicking a bookmark — these arrive as a new tab scope. See [vaadin/flow#21141](https://github.com/vaadin/flow/issues/21141) and INTERNALS.md ("Tab identity fragility") before proposing changes that rely on tab identity.

### Cleanup is deliberately partial

Tab scopes are **not** removed when the browser tab closes — only when the whole session is destroyed, or when a scope is observed orphaned for >60s during another request. This mirrors `vaadin-spring`'s `VaadinRouteScope` behavior. The cleanup mechanics — and why the grace-period timer can't be removed (the unload-beacon race on non-`@PreserveOnRefresh` reloads) — are documented in INTERNALS.md ("Cleanup" → "Why the timer is necessary"). Revisit that before changing cleanup.

## Testing

`AbstractAppTest` spins up `MockVaadin` with auto-discovered routes and resets `ApplicationServiceInitListener.counter` so counter-dependent assertions are deterministic. New view tests should extend it. There is no browser/Selenium layer in this repo — the `window.name`-preservation behavior described above is only testable manually across real browsers.
