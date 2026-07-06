# Vaadin Tab-Scope Example App

A demo project demoing a proper way of having tab-scoped values and routes.
No Spring - pure Servlet project.

In Vaadin 8, things were simple: both `UI` and `UI.init()` was working predictably:
the UI was instantiated once per tab (when using `@PreserveOnRefresh`), and the init
listener ran exactly once, before everything else, and so it was the right place to perform initialization.

In Vaadin 23+, this is no longer the case. You don't use UI almost at all, the only
sane way to use `UI` is to store data to it via `ComponentUtil` ,
and there is a `UIInitListener`. However, even with `@PreserveOnRefresh`, the `UI`
won't survive reload and thus:

- The init listener is called multiple times per browser tab, once for every reload; and
- Route instances are preserved on page reload, but they're killed when navigating away to another view and back.

Please see the [Tab Scope blog post](https://mvysny.github.io/vaadin-ui-scope/)
and [issue #13468](https://github.com/vaadin/flow/issues/13468) for more details.

This project implements tab-scoped values and tab-scoped routes and fixes both of the abovementioned
issues. Moreover, the implementation works correctly even without the `@PreserveOnRefresh` annotation.

[Live demo at v-herd](https://v-herd.eu/vaadin-tab-scope-example).

> Note: this branch demoes the tab scope for Vaadin 24. See the [v23](../../tree/v23) branch for
> Vaadin 23 version of this app.

# Documentation

Please see the [Vaadin Boot](https://github.com/mvysny/vaadin-boot#preparing-environment) documentation
on how you run, develop and package this Vaadin-Boot-based app.

The `TabScope` class is used to store tab-scoped values. First, it needs to be
initialized in the UI Init Listener:
```java
public class ApplicationServiceInitListener
        implements VaadinServiceInitListener {

    static final AtomicInteger counter = new AtomicInteger();

    @Override
    public void serviceInit(ServiceInitEvent event) {
        event.getSource().addUIInitListener(TabScope.uiInitListener(ts -> {
            ts.getValues().setAttribute("hello", counter.incrementAndGet());
        }));
    }
}
```
You can now access/modify the tab-scoped values from your routes, layouts and components, or generally any other code which runs in
Vaadin UI thread. See the `MainView` and `MainViewNoAppLayout` views for example on a regular route (prototype-scoped:
new instance every time) accessing tab-scoped values.

## Tab-scoped routes

It's also possible to have tab-scoped routes - instances of those routes are preserved and reused
when they're navigated to repeatedly. A special `Instantiator` is implemented, to
cache `@TabScoped`-annotated views in the `TabScope` map - see `TabScopedRouteInstantiator` for more details.

See the `TabScopedView` and `TabScopedViewNoAppLayout` routes for more details.

## Limitations

The functionality of this prototype depends on browsers preserving the `window.name` value on navigation.
In general, the following actions trigger a navigation:

- Clicking a link from within the app
- Typing in a new address into browser's address bar, or clicking bookmarked link in browser's bookmark toolbar
- Calling `document.location = url` from JavaScript
- Going forward/back in history
- When a Selenium-controlled `Driver` performs page `get()`.

Unfortunately, certain browsers will not preserve `window.name` under certain navigation types:

- Safari 18.3.1 will not preserve `window.name` when URL is typed in to browser's address bar via keyboard, or when
  a bookmarked URL is clicked. TODO untested: possibly also when back/forward button is pressed, and also when
  the Selenium-controlled driver performs `get()`.
  - However, when Safari's dev tools (Web Inspector) is open, the `window.name` is preserved.
- Similar behavior observed with Chrome as well - unconfirmed, TODO verify.

See the discussion of the [issue #21141](https://github.com/vaadin/flow/issues/21141) for more details.

## Cleaning up

The way `TabScope` works is that it keeps a `Map` from `ExtendedClientDetails.windowName`
(which identifies the browser tab uniquely) to the instance of `TabScope`. That map lives on the
`VaadinSession`, so at the latest all tab scopes are removed and GC-ed when the whole session goes down
(`destroyAllTabScopes()`, hooked to a session destroy listener). This mirrors the official
`vaadin-spring` plugin, where `VaadinRouteScope`'s `BeanStore` is also bound to the session.

In addition, orphaned tab scopes are cleaned up eagerly, well before the session ends. Each `TabScope`
tracks the set of `UI`s pointing to it (`Lifecycle`). A scope can't simply be purged the moment its UI
count hits zero: on page reload the old UI is detached *before* the new one is created, so there's a
window where zero UIs point to an otherwise-live tab scope. Purging on UI detach would therefore break
reload. Instead, when the last UI detaches the scope is marked as *orphaned* (`orphanedSince`), and it's
only closed once it has been orphaned for longer than a grace period (`CLEANUP_DURATION_MS`, 60 seconds) —
long enough for the reload's new UI to spring to life and re-attach.

The cleanup itself (`cleanupOrphans()`) runs opportunistically during other requests — on every UI init
and on every UI detach — since there is no dedicated timer thread. This means a lone orphaned scope may
linger past its 60 seconds until *some* request triggers the sweep, but it will never survive the session.

Note that closing a browser tab does not need any special handling: when a tab is closed,
[the beacon kills the UI eagerly](https://vaadin.com/blog/vaadin-flow-24.1-drastically-reduces-memory-usage),
which detaches the UI and starts the orphan grace period. Reopening the tab does not preserve `window.name`,
so it arrives as a brand-new tab scope — there is nothing to reconnect to, and the old orphaned scope is
free to be collected. (The beacon is a bit flaky in practice — e.g. Vaadin 25.0 with LibreWolf — but the
grace-period sweep catches the scope regardless.)
