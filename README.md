# Vaadin Tab-Scope Example App

A demo project demoing a proper way of having tab-scoped values and routes.
No Spring - pure Servlet project.

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
- Future ideas: see [ideas/](ideas/).

> Note: this branch demoes the tab scope for Vaadin 24/25. See the [v23](../../tree/v23) branch for
> the Vaadin 23 version of this app.

## Running

Please see the [Vaadin Boot](https://github.com/mvysny/vaadin-boot#preparing-environment) documentation
on how you run, develop and package this Vaadin-Boot-based app. In short: `./gradlew run`, then open
<http://localhost:8080>.

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

## Limitations

The functionality of this prototype depends on browsers preserving the `window.name` value on
navigation. Some browsers (notably Safari, and possibly Chrome) do **not** preserve it in all
cases — e.g. when a URL is typed into the address bar or a bookmark is clicked — which makes
such a navigation arrive as a brand-new tab scope.

The exact browser behaviors, the reload/refresh mechanics, tab-scope cleanup, and the reasons
behind every design choice are documented in **[INTERNALS.md](INTERNALS.md)**.

See also [issue #21141](https://github.com/vaadin/flow/issues/21141).
