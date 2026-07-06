# Internals

This document collects the hard-won, investigated facts about how tab scoping is implemented
on top of Vaadin Flow, and *why* each design choice is the way it is. The user-facing
kick-start lives in [README.md](README.md); forward-looking proposals live in [ideas/](ideas/).

## The problem

Unlike Vaadin 8's `UI`, a Vaadin Flow `UI` does **not** survive a page reload, and
`UIInitListener` fires multiple times per browser tab — once per reload. There is therefore no
built-in place that runs exactly once per tab, and no built-in per-tab storage that survives a
reload. See [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468).

Tab identity is instead derived from the browser's `window.name`, exposed to the server via
`ExtendedClientDetails.getWindowName()` (a unique ID per browser tab).

## The three pieces

Changing one of these usually means thinking about the other two.

### 1. `TabScope` — per-tab state, keyed by `window.name`

`TabScope` holds per-tab state keyed by `ExtendedClientDetails.getWindowName()`. The map of all
scopes lives on the `VaadinSession` under the attribute `"tab-scopes"`
(`Map<String, TabScope>`), so its lifetime is bounded by the session.

### 2. `TabScopedRouteInstantiator` — caching `@TabScoped` routes/layouts

Registered via `META-INF/services/com.vaadin.flow.di.InstantiatorFactory`, it intercepts
route/layout instantiation. For classes annotated `@TabScoped`, it caches the instance in
`TabScope.getValues()` and — crucially — calls `element.removeFromTree()` before returning it.

Without `removeFromTree()`, reattaching a cached component to a *new* UI throws:

> Can't move a node from one state tree to another. If this is intentional, first remove the
> node from its current state tree by calling removeFromTree.

Every reload creates a new UI (see below), so a cached `@TabScoped` component is always being
moved from the old UI's state tree to the new one — hence the detach is mandatory.

### 3. `ApplicationServiceInitListener` — one-time per-tab init

Registered via `META-INF/services/com.vaadin.flow.server.VaadinServiceInitListener`, it calls
`TabScope.setup(...)` exactly once, wiring up the tab-init callback that seeds per-tab values.

## Ordering: the tab-init callback runs before any route/layout is built

The tab-init callback (passed to `TabScope.setup`) must run **before** any route or layout is
constructed for that tab, so that `TabScope.getCurrent()` is usable from constructors.

**This ordering is not enforced anywhere in this project's code.** It relies on Vaadin's
internal machinery: `TabScope.init()` registers an `ExtendedClientDetails` receiver via
`ui.getPage().retrieveExtendedClientDetails(...)`, and Vaadin defers navigation until the ECD
has been fetched (because `@PreserveOnRefresh` needs `windowName` too). Our receiver is invoked
first — creating and seeding the `TabScope` — and the deferred navigation happens afterwards.

This is fragile ("hopefully our receiver runs first"), but it is currently the only available
hook point. It is exactly why [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468)
asks for a proper per-tab init callback.

## Tab identity fragility: `window.name`

Tab identity depends on the browser preserving `window.name` across navigation. The following
actions normally trigger a navigation that *should* preserve it:

- Clicking a link from within the app
- Typing a new address into the browser's address bar, or clicking a bookmarked link
- Calling `document.location = url` from JavaScript
- Going forward/back in history
- A Selenium-controlled `Driver` performing page `get()`

However, some browsers do **not** preserve `window.name` under some of these:

- **Safari 18.3.1** does not preserve `window.name` when a URL is typed into the address bar via
  keyboard, or when a bookmarked URL is clicked. (TODO untested: possibly also on back/forward,
  and when a Selenium driver performs `get()`.)
  - When Safari's dev tools (Web Inspector) are open, `window.name` *is* preserved.
- Similar behavior has been observed with **Chrome** — unconfirmed, TODO verify.

When `window.name` is not preserved, the navigation arrives as a brand-new tab scope. See the
discussion in [vaadin/flow#21141](https://github.com/vaadin/flow/issues/21141).

## Cleanup

The scope map lives on the `VaadinSession`, so at the latest everything is removed and GC-ed
when the session is destroyed: `TabScope.setup` registers a session destroy listener that calls
`destroyAllTabScopes()`. This mirrors the official `vaadin-spring` plugin, where
`VaadinRouteScope`'s `BeanStore` is bound to the session too.

On top of that, **orphaned scopes are cleaned up eagerly**, well before the session ends.

### Why not just purge on UI detach?

A scope cannot be purged the moment its UI count hits zero. On a page reload the old UI is
detached **before** the new UI is created, so there is a transient window during which zero UIs
point to an otherwise-live tab scope. Purging on detach would destroy tab-scoped values that are
supposed to survive the reload. Consequently a single `TabScope` may transiently have **0 or 2**
UIs attached.

### The grace period

`TabScope.Lifecycle` tracks the set of `UI`s pointing to the scope. When the last UI detaches,
the scope is marked orphaned (`orphanedSince = now`). It is only actually closed once it has
been orphaned for longer than `CLEANUP_DURATION_MS` (**60 seconds**) — long enough for the
reload's new UI to spring to life and re-attach, which clears `orphanedSince`.

**Do not shorten this without considering reload races.** The grace period is what makes the
0-UIs-during-reload window safe. (This timer-based approach has a theoretical fragility — if
network comms stall for longer than the grace period during a reload, a live scope could be
retired prematurely. See [ideas/retire-cleanup-timeout.md](ideas/retire-cleanup-timeout.md) for
a proposed timer-free alternative.)

### When cleanup actually runs

There is no dedicated timer thread. `cleanupOrphans()` runs opportunistically during other
requests — on every UI init and on every UI detach (`removeUI`). It sweeps all scopes and closes
any that have been orphaned past the grace period. This means a lone orphaned scope can linger
past its 60 seconds until *some* request triggers the sweep — but it will never outlive the
session.

`updateOrphaned()` also drops UIs for which `UI.isClosing()` is true, so a UI already flagged
for closing does not keep a scope alive.

### Tab close needs no special handling

When a tab is closed, [the beacon kills the UI eagerly](https://vaadin.com/blog/vaadin-flow-24.1-drastically-reduces-memory-usage),
which detaches the UI and starts the orphan grace period. Reopening the tab does not preserve
`window.name`, so it arrives as a brand-new tab scope — there is nothing to reconnect to, and
the old orphaned scope is free to be collected. (The beacon is flaky in practice — e.g. Vaadin
25.0 with LibreWolf — but the grace-period sweep catches the scope regardless.)

### Destroy listeners are best-effort

`TabScope.addDestroyListener` callbacks fire before `values` are cleared, but **must not be
relied upon**: when a session times out and is closed by the servlet container, Vaadin's session
destroy listeners are not called at all, so neither are ours.

## Testing

There is no browser/Selenium layer in this repo — the `window.name`-preservation behavior above
is only testable manually across real browsers. `AbstractAppTest` spins up `MockVaadin` with
auto-discovered routes and resets `ApplicationServiceInitListener.counter` so counter-dependent
assertions are deterministic. New view tests should extend it.
