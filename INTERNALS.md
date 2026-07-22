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

### ECD API: why the deprecated `retrieveExtendedClientDetails`

`TabScope.init` (and the testapp `MainLayout`) fetch the window name via
`Page.retrieveExtendedClientDetails(receiver)`, which Flow 25.x **deprecates** in favor of the
synchronous `Page.getExtendedClientDetails()` plus `ExtendedClientDetails.refresh(...)`. We keep the
deprecated call on purpose — migrating would trade a compile-time warning for a **runtime
`NoSuchMethodError` on older Vaadins**, which is strictly worse for a version-agnostic add-on:

| API | `@since` |
|---|---|
| `retrieveExtendedClientDetails(receiver)` (deprecated) | 2.0 — every Flow version |
| `Page.getExtendedClientDetails()`, `ExtendedClientDetails.refresh(...)` | 25.0 |
| eager `v-wn`-on-bootstrap (`ExtendedClientDetails.updateFromValues`) | 25.2 / 25.2.1 |

`tab-scope` is `compileOnly(vaadin-core)` precisely so consumers bring their own Vaadin version, so a
published jar compiled against the `@since 25.0` getters would `NoSuchMethodError` the moment
`init()` runs on a consumer's Vaadin 24.x. The deprecated method (`@since 2.0`) has no such floor,
and it also self-adjusts across the 25.0/25.1 → 25.2 boundary: pre-25.2 it does the async roundtrip
(and participates in the navigation deferral the "Ordering" section relies on), 25.2+ it
short-circuits synchronously off the eagerly-sent `v-wn`. `getExtendedClientDetails()` never returns
null (it lazily builds a placeholder with dims `-1` and `windowName == null`, `UIInternals`), so the
hazard is linkage, not an NPE. Both call sites carry `@SuppressWarnings("deprecation")` with a
pointer here; don't "clean them up" without raising the Vaadin floor to 25.2. Verified against Flow
25.2.4 sources.

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
- **Client-side reload detection.** From the outgoing page a reload and a tab close are
  indistinguishable — `pagehide`/`beforeunload`/`unload`/`visibilitychange→hidden` all fire in
  both cases (this is exactly why the beacon can't guard against reload; see Path 2 above). The
  only clean way to know a load *was* a reload is on the **next** page, via
  `performance.getEntriesByType("navigation")[0].type === "reload"`. Rejected as a cleanup
  mechanism because it can't help us: (1) the server already learns "this tab came back" sooner
  and more reliably from the matching `window.name` (the `v-wn` param on bootstrap request A),
  which is what clears `orphanedSince` — the nav-timing check is strictly redundant with it and
  arrives no earlier; (2) it's on the wrong side of the gap — the zero-UI window opens *before*
  the new page loads, and the close case is silence (no page loads, no navigation entry), so
  "reload → new load" vs "close → nothing" is exactly what the timer already keys on; (3) it
  doesn't rescue the painful Safari `window.name`-loss case (see "Tab identity fragility") —
  knowing "you reloaded" without the identity link can't reconnect the new page to the orphaned
  scope. Its only real use would be **diagnostic**: logging `nav.type` on the new page could label
  a scope-recreation as "followed a reload that dropped `window.name`" vs. a genuinely new tab,
  turning part of the manual browser investigation into a recordable signal — a telemetry nicety,
  not a behavior change.

### When cleanup actually runs

Three things drive cleanup:

- **The scheduled reap (on by default).** When a scope orphans, a one-shot task is armed on a shared
  daemon `ScheduledExecutorService` for `CLEANUP_DURATION_MS` later. When it fires it hops onto the
  session lock via `session.access` — which self-purges its queue *on the reaper thread* when no
  request holds the lock (`VaadinService#ensureAccessQueuePurged`) — and reaps the scope if it is
  still orphaned. Set the public flag `TabScope.scheduledReapEnabled = false` to switch this off
  entirely (`armReap()` becomes a no-op, no reaper thread is created) and fall back to the two
  request-driven triggers below — the behavior before the scheduled reaper was added, for apps that
  prefer to ride Vaadin's default UI-closing without a background thread. This is what makes a **sole last tab** reap promptly: with no other tab there is
  no future request, yet the timer still fires. The task is cancelled when a UI re-attaches (which
  clears `orphanedSince`) and is idempotent anyway (a no-op if a UI came back). The `ScheduledFuture`
  lives in a `transient` field, so a passivated/reactivated scope simply falls back to the two
  request-driven triggers below.
- **On UI init / detach (request-driven).** `cleanupOrphans()` still sweeps on every UI init and
  every UI detach (`removeUI`), so a scope orphaned by one tab is also reaped by activity in another.
- **Session destroy.** `destroyAllTabScopes()` closes everything as the reliable floor.

`updateOrphaned()` also drops UIs for which `UI.isClosing()` is true (and does not count
beacon-closed UIs — see "Tab close"), so such a UI does not keep a scope alive.

The shared reaper thread is a daemon, lazily created, and shut down on `VaadinService` destroy
(`addServiceDestroyListener`) so a servlet-container redeploy does not leak it.

### Tab close

For a **plain (non-`@PreserveOnRefresh`) route**, tab close needs no special handling:
[the beacon kills the UI eagerly](https://vaadin.com/blog/vaadin-flow-24.1-drastically-reduces-memory-usage),
which detaches it and starts the orphan grace period; the scheduled reap then destroys the scope
~60 s later. Reopening the tab does not preserve `window.name`, so it arrives as a brand-new scope —
nothing to reconnect to. (The beacon is flaky in practice — e.g. Vaadin 25.0 with LibreWolf — but
the session-destroy backstop catches the scope regardless.)

For a **`@PreserveOnRefresh` route**, Flow *ignores* the beacon (closing the UI would defeat the
preserve), so on a sole-tab close nothing detaches the UI and the scope never orphans — it would
linger until session-destroy. The optional tab-close beacon hook closes this gap: an app wires
`TabScope.onUnloadBeacon(UI)` into its own `ServerRpcHandler` (via the shipped
`TabScopeServerRpcHandler` + `TabScope.installTabCloseBeacon`), and the hook starts the grace clock
**without** closing the UI. A beacon-closed UI stays in `Lifecycle.uis` (so its eventual real detach
is handled normally) but no longer counts as keeping the scope alive — orphan-eligibility is
`uis − beaconClosed`. A genuine F5 re-attaches a same-`window.name` UI within the grace and cancels
the reap; a real close has no such reattach, so the timer reaps it. This was verified end-to-end in
a real browser (the case Karibu can't reproduce — see "Testing"). See "Relationship to
`@PreserveOnRefresh`" and README for the wiring.

### When destroy listeners fire

`TabScope.addDestroyListener` callbacks fire before `values` are cleared, and they fire **reliably**
on every graceful session teardown: explicit `session.close()`, browser-tab close, and an ordinary
idle session timeout alike. The idle-timeout bridge is `HttpSessionBindingListener` (not a registered
`HttpSessionListener`), so it needs no `web.xml` or programmatic registration and works uniformly. It
is verified end-to-end (container idle-timeout → `HttpSession` invalidation → Vaadin
`SessionDestroyListener`) on **both** embedded Jetty and Tomcat in
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

Rely on them for graceful lifecycles (timeout, explicit close, redeploy). Two things are worth
knowing, neither a reason to hedge the feature as "best-effort":

- **Sole-last-tab close is now prompt; a genuine idle timeout still is not.** When the last tab
  *closes*, the scheduled reap fires destroy within the grace period (~60 s) — for plain routes via
  the beacon's eager UI-close, and for `@PreserveOnRefresh` routes via the tab-close beacon hook (see
  "Cleanup" → "Tab close"). What remains container-paced is a genuine idle *timeout* with the tab
  still open: destroy then waits for the container's session sweep (Jetty `HouseKeeper`, default
  every 10 min; Tomcat `backgroundProcessorDelay`, default 10 s) after Vaadin heartbeats stop
  (default every 5 min). This closes [issue #3](https://github.com/mvysny/vaadin-tab-scope/issues/3).
- **An abrupt kill skips it.** `kill -9` / power loss / JVM crash run no orderly shutdown, so nothing
  fires — but that is true of every shutdown hook in every framework, not a property of this listener.
  (One narrow non-crash edge: a session serialized to disk, restored after a container restart, and
  expired before any request touched it — Flow's `!isInitialized()` guard at
  `VaadinSession.java:194-200` logs "Session destroy events will not be fired …" and returns.)

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

The prompt-reap seams (the tab-close beacon hook and the scheduled reaper, verified against Flow **25.2.4**):

- **Beacon hook seam:** `ServerRpcHandler#createRpcHandler` is not overridable directly; the factory
  is `UidlRequestHandler#createRpcHandler` (protected), and the handler chain is built by
  `VaadinService#createRequestHandlers` (protected). `ServerRpcHandler#handleUnloadBeaconRequest`
  (protected) is the only beacon hook; its `RpcRequest#isUnloadBeaconRequest` is **private**, so we
  replicate it via the public `RpcRequest#getRawJson().has(ApplicationConstants.UNLOAD_BEACON)`. The
  beacon is a plain `?v-r=uidl` request (`UNLOAD` lives in the JSON body, read once by
  `UidlRequestHandler#synchronizedHandleRequest`), so there is no HTTP-level marker a front-of-chain
  `RequestHandler` could catch without consuming the body. (Flow FR for a clean hook:
  [vaadin/flow#17360](https://github.com/vaadin/flow/issues/17360).)
- **Off-request reap:** `VaadinSession#access` → `VaadinService#accessSession` →
  `#ensureAccessQueuePurged` (2469) — when the scheduler thread calls `access` and no thread holds
  the lock, it acquires and releases the lock itself, and `#runPendingAccessTasks` runs the reap on
  the scheduler thread. This is why the timer fires with no client request in flight.

## Relationship to `@PreserveOnRefresh` (and why it is not required)

**Decision: tab scoping is intentionally annotation-agnostic. `@PreserveOnRefresh` is never a
prerequisite for tab-scope *semantics*.** The README's claim that the project "works correctly even
without `@PreserveOnRefresh`" is a deliberate design property, not an accident. What *has* changed
(see "The cleanup lifecycle" below): for **prompt** cleanup of an app that *does* use
`@PreserveOnRefresh`, the tab-close beacon hook is load-bearing — a first-class, wired case, no
longer the "optional optimization" earlier drafts called it.

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

### The cleanup lifecycle: a first-class, wired case (not an optimization)

`@PreserveOnRefresh` does not change tab-scope *semantics* — values and `@TabScoped` routes behave
identically with or without it. Where it matters is the **cleanup lifecycle**, and there it is
first-class, not the theoretical optimization earlier drafts described:

- On the preserve path Flow **ignores** the unload beacon, so a sole-tab close never orphans the
  scope on its own; it would linger until session-destroy. Prompt reap therefore *requires* the
  beacon hook (`onUnloadBeacon`), which starts the grace clock without closing the preserved UI.
  This is a real, wired feature (see "Cleanup" → "Tab close"), verified end-to-end in a real browser.
- An app **cannot** simply drop `@PreserveOnRefresh` to sidestep this. Flow offers no public API to
  move a component tree onto the fresh UI it builds on every F5
  ([vaadin/flow#25019](https://github.com/vaadin/flow/issues/25019)), so the annotation is currently
  the *only* supported cross-UI state transfer; dropping it would lose all UI state on every refresh.

So the balance is:

- **Semantic layer** — tab scope must *not* require `@PreserveOnRefresh`. Keep it working both ways.
- **Cleanup layer** — the always-on scheduled reaper covers plain routes and is the general floor;
  the tab-close beacon hook, *when the app opts in*, extends prompt reap to the `@PreserveOnRefresh`
  case. For a preserve app that wants prompt cleanup, the beacon hook is load-bearing.

The full analysis of why the timer can't be replaced by reap-on-empty (the unload-beacon race,
per-path table, and rejected alternatives) is under "Cleanup" → "Why the timer is necessary".

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
  `TabScope.CLEANUP_DURATION_MS` is a public, app-configurable grace period (60 s by default); the
  test shrinks it (to `-1`) to let an EAGER reload run straight through orphan → reap → fresh scope
  in one call.
- `TabScopePromptReapTest` drives the **prompt** reap (the beacon hook and the scheduled reaper). Its second seam is
  `TabScope.reapScheduler`: a package-private field that, when set to a `ManualReapScheduler`,
  captures the armed reap instead of scheduling it on the real daemon executor — so a test can fire
  it (or assert it was cancelled on reattach) deterministically, with no real sleeps, then drain the
  `session.access` the reap enqueues via `MockVaadin.clientRoundtrip()`. It covers: the timer alone
  reaping a closed tab with no further request; a reattach within grace cancelling the reap;
  `onUnloadBeacon` starting the clock while the `@PreserveOnRefresh` UI stays attached; a
  beacon-then-F5 keeping the scope; and `installTabCloseBeacon` swapping the stock handler.

  These two — `CLEANUP_DURATION_MS` and `reapScheduler` — are the only production seams added for
  testing. What Karibu **cannot** do is route a beacon through a real (app-customized)
  `ServerRpcHandler` — it reimplements the beacon outcome and detaches preserve UIs unconditionally
  — so the full A HTTP path (custom servlet → `TabScopeUidlRequestHandler` →
  `TabScopeServerRpcHandler` → `onUnloadBeacon`) is verified with a **real browser** against the
  `testapp` `/preserve` route, not browserlessly. (Upstream FR to lift this:
  [mvysny/karibu-testing#210](https://github.com/mvysny/karibu-testing/issues/210).)

### Multi-tab isolation

The capability tab scope exists for — two browser tabs (distinct `window.name`s) in one session
getting two independent scopes — is now tested via Karibu-Testing's `MockBrowser`:

- `MultiTabTest` — `MockBrowser.newTab()` / `switchTo(...)` / `closeTab(...)`: two tabs get
  distinct scopes with per-tab init (no value leakage), a `@TabScoped` route resolves to a
  different instance per tab, and closing one tab leaves the other's scope intact.
- `TabIdentityTest`, and the `reapInactiveUIs()` cases above, also lean on `MockBrowser`.

These use `MockBrowser`, `KaribuConfig.windowName` and `MockVaadin.reapInactiveUIs()` from
Karibu-Testing 2.7.1 (a normal Maven Central dependency).
