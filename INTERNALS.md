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
0-UIs-during-reload window safe.

The concrete mechanism that creates that 0-UI window is the **unload beacon** (Vaadin 24.1+). On
`pagehide` the client unconditionally sends `navigator.sendBeacon(uidlUrl, {"UNLOAD": true})` — on
a plain F5 reload, not just on tab close. The server (`ServerRpcHandler#handleUnloadBeaconRequest`)
then `ui.close()`es the old UI **unless** its active route/layout is `@PreserveOnRefresh`, in which
case the beacon is ignored. Because the beacon and the new tab's bootstrap request are independent
requests (ordered only by the session lock), on a **non-`@PreserveOnRefresh`** reload the beacon
can close the old UI *before* the new UI is created — a real interval with zero live UIs for the
window name. The grace period exists to survive exactly that interval. (Absent the beacon, the old
UI would instead linger ~15.5 min until heartbeat timeout, so there would be no gap — the beacon is
what makes the timer necessary.) This is also why the timer cannot simply be replaced by
"reap when the UI set empties": see [ideas/retire-cleanup-timeout.md](ideas/retire-cleanup-timeout.md),
which investigates and rejects that for the general (annotation-agnostic) case.

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

## Relationship to `@PreserveOnRefresh` (and why it is not required)

**Decision: tab scoping is intentionally annotation-agnostic. `@PreserveOnRefresh` is never a
prerequisite — at most it is an optional implementation optimization.** The README's claim that
the project "works correctly even without `@PreserveOnRefresh`" is a deliberate design property,
not an accident.

### The two are distinct concepts that merely share one primitive

`@PreserveOnRefresh` is a narrow, purpose-built marker: per its docs, the routed component
instance is reused "only when reloaded in the same browser tab … and only if the URL stays the
same", and its state is "discarded permanently" on navigation to a different route or a URL
parameter change. The `UI` itself is still not preserved — the component reattaches to a fresh
UI. It exists mainly for the data-entry-form UX case (accidental F5 shouldn't lose typed data).

Tab scope, as built here, is a strict superset of that and then some:

| | `@PreserveOnRefresh` | This project's tab scope |
|---|---|---|
| Survives F5 (same URL) | yes | yes |
| Survives navigate-away-and-back | no (discarded) | yes (`TabScopedRouteInstantiator` re-caches) |
| Arbitrary per-tab key/value store | no | yes (`TabScope.getValues()`) |
| Keyed by | `window.name` **+ location** | `window.name` only |

They overlap only in using `window.name` as tab identity — but that is simply *the* browser
primitive for tab identity; sharing it implies no shared design lineage. Semantically, tab scope
cannot derive from `@PreserveOnRefresh`, because it is broader on every axis.

### Neither deliverable depends on the annotation

- **Tab-scoped values** live in the `VaadinSession` keyed by `window.name`, and `TabScope.init`
  fetches `ExtendedClientDetails` itself (`retrieveExtendedClientDetails`). The annotation is
  irrelevant to them.
- **`@TabScoped` routes** are cached and reattached by `TabScopedRouteInstantiator`
  (`removeFromTree` + reuse). This *reimplements and exceeds* `@PreserveOnRefresh` — it even
  survives navigation, which `@PreserveOnRefresh` explicitly does not.

### Vaadin does not treat `@PreserveOnRefresh` as a step toward tab scope

We checked whether Vaadin intends `@PreserveOnRefresh` as an early rung on a ladder toward proper
tab scope (which would justify requiring it). It does not. [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468)
("Add support for browser tab scope") explicitly lists a `@PreserveOnRefresh` parent layout among
the *failed* workarounds (survives F5, breaks on back/forward and manual URL entry). The issue is
still labelled `investigation` / `needs design` and sits in the backlog — a real tab scope is
unbuilt and undesigned, and nothing positions `@PreserveOnRefresh` as its precursor. Coupling to
it would therefore inherit its limitations (single-URL, no navigation survival) and bet on a
design lineage that does not exist.

### The one legitimate dependency is an optimization, not a requirement

The single place `@PreserveOnRefresh` genuinely helps is the **cleanup lifecycle**, not the
scoping semantics. On the preserve path, Flow guarantees a non-closing UI always exists during a
reload (the 2-UI overlap described under "Cleanup"), which could let us drop the grace-period
timer. Without the annotation, the old UI lingers to heartbeat timeout and a real zero-UI gap
appears — which is exactly what the timer covers. So:

- **Semantic layer** — tab scope must *not* require `@PreserveOnRefresh`. Keep it working both ways.
- **Implementation layer** — `@PreserveOnRefresh`, *when present*, offers a stronger UI-lifecycle
  guarantee we may exploit as an optimization, always with a fallback for when it is absent.

See [ideas/retire-cleanup-timeout.md](ideas/retire-cleanup-timeout.md) for that optimization and
its caveats.

Sources: [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468),
[Vaadin docs — Preserving State on Refresh](https://vaadin.com/docs/latest/flow/advanced/preserving-state-on-refresh),
[vaadin/flow#3522](https://github.com/vaadin/flow/issues/3522).

## Testing

There is no browser/Selenium layer in this repo — the `window.name`-preservation behavior above
is only testable manually across real browsers. `AbstractAppTest` spins up `MockVaadin` with
auto-discovered routes and resets `ApplicationServiceInitListener.counter` so counter-dependent
assertions are deterministic. New view tests should extend it.
