# Architecture

How the pieces compose — what no single symbol can say and what would be expensive to overturn:
wiring and dependency direction, the lifecycle / threading / data-flow story, the flows a newcomer
needs, where to start reading. **Normative: the code conforms.** Change this file first, then the
code. Not here: why (`decisions.md` — cite the `D_`), what upstream does (`research.md` — cite the
`R_`), one symbol's behaviour (its doc comment), the module map (`AGENTS.md`). Cap 12 KB — over
it, research or doc-comment content has crept in.

---

## Wiring

- Dependencies point one way: `testapp` → `tab-scope` → Vaadin Flow. Nothing in `tab-scope`
  references `testapp`, and the library is `compileOnly(vaadin-core)` — it links against whatever
  Vaadin the consumer brings (`D_compileonly_api_floor`).
- The app constructs nothing. `TabScope.setup(tabInitListener)` is one call from the app's
  `VaadinServiceInitListener`, and it registers the three Flow extension points the library hangs
  off: a UI-init listener (the per-tab bootstrap, which is where a scope is actually built), a
  session-init listener that adds the session-destroy backstop, and a service-destroy listener that
  shuts the reaper thread down so a container redeploy leaks neither it nor its classloader.
- The store is session state, not static state: the whole scope map lives on the `VaadinSession`
  under the attribute `"tab-scopes"` (`Map<String, TabScope>`, keyed by `window.name`), so scope
  lifetime is bounded by session lifetime whatever else fails. Reading or mutating a scope requires
  the session lock.
- `TabScopedRouteInstantiator extends DefaultInstantiator` and reads `TabScope.getCurrent()` — so
  the instantiator depends on the store, never the reverse. The app registers it through
  `META-INF/services/com.vaadin.flow.di.InstantiatorFactory`; the library ships no such file
  (`D_no_spi_files`).
- The tab-close beacon is the one path the library cannot reach by itself.
  `TabScopeUidlRequestHandler` → `TabScopeServerRpcHandler` → `TabScope.onUnloadBeacon(UI)` is a
  complete chain, but only an app's own `VaadinServlet` can put it in place, via
  `TabScope.installTabCloseBeacon(handlers)` inside `createRequestHandlers()`
  (`R_flow_unload_beacon`).
- Two threads touch a scope. Everything above runs on a Vaadin UI thread under the session lock;
  the reaper is a shared daemon `ScheduledExecutorService` (`ReapScheduler`) that runs off-request
  and hops onto the lock via `session.access` before touching anything (`D_scheduled_reaper`).
- Two seams let a test drive that clock: `TabScope.CLEANUP_DURATION_MS`, which is also a public
  tuning knob, and the package-private `TabScope.reapScheduler`, which a test swaps for a manual
  scheduler. Adding a third is a design change, not a test fix.

## Flows

**A tab's first request** — this is the flow the whole project exists to provide, and the one
nothing in our code enforces:

1. Flow bootstraps a new `UI` and fires UI-init listeners; `TabScope.init` runs.
2. `init` registers an ECD receiver via `ui.getPage().retrieveExtendedClientDetails(...)`. Vaadin
   defers navigation until the ECD is in hand, because `@PreserveOnRefresh` needs `window.name`
   too — **that deferral, not our code, is what puts the callback before any route or layout is
   constructed** (`R_flow_ecd_api`). Treat the ordering as borrowed, not owned.
3. In the receiver: `cleanupOrphans()` sweeps the session, then the scope for this `window.name` is
   looked up. Absent, it is created, registered in the map, and the app's tab-init listener runs
   against it — exactly once per tab.
4. The UI is hooked into the scope's `Lifecycle`, and a detach listener is registered on it that
   acts **only when `ui.isClosing()`** — a detach event is not proof of a detach
   (`R_flow_resync`, `D_strict_uis`).
5. Navigation proceeds. A route or layout annotated `@TabScoped` is served by
   `TabScopedRouteInstantiator` from `TabScope.getValues()`, keyed by class; a cached component is
   `removeFromTree()`d first, because Flow refuses to move a node between state trees and every
   reload is a new UI.

**A reload (F5)** — the flow the 60-second grace period exists for:

1. The browser's `pagehide` fires the unload beacon. On a plain route Flow closes the old UI; on a
   `@PreserveOnRefresh` route Flow ignores the beacon, and the beacon hook — if the app installed
   it — instead marks the UI beacon-closed without closing it (`R_flow_unload_beacon`).
2. The bootstrap request builds the new UI and runs the first-request flow above. Since the scope
   already exists under this `window.name`, the tab-init listener does *not* re-run; the new UI is
   simply added to the same `Lifecycle`.
3. These are independent requests with no ordering guarantee, so step 1 can win: the scope
   transiently holds zero live UIs while the tab is very much alive. It can equally hold two, when
   the beacon is late or lost.
4. Whenever the live-UI count reaches zero the scope is marked orphaned and a one-shot reap is
   armed; a UI re-attaching clears `orphanedSince` and cancels the reap. The scope is only actually
   closed once it has been orphaned longer than `CLEANUP_DURATION_MS` (`D_grace_period`).

**A tab close** — the same machinery, with nothing coming back:

1. The beacon arrives as above; nothing re-attaches.
2. The reap fires after the grace period on the reaper thread, hops onto the session lock, and —
   the scope still being orphaned — runs its destroy listeners, clears the values and drops it from
   the map. This is what makes a *sole last tab* reap promptly, with no further request to ride on.
3. Two backstops sit behind that: the request-driven `cleanupOrphans()`, which any other tab's UI
   init or detach runs; and, as the floor that always holds, `destroyAllTabScopes()` on session
   destroy (`R_flow_session_destroy`).

Liveness, throughout, is `uis − closing − beaconClosed`. A UI that stops counting as live is
**never** removed from `uis` — entries leave only through that UI's own detach listener, so `uis`
always means "UIs whose detach we still expect" (`D_strict_uis`).

## Where to start reading

`TabScope.java` — the whole store, its `Lifecycle` inner class, and every Flow hook the library
uses are in that one file; `TabScopedRouteInstantiator` is a thin `DefaultInstantiator` subclass on
top of it, and the two beacon classes are adapters with no logic of their own.

## What is deliberately absent

- **No `META-INF/services` files in the library**, so the app does its own wiring (`D_no_spi_files`).
- **No Spring module.** `tab-scope-spring` is left possible, not built (`D_no_spi_files`).
- **No requirement that tab-scoped routes be `@PreserveOnRefresh`** (`D_annotation_agnostic`).
- **No browser/Selenium layer.** `window.name` preservation and the beacon's full HTTP path are
  verified by hand against `testapp`; Karibu reimplements the beacon outcome rather than running a
  real `ServerRpcHandler` ([mvysny/karibu-testing#210](https://github.com/mvysny/karibu-testing/issues/210)).
- **No client-side reload detection**, and no shortening of the grace period (`D_grace_period`).
