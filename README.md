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
    implementation("com.github.mvysny.vaadintabscope:tab-scope:0.2")
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

### Optional: prompt reap for `@PreserveOnRefresh` routes

An orphaned scope is always reaped ~60 s after its last tab closes. For a plain route the browser's
unload beacon triggers that on close; but Flow **ignores** the beacon for a `@PreserveOnRefresh`
route, so such a scope would otherwise linger until the session times out. To make it prompt, route
the beacon through tab-scope by installing its handler from your own `VaadinServlet` — the library
ships the handler but registers nothing (so it stays Spring-safe):

```java
@WebServlet(urlPatterns = "/*", name = "my-servlet", asyncSupported = true)
public class MyServlet extends VaadinServlet {
    @Override
    protected VaadinServletService createServletService(DeploymentConfiguration config)
            throws ServiceException {
        VaadinServletService service = new VaadinServletService(this, config) {
            @Override
            protected List<RequestHandler> createRequestHandlers() throws ServiceException {
                List<RequestHandler> handlers = new ArrayList<>(super.createRequestHandlers());
                TabScope.installTabCloseBeacon(handlers); // swaps in TabScopeUidlRequestHandler
                return handlers;
            }
        };
        service.init();
        return service;
    }
}
```

Vaadin Boot auto-discovers the `@WebServlet`, so declaring it is all the wiring needed. See
`testapp/TabScopeBeaconServlet` and the `/preserve` route. (Skip this entirely if you don't use
`@PreserveOnRefresh` — plain routes reap promptly without it.)

## Configuration

Two `public static` fields on `TabScope` tune cleanup. Both are global and read live, so set them
once at startup (e.g. from your `VaadinServiceInitListener`) before your app serves requests:

| Field | Default | What it does |
| --- | --- | --- |
| `TabScope.CLEANUP_DURATION_MS` | `60_000` (60 s) | Grace period after a tab's last UI goes away before its scope is reaped. Absorbs the page-reload race, where the old UI detaches before the new one attaches, leaving the scope momentarily UI-less. Don't shorten it without weighing that race. |
| `TabScope.scheduledReapEnabled` | `true` | Whether a background daemon thread reaps an orphaned scope ~`CLEANUP_DURATION_MS` after close even with no further request — what makes a *sole last tab* reap promptly. Set `false` to drop the reaper thread and fall back to request-driven cleanup plus session-destroy. |

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

The listener fires before the scope's values are cleared, and it fires **reliably** on every graceful
teardown — explicit `session.close()`, browser-tab close, and an ordinary idle session timeout alike.
On a container timeout Vaadin fires session-destroy via `HttpSessionBindingListener`, needing no
listener registration; this is verified end-to-end on both embedded Jetty and Tomcat in
[mvysny/vaadin-boot#39](https://github.com/mvysny/vaadin-boot/issues/39) (the source of truth). Only an
abrupt `kill -9` / power loss skips it, exactly as it skips every shutdown hook — not a limitation
particular to this listener. A **sole-last-tab close** fires the listener promptly too (within ~60 s),
via an always-on scheduled reap — for `@PreserveOnRefresh` routes you additionally wire the tab-close
beacon hook (see below). What stays container-paced is only a genuine *idle timeout* with the tab left
open (see [INTERNALS.md](INTERNALS.md) → "When destroy listeners fire" and
[issue #3](https://github.com/mvysny/vaadin-tab-scope/issues/3)).

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

## FAQ

### Do I need `@PreserveOnRefresh`?

No — not for the scoping itself. Tab scoping works the same with or without it, and you should
**not** treat the annotation as a prerequisite. `@PreserveOnRefresh` is a narrow, separate Vaadin
feature — it only reuses a single view instance across F5 of the *same URL*, and throws that state
away as soon as you navigate elsewhere. Tab scope here is broader: arbitrary per-tab values plus
`@TabScoped` routes that survive both reload *and* navigation. Even Vaadin's own tab-scope discussion
([flow#13468](https://github.com/vaadin/flow/issues/13468)) lists `@PreserveOnRefresh` as an
insufficient workaround rather than the solution.

The one place it interacts is **prompt cleanup**: if you *do* use `@PreserveOnRefresh`, Flow ignores
the unload beacon for that route, so wire the optional tab-close beacon hook (see "Optional: prompt
reap for `@PreserveOnRefresh` routes" above) to have the scope reaped promptly on a sole-tab close
rather than at session timeout. Plain routes need no such wiring.

For the full reasoning, see [INTERNALS.md](INTERNALS.md) → "Relationship to `@PreserveOnRefresh`".

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
navigation. If a browser drops it — e.g. on an address-bar reload or a bookmark click — that
navigation arrives as a brand-new tab scope. Historically **Safari 18.3.1** did this (with Web
Inspector closed), but a full cross-browser sweep on 2026-07-22 found **current Chrome, Firefox and
Safari 26.5.2 all preserve `window.name`** across every reload/navigation — the Safari drop is fixed
as of 26.5.2. Quit/crash-restore and reopen-closed-tab legitimately start a fresh scope on every
browser. The complete matrix and per-browser results are in
**[WINDOW-NAME-BROWSER-TESTS.md](WINDOW-NAME-BROWSER-TESTS.md)**.

The exact browser behaviors, the reload/refresh mechanics, tab-scope cleanup, and the reasons
behind every design choice are documented in **[INTERNALS.md](INTERNALS.md)**.

See also [issue #21141](https://github.com/vaadin/flow/issues/21141).
