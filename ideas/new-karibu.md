# Idea: Karibu-Testing capabilities we want (and how we'll use them)

**Status:** tracking — waiting on upstream Karibu-Testing. This file records the test capabilities
this project needs from Karibu, the proposed upstream API, and **how we'll wire each one in once it
lands**, so picking it up later is mechanical.
**Relates to:** [INTERNALS.md](../INTERNALS.md) → "Testing", "Tab identity fragility", "Cleanup";
[CLAUDE.md](../CLAUDE.md) → "Testing".

## Context

We already consume an unreleased Karibu (`2.7.1-SNAPSHOT`) via the composite build in
`settings.gradle.kts` for `KaribuConfig.unloadBeaconTiming` (EAGER/LATE/NEVER), which
`TabScopeReloadTimingTest` uses. The items below are the *next* things we need, all currently
impossible. Upstream idea files live in the Karibu repo under `ideas/`:

- `ideas/configurable-window-name.md`
- `ideas/multiple-uis-per-session.md`
- `ideas/heartbeat-emulation.md` (pre-existing; suits us as-is)

## 1. Multiple UIs (browser tabs) per session — the big one

**Why:** tab-scope's whole reason to exist is *per-tab* isolation keyed by `window.name`, and it's
the one guarantee we test **nowhere** — Karibu fakes a single hardcoded `window.name` shared by
every UI, so two UIs would collide into one `TabScope`.

**Upstream:** `MockVaadin.newBrowserTab(windowName)` + `MockVaadin.switchToTab(ui)` (see Karibu
`ideas/multiple-uis-per-session.md`), which depends on configurable `window.name`.

**How we'll use it** — a new `MultiTabTest` in `tab-scope`:
- `newBrowserTab("tab-B")` → `TabScope.getCurrent()` differs from tab A's; `getInstances()` holds 2.
- a value set in tab A's `getValues()` is absent in tab B (no leakage).
- a `@TabScoped` route resolves to a **different** instance per tab (`INSTANCES == 2`).
- closing/orphaning tab A leaves tab B's scope intact (independent lifecycle).

## 2. Changing `window.name` on reload (tab-identity fragility)

**Why:** some browsers don't preserve `window.name` across reload (Safari 18.3.1 / bookmark / typed
URL — [vaadin/flow#21141](https://github.com/vaadin/flow/issues/21141)); such a reload must arrive as
a **new** tab scope. Karibu always preserves the faked name, so this branch is invisible.

**Upstream:** `MockVaadin.reloadWithNewWindowName(windowName)` or honoring a changed
`KaribuConfig.windowName` on the next reload (see Karibu `ideas/configurable-window-name.md`).

**How we'll use it** — extend `TabScopeReloadTimingTest` (or a new test):
- capture the scope, reload with a *new* `window.name`, assert `TabScope.getCurrent()` is a
  **different** instance (and the tab-init listener ran again → `counter == 2` for the new tab),
  while the old scope is now orphaned.

## 3. Idle-UI reaping / heartbeat (completes the reaping loop)

**Why:** the orphan-cleanup path (`Lifecycle.close(true)` via `closeIfOrphaned()`) and the
`UnloadBeaconTiming.NEVER` lingering-UI case are only fully exercised if a lingering/inactive UI can
actually be reaped. Karibu has no time axis today.

**Upstream:** `MockVaadin.expireInactiveUIs()` (the timeless "reap happened" option in Karibu
`ideas/heartbeat-emulation.md` — sufficient for us; we don't need a virtual clock).

**How we'll use it:**
- after a `NEVER` reload (old UI lingers), call `expireInactiveUIs()` → the old UI detaches → its
  `TabScope` becomes orphaned; then a triggered `cleanupOrphans()` sweep reaps it.
- Note the *grace-period* reap in tab-scope is still time-gated on our own
  `System.currentTimeMillis()`; testing "reaped after 60 s" needs a small **project-side** seam
  (make `CLEANUP_DURATION_MS` / a clock injectable), not a Karibu change. See the reaping test in the
  "no-Karibu-change" plan.

## Handoff when each lands

When a capability ships in a released Karibu: bump the catalog to that release, drop the relevant
composite-build/snapshot scaffolding if no longer needed, add the test(s) described above, and remove
that item from this file (delete the file once all are done).
