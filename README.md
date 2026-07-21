# Vaadin Tab Scope

A small library giving Vaadin Flow apps **tab-scoped values** and **tab-scoped routes**.
No Spring - pure Servlet.

This repo is a two-module Gradle build:

- **`tab-scope/`** — the reusable library (published to Maven Central as
  `com.github.mvysny.vaadintabscope:tab-scope`). This is the product.
- **`testapp/`** — a runnable [Vaadin Boot](https://github.com/mvysny/vaadin-boot) demo app that
  uses the library and shows the wiring.

In Vaadin 8, things were simple: both `UI` and `UI.init()` worked predictably.
The UI was instantiated once per tab (when using `@PreserveOnRefresh`), and the init
listener ran exactly once, before everything else, and so it was the right place to perform initialization.

In Vaadin 23+, this is no longer the case. You barely use `UI` at all, and the
`UIInitListener` is called multiple times per browser tab (once for every reload). Even with
`@PreserveOnRefresh`, the `UI` won't survive reload, and:

- The init listener runs multiple times per browser tab, once for every reload; and
- Route instances are preserved on page reload, but they're killed when navigating away to another view and back.

This project implements **tab-scoped values** and **tab-scoped routes** and fixes both of
those issues. Moreover, the implementation works correctly even without the `@PreserveOnRefresh` annotation.

- [Live demo at v-herd](https://v-herd.eu/vaadin-tab-scope-example)
- Background: [Tab Scope blog post](https://mvysny.github.io/vaadin-ui-scope/) and [issue #13468](https://github.com/vaadin/flow/issues/13468)
- **How it actually works, and every investigated fact behind it: see [INTERNALS.md](INTERNALS.md).**
- Future design proposals, when there are any, go under `ideas/`.

> Note: this branch demoes the tab scope for Vaadin 24/25. See the [v23](../../tree/v23) branch for
> the Vaadin 23 version of this app.

## Running the demo

Please see the [Vaadin Boot](https://github.com/mvysny/vaadin-boot#preparing-environment) documentation
on how you run, develop and package this Vaadin-Boot-based app. In short: `./gradlew :testapp:run`, then open
<http://localhost:8080>.

## Wiring the library into your app

Add the dependency (published to Maven Central):

```kotlin
dependencies {
    implementation("com.github.mvysny.vaadintabscope:tab-scope:0.1")
}
```

The library deliberately ships **no** `META-INF/services` files, so that it stays compatible with a
future Spring integration (Vaadin allows only one `InstantiatorFactory`, and Spring registers its
own). A plain Servlet / Vaadin-Boot app registers the two pieces itself:

1. `src/main/resources/META-INF/services/com.vaadin.flow.di.InstantiatorFactory` containing:
   ```
   com.github.mvysny.vaadin.tabscope.TabScopedRouteInstantiator$Factory
   ```
2. `src/main/resources/META-INF/services/com.vaadin.flow.server.VaadinServiceInitListener` pointing
   at your own listener, which calls `TabScope.setup(...)` (see below).

See the `testapp/` module for both files in context.

## Tab-scoped values

The `TabScope` class stores tab-scoped values. First, initialize it from your
`VaadinServiceInitListener` and seed any per-tab values in the init callback:

```java
public class ApplicationServiceInitListener
        implements VaadinServiceInitListener {

    static final AtomicInteger counter = new AtomicInteger();

    @Override
    public void serviceInit(ServiceInitEvent event) {
        TabScope.setup(ts -> {
            // runs exactly once per browser tab, before any route or layout is built
            ts.getValues().setAttribute("hello", counter.incrementAndGet());
        });
    }
}
```

Then, from anywhere running in the Vaadin UI thread (routes, layouts, components), read and
modify the values via `TabScope.getCurrent().getValues()`:

```java
Object hello = TabScope.getCurrent().getValues().getAttribute("hello");
```

If a value you seed in `setup(...)` needs releasing when the tab goes away, register a destroy
listener on the scope — the natural counterpart to seeding:

```java
TabScope.setup(ts -> {
    ts.getValues().setAttribute("hello", counter.incrementAndGet());
    ts.addDestroyListener(scope -> { /* release whatever "hello" held */ });
});
```

The listener fires before the scope's values are cleared. An ordinary idle session timeout **does**
run it — Vaadin fires session-destroy on container timeout via `HttpSessionBindingListener`, no
listener registration required ([mvysny/vaadin-boot#39](https://github.com/mvysny/vaadin-boot/issues/39)
is the source of truth). It's still **best-effort** for the residual gaps — a JVM crash / `kill -9`,
or a session deserialized-but-never-used before it expires — so don't depend on it for correctness.
See [INTERNALS.md](INTERNALS.md) → "Destroy listeners are best-effort".

See the `MainView` and `MainViewNoAppLayout` views for a regular route (prototype-scoped:
new instance every time) accessing tab-scoped values.

## Tab-scoped routes

You can also make routes themselves tab-scoped: annotate the route (or layout) with
`@TabScoped`, and its instance is cached and reused for that tab, surviving both page reload
and navigating away and back:

```java
@Route(value = "tab-scoped-route", layout = MainLayout.class)
@TabScoped
public class TabScopedView extends VerticalLayout { ... }
```

A custom `Instantiator` (`TabScopedRouteInstantiator`) caches `@TabScoped`-annotated views in
the `TabScope`. See the `TabScopedView` and `TabScopedViewNoAppLayout` routes for examples.

## Do I need `@PreserveOnRefresh`?

No. Tab scoping works the same with or without it, and you should **not** treat the annotation as
a prerequisite. `@PreserveOnRefresh` is a narrow, separate Vaadin feature — it only reuses a
single view instance across F5 of the *same URL*, and throws that state away as soon as you
navigate elsewhere. Tab scope here is broader: arbitrary per-tab values plus `@TabScoped` routes
that survive both reload *and* navigation. Even Vaadin's own tab-scope discussion
([flow#13468](https://github.com/vaadin/flow/issues/13468)) lists `@PreserveOnRefresh` as an
insufficient workaround rather than the solution.

For the full reasoning, see [INTERNALS.md](INTERNALS.md) → "Relationship to `@PreserveOnRefresh`".

## FAQ

### The browser can detect a reload — can't you use that to clean up tab scopes faster?

Not usefully. The browser *can* tell, on the freshly loaded page, that the load was a reload
(via `performance.getEntriesByType("navigation")[0].type === "reload"`). But a page being torn
down cannot tell whether it is about to be reloaded or the tab is being closed — both look
identical from the outgoing side. So that signal only arrives *after* the new page has already
loaded, at which point tab scoping has already recognized the returning tab by its `window.name`
and reused its scope — the reload flag tells us nothing new. And a closed tab never loads a new
page, so there's no signal to read there at all; a scope orphaned by a real close is only ever
detected by the fact that no new page comes back for it. That's exactly what the built-in
grace-period cleanup already handles. See [INTERNALS.md](INTERNALS.md) → "Cleanup" for the full
reasoning.

## Limitations

The functionality of this prototype depends on browsers preserving the `window.name` value on
navigation. Some browsers (notably Safari, and possibly Chrome) do **not** preserve it in all
cases — e.g. when a URL is typed into the address bar or a bookmark is clicked — which makes
such a navigation arrive as a brand-new tab scope.

The exact browser behaviors, the reload/refresh mechanics, tab-scope cleanup, and the reasons
behind every design choice are documented in **[INTERNALS.md](INTERNALS.md)**.

See also [issue #21141](https://github.com/vaadin/flow/issues/21141).
