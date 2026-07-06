# Idea: retire the cleanup timeout

**Status:** proposal — the mechanism is de-risked by a Flow 25.2.1 source study (below); remaining
work is a runtime confirmation and deciding the version-support policy.
**Relates to:** [INTERNALS.md](../INTERNALS.md) → "Cleanup" and "Relationship to `@PreserveOnRefresh`".

## Problem with the current approach

Cleanup currently leans on a 60-second grace period (`TabScope.Lifecycle.CLEANUP_DURATION_MS`).
When the last UI detaches, the scope is marked `orphanedSince` and only reaped once it has been
orphaned longer than the grace period. The grace period exists purely to bridge the gap during a
page reload, where the old UI can go away before the new UI re-attaches.

This is a timer, and timers are fragile: if network comms stall for longer than the grace period
during a legitimate reload (slow client, tab throttled in the background, laptop sleep, GC pause,
flaky connection), a *live* tab scope can be retired prematurely, and the user silently loses
their tab-scoped state. The timeout is a heuristic, not a correctness guarantee.

## The finding: on a reload the successor UI is already registered before the old one leaves

A study of the actual Flow **25.2.1** sources (`flow-server-25.2.1-sources.jar` +
`flow-client-25.2.1.jar`) establishes two facts that together make the timer unnecessary — in
**both** the `@PreserveOnRefresh` and the plain path.

### Fact 1 — a reload always produces a 2-UI overlap (never a zero-UI gap)

A full reload is a two-request dance:

- **Request A — bootstrap (`?v-r=init`)**: `BootstrapHandler#createAndInitUI` builds the new UI,
  and in this exact order: `extractAndStoreBrowserDetails(request, ui)` (line 1380) →
  `session.addUI(ui)` (1385) → `fireUIInitListeners(ui)` (1387). The old UI is still fully live
  and in `session.getUIs()` throughout.
- **Request B — navigation UIDL** (`UI#browserNavigate`): the router runs, and only now is the
  old UI retired — and only on the preserve path.

**With `@PreserveOnRefresh`:** `AbstractNavigationStateRenderer#disconnectElements` (lines
1055–1073) teleports the component chain to the new UI and then, as its **last** statement, calls
`prevUi.close()` (line 1071; `UI#close` sets `closing=true`, lines 375–376). The old UI is removed
from the session later still, by `VaadinService#removeClosedUIs` (line 1748). So there is a real
overlap where both UIs share the window name; the durable observable state is "new UI live + old
UI `isClosing()`".

**Without `@PreserveOnRefresh`:** the `else` branch of `populateChain` (lines 328–339) creates
fresh components and **never calls `prevUi.close()`**. The old UI simply lingers, inactive, in
`session.getUIs()` until `VaadinService#closeInactiveUIs` (lines 1764–1772) reaps it once
`isUIActive` (1832) goes false — i.e. after `getHeartbeatTimeout` (1791) = `heartbeatInterval ×
3.1`, default `300 × 3.1 ≈ 930 s ≈ 15.5 min`. So the overlap here is *longer*, not absent.

Either way: **during a reload the window name is served by two UIs; there is no instant with zero
live UIs.** This directly contradicts the premise the grace period was protecting against.

### Fact 2 — the new UI's `window.name` is known server-side synchronously, before the old UI leaves

The browser sends `window.name` (as `v-wn`) on the **bootstrap** request A, not on
`browserNavigate` (`BrowserNavigateEvent`, lines 1957–1970, carries no window name). The client
mints one early if absent (`Flow.ts` lines 116–118, *"Set window.name early so
@PreserveOnRefresh can use it to identify the browser tab"*) and includes it in
`collectBrowserDetails` (`v-wn`, lines 534–536; `v-sw`, 484). Server-side,
`extractAndStoreBrowserDetails` → `ExtendedClientDetails.updateFromValues` reads `v-wn` (line 556)
and calls `ui.getInternals().setExtendedClientDetails(details)` (line 560) — all *before*
`addUI`/`fireUIInitListeners`.

Consequences:

- By the time our `UIInitListener` runs for the new UI (request A),
  `ui.getInternals().getExtendedClientDetails().getWindowName()` is **already populated**.
- `Page#retrieveExtendedClientDetails` (lines 782–791) short-circuits and calls back
  **synchronously** when details were populated at bootstrap (`screenWidth != -1`) — so
  `TabScope.init`'s existing `retrieveExtendedClientDetails` callback fires *in request A*, with
  no round-trip.
- Flow's own preserve path confirms the value is in hand synchronously during navigation:
  `getPreservedChain` (lines 1024–1053) takes the synchronous branch (line 1039) and only falls
  back to async `retrieveExtendedClientDetails` (line 1035) if the window name is null.

### Why that kills the timer

Because request A (which runs our `UIInitListener` → `lifecycle.add(newUI)`) completes *before*
request B (which closes the old UI → detach → `removeUI(oldUI)` → `lifecycle.remove(oldUI)`), and
the new UI carries the *same* window name, the successor is added to the scope **before** the
predecessor is removed. The scope's live-UI set therefore **never becomes empty during a reload**.
The set only empties when the tab is genuinely gone with no successor — which is exactly when we
*want* to reap.

## Proposed change

Drop the timer and reap on a genuinely empty set:

- Remove `CLEANUP_DURATION_MS` and `orphanedSince`.
- In `Lifecycle.remove(...)`: after `uis.removeIf(UI::isClosing)`, if `uis` is empty, close the
  scope immediately. Keep `destroyAllTabScopes` on session destroy.

Optionally, as belt-and-suspenders (see caveat 3), instead of trusting our own set, re-derive the
truth at reap time: scan `VaadinSession.getUIs()` for any non-closing UI whose
`getExtendedClientDetails().getWindowName()` equals this scope's window name, and reap only if none
exists. This is robust against any add/remove interleaving surprise, at the cost of an O(#UIs) scan
on detach.

This turns a fragile heuristic timer into event-driven, correct-by-construction cleanup, and as a
bonus reaps closed tabs *immediately* instead of after 60 s.

## Remaining caveats

1. **Version-specific (Flow 25.2+).** The "window name arrives on the bootstrap request and is
   stored before navigation" behavior is a 25.2+ optimization (`ExtendedClientDetails.updateFromValues`
   is `@since 25.3`, `updateFromJson` `@since 25.2`). On older Flow (≤ 24.x) the window name was
   fetched via a separate async round-trip, so it could be null during the first navigation and the
   new UI's `add` could land *after* the old UI's `remove` — reintroducing the zero-UI window.
   **Do not back-port the timer removal to the v23/24 branches without re-verifying.** This master
   branch targets Vaadin 25.2.1, so it is in scope here.

2. **Browser dropping `window.name` (the pre-existing Safari limitation).** If the browser fails
   to preserve `window.name` (Safari 18.3.1 with dev tools closed; [flow#21141](https://github.com/vaadin/flow/issues/21141)),
   the client mints a *new* random name, so the reloaded UI does **not** share the old scope's
   window name. Successor detection then finds nothing and the old scope is reaped immediately.
   This is **acceptable and not a regression**: the tab identity is genuinely lost either way (the
   value can't be recovered), so immediate reap is no worse than today's "reap after 60 s". It is
   the same limitation the README already documents.

3. **No UI-by-windowName index in Flow.** `VaadinSession` keys UIs by id (`getUIs()`, line 590);
   there is no window-name index, and a UI's window name is only reachable via
   `ui.getInternals().getExtendedClientDetails().getWindowName()`, which can be null on
   non-standard bootstrap requests (missing `v-sw`). The optional session-scan above must tolerate
   null and iterate.

## Verification plan

The source study answers the "can Flow ever leave a zero-UI gap on reload" question (no, on 25.2+).
What remains is runtime confirmation of *our* code's behavior:

- Instrument `add`/`remove` and log the interleaving across a reload — **with** and **without**
  `@PreserveOnRefresh`, on a throttled/slow connection — to confirm `add(new)` precedes
  `remove(old)` and `uis` never empties during reload.
- Confirm closed-tab reaping still happens promptly (beacon detach → empty set → immediate close).
- Decide the version-support policy (caveat 1): timer-free on the 25.x master branch, keep the
  timer on v23/24 branches, or feature-detect.

## References

All line numbers from `com.vaadin:flow-server:25.2.1` sources unless noted.

- `BootstrapHandler#createAndInitUI` (1349): `extractAndStoreBrowserDetails` (1380), `addUI`
  (1385), `fireUIInitListeners` (1387); `ExtendedClientDetails.updateFromValues` reads `v-wn`
  (556), `setExtendedClientDetails` (560)
- `AbstractNavigationStateRenderer#disconnectElements` (1055–1073, `prevUi.close()` 1071),
  `#populateChain` non-preserve else (328–339), `#getPreservedChain` (1024–1053, sync branch 1039,
  async fallback 1035)
- `UI#browserNavigate` / `BrowserNavigateEvent` (1957–1970, no window name), `UI#close` (375–376)
- `Page#retrieveExtendedClientDetails` (782–791, synchronous short-circuit)
- `UIInternals#getExtendedClientDetails` (1514–1522)
- `VaadinService#closeInactiveUIs` (1764–1772), `#removeClosedUIs` (1748), `#isUIActive` (1832),
  `#getHeartbeatTimeout` (1791); `DefaultDeploymentConfiguration.DEFAULT_HEARTBEAT_INTERVAL` = 300 (72)
- `VaadinSession#getUIs` (590)
- Client (`flow-client-25.2.1.jar`, `Flow.ts`): window.name mint (116–118), `collectBrowserDetails`
  `v-wn` (534–536) / `v-sw` (484), init request assembly (432, 445–453)
