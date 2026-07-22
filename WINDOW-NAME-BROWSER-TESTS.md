# `window.name` preservation — manual browser test matrix

Test plan for [issue #2](https://github.com/mvysny/vaadin-tab-scope/issues/2): confirm, across real
browsers, **when a browser preserves `window.name` and when it drops it**. Tab identity — hence the
whole survive-F5/navigation guarantee — rests on the browser keeping `window.name` stable for the
lifetime of a physical tab. When it silently drops it, the same tab reads as a *new* tab (scope
resets) and the old scope orphans and is reaped, which a `addDestroyListener` consumer sees as a
**spurious destroy**.

This is only testable across real browsers — Karibu can simulate the *consequence* of a changed
name (`TabIdentityTest`) but cannot measure which browser/action actually drops it. Run this matrix
by hand and record the results under **[Last Testing Outcome](#last-testing-outcome)** at the bottom,
then fold the confirmed findings into README (INTERNALS "Tab identity fragility").

The file has two halves: everything above the "Last Testing Outcome" heading is the stable **test
definition** (how to run, what to observe, the scenarios). That half is edited only when the harness
or scenarios change. The bottom half is the **latest run** — overwrite it wholesale each time the
matrix is re-run; only the most recent run is kept.

---

## 1. Run the harness

The baked-in `testapp` is a complete harness — no code changes needed.

```bash
# dev mode (Vite dev server, hot reload); app on http://localhost:8080
./gradlew :testapp:run
```

Leave this terminal visible — **the server console is one of the three observable signals** (see
below). Library lifecycle events are already logged at `debug` in
`testapp/src/main/resources/simplelogger.properties`, so no extra config is needed.

For a run closer to what a real user hits (no dev server, minified frontend):

```bash
./gradlew :testapp:run -Pvaadin.productionMode
```

### Transport: plain HTTP is enough — no HTTPS needed

The harness serves plain **HTTP on port 8080**; **none** of the scenarios below require HTTPS.
`window.name` preservation, bfcache, session/crash restore, and Vaadin push (WebSocket) all work
over plain HTTP, and no row depends on a browser "secure context".

- **Desktop (B1–B4):** point the browser at **`http://localhost:8080`**. `localhost` is treated as
  a secure context by every browser even over plain HTTP, so nothing is gated.
- **Mobile / another machine (B5, B6):** there is no `localhost` on the device — use the harness
  machine's LAN address, **`http://<host-ip>:8080`** (find it with `ip addr` / `ifconfig`; both
  devices on the same network). A LAN IP over HTTP is *not* a secure context, but again no test row
  needs one, so this is fine. If a corporate network blocks it, an `ngrok`/Cloudflare tunnel
  (which gives an `https://…` URL) also works and changes nothing in the results.
- **Watch for silent HTTPS upgrades.** If a browser has "HTTPS-Only mode" / "Always use secure
  connections" on, or the host has ever sent HSTS, it may auto-upgrade `http://…:8080` to `https`
  and fail to connect. Disable HTTPS-only for the test host, or use the tunnel URL. HSTS is not an
  issue for a fresh `localhost` over plain HTTP.
- The cross-origin hop (S7) navigates to `https://example.com` — that page being HTTPS is
  irrelevant to the app's transport; the app stays on HTTP.

Run the whole matrix once per browser. For a clean baseline between browsers, either use a fresh
private/incognito window or clear the site for `localhost:8080` (this drops the `JSESSIONID`, so a
fresh Vaadin session — and fresh `Value` counter — starts).

### Browsers to cover (issue #2)

| # | Browser | Platform | How to reach it |
|---|---------|----------|-----------------|
| B1 | Chrome | desktop | — |
| B2 | Firefox | desktop | — |
| B3 | Edge | desktop | — |
| B4 | Safari | macOS desktop | record **two chapters**: Web Inspector closed *and* open (known modifier) |
| B5 | Safari | iOS/iPadOS | use a LAN URL `http://<host-ip>:8080` or a tunnel; iOS has no localhost |
| B6 | Chrome | Android | optional; same LAN-URL note |

---

## 2. The three observable signals

Every scenario is read off the same three signals. **Signal C (the server log) is the ground
truth** — it prints the exact `window.name` the server received and every scope create/orphan/reap.

**A. Drawer — "Browser tab ID: `<windowName>`".** In the AppLayout drawer (open the hamburger /
DrawerToggle if collapsed). This is the raw `window.name` as the server saw it via
`ExtendedClientDetails`. *If this string changes for the same physical tab, the browser dropped
`window.name`.*

**B. View body — "Value: `N`".** A per-tab-scope counter, seeded **once** when a scope is created.
- Stays the **same** number → same scope survived (name preserved). ✅
- Jumps to a **new** number → a new scope was created (name lost, or genuinely a new tab). ⚠️

**C. Server console.** The library logs (package `com.github.mvysny.vaadin.tabscope`, debug):
- `Created TabScope{<windowName>}` — a new scope was seeded for this name.
- `TabScope{<windowName>} is now orphaned ...` — its last UI went away; grace clock (~60 s) armed.
- `Destroying TabScope{<windowName>}` — the scope was reaped. **After a name-loss this is the
  spurious destroy** the issue is about.

> Reading the pair: a name-preserving reload logs **no** new `Created` and keeps the same `Value`.
> A name-*dropping* reload logs a `Created TabScope{<newName>}` immediately, and ~60 s later a
> `Destroying TabScope{<oldName>}` (the old scope orphaning out) — that delayed destroy is the
> false-positive a tab-close consumer must not confuse with a real close.

### Routes to use

| Route | URL | Purpose |
|-------|-----|---------|
| Main View | `/` | prototype route + tab-scoped `Value` — default for most rows |
| Tab Scoped View | `/tab-scoped-route` | `@TabScoped` cached instance — cross-check caching survives too |
| @PreserveOnRefresh View | `/preserve` | `@PreserveOnRefresh` path — the beacon-hook / no-reset-on-F5 case |

---

## 3. Scenarios

The scenarios under test and their **expected** (correct) behavior. Outcomes are not recorded here —
they go under [Last Testing Outcome](#last-testing-outcome), one chapter per browser. "Expected =
new scope" rows are not bugs; the point there is to confirm it's a genuine new tab. Read a row as
**preserved** when the `Value` is unchanged and no new `Created` appears; **dropped** when the
`Value` jumps, a `Created TabScope{...}` fires at once, and a `Destroying TabScope{...}` follows
~60 s later.

| ID | Scenario | Expected |
|----|----------|----------|
| S0 | Control: open `/` in a 2nd tab | new scope (distinct name + value) |
| S1 | Plain reload (F5 / Cmd-R) | preserved |
| S2 | Reload via address-bar Enter (focus URL, press Enter) | preserved |
| S3 | Reload via bookmark click | preserved |
| S4 | In-app link click (SideNav → another view) then back | preserved |
| S5 | `document.location = location.href` from console | preserved |
| S6 | Back / Forward navigation (same origin) | preserved |
| S7 | Cross-origin hop out then Back to app | preserved |
| S8 | Duplicate Tab (browser tab-context menu) | new scope |
| S9 | Open link `target=_blank` | new scope |
| S10 | Reopen-closed-tab (Cmd/Ctrl-Shift-T) | new scope |
| S11 | Restore-after-quit (reopen windows on relaunch) | undefined — measure |
| S12 | Restore-after-crash | undefined — measure |
| S13 | bfcache restore (Back into a page left via link) | preserved |
| S14 | `@PreserveOnRefresh` route F5 (`/preserve`) | preserved, no reset |

---

## 4. Exact steps per scenario

Each step assumes the harness is running and you start on `http://localhost:8080/`. **Before each
scenario** note the current `Value` (signal B) and the `Browser tab ID` (signal A); after the
action, compare, and glance at the server console (signal C).

- **S0 — Control (proves the harness reacts).** With `/` open, open a second browser tab and go to
  `http://localhost:8080/`. Expect a **different** `Browser tab ID` and a **different** `Value`, and
  a fresh `Created TabScope{...}` in the log. This confirms distinct tabs really get distinct
  scopes; every "preserved" row below is meaningful only relative to this.

- **S1 — Plain reload.** Press **F5** (Windows/Linux) or **Cmd-R** (macOS). Expect same `Value`,
  same tab ID, **no** new `Created`.

- **S2 — Address-bar reload.** Click into the address bar (or Cmd/Ctrl-L), leave the URL unchanged,
  press **Enter**. *This is the known Safari failure path* — Safari 18.3.1 with Web Inspector
  **closed** was observed to drop `window.name` here. Watch for a new `Value` + new `Created`.

- **S3 — Bookmark reload.** Bookmark `http://localhost:8080/` once. Then, in the same tab, click the
  bookmark. Same expectation and same suspected Safari failure as S2.

- **S4 — In-app navigation.** Click a SideNav entry (e.g. "Tab Scoped View"), then click back to
  "Main View" (or use browser Back). Router navigation stays within one document; expect preserved.
  Cross-check on `/tab-scoped-route` that the `@TabScoped` instance's `Value` is stable too.

- **S5 — JS-driven navigation.** Open DevTools console and run `document.location = location.href`.
  Expect preserved. (On Safari, note this is *with* Web Inspector open — see the modifier below.)

- **S6 — Back/Forward.** Navigate `/` → `/tab-scoped-route` via the SideNav, then use the browser's
  **Back** then **Forward** buttons. Expect the `/` `Value` unchanged on return.

- **S7 — Cross-origin hop.** In the same tab, type `https://example.com` in the address bar and go;
  then press **Back** to return to the app. Browsers clear `window.name` on cross-origin navigation
  for security and may or may not restore it on Back — this row measures that. Expect preserved on
  Back; a new scope here is a real finding.

- **S8 — Duplicate Tab.** Right-click the tab → **Duplicate** (Chrome/Edge/Safari) or middle-tools
  equivalent. The duplicate typically *copies* `window.name`, so both tabs may momentarily claim the
  same ID. Record what the duplicate shows: same ID/`Value` (name copied) vs. new.

- **S9 — `target=_blank`.** From the console run
  `window.open('http://localhost:8080/tab-scoped-route')` (or any link opening a new tab). The new
  tab should get a **fresh** `window.name` → new scope. Confirms new tabs aren't mis-merged.

- **S10 — Reopen closed tab.** Close the tab, then **Cmd/Ctrl-Shift-T**. Per INTERNALS, reopening
  does **not** preserve `window.name` → expect a new scope, and (~60 s after the original close)
  a `Destroying` for the old name.

- **S11 — Restore-after-quit.** Configure the browser to reopen windows/tabs on launch (Chrome:
  "Continue where you left off"; Safari: reopen all windows). Fully **quit** and relaunch the
  browser. Record whether the restored tab keeps its ID/`Value`.

- **S12 — Restore-after-crash.** Force-kill the browser process (so the crash-restore prompt
  appears), relaunch, choose **Restore**. Record ID/`Value`.

- **S13 — bfcache restore.** Navigate away via a same-tab link to another same-origin page, then
  press **Back** so the page is restored from the back/forward cache (no full reload). Expect
  preserved; a reset here means bfcache restore re-runs bootstrap with a lost name.

- **S14 — `@PreserveOnRefresh` F5.** Open `/preserve`, note `Value`, press **F5**. Expect the same
  `Value` (the DOM is preserved and the scope survives). This isolates the preserve path from the
  plain-route path in S1.

### Safari-specific: Web Inspector modifier

For **every** Safari row (B4), run it **twice**: once with **Web Inspector closed**, once with it
**open** (Develop → Show Web Inspector). Safari 18.3.1 was observed to preserve `window.name` when
the Inspector is open but drop it (S2/S3) when closed — so testing only with DevTools open would
*hide* the very bug. Record both in the "closed / open" cell.

---

## 5. Interpreting a failure

When a "preserved"-expected row shows ⚠️:
- **Signal A** (`Browser tab ID`) changes → the browser handed the server a different `window.name`.
- **Signal B** (`Value`) jumps → tab-scoped state silently reset mid-session.
- **Signal C** logs `Created TabScope{<new>}` at once, then `Destroying TabScope{<old>}` ~60 s later
  → the **spurious destroy**: a `addDestroyListener` consumer (e.g. swing-on-vaadin mapping
  tab-close → app shutdown) would tear down as if the tab had closed. This delayed destroy is the
  concrete false-positive to document per browser/action.

---

## 6. Mitigation notes (record findings)

- **No server-side fix.** The server only sees the `window.name` the browser sends; if the browser
  drops it, the two navigations are indistinguishable from close-then-new-tab. State this explicitly
  in README as an inherent limitation for any confirmed ⚠️ row.
- **Possible client-side probe (to evaluate, not yet built):** mirror `window.name` into
  `sessionStorage` (which *is* tab-scoped and survives reload) and, on bootstrap, if `window.name`
  is empty but `sessionStorage` holds a prior name, restore it before Vaadin reads it. This could
  recover S2/S3 losses. Note whether `sessionStorage` itself survives each failing scenario (it does
  **not** survive S9/S10/new-tab, which is correct). If evaluated, capture the result here.
- Otherwise record **"no known mitigation — inherent limitation"** for the confirmed rows, as the
  issue permits.

---

# Last Testing Outcome

> Overwrite this whole section on each run — only the latest run is kept. One chapter per browser
> (Safari gets two: Web Inspector closed and open). Record the **exact browser name + version** and
> the **test date**, then a two-column table: **Scenario ID** and **Outcome** — `passes` or `fails`;
> on `fails`, a short free-text description of what went wrong (which signal changed, delayed
> destroy, etc.). Scenario IDs and their expectations are defined in [§3](#3-scenarios).

_Not yet run. The chapters below are a template — replace them with real results._

## Chrome <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2 | |
| S3 | |
| S4 | |
| S5 | |
| S6 | |
| S7 | |
| S8 | |
| S9 | |
| S10 | |
| S11 | |
| S12 | |
| S13 | |
| S14 | |

## Firefox <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2 | |
| S3 | |
| S4 | |
| S5 | |
| S6 | |
| S7 | |
| S8 | |
| S9 | |
| S10 | |
| S11 | |
| S12 | |
| S13 | |
| S14 | |

## Edge <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2 | |
| S3 | |
| S4 | |
| S5 | |
| S6 | |
| S7 | |
| S8 | |
| S9 | |
| S10 | |
| S11 | |
| S12 | |
| S13 | |
| S14 | |

## Safari (Web Inspector closed) <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2 | |
| S3 | |
| S4 | |
| S5 | |
| S6 | |
| S7 | |
| S8 | |
| S9 | |
| S10 | |
| S11 | |
| S12 | |
| S13 | |
| S14 | |

## Safari (Web Inspector open) <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2 | |
| S3 | |
| S4 | |
| S5 | |
| S6 | |
| S7 | |
| S8 | |
| S9 | |
| S10 | |
| S11 | |
| S12 | |
| S13 | |
| S14 | |

## iOS Safari <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2 | |
| S3 | |
| S4 | |
| S5 | |
| S6 | |
| S7 | |
| S8 | |
| S9 | |
| S10 | |
| S11 | |
| S12 | |
| S13 | |
| S14 | |
