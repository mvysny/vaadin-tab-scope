# Research — Vaadin Flow's UI lifecycle, and the browsers' `window.name`

What the things we don't own actually do. About *them*, never us: a sentence starting "we chose"
is a `D_`. `## R_<slug> — <title>`, one claim per bullet, one provenance marker per claim —
**[docs]**, **[src]**, **[verified <date>, <version>]**, **[unverified]** (a hypothesis; a design
built on it says so). A claim is earned by its provenance, or by having cost real work to find
out. Line numbers under **[src]** are `flow-server-25.2.1-sources.jar` unless the bullet names
another version; browser claims name the build they were seen on. Cite by slug, `R_<slug>`, never
by position; `grep '^## R_' design/research.md` is the index. The first entry is the ruler: every
later one trims to its length. When you have written an entry, re-read it against the one above
and cut what the provenance markers already carry.

---

## R_flow_reload_ui_lifecycle — Flow: what retires the old UI on a page reload

- A reload is two requests. Request A is the bootstrap (`?v-r=init`):
  `BootstrapHandler#createAndInitUI` (1349) runs `extractAndStoreBrowserDetails` (1380) →
  `session.addUI` (1385) → `fireUIInitListeners` (1387). **[src]**
- Since Flow 25.2 the browser sends `window.name` as the `v-wn` param on request A, and
  `ExtendedClientDetails.updateFromValues` (556) stores it on the new UI *before* `addUI` — so the
  new UI's window name is known synchronously inside request A, with no round-trip. Older Flow
  fetched it in a separate async round-trip, so this ordering must be re-verified before relying
  on it on a v23/v24 branch. **[src]**
- Request B is the navigation UIDL. With `@PreserveOnRefresh`,
  `AbstractNavigationStateRenderer#disconnectElements` (1055–1073) teleports the component chain
  onto the new UI and calls `prevUi.close()` as its **last** step (1071). **[src]**
- Without `@PreserveOnRefresh`, navigation does not close the old UI at all — the `else` branch of
  `#populateChain` (328–339); it lingers, inactive, until the inactive-UI reaper
  (`R_flow_ui_removal`). **[src]**
- So the navigation path on its own is gap-free: the new UI is added in request A before any old
  UI is removed. The zero-UI gap comes only from the unload beacon (`R_flow_unload_beacon`), an
  independent request serialized against A by nothing but the session lock — no ordering
  guarantee. **[src]**

## R_flow_unload_beacon — Flow: the `pagehide` unload beacon, and where it can be hooked

- Client (GWT `FlowClient.js`, `flow-client-25.2.1.jar`): a `pagehide` listener **unconditionally**
  calls `navigator.sendBeacon(uidlUrl, {"UNLOAD": true})`. `pagehide` fires on a plain reload, not
  only on tab close, and there is no client-side guard for either reload or `@PreserveOnRefresh`.
  `beforeunload` is registered too but only sets an internal "unloading" flag. Symbols are
  minified; the event strings, `navigator.sendBeacon` and the payload are verbatim. **[src]**
- Server: `ServerRpcHandler#handleUnloadBeaconRequest` (468–478) calls `ui.close()` **unless** the
  UI's active route/layout chain is `@PreserveOnRefresh` (`#isPreserveOnRefreshTarget`, 525–529),
  in which case it logs "Eager UI close ignored" and does nothing.
  `ApplicationConstants.UNLOAD_BEACON = "UNLOAD"` is `@since 24.1`. **[src]**
- The beacon is an ordinary `?v-r=uidl` request — `UNLOAD` lives in the JSON body, read once by
  `UidlRequestHandler#synchronizedHandleRequest` — so there is no HTTP-level marker a
  front-of-chain `RequestHandler` could catch without consuming the body. **[src, 25.2.4]**
- The only hook is `#handleUnloadBeaconRequest` (protected). `ServerRpcHandler#createRpcHandler`
  is not overridable, but `UidlRequestHandler#createRpcHandler` is, and the chain is built by the
  protected `VaadinService#createRequestHandlers` — so an app's own `VaadinServlet` can swap the
  handler in. `RpcRequest#isUnloadBeaconRequest` is **private**; the public equivalent is
  `getRawJson().has(ApplicationConstants.UNLOAD_BEACON)`. **[src, 25.2.4]**
- Flow has an open FR for a clean hook:
  [vaadin/flow#17360](https://github.com/vaadin/flow/issues/17360). **[docs]**
- In practice the beacon is sometimes simply lost, so nothing closes the UI at all.
  **[verified, Vaadin 25.0 + LibreWolf]**

## R_flow_ui_removal — Flow: when a closed UI is actually detached

- `VaadinSession#removeUI` (681) → `UIInternals#setSession(null)` (506–535) is the detach, and it
  has exactly two callers: `VaadinService#removeClosedUIs` (1748), which runs at every `requestEnd`
  with an active session and sweeps the whole session, and `#fireSessionDestroy` (1050–1063), which
  closes each UI before removing it. So `UI.isClosing()` is always true inside a genuine detach —
  `fireSessionDestroy`'s own comment says so. **[src]**
- Heartbeats are requests, so even an idle tab's client keeps triggering `removeClosedUIs` about
  every 300 s. The one way a UI stays closing-but-attached indefinitely is being closed
  off-request, in a session that then receives no request at all. **[src]**
- `UI#close` (375–376) only sets `closing = true`; the detach follows later. **[src]**
- Inactive-UI reaping: `VaadinService#closeInactiveUIs` (1764–1772), `#isUIActive` (1832),
  `#getHeartbeatTimeout` (1791) = heartbeat × 3.1, and
  `DefaultDeploymentConfiguration.DEFAULT_HEARTBEAT_INTERVAL` = 300 (72) — so ≈ 930 s ≈ 15.5 min
  before a UI nothing closed is reaped. **[src]**
- `VaadinSession#getUIs` (590) is keyed by UI id. **There is no UI-by-window-name index**; a UI's
  window name is reachable only via
  `ui.getInternals().getExtendedClientDetails().getWindowName()`, which can be null on
  non-standard bootstrap requests that omit `v-sw`. **[src]**

## R_flow_resync — Flow: a detach event is not proof of a detach

- A client-requested **resynchronization** — a lost UIDL message, or a proxy that killed a push
  connection — is served by `ServerRpcHandler#handleRpc` (436–463) calling
  `StateTree#prepareForResync` (484), which runs
  `visitNodeTreeBottomUp(StateNode::fireDetachListeners)` and *then* re-fires attach listeners
  across the whole node tree of a UI that never leaves the session. **[src]**
- The UI's own node is in that tree, so the UI's `DetachEvent` fires while the tab is alive; the
  UI-level event arrives via `ComponentMapping#onDetach` (110) → `ComponentUtil#onComponentDetach`
  (359). Flow logs a WARN, "Resynchronizing UI by client's request…". **[src]**
- What a detach listener can observe, measured in both cases: on a resync `isAttached()` true,
  **`isClosing()` false**, session non-null, `isPreparingForResync()` **true**; on a genuine detach
  `isAttached()` true, **`isClosing()` true**, session non-null, `isPreparingForResync()` false.
  **[verified, Flow 25.2.1]**
- `isAttached()` and `getSession()` are useless as discriminators: during a genuine detach
  `UIInternals#setSession` fires the listeners *before* nulling the session, and
  `StateNode#setParent` calls `onDetach()` *before* clearing the parent. **[src]**
- `StateTree#isPreparingForResync` (505) is the precise discriminator but is `@since 24.7.5`;
  `isClosing()` has no such floor (`R_flow_ui_removal`). **[src]**

## R_flow_session_destroy — Flow: when session-destroy listeners fire

- `VaadinSession implements HttpSessionBindingListener` (`VaadinSession.java:78`) and is itself an
  attribute of the servlet `HttpSession`, so a container invalidating the session on timeout
  unbinds it and calls `valueUnbound()` (185) — no `web.xml` and no `HttpSessionListener`
  registration involved. **[src]**
- Timeout runs on the container's reaper thread, outside any Vaadin request, so
  `getCurrentRequest() == null` and `valueUnbound` takes the branch calling
  `service.fireSessionDestroy(this)` directly (218–220). With a request in flight it instead
  `close()`s, and `cleanupSession` fires destroy at request end
  (`VaadinService.java:1712-1738`). Either way `fireSessionDestroy` (1040) invokes every
  `SessionDestroyListener`. **[src]**
- The whole chain — container idle timeout → `HttpSession` invalidation → Vaadin
  `SessionDestroyListener` — is verified end to end on **both** embedded Jetty and Tomcat in
  [mvysny/vaadin-boot#39](https://github.com/mvysny/vaadin-boot/issues/39), which is the source of
  truth for it. **[verified, Jetty + Tomcat]**
- Timeout is container-paced, not Vaadin-paced: Jetty's `HouseKeeper` sweeps every 10 min by
  default, Tomcat's `backgroundProcessorDelay` every 10 s, and both only start counting after
  Vaadin's heartbeats (default 5 min) stop. **[docs]**
- A session serialized to disk, restored after a container restart and expired before any request
  touched it fires nothing: Flow's `!isInitialized()` guard (194–200) logs "Session destroy events
  will not be fired …" and returns. **[src]**

## R_flow_off_request_access — Flow: a background thread's `session.access` runs without a request

- `VaadinSession#access` → `VaadinService#accessSession` → `#ensureAccessQueuePurged` (2469): when
  the caller is a background thread and no thread holds the session lock, `accessSession` takes and
  releases the lock itself, and `#runPendingAccessTasks` runs the queued task **on that calling
  thread**. **[src, 25.2.4]**
- So a task submitted off-request executes there and then, with no client request in flight and no
  push connection needed. Only when a request already holds the lock is it deferred, to that
  request's end. **[src, 25.2.4]**

## R_flow_ecd_api — Flow: the `ExtendedClientDetails` APIs and their version floors

- `Page.retrieveExtendedClientDetails(receiver)` is `@since 2.0` — present in every Flow version.
  `Page.getExtendedClientDetails()` and `ExtendedClientDetails.refresh(...)`, which deprecate it,
  are `@since 25.0`. Eager `v-wn`-on-bootstrap is a 25.2+ *behavior*, but the method reading it,
  `ExtendedClientDetails.updateFromValues`, is `@since 25.3` (`updateFromJson` is `@since 25.2`) —
  so don't date the behavior off that method. **[src, 25.2.4]**
- The deprecated call self-adjusts across that boundary: pre-25.2 it does the async round-trip,
  25.2+ it short-circuits synchronously off the eagerly-sent `v-wn`
  (`Page#retrieveExtendedClientDetails`, 782–791). **[src]**
- Vaadin defers navigation until the ECD has been fetched, because `@PreserveOnRefresh` needs
  `windowName` too — which is what makes an ECD receiver a usable "before any route is built"
  hook. Nothing in Flow's API guarantees the receiver ordering; it is machinery, not contract.
  **[src]**
- `getExtendedClientDetails()` never returns null — `UIInternals` lazily builds a placeholder with
  dims `-1` and `windowName == null` — so migrating off the deprecated call would be a linkage
  hazard, not an NPE hazard. **[src, 25.2.4]**
- A proper per-tab init callback is what
  [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468) asks for; it is still labelled
  `investigation` / `needs design`. **[docs]**

## R_preserve_on_refresh — Flow: what `@PreserveOnRefresh` actually preserves

- The routed component instance is reused "only when reloaded in the same browser tab … and only
  if the URL stays the same"; its state is "discarded permanently" on navigation to another route
  or on a URL-parameter change — so the annotation is keyed on `window.name` **plus** location.
  **[docs — [Preserving State on Refresh](https://vaadin.com/docs/latest/flow/advanced/preserving-state-on-refresh),
  [vaadin/flow#3522](https://github.com/vaadin/flow/issues/3522)]**
- The `UI` itself is *not* preserved: the preserved component chain is reattached to a fresh UI on
  every reload, exactly as an unannotated route's would be rebuilt there. **[docs]**
- [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468) ("Add support for browser tab
  scope") lists a `@PreserveOnRefresh` parent layout among the **failed** workarounds — it
  survives F5 but breaks on back/forward and manual URL entry — so Vaadin does not position the
  annotation as a rung toward tab scope. The issue sits in the backlog, undesigned. **[docs]**
- There is no public API to move a component tree onto the fresh UI Flow builds on every F5, so an
  app that wants cross-refresh UI state has no alternative to the annotation:
  [vaadin/flow#25019](https://github.com/vaadin/flow/issues/25019). **[docs]**

## R_window_name_browsers — Browsers: when `window.name` survives

- Chrome (Chromium 150), Firefox 152.0 / LibreWolf 149.0.2-2 and Safari 26.5.2 / macOS Tahoe all
  preserve `window.name` across **every** reload and navigation tested: F5, programmatic and
  address-bar reload, bookmark click, in-app router navigation, `document.location =`,
  back/forward, a cross-origin hop and back, bfcache restore, and `@PreserveOnRefresh` F5.
  **[verified 2026-07-22]**
- **Safari 18.3.1 dropped it** on an address-bar Enter and on a bookmark click when Web Inspector
  was *closed* — and preserved it with the Inspector open, so testing only with devtools open hid
  the bug. On 26.5.2 both chapters (Inspector closed and open) pass identically; treat the drop as
  an 18.3.x-era bug, not current behavior. **[verified 2026-07-22, Safari 26.5.2 vs. 18.3.1]**
- Quit-restore, crash-restore and reopen-closed-tab mint a **new** `window.name` on every browser
  tested — a restored tab is a new tab as far as the server can tell. That is the browser minting
  a fresh name, not a bug. **[verified 2026-07-22]**
- Duplicate Tab and `window.open` also get a fresh name on every browser tested; two tabs never
  collide on one name. **[verified 2026-07-22]**
- Safari's "Preload Top Hit in the background" pre-renders the page in a hidden webview with its
  own blank `window.name`, which reads server-side as a phantom tab that immediately orphans. It
  never touches the real tab's name — noise in a log, not a verdict.
  **[verified 2026-07-22, Safari 26.5.2]**
- The protocol behind these claims, the per-browser run tables, the automation caveats, and Edge
  and iOS Safari (still unrun) are in [WINDOW-NAME-BROWSER-TESTS.md](../WINDOW-NAME-BROWSER-TESTS.md)
  ([issue #2](https://github.com/mvysny/vaadin-tab-scope/issues/2),
  [vaadin/flow#21141](https://github.com/vaadin/flow/issues/21141)).
