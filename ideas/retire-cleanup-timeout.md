# Idea: retire the cleanup timeout

**Status:** proposal / unverified
**Relates to:** [INTERNALS.md](../INTERNALS.md) → "Cleanup"

## Problem with the current approach

Cleanup currently leans on a 60-second grace period (`TabScope.Lifecycle.CLEANUP_DURATION_MS`).
When the last UI detaches, the scope is marked `orphanedSince` and only reaped once it has been
orphaned longer than the grace period. The grace period exists purely to bridge the gap during a
page reload, where the old UI can go away before the new UI re-attaches.

This is a timer, and timers are fragile: if network comms stall for longer than the grace period
during a legitimate reload (slow client, tab throttled in the background, laptop sleep, GC pause,
flaky connection), a *live* tab scope can be retired prematurely, and the user silently loses
their tab-scoped state. The timeout is a heuristic, not a correctness guarantee.

## The way out: Flow already guarantees an overlap (with `@PreserveOnRefresh`)

Investigation of the actual Flow **25.2.1** sources (`flow-server-25.2.1-sources.jar`) shows that,
on the `@PreserveOnRefresh` path, there is **always at least one non-closing `UI` pointing to a
window** during a reload — the new UI is fully live *before* the old one is ever marked closing.
If we can rely on that, the timer becomes unnecessary: a scope is orphaned only when its UI count
genuinely, permanently hits zero.

### What reload actually does

A full reload in the client-side-bootstrap flow (Flow 24+/25) is a two-request dance:

1. **Request A — bootstrap (`?v-r=init`)**: `BootstrapHandler#createAndInitUI` (line 1349)
   constructs a **new** `UI`, assigns a **new** UI id (`session.getNextUIid()`), calls
   `session.addUI(ui)` (1385) and `fireUIInitListeners(ui)` (1387) — which is exactly why
   `UIInitListener`/our `TabScope.init` runs once per reload. Note
   `JavaScriptBootstrapHandler#initializeUIWithRouter` (line 179) is an **empty no-op**, so no
   route is rendered yet. **At the end of request A the old UI is still fully live and still in
   `session.getUIs()`.**
2. **Request B — navigation UIDL**: the client invokes `UI#browserNavigate(...)` (line 2018) →
   `renderViewForRoute` → `Router` → `AbstractNavigationStateRenderer#handle`. This is where the
   old UI is finally retired — but only on the preserve path.

### The ordering guarantee (the crux)

`AbstractNavigationStateRenderer#disconnectElements` (lines 1055–1073), running on the **new** UI
during request B:

```java
final Optional<UI> maybePrevUI = component.getUI();
if (maybePrevUI.isPresent() && maybePrevUI.get().equals(ui)) return;
root.getElement().removeFromTree(false);          // move cached chain off old UI
maybePrevUI.ifPresent(prevUi -> {
    ui.getInternals().moveElementsFrom(prevUi);    // move dialogs/etc to new UI
    prevUi.close();                                // old UI marked closing — LAST
});
```

So the true order is:

1. **(b)** new UI created + `session.addUI` — request A. Old UI still open, not closing.
2. **(c)** component tree moved old → new — request B, `disconnectElements`.
3. **(a)** `prevUi.close()` sets `UI.closing = true` (`UI#close` line 375, `isClosing` line 405) —
   the **last** step, *after* the new UI exists and the tree has moved.
4. **(d)** old UI removed from session — later, asynchronously, by
   `VaadinService#removeClosedUIs` (1748) / `closeInactiveUIs` (1764), which also calls
   `AbstractNavigationStateRenderer.purgeInactiveUIPreservedChainCache` (1263).

The important asymmetry: it is **not** "old marked closing → then new created". It is the reverse.
There is a deliberate 2-UI overlap for the window, and never a zero-live-UI instant within the
preserve path. (This is the "transiently 2 UIs" case already noted in INTERNALS.md.)

### Flow's own preserve registry (for reference)

Flow keys preserved component chains by `window.name` in
`AbstractNavigationStateRenderer.PreservedComponentCache` (line 1179), a
`HashMap<String, Pair<String, ArrayList<HasElement>>>` stored as a `VaadinSession` attribute under
the key `PreservedComponentCache.class`. Value = `Pair<locationPath, chain>`. This confirms Flow
itself treats `window.name` as tab identity — the same key `TabScope` uses.

## Proposed change

If the overlap guarantee holds end-to-end for our listeners, then:

- Drop `CLEANUP_DURATION_MS` / `orphanedSince` entirely.
- Close a scope the moment its live-UI set becomes empty (`uis` empty after `removeIf(isClosing)`),
  because that now means the tab is genuinely gone, not mid-reload.

This turns a heuristic timer into an event-driven, correct-by-construction cleanup.

## Why this is NOT safe to do yet — the caveats

1. **It only holds with `@PreserveOnRefresh`.** Without the annotation, `AbstractNavigationStateRenderer`
   takes the `else` branch (lines 328–339): fresh component instances, `clearAllPreservedChains`,
   and the old UI is **never explicitly closed** by the navigation code — it just goes stale and is
   reaped by `closeInactiveUIs` on heartbeat timeout (`getHeartbeatTimeout` ≈ 3.1× heartbeat
   interval, line 1791). On that path there **is** a real zero-UI gap between the old UI being
   reaped and the new UI's `browserNavigate` re-establishing the scope — exactly what today's grace
   period covers. This library explicitly advertises that it "works correctly even without
   `@PreserveOnRefresh`". Retiring the timer would therefore either:
   - require making `@PreserveOnRefresh` mandatory for tab-scoped routes/layouts (a behavior change
     to document loudly), or
   - keep the timer as a fallback for the non-preserve path.

2. **Ordering of *our* listeners vs. Flow's close is unverified.** The guarantee above is at the
   Flow UI level. We need to confirm that `TabScope.lifecycle.add(newUI)` (which happens inside the
   new UI's `retrieveExtendedClientDetails` callback in `TabScope.init`) reliably runs **before**
   `removeUI(oldUI)` (fired from the old UI's detach listener on `prevUi.close()`). If the new UI's
   ECD round-trip is slow, `add(newUI)` could land *after* `remove(oldUI)` — reintroducing a
   transient zero-UI moment even on the preserve path. This is the single most important thing to
   test before removing the timer.

3. **No built-in UI-by-windowName index.** `VaadinSession` stores UIs in a `Map<Integer, UI>` keyed
   by UI id (`getUIs()` line 590; no window-name index). Window name is only reachable per-UI via
   `ui.getInternals().getExtendedClientDetails().getWindowName()`, and that can be `null` until the
   client round-trip completes. So any "is any live UI still pointing at this window?" check must
   iterate `session.getUIs()` and tolerate null ECD — it cannot be a cheap map lookup.

## Verification plan

- Instrument `add`/`remove` and log the exact interleaving across a reload **with**
  `@PreserveOnRefresh`, on a throttled/slow connection, to confirm `add(new)` precedes
  `remove(old)` every time (caveat 2).
- Confirm the non-preserve path behavior (caveat 1) — measure the real gap and decide whether to
  mandate `@PreserveOnRefresh` or keep the timer as a fallback.
- Consider a hybrid: event-driven close on the preserve path, timer fallback otherwise, so we keep
  the "works without `@PreserveOnRefresh`" promise while removing the fragility for the common case.

## References

All line numbers from `com.vaadin:flow-server:25.2.1` sources.

- `BootstrapHandler#createAndInitUI` (1349), `#initializeUIWithRouter` no-op override in
  `JavaScriptBootstrapHandler` (179)
- `UI#browserNavigate` (2018), `UI#close` (375), `UI#isClosing` (405)
- `AbstractNavigationStateRenderer#handle` (205), `#disconnectElements` (1055),
  `#isPreserveOnRefreshTarget` (1158), `#getPreservedChain` (1024), `#setPreservedChain` (1227),
  `#clearAllPreservedChains` (1238), `#purgeInactiveUIPreservedChainCache` (1263),
  `PreservedComponentCache` (1179)
- `UIInternals#moveElementsFrom` (1033), `UIInternalUpdater#moveToNewUI` (86)
- `VaadinService#removeClosedUIs` (1748), `#closeInactiveUIs` (1764), `#getHeartbeatTimeout` (1791)
- `VaadinSession#getUIs` (590), `#addUI` (941), `#removeUI` (681)
