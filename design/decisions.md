# Decisions

Why this project is the way it is and not otherwise — FAQ-shaped: each entry is a question and
its current answer. Rewrite the answer when it changes; delete the entry when nobody asks any
more. An entry is earned by what it would cost to reverse — half the code base — or by research
the next person would otherwise redo (cited as its `R_`). Not an entry: windows → panels
"because that's the trend", this red over that red, `get_foo` over `is_foo?`, the testing library,
the CI host, a version bump — a comment at the site of the choice, or nothing; nothing about
`design/` itself. Cite by slug, `D_<slug>`, never by position; `grep '^## D_' design/decisions.md`
is the index. The first entry is the ruler: every later one trims to its length — which is how
long this file gets, so keep it short. When you have written an entry, re-read it against the one
above, check it says nothing the doc comments already say, and cut what is left over.

---

## D_window_name_identity — Why key a tab scope on the browser's `window.name` rather than an id the server mints?

A tab scope has to be found again after F5, and the only identity the server holds across
requests is the session, which is per browser, not per tab. `window.name` is the one slot a
browser keeps for the life of a physical tab and hands back on every navigation, and Flow
already reads it into `ExtendedClientDetails.getWindowName()` for `@PreserveOnRefresh` — so it
costs us no client-side code and no extra round-trip. Why not a server-minted id in a cookie:
cookies are per browser, so every tab would collapse onto one scope. Why not the URL: it is the
user's to retype, bookmark and share. Why not `sessionStorage`, which *is* per tab: reaching it
means injecting JS of our own and round-tripping it before Vaadin bootstraps — and an ordering
problem Flow already solves for us at the ECD receiver. The cost we carry is that identity is the
browser's to lose: quit-restore, crash-restore and reopen-closed-tab always mint a fresh name,
and a browser bug can drop it mid-tab (`R_window_name_browsers`). Both look server-side like
close-then-new-tab — a fresh scope now, and the old one's destroy listener firing ~60 s later on
a tab that never closed. There is no server-side repair.

## D_no_spi_files — Why does the library ship no `META-INF/services` files?

Vaadin resolves exactly one `InstantiatorFactory`, and Spring registers its own
`SpringInstantiatorFactory` as a `@Component`; a `com.vaadin.flow.di.InstantiatorFactory` file in
our jar would collide with it and break every Spring app that merely put us on the classpath — by
construction, not by misconfiguration. The `VaadinServiceInitListener` file is only additive, but
shipping it invites the double-registration trap under Spring, where the listener is found both
by `ServiceLoader` and as a bean ([vaadin/spring#531](https://github.com/vaadin/spring/issues/531)).
So both files live in `testapp` and the README documents them as the wiring each consuming app
writes itself — the same choice `jdbi-orm-vaadin` makes. Two things follow. There is no drop-in
auto-wiring, so `TabScope.setup(consumer)` stays the app-facing contract, called from the app's
own listener; the "jar on the classpath but `setup()` never called" footgun goes with it, since
the SPI files and the call now sit together in the app. And a `tab-scope-spring` module stays
possible: `TabScope` itself is framework-agnostic, while `TabScopedRouteInstantiator` is
inherently non-Spring — a Spring integration would implement a bean scope the way `vaadin-spring`
does `@RouteScope`, not reuse that class.

## D_grace_period — Why reap an orphaned scope on a 60-second timer rather than the moment its last UI goes away?

Because a plain page reload leaves a window in which a live tab has zero UIs. Flow retires the
old UI by two independent mechanisms (`R_flow_reload_ui_lifecycle`): navigation, which is
gap-free, and the unload beacon, which fires on `pagehide` for a reload exactly as for a close
and can close the old UI before the bootstrap request has created the new one. Reaping on empty
would destroy the scope of a tab the user is merely refreshing. `CLEANUP_DURATION_MS` (60 s) is
the grace the returning UI re-attaches within, clearing `orphanedSince`. Why not shorten it: the
gap is a request race — milliseconds, seconds on a bad connection — so tuning down buys
marginally faster cleanup at real risk on a slow reload. Why not skip the timer per scope, for
`@PreserveOnRefresh` targets whose beacon Flow ignores: a scope's route changes over its
lifetime, so it cannot be classified statically, and the bookkeeping comes out more fragile than
the timer it replaces. Why not read the browser's navigation type
(`performance.getEntriesByType("navigation")[0].type === "reload"`): it arrives on the *next*
page, long after the gap opened, and the `window.name` on the bootstrap request already says the
tab came back, sooner and more reliably — while a real close produces no next page at all, which
is precisely the silence the timer keys on.

## D_scheduled_reaper — Why a daemon reaper thread rather than reaping only when the next request arrives?

Because the request-driven sweep cannot reach the case the feature exists for. `cleanupOrphans()`
runs on every UI init and every UI detach, so a scope orphaned by one tab is reaped by activity in
another — but when the *sole last* tab closes there is no other tab, no further request, and
nothing to drive a sweep; the scope would sit until session destroy, which the container paces by
its own idle timeout ([issue #3](https://github.com/mvysny/vaadin-tab-scope/issues/3)). A one-shot
task on a shared daemon `ScheduledExecutorService`, armed when a scope orphans and cancelled when
a UI re-attaches, fires regardless, and hops onto the session lock via `session.access`, which runs
the task on the reaper thread itself when no request holds the lock
(`R_flow_off_request_access`). Why keep it switchable (`scheduledReapEnabled`): an app that would
rather carry no background thread can set it false and ride the two request-driven triggers plus
session destroy, which is how the library behaved before the reaper existed. Why not lean on
Flow's own `closeInactiveUIs`: it fires at heartbeat × 3.1 ≈ 15.5 min and only for a UI the beacon
failed to close (`R_flow_ui_removal`).

## D_annotation_agnostic — Why not require `@PreserveOnRefresh` on tab-scoped routes?

Because tab scope is a superset of that annotation, not a layer on top of it: the annotation is
keyed on `window.name` **plus** location and discards its state the moment you navigate, where a
tab scope keys on `window.name` alone and carries an arbitrary per-tab store besides
(`R_preserve_on_refresh`). They overlap only in using `window.name` as tab identity, which is
simply *the* browser primitive for it. Requiring the annotation would inherit its limits — one
URL, no navigation survival — and bet on a design lineage that does not exist:
[vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468) lists a `@PreserveOnRefresh`
parent layout among the *failed* workarounds for tab scope. So semantics must hold identically
with and without it, and `TabScopedRouteInstantiator` reimplements the reattach itself. Where the
annotation does matter is the cleanup lifecycle: Flow ignores the unload beacon for a preserve
target, so a sole-tab close never orphans such a scope by itself and prompt reap needs the app to
wire the tab-close beacon hook. An app cannot dodge that by dropping the annotation —
Flow offers no other public way to move a component tree onto the fresh UI it builds on every F5
([vaadin/flow#25019](https://github.com/vaadin/flow/issues/25019)).

## D_compileonly_api_floor — Why call deprecated Flow APIs rather than their modern replacements?

Because `tab-scope` is `compileOnly(vaadin-core)` so that consumers bring their own Vaadin, and a
published jar linking against a method added in 25.0 throws `NoSuchMethodError` the moment it runs
on a consumer's 24.x. A deprecation warning at our compile time is strictly cheaper than a linkage
error at their runtime, so the API floor is whatever the oldest Vaadin we mean to support has, and
stepping above it means raising that floor in public. Two live cases.
`Page.retrieveExtendedClientDetails(receiver)` is `@since 2.0` and is kept over the 25.0
`Page.getExtendedClientDetails()` / `ExtendedClientDetails.refresh(...)` pair; it self-adjusts
across the 25.2 boundary on its own (`R_flow_ecd_api`), so there is nothing the migration would
buy. And the UI-detach guard tests `ui.isClosing()` rather than
`StateTree#isPreparingForResync()`, which is the *precise* discriminator for a resync but
`@since 24.7.5` (`R_flow_resync`). Both `retrieveExtendedClientDetails` call sites carry
`@SuppressWarnings` and a pointer here. Neither is a cleanup waiting to happen.

## D_strict_uis — Why does unhooking an unknown UI stay an error rather than a no-op?

Because that throw is what pins the rule that `Lifecycle.uis` means "UIs whose detach we still
expect", and that entries leave it only through the UI's own detach listener.
[Issue #5](https://github.com/mvysny/vaadin-tab-scope/issues/5) was `updateOrphaned()` evicting a
closed-but-not-yet-detached UI: `cleanupOrphans()` sweeps every scope in the session, so an
unrelated tab's UI init dropped a UI out from behind its own pending detach listener, which then
hit this very exception. The fix moved the `isClosing` test out of a mutation of `uis` and into
the liveness filter. The other candidate fix — making the unhook tolerant of an absent UI — would
have silenced the symptom and, with it, the regression tests that keep the eviction from coming
back. One residual window is left open deliberately: nothing in Flow refuses a request for a
closing UI, so a resync landing between `ui.close()` and the detach at `requestEnd` takes the UI
out early and its real detach then throws (`R_flow_resync`). A logged exception at `requestEnd`
costs no tab-scoped state; a defanged assertion costs the guard.
