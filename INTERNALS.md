# Internals

This document collects the hard-won, investigated facts about how tab scoping is implemented
on top of Vaadin Flow, and *why* each design choice is the way it is. The user-facing
kick-start lives in [README.md](README.md); forward-looking design proposals, when there are any,
go under `ideas/`.

## The problem

Unlike Vaadin 8's `UI`, a Vaadin Flow `UI` does **not** survive a page reload, and
`UIInitListener` fires multiple times per browser tab — once per reload. There is therefore no
built-in place that runs exactly once per tab, and no built-in per-tab storage that survives a
reload. See [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468).

Tab identity is instead derived from the browser's `window.name`, exposed to the server via
`ExtendedClientDetails.getWindowName()` (a unique ID per browser tab).

## Library vs. app boundary

The project is a two-module Gradle build. The **`tab-scope`** library (package
`com.github.mvysny.vaadin.tabscope`) ships pieces 1–2 below plus the `@TabScoped` annotation, and
**nothing else** — in particular, no `META-INF/services` files. The **`testapp`** demo owns both
SPI registration files and piece 3 (`ApplicationServiceInitListener`). The library is pure Java with
no frontend and does not apply the `com.vaadin` Gradle plugin; only the demo runs the Vaadin frontend
build. `tab-scope` is the product published to Maven Central — not a starter template — since Vaadin
upstream has no plans to support tab scope ([vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468));
`testapp` is the runnable demo that shows the wiring.

### Coordinates and naming

Following the [`jdbi-orm-vaadin`](https://gitlab.com/mvysny/jdbi-orm-vaadin) convention that the Maven
**group id squashes** while the Java **package stays dotted**:

| | value |
|---|---|
| group id (squashed) | `com.github.mvysny.vaadintabscope` |
| artifact id | `tab-scope` |
| full coordinates | `com.github.mvysny.vaadintabscope:tab-scope` |
| library package | `com.github.mvysny.vaadin.tabscope` |
| demo package | `testapp` |

### Why the library ships no `META-INF/services` files

Spring support is deferred, but deliberately **not prevented** — the lever that keeps that door open
is shipping no SPI service files from the library. Verified Flow/Spring mechanics:

- **`com.vaadin.flow.di.InstantiatorFactory`** — Vaadin resolves exactly **one** instantiator. Spring
  ships its own `SpringInstantiatorFactory` and registers instantiator factories by `@Component`, not
  SPI. If our library shipped this file it would collide with Spring's and **break Spring apps by
  construction** — a hard blocker, not a style choice.
- **`com.vaadin.flow.server.VaadinServiceInitListener`** — Spring auto-registers any `@Component`
  implementing it; non-Spring apps use SPI. Even though this file is merely *additive*, shipping it
  invites a double-registration trap under Spring (the listener discovered both via `ServiceLoader`
  and as a bean — [vaadin/spring#531](https://github.com/vaadin/spring/issues/531)).

So both files live in `testapp`, and the README documents them as the wiring a plain
Servlet/Vaadin-Boot app must add. (Consistent with `jdbi-orm-vaadin`, whose library also ships no SPI.)

### Consequence for the `setup()` contract

Because the library can't ship a `VaadinServiceInitListener`, there is no drop-in auto-wiring path:
the app **must** wire the plumbing. So `TabScope.setup(consumer)` stays the app-facing contract,
called from the app's own `VaadinServiceInitListener`. The "jar on the classpath but `setup()`
forgotten" footgun largely evaporates — both SPI files and the `setup()` call now live together and
visibly in the app.

### Future `tab-scope-spring` module (not built)

"Support Spring later" splits cleanly along the two product pieces:

- **`TabScope`** (the value store) is framework-agnostic — reusable under Spring as-is.
- **`TabScopedRouteInstantiator`** is *inherently* non-Spring: Spring needs its own `SpringInstantiator`,
  so ours cannot be stacked. A future Spring integration would be a **separate `tab-scope-spring`
  module** implementing a custom bean scope (the way `vaadin-spring` does `@RouteScope` /
  `VaadinRouteScope`), not a reuse of this class.

So: don't bake SPI assumptions into the core, and leave room beside `tab-scope/` for a future
`tab-scope-spring/`.

## The three pieces

Changing one of these usually means thinking about the other two.

### 1. `TabScope` — per-tab state, keyed by `window.name`

`TabScope` holds per-tab state keyed by `ExtendedClientDetails.getWindowName()`. The map of all
scopes lives on the `VaadinSession` under the attribute `"tab-scopes"`
(`Map<String, TabScope>`), so its lifetime is bounded by the session.

### 2. `TabScopedRouteInstantiator` — caching `@TabScoped` routes/layouts

Registered by the app via `META-INF/services/com.vaadin.flow.di.InstantiatorFactory` (the library
does not ship that file), it intercepts
route/layout instantiation. For classes annotated `@TabScoped`, it caches the instance in
`TabScope.getValues()` and — crucially — calls `element.removeFromTree()` before returning it.

Without `removeFromTree()`, reattaching a cached component to a *new* UI throws:

> Can't move a node from one state tree to another. If this is intentional, first remove the
> node from its current state tree by calling removeFromTree.

Every reload creates a new UI (see below), so a cached `@TabScoped` component is always being
moved from the old UI's state tree to the new one — hence the detach is mandatory.

### 3. `ApplicationServiceInitListener` — one-time per-tab init

Living in `testapp` and registered via `META-INF/services/com.vaadin.flow.server.VaadinServiceInitListener`,
it calls the library's `TabScope.setup(...)` exactly once, wiring up the tab-init callback that seeds per-tab values.

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

The *consequence* — a reload with a changed `window.name` producing a fresh scope — is now tested
browserlessly in `TabIdentityTest` via `MockBrowser.reload(newWindowName)`. What still needs a real
browser is confirming *which* browsers/actions actually fail to preserve `window.name` (the Safari
rows above); Karibu can only simulate the changed-name outcome, not measure a given browser.

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

**Do not shorten this without considering reload races**, and do not try to replace it with
"reap the moment the UI set empties" — the next subsection explains why that is unsafe.

### Why the timer is necessary (and can't be replaced by reap-on-empty)

It is tempting to drop the timer and simply close a scope the instant its live-UI set becomes
empty — event-driven and correct-by-construction, no heuristic. This does **not** work in the
general case. The reason is that a page reload retires the old UI through **two independent
mechanisms**, and only one of them is gap-free. This was verified against the Flow **25.2.1**
sources (`flow-server-25.2.1-sources.jar` + `flow-client-25.2.1.jar`).

**Path 1 — the navigation path (gap-free).** A reload is a two-request dance:

- *Request A — bootstrap (`?v-r=init`):* `BootstrapHandler#createAndInitUI` builds the new UI in
  the order `extractAndStoreBrowserDetails` → `session.addUI` → `fireUIInitListeners`. Since Flow
  25.2, the browser sends `window.name` (as the `v-wn` param) on *this* request, and
  `extractAndStoreBrowserDetails` stores it on the new UI **before** `addUI`. So the new UI's
  `window.name` is known synchronously here, and `Page#retrieveExtendedClientDetails`
  short-circuits synchronously — meaning `TabScope.init`'s callback (hence `lifecycle.add(newUI)`)
  runs *within request A*, with no round-trip.
- *Request B — navigation UIDL:* with `@PreserveOnRefresh`, `AbstractNavigationStateRenderer`
  teleports the component chain to the new UI and calls `prevUi.close()` as its **last** step.
  Without it, the old UI is **not** closed by navigation at all and lingers, inactive, until
  `VaadinService#closeInactiveUIs` reaps it after `heartbeatInterval × 3.1` (default ≈ 15.5 min).

Considering only this path, `add(newUI)` (request A) always precedes any `remove(oldUI)`, so the
set never empties on reload. If this were the whole story, the timer *could* go.

**Path 2 — the unload beacon (reopens the gap).** Vaadin 24.1 added an eager UI-close beacon:

- *Client:* a `pagehide` listener **unconditionally** sends
  `navigator.sendBeacon(uidlUrl, {"UNLOAD": true})`. `pagehide` fires on a plain **reload**, not
  only on tab close, and there is no client-side guard for reload or for `@PreserveOnRefresh`.
  (`beforeunload` is also registered but only sets an internal "unloading" flag — it does not send
  the beacon.)
- *Server (`ServerRpcHandler#handleUnloadBeaconRequest`):* on receiving the beacon it calls
  `ui.close()` on the old UI **unless** that UI's active route/layout chain is
  `@PreserveOnRefresh`, in which case it logs "Eager UI close ignored" and does nothing.

The beacon and bootstrap request A are independent HTTP requests, serialized server-side only by
the `VaadinSession` lock — with **no ordering guarantee**. So on a **non-`@PreserveOnRefresh`**
reload the beacon can win the race:

```
beacon → old UI ui.close()   (old UI now isClosing / inactive)
        ── zero live UIs for this window.name ──
request A → new UI created, window.name registered
```

During that interval the scope genuinely has no live UI, even though the tab is merely reloading.
Reaping on empty would kill a live scope. The grace period exists to survive exactly this window.

Note the irony: **without** the beacon, even the non-preserve reload would be gap-free (the old UI
would linger ~15.5 min). The beacon is precisely what *manufactures* the reload gap — and thus
what makes the timer necessary.

| Reload path | Old UI retired by | Zero-UI gap? | Timer needed? |
|---|---|---|---|
| With `@PreserveOnRefresh` | preserve-nav, after new UI holds the chain; beacon **ignored** | No | No |
| Without `@PreserveOnRefresh` | unload beacon (`ui.close()`), can beat bootstrap | **Yes** | **Yes** |

Because a scope's route at reload time may or may not be `@PreserveOnRefresh`, and the project
deliberately stays annotation-agnostic (see "Relationship to `@PreserveOnRefresh`" below),
**the timer must remain the general mechanism.** Removing it would require mandating
`@PreserveOnRefresh` on every tab-scoped route/layout, which we rejected.

### Alternatives considered (and rejected)

- **Shorten, don't remove.** The gap is a request-race window (tens of milliseconds to a few
  seconds on a bad connection), not minutes, so `CLEANUP_DURATION_MS` could in principle be tuned
  down. But shortening trades robustness on slow reloads for marginally faster cleanup — low
  value, real risk. Left at 60 s.
- **Hybrid (per-scope).** Skip the timer only for scopes whose current UI is a
  `@PreserveOnRefresh` target, keep the timer otherwise. Rejected: a scope's route changes over
  its lifetime, so it cannot be statically classified as preserve/non-preserve; the bookkeeping
  ends up more fragile than the timer it would replace.
- **Successor detection as an *optimization* (not a replacement).** On detach, if a non-closing UI
  with the same `window.name` already exists in `VaadinSession.getUIs()`, skip straight to "not
  orphaned"; otherwise fall back to the timer. This would reap faster on the preserve path while
  staying safe on the non-preserve path, but it adds an O(#UIs) scan (Flow keeps **no**
  UI-by-`windowName` index — see references) plus complexity, for marginal gain. Possible future
  refinement, not worth it today.

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

`TabScope.addDestroyListener` callbacks fire before `values` are cleared. An **ordinary idle
session timeout does invoke them** — contrary to what this section previously claimed. The bridge
is `HttpSessionBindingListener`, not a registered `HttpSessionListener`, so it needs no `web.xml`
or programmatic registration and works uniformly (embedded Jetty / Vaadin Boot included). Verified
against Flow 25.2.4 and confirmed empirically in
[mvysny/vaadin-boot#39](https://github.com/mvysny/vaadin-boot/issues/39) (the source of truth):

- `VaadinSession implements HttpSessionBindingListener` (`VaadinSession.java:78`). The
  `VaadinSession` is itself an attribute of the servlet `HttpSession`, so when the container
  invalidates the session on timeout it unbinds that attribute and calls
  `VaadinSession.valueUnbound()` (`VaadinSession.java:185`).
- Timeout runs on the container's reaper thread, outside any Vaadin request, so
  `VaadinService.getCurrentRequest() == null` and `valueUnbound` takes the `else` branch that calls
  `service.fireSessionDestroy(this)` directly (`VaadinSession.java:218-220`). If a request *is* in
  flight, it instead `close()`s and `cleanupSession` fires destroy at request end
  (`VaadinService.java:1712-1738`). Either way `fireSessionDestroy` (`VaadinService.java:1040`)
  invokes every `SessionDestroyListener` — including the one `TabScope.setup` registers.

So the callbacks are **more reliable than "best-effort" suggested**, but still not guaranteed. The
genuine gaps are narrow:

- **JVM crash / `kill -9` / power loss** — no orderly shutdown, nothing fires.
- **Deserialized-but-never-used session** — a session persisted to disk, restored after a container
  restart, and expired before any request touched it (transients never refreshed). Flow detects
  this via the `!isInitialized()` guard at the top of `valueUnbound` (`VaadinSession.java:194-200`)
  and logs "Session destroy events will not be fired …" before returning.

Treat destroy listeners as reliable for graceful lifecycles (timeout, explicit close, redeploy) and
as best-effort only against the two gaps above; don't depend on them for correctness-critical
cleanup that a crash could skip.

### Source references (Flow 25.2.1)

The cleanup analysis above was verified against `flow-server-25.2.1-sources.jar` and
`flow-client-25.2.1.jar`. Exact locations:

- **Beacon, client** (GWT `FlowClient.js`): `pagehide` listener → `navigator.sendBeacon(url,
  {"UNLOAD": true})`, sent unconditionally; `beforeunload` only sets an "unloading" flag. (Symbols
  are minified, but the event strings, `navigator.sendBeacon`, and the `{"UNLOAD":true}` payload
  are verbatim and line up with the server-side handler below.)
- **Beacon, server:** `ServerRpcHandler#handleUnloadBeaconRequest` (468–478),
  `#isPreserveOnRefreshTarget` (525–529), `RpcRequest#isUnloadBeaconRequest` (203–205);
  `ApplicationConstants.UNLOAD_BEACON = "UNLOAD"` (268, `@since 24.1`).
- **Bootstrap / `window.name`:** `BootstrapHandler#createAndInitUI` (1349) →
  `extractAndStoreBrowserDetails` (1380) → `session.addUI` (1385) → `fireUIInitListeners` (1387);
  `ExtendedClientDetails.updateFromValues` reads the `v-wn` param (556). (`v-wn`-on-bootstrap is a
  Flow 25.2+ behavior — `updateFromValues` is `@since 25.3`, `updateFromJson` `@since 25.2`; older
  Flow fetched `window.name` via a separate async round-trip, so this ordering must be re-verified
  before relying on it on the v23/24 branches.)
- **Navigation / preserve:** `AbstractNavigationStateRenderer#disconnectElements` (1055–1073,
  `prevUi.close()` at 1071), the non-preserve `else` branch of `#populateChain` (328–339).
- **ECD synchronous short-circuit:** `Page#retrieveExtendedClientDetails` (782–791).
- **UI close flag:** `UI#close` (375–376, sets `closing = true`).
- **Inactive-UI reaping:** `VaadinService#closeInactiveUIs` (1764–1772), `#removeClosedUIs`
  (1748), `#isUIActive` (1832), `#getHeartbeatTimeout` (1791) = `heartbeatInterval × 3.1`;
  `DefaultDeploymentConfiguration.DEFAULT_HEARTBEAT_INTERVAL` = 300 (72) → ≈ 930 s ≈ 15.5 min.
- **UI enumeration:** `VaadinSession#getUIs` (590) — keyed by UI id; there is no window-name index,
  and a UI's window name is only reachable via
  `ui.getInternals().getExtendedClientDetails().getWindowName()` (can be null on non-standard
  bootstrap requests that omit `v-sw`).

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

The full analysis of why the timer can't simply be dropped (the unload-beacon race, per-path
table, and the alternatives that were rejected) is under "Cleanup" → "Why the timer is necessary".

Sources: [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468),
[Vaadin docs — Preserving State on Refresh](https://vaadin.com/docs/latest/flow/advanced/preserving-state-on-refresh),
[vaadin/flow#3522](https://github.com/vaadin/flow/issues/3522).

## Testing

There is no browser/Selenium layer in this repo — the `window.name`-preservation behavior above
is only testable manually across real browsers. `AbstractAppTest` spins up `MockVaadin` with
auto-discovered routes and resets `ApplicationServiceInitListener.counter` so counter-dependent
assertions are deterministic. New view tests should extend it.

### Reproducing the unload-beacon reload orderings

The zero-UI gap analyzed under "Why the timer is necessary" *is* reproducible browserlessly.
Karibu-Testing 2.7.1's `KaribuConfig.unloadBeaconTiming` (`UnloadBeaconTiming.EAGER` / `LATE` /
`NEVER`) controls when the simulated unload beacon closes the old UI during an F5
`Page.reload()`, letting `tab-scope`'s `TabScopeReloadTimingTest` drive each reload path against
the library directly:

- **EAGER** — old UI detached *before* the new one attaches: the zero-UI gap. The test asserts the
  scope's destroy listener does **not** fire, i.e. the 60 s grace period is what carries it across
  the gap, and that exactly one UI remains afterward.
- **LATE** — new UI attaches *before* the old detaches: the UI set never empties.
- **NEVER** — beacon lost, old UI lingers: the session transiently holds **two** UIs (the "0 or 2
  UIs" case on `TabScope.Lifecycle.uis`).
- **`@PreserveOnRefresh`** — Flow ignores the beacon, so the timing has no effect; a separate
  parameterized case confirms the scope survives and one UI remains regardless of the flag.

What still isn't testable this way is the *timing* itself (the race is deterministic here) or
`window.name` preservation — those remain manual across real browsers.

### Routing, layout caching, and lifecycle coverage

- `TabScopedRoutingTest` exercises `TabScopedRouteInstantiator` across **navigation** (not just
  reload): a `@TabScoped` route survives navigate-away-and-back (the property that makes tab scope
  a superset of `@PreserveOnRefresh`), a `@TabScoped` *layout* is reused, and two `@TabScoped`
  types coexist in one scope keyed by class.
- `TabScopeLifecycleTest` drives the **reaping** branches the survival tests never reach: an
  orphaned scope is destroyed and dropped from the scope map once past the grace period, a
  lost-beacon background tab is reaped (`MockVaadin.reapInactiveUIs()`) and its scope destroyed,
  session destroy closes all scopes, and `getCurrent()` / `getValues()` fail fast in their guard
  cases.

  Reaping is time-gated on `System.currentTimeMillis()` inside our own code, so Karibu can't help.
  `TabScope.CLEANUP_DURATION_MS` is therefore package-private and non-final **solely** so the test
  can shrink it (to `-1`) and let an EAGER reload run straight through orphan → reap → fresh scope
  in one call; treat it as a 60 s constant in production. This is the one production seam added for
  testing.

### Multi-tab isolation

The capability tab scope exists for — two browser tabs (distinct `window.name`s) in one session
getting two independent scopes — is now tested via Karibu-Testing's `MockBrowser`:

- `MultiTabTest` — `MockBrowser.newTab()` / `switchTo(...)` / `closeTab(...)`: two tabs get
  distinct scopes with per-tab init (no value leakage), a `@TabScoped` route resolves to a
  different instance per tab, and closing one tab leaves the other's scope intact.
- `TabIdentityTest`, and the `reapInactiveUIs()` cases above, also lean on `MockBrowser`.

These use `MockBrowser`, `KaribuConfig.windowName` and `MockVaadin.reapInactiveUIs()` from
Karibu-Testing 2.7.1 (a normal Maven Central dependency).
