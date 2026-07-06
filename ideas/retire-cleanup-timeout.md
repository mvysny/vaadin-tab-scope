# Idea: retire the cleanup timeout

**Status:** investigated — **rejected for the general case.** A Flow 25.2.1 source study shows the
timer *could* be retired only if `@PreserveOnRefresh` were mandatory; because this project is
deliberately annotation-agnostic (see [INTERNALS.md](../INTERNALS.md) → "Relationship to
`@PreserveOnRefresh`"), the grace-period timer must stay. The unload beacon is the reason.
**Relates to:** [INTERNALS.md](../INTERNALS.md) → "Cleanup".

## Problem with the current approach

Cleanup currently leans on a 60-second grace period (`TabScope.Lifecycle.CLEANUP_DURATION_MS`).
When the last UI detaches, the scope is marked `orphanedSince` and only reaped once it has been
orphaned longer than the grace period. The grace period exists purely to bridge the gap during a
page reload, where the old UI can go away before the new UI re-attaches.

This is a timer, and timers are fragile: if network comms stall for longer than the grace period
during a legitimate reload (slow client, tab throttled in the background, laptop sleep, GC pause,
flaky connection), a *live* tab scope can be retired prematurely, and the user silently loses
their tab-scoped state. The motivation for this idea was to replace that heuristic with something
correct-by-construction. The investigation below shows why we can't, in general.

## Two independent UI-retirement paths on reload

The key realisation is that a page reload retires the old UI through **two independent
mechanisms**, and they behave differently:

1. the **navigation path** (`@PreserveOnRefresh` teleport), and
2. the **unload beacon** (`pagehide` → `sendBeacon`), added in Vaadin 24.1.

The timer question turns entirely on these two.

### Path 1 — navigation: a clean 2-UI overlap, no gap

A reload is a two-request dance:

- **Request A — bootstrap (`?v-r=init`)**: `BootstrapHandler#createAndInitUI` builds the new UI,
  in this order: `extractAndStoreBrowserDetails(request, ui)` (line 1380) → `session.addUI(ui)`
  (1385) → `fireUIInitListeners(ui)` (1387). Crucially, `extractAndStoreBrowserDetails` reads the
  browser-sent `v-wn` param and calls `setExtendedClientDetails`, so **the new UI's `window.name`
  is populated synchronously here, before it is even added to the session** — and
  `Page#retrieveExtendedClientDetails` (782–791) then short-circuits synchronously, so
  `TabScope.init`'s callback (hence `lifecycle.add(newUI)`) runs *within request A*, with no
  round-trip.
- **Request B — navigation UIDL**: with `@PreserveOnRefresh`,
  `AbstractNavigationStateRenderer#disconnectElements` (1055–1073) teleports the chain to the new
  UI and calls `prevUi.close()` as its **last** statement (1071). Without `@PreserveOnRefresh`, the
  `else` branch (328–339) makes fresh components and **never** closes the old UI — which then
  lingers, inactive, until `VaadinService#closeInactiveUIs` reaps it after `heartbeatInterval ×
  3.1` (default `300 × 3.1 ≈ 930 s ≈ 15.5 min`).

Considering **only** this path, `add(newUI)` (request A) always precedes any `remove(oldUI)`
(request B or heartbeat), so the scope's UI set never empties on reload. That was the earlier,
optimistic conclusion. It is incomplete.

### Path 2 — the unload beacon: reopens the gap, and only `@PreserveOnRefresh` closes it

The Vaadin 24.1 beacon (["drastically reduces memory usage"](https://vaadin.com/blog/vaadin-flow-24.1-drastically-reduces-memory-usage))
eagerly closes the UI on unload:

- **Client (GWT `FlowClient.js`)**: a `pagehide` listener sends
  `navigator.sendBeacon(uidlUrl, {"UNLOAD": true})` **unconditionally**. There is **no**
  client-side guard distinguishing a reload from a genuine tab close, and **no**
  `@PreserveOnRefresh` check. `beforeunload` is also registered but only sets an "unloading" flag;
  it does not beacon. **A plain F5 reload sends the UNLOAD beacon.**
- **Server (`ServerRpcHandler#handleUnloadBeaconRequest`, lines 468–478)**:
  ```java
  if (rpcRequest.isUnloadBeaconRequest()) {          // json has "UNLOAD" (ApplicationConstants.UNLOAD_BEACON, @since 24.1)
      if (isPreserveOnRefreshTarget(ui)) {
          getLogger().debug("Eager UI close ignored for @PreserveOnRefresh view");
      } else {
          ui.close();                                 // synchronous close of the OLD UI
      }
  }
  ```
  `isPreserveOnRefreshTarget` (525–529) inspects the old UI's active route/layout chain for the
  annotation. So the **client always beacons; the server decides**, and it only *ignores* the
  beacon for `@PreserveOnRefresh` targets.

**The race.** The beacon (pagehide of the *old* document) is a fire-and-forget POST dispatched by
the browser before the new document loads. It and bootstrap request A are two independent HTTP
requests, serialized server-side only by the `VaadinSession` lock — **no ordering guarantee**. So
without `@PreserveOnRefresh` the beacon can win:

```
beacon → old UI ui.close()  (old UI now isClosing/inactive)
        ── zero live UIs for this window.name ──
request A → new UI created, window.name registered
```

`isUIActive` (1832) returns false the instant `isClosing()` is true, so during that window the
scope genuinely has no live UI, even though the tab is merely reloading. **Reaping the scope the
moment its set empties would kill a live scope.**

Note the irony: *without* the beacon, the non-preserve path would be gap-free too (the old UI
lingers ~15.5 min). **The beacon is precisely what manufactures the reload gap** — and it is the
concrete reason the grace-period timer is needed.

## Conclusion (per case, Flow 25.2.1)

| Reload path | Old UI retired by | Zero-UI gap on reload? | Timer needed? |
|---|---|---|---|
| **With `@PreserveOnRefresh`** | preserve-nav, after new UI holds the chain; beacon **ignored** | No | No |
| **Without `@PreserveOnRefresh`** | unload beacon (`ui.close()`), can beat bootstrap | **Yes** | **Yes** |

Because a scope's route at reload time may or may not be `@PreserveOnRefresh` — and the project
promises to work either way — **the timer must stay as the general mechanism.** Fully retiring it
would require mandating `@PreserveOnRefresh` on all tab-scoped routes/layouts, which we explicitly
decided against (INTERNALS.md → "Relationship to `@PreserveOnRefresh`"). The two ideas are in
direct tension; keeping the annotation optional wins, so the timer stays.

## What could still be done (not recommended now)

- **Shorten, don't remove.** The gap is a request-race window (tens of ms to a few seconds on a
  bad connection), not minutes. The 60 s is conservative; it could be tuned. But shortening trades
  robustness on slow reloads for faster cleanup — low value, real risk. Leave it.
- **Hybrid (per-scope).** Skip the timer only for scopes whose current UI is a
  `@PreserveOnRefresh` target, timer otherwise. Rejected: a scope's route changes over its
  lifetime, so it can't be statically classified; the bookkeeping is more fragile than the timer
  it replaces.
- **Successor detection as an *optimization*, not a replacement.** On detach, if a non-closing UI
  with the same `window.name` already exists in `session.getUIs()`, skip straight to "not
  orphaned"; otherwise fall back to the timer. This would reap faster on the preserve path while
  staying safe on the non-preserve path — but it adds an O(#UIs) scan and complexity for marginal
  gain. Possible future refinement.

## References

All line numbers from `com.vaadin:flow-server:25.2.1` sources unless noted.

- Beacon (client, GWT `FlowClient.js` 25.2.1): `pagehide` listener → `navigator.sendBeacon(url,
  {"UNLOAD":true})`, sent unconditionally; `beforeunload` only sets an unloading flag.
- `ServerRpcHandler#handleUnloadBeaconRequest` (468–478), `#isPreserveOnRefreshTarget` (525–529),
  `RpcRequest#isUnloadBeaconRequest` (203–205); `ApplicationConstants.UNLOAD_BEACON = "UNLOAD"`
  (268, `@since 24.1`)
- `BootstrapHandler#createAndInitUI` (1349): `extractAndStoreBrowserDetails` (1380), `addUI`
  (1385), `fireUIInitListeners` (1387); `ExtendedClientDetails.updateFromValues` reads `v-wn` (556)
- `AbstractNavigationStateRenderer#disconnectElements` (1055–1073, `prevUi.close()` 1071),
  `#populateChain` non-preserve else (328–339)
- `Page#retrieveExtendedClientDetails` (782–791, synchronous short-circuit)
- `UI#close` (375–376, sets `closing=true`)
- `VaadinService#closeInactiveUIs` (1764–1772), `#removeClosedUIs` (1748), `#isUIActive` (1832),
  `#getHeartbeatTimeout` (1791); `DefaultDeploymentConfiguration.DEFAULT_HEARTBEAT_INTERVAL` = 300 (72)
- `VaadinSession#getUIs` (590)
