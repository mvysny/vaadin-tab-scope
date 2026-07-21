# Prompt last-tab-close reap

Tracking: [issue #3](https://github.com/mvysny/vaadin-tab-scope/issues/3). This file collects the
research so the ticket discussion has a durable home. **No decision has been made yet** — scope and
opt-in shape are still open (see "Open decisions").

## The goal / use case

Make a tab scope's **destroy listener fire promptly when the last browser tab of the app is closed**,
instead of only at session-destroy latency (container idle timeout — many minutes, see below). The
concrete driver is [swing-on-vaadin](https://github.com/mvysny/swing-on-vaadin): a migrated Swing
app delivers its `WINDOW_CLOSING` cleanup from the scope-destroy hook, so "user closed the app" must
be observable soon after the close, not ~30 min later. It's a general tab-scope limitation, not
specific to that project.

Note the two independent axes — don't conflate them:

- **Reliability** — *does* the destroy listener fire at all? **Yes, reliably**, on every graceful
  teardown (explicit close, tab close, idle timeout) on both embedded Jetty and Tomcat. Verified
  end-to-end in [mvysny/vaadin-boot#39](https://github.com/mvysny/vaadin-boot/issues/39). The old
  "best-effort" framing was wrong and has been eradicated from the docs (see "Doc cleanup done").
- **Promptness** — does it fire *soon* after the last tab closes? **No** today. That is the gap this
  idea is about.

Why timeout is not prompt: the idle clock only starts once the tab is closed and Vaadin heartbeats
stop (default every 5 min), and a container reaps the expired session on its background sweep
(Jetty `HouseKeeper` default 10 min; Tomcat `backgroundProcessorDelay` default 10 s) **or** on the
next request bearing the expired cookie — which for a sole closed tab never comes. Net latency:
session-timeout + up-to-a-sweep, i.e. many minutes to ~30 min+.

## Root cause — two gates (verified against `TabScope.java` @ current HEAD + Flow 25.2.1 sources)

**Gate 1 — no orphan without the beacon (only bites `@PreserveOnRefresh` routes).**
A scope becomes orphaned only when its last UI is removed (`Lifecycle.updateOrphaned()` on UI
detach, `TabScope.java:115`). On a real tab-close of a `@PreserveOnRefresh` route, Flow **ignores
the unload beacon** (`ServerRpcHandler#handleUnloadBeaconRequest` → `#isPreserveOnRefreshTarget` →
logs "Eager UI close ignored for @PreserveOnRefresh view"), so the UI is *not* closed, never leaves
`Lifecycle.uis`, and **the scope never orphans at all**. The dead UI also never times out on
heartbeats, because `VaadinService#closeInactiveUIs` runs only inside `requestEnd` and the sole
closed tab sends no further request. So for a `@PreserveOnRefresh` sole tab there is *no* server-side
event that orphans the scope until the session itself is invalidated.

**Gate 2 — no timer, so no reap without another request (bites every route).**
`cleanupOrphans()` runs only from a new tab's ECD-fetch `init` callback (`TabScope.java:281`) or a UI
`detach` (`removeUI`, `TabScope.java:336`) — there is no periodic sweep and no timer thread
(`INTERNALS.md` "When cleanup actually runs"). So even once a scope *is* orphaned (a non-preserve
route whose beacon did close the UI), it is reaped only when *another* tab inits/detaches. A sole
last tab has no such event, so the orphan lingers until the session-destroy backstop.

**Mnemonic: beacon = "start the clock," timer = "ring the bell."** `@PreserveOnRefresh` needs both;
plain tab-scope needs only the timer.

## Candidate solutions

**A. Capture the unload beacon → fixes Gate 1.**
tab-scope observes the beacon (custom `ServerRpcHandler`) and, for a preserve target, **starts the
orphan grace clock *without* closing the UI** — leaving Flow free to re-adopt the preserved tree on a
real F5. This "don't close the UI, just start the clock" behaviour is the distinguishing requirement
of the preserve case and the novel bit vs. Flow's plain non-preserve eager-close. It is the *only*
mechanism that makes a `@PreserveOnRefresh` sole-tab scope orphan promptly — a timer alone cannot,
because without the beacon the UI never leaves `scope.uis` and the scope never orphans.

**B. Timer / scheduled reap → fixes Gate 2.**
The "ring the bell after grace" half: reap an orphan with no other tab's activity. Fully closes the
gap for non-preserve routes on its own (their beacon already closes the UI and orphans the scope);
for preserve routes it pairs with A. Design preference from the ticket: a **one-shot scheduled check
per orphan** (`ScheduledExecutorService`, armed when the scope orphans) over a periodic cross-session
sweep — the latter must enumerate and lock every session; a one-shot task is surgical.

They compose: **preserve routes need A + B; plain tab-scope needs only B.**

## Why `@PreserveOnRefresh` is a VITAL case, not opt-out-able

My first pass reasoned "tab-scope reimplements/exceeds `@PreserveOnRefresh`, so a tab-scope user can
just drop the annotation and get eager beacon-close for free, making A unnecessary." **That is
wrong.** (Ticket comment by @mvysny, 2026-07-21.)

An app **cannot** opt out of `@PreserveOnRefresh` without a supported way to teleport its component
tree to the fresh UI Flow builds on every F5 — and **Flow offers no such public API today**
([vaadin/flow#25019](https://github.com/vaadin/flow/issues/25019)). `@PreserveOnRefresh` is currently
the *only* supported cross-UI transfer mechanism. Telling such apps "just drop it" would make them
lose their entire UI state on every refresh. So A (beacon-capture that starts the clock without
closing the UI) is **load-bearing**, not an optional optimization.

Related upstream:
- [vaadin/flow#25019](https://github.com/vaadin/flow/issues/25019) — public cross-UI move API (open).
- [vaadin/flow#23410](https://github.com/vaadin/flow/issues/23410) — UI detach not immediate on
  tab-close under `@PreserveOnRefresh`.
- [vaadin/flow#17360](https://github.com/vaadin/flow/issues/17360) — customize unload-beacon handling
  (the hook a beacon-capture impl would build on).
- [vaadin/flow#21141](https://github.com/vaadin/flow/issues/21141) — `window.name` not preserved
  (Safari / address-bar / bookmark), the correlation caveat below.
- [vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468) — the umbrella "browser tab scope"
  FR this whole project works around.

## Design notes / subtleties

- **Don't close the UI on the beacon for preserve targets** — just start the grace. That keeps Flow's
  F5 tree re-adoption intact.
- **Grace length vs. false-positive on a slow F5.** The beacon also fires on refresh, so the reap
  must wait long enough to see whether the same `window.name` re-appears before destroying the scope.
  Too short → a real refresh reads as a close (session killed mid-F5); too long → not prompt. The
  existing `CLEANUP_DURATION_MS` (60 s) is safe; a prompt path might want ~10–15 s — worth an
  empirical worst-case `window.name` round-trip check before shortening. Reusing the existing
  constant (one source of truth) is the conservative default.
- **Keep the backstops — they are the reliable floor.** The unload beacon is best-effort (~85–91 % in
  field data; missed on bfcache, mobile app-switch-then-kill, crash, background-tab discard), so the
  session-destroy path (and heartbeat timeout for non-preserve) must remain the floor. Beacon capture
  is an *accelerator for promptness* in the common case, not a new correctness guarantee — and it does
  not need to be, because session-destroy already fires the listener reliably (just not promptly).
- **Off-request execution.** A timer fires outside any request, so the reap must `session.access(...)`
  to take the lock before touching the scope / firing listeners. This is not a new execution model
  for listener authors: the session-timeout path already fires destroy on the container's reaper
  thread with `UI.getCurrent() == null`. Document it.
- **Serialization / clustering.** A `ScheduledFuture` is not serializable and the `TabScope` is (it
  lives on the session). Don't store the future in the scope — keep scheduling state outside the
  serializable graph, or mark it `transient`. On passivate/activate the queued task is simply lost;
  the request-driven sweep + session-destroy backstop still cover it, so the timer stays a pure
  accelerator.
- **Executor lifecycle.** One shared daemon single-thread scheduler, lazily created, shut down on
  `VaadinService` destroy (`addServiceDestroyListener`).
- **Wiring / opt-in.** Beacon capture (A) needs a custom `ServerRpcHandler` → custom
  `UidlRequestHandler` → custom `VaadinService`. Shipping a **service mixin + documented wiring**
  (rather than self-registering) lets apps opt in cleanly and plays nicely with Spring, where
  SPI-registered service-init listeners conflict with bean discovery. The timer (B) alone needs *no*
  custom `VaadinService` — it can be armed from inside `updateOrphaned()`/`closeIfOrphaned` via a
  static executor, so it could even be always-on.
- **`window.name` caveat.** The re-adopt correlation inherits the known limitation that some browsers
  (Safari; address-bar / bookmark navigations) don't preserve `window.name` (see #21141 and
  `INTERNALS.md` "Tab identity fragility").

## TODO when this graduates — documentation to update

- **Upgrade `@PreserveOnRefresh` from "not required / optional optimization" to "a first-class,
  vital case."** `INTERNALS.md` → "Relationship to `@PreserveOnRefresh` (and why it is not required)"
  currently argues the annotation is at most an optional implementation optimization and that
  tab-scope "reimplements and exceeds" it. That framing is now contradicted by the finding above:
  because Flow has no public cross-UI tree-transfer API (vaadin/flow#25019), a preserve app **cannot**
  drop the annotation, and beacon-capture must treat the preserve path as load-bearing. When A/B land,
  revise that section so the docs stop implying `@PreserveOnRefresh` is second-class.
- **Revisit `CLAUDE.md`.** Its "Cleanup is deliberately partial" note (line ~45) currently says tab
  scopes are removed only at session-destroy or via the >60 s orphan sweep during another request —
  which A/B will change (prompt sole-last-tab reap). Update that note, and give `@PreserveOnRefresh`
  the same promotion from "optional optimization" to first-class there too.

## Doc cleanup done (2026-07-21, alongside filing this idea)

The "session-destroy is best-effort" framing was **removed** from `README.md`, `INTERNALS.md`
(heading "Destroy listeners are best-effort" → "When destroy listeners fire"), and
`TabScope.addDestroyListener`'s javadoc. Rationale: vaadin-boot#39 verified destroy fires reliably on
graceful teardown on both Jetty and Tomcat; the only gaps are `kill -9` / power loss / crash, which
skip *every* shutdown hook in every framework and are not a property of this listener. The docs now
state reliability plainly and flag only the *promptness* limitation (which links here).

## Open decisions (deferred)

- **Scope:** ship B (timer) alone, ship A + B, document-only, or reject? Given the vital-case finding,
  A is now in play where it previously looked skippable.
- **Opt-in shape:** `setup(...)` flag/overload vs. always-on (for B); service-mixin wiring (for A).
- **Grace length for the prompt path:** reuse 60 s vs. a shorter prompt-specific value.
