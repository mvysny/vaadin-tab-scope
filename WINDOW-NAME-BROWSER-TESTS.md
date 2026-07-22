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

### Automating a pass (Playwright / headless Chrome)

Most rows can be driven from Playwright (or any DevTools-protocol driver) against headless
Chromium, which is a fast way to smoke-test the Chrome chapter and the harness itself. Read all
three signals per row with one `page.evaluate`:

```js
() => ({
  windowName: window.name,
  tabId: document.body.innerText.match(/Browser tab ID: (\S+)/)?.[1],
  value:  document.body.innerText.match(/Value: (\d+)/)?.[1],
})
```

| Row | How to drive it | Notes |
|-----|-----------------|-------|
| S0  | open a 2nd tab, navigate it to `/` | expect a distinct `windowName` + `Value` |
| S1  | `location.reload()` | true F5 |
| S2a | `page.goto(location.href)` | programmatic same-URL reload — **the only half of S2 that automates** |
| S4  | click a SideNav link, then click back | |
| S5  | `document.location = location.href` | |
| S6  | `page.goBack()` / `page.goForward()` | |
| S7  | `page.goto('https://example.com')` then `page.goBack()` | needs outbound network |
| S9  | `window.open('http://localhost:8080/tab-scoped-route')` | new tab → fresh name |
| S13 | link away same-origin, then `page.goBack()` | bfcache restore |
| S14 | open `/preserve`, `location.reload()` | |

**Strictly manual — hand-run these in a real browser.** Each is human-only for a concrete reason.
Two of them (**S8**, S10) are blocked purely by a **missing Playwright MCP tool**; the rest are
browser-*chrome* or browser-*lifecycle* actions that no page-automation surface reaches — not the
MCP, and not raw Playwright/CDP either, so a new MCP tool alone wouldn't unblock them.

- **S2b** (address-bar Enter) — the URL bar is browser chrome, not page DOM. No MCP tool targets it,
  and even raw Playwright/CDP can't type into the omnibox; `browser_navigate` is a *programmatic*
  goto (that's S2a), a different code path that never trips the Safari drop.
- **S3** (bookmark click) — bookmarks live in browser chrome; no MCP tool, and no Playwright/CDP API
  creates or clicks them.
- **S8** (Duplicate Tab) — **missing MCP tool:** `browser_tabs` offers new/select/close/list but no
  *duplicate*. (Underlying CDP has no duplicate-target either — `Target.createTarget` only opens a
  *fresh* tab, i.e. S0/S9 — so this one stays manual until such a tool exists.) The user drives it
  by right-clicking the tab; the driver can only inspect the resulting tabs afterward.
- **S10** (reopen-closed-tab, Cmd/Ctrl-Shift-T) — **missing MCP tool** for the browser's
  reopen-closed-tab command; it's a chrome/history action, and `browser_press_key` sends keys to the
  *page*, not to browser chrome, so the shortcut can't be synthesized.
- **S11** (restore-after-quit) — needs a full **quit + relaunch with session restore**. The MCP owns
  the browser lifecycle and exposes no quit-and-relaunch tool; performing it would end the
  automation session, so it can't be scripted from within one.
- **S12** (restore-after-crash) — needs **force-killing the browser process** then choosing Restore.
  Same lifecycle problem as S11, and killing the MCP-managed browser kills the driver with it.

**Two caveats that make automation a smoke test, not a substitute:**

1. **Headless Chrome preserves `window.name` across every scriptable navigation** (`goto`,
   `reload`, `goBack`). So an automated pass confirms the *happy path* and that the harness/signals
   react, but it **cannot reproduce a name drop** — the whole point of S2b/S3. The Safari failure in
   particular is unreachable from headless Chrome and must be hand-run in real Safari (see the
   Safari modifier in §3).
2. **The programmatic reload (S2a) is a different code path from the human address-bar Enter
   (S2b).** That is exactly why S2 is split: `page.goto(location.href)` (S2a) always preserves the
   name in Chromium and is what automation can reach; a real address-bar Enter (S2b) is what trips
   Safari and only a human can perform it. An automated S2a pass says nothing about S2b.

### Running the whole matrix with an agent

**Every** row can be driven from a single agent session — it just takes **two browsers**, because
the Playwright-controlled one can't do the last three rows. This is how the complete Chrome chapter
under [Last Testing Outcome](#last-testing-outcome) was produced (all 17 rows, 2026-07-22). The
agent:

1. **Starts the harness and the browser.** It runs `./gradlew :testapp:run` (server) and launches
   Chrome via Playwright. That Playwright browser is **headed and visible to the human**, so the one
   window serves both the agent's automation and the human's chrome-actions.
2. **Automates the scriptable rows** itself — S0, S1, S2a, S4, S5, S6, S7, S9, S13, S14 (the table
   above) — reading signals A/B with one `page.evaluate` and signal C from the server log.
3. **Guides the human one row at a time** for the browser-chrome actions it cannot synthesize —
   **S2b** (address-bar Enter), **S3** (bookmark), **S8** (Duplicate Tab). Per row the agent notes
   the current server-log position, tells the human exactly what to do, the human reports what they
   see (tab ID + `Value`), and the agent renders the verdict against signal C — the ground truth,
   since the log prints the `window.name` the server received plus every `Created`/orphaned/
   `Destroying`. The human only ever reports signals A/B; the agent owns the verdict.
4. **Co-launches a second, plain Chromium** (outside Playwright, normal profile) for **S10, S11,
   S12** — the three rows the Playwright browser can't do. The Playwright automation profile
   disables tab-restore (Ctrl-Shift-T dead, "Reopen closed tab" greyed out — confirmed 2026-07-22,
   Chromium 150), and quitting/crashing the Playwright browser would sever the agent's control
   channel. A plain Chromium started with a shell command has neither problem: it is visible on the
   same display, has working tab-restore and quit/crash-restore, and can be quit or killed freely.
   The agent launches it, guides the human (who does the close/reopen for S10, sets "Continue where
   you left off" then quits/relaunches for S11, clicks **Restore** for S11/S12), and reads the
   verdict from signal C. For **S12** the agent triggers the crash itself by **SIGKILLing the
   browser's *main* process** — killing a child renderer only auto-reloads the page (a false crash);
   the main process is the one with no `--type=` in its command line.

The only thing the human must do is the physical chrome-actions and reporting signals A/B for the
plain-Chromium rows; the agent owns every verdict via signal C. Nothing in the matrix (bar the
human's hands) is beyond a single agent session.

### Driving Firefox (B2)

The same Playwright MCP drives Firefox — set `--browser firefox` in its args (this repo's dev box
is configured that way). One constraint that isn't obvious: Playwright drives **only its own bundled
Firefox build** (`playwright install firefox`); a stock, distro, or snap-packaged Firefox is *not* a
valid Playwright channel and can't be driven at all. So the scriptable rows and the human-guided
chrome-action rows (S2b/S3/S8) run exactly as in the Chrome chapter. The **plain-second-browser**
lifecycle rows (S10–S12) need a *separately-installed* real Firefox — Playwright's bundled build is
the driver's own channel, the same reason the Chrome chapter co-launches a plain Chromium. Expect the
automation caveats (scripted navigations preserve `window.name`; S2b/S3 stay human-only) to hold the
same as Chrome, but confirm when the chapter is actually run.

### Driving Safari (B4)

Safari runs only on macOS, so on a Linux/CI harness box the agent **drives nothing** — there is no
Playwright channel for Safari and no remote-drive. **All 17 rows are hand-run by the human** on the
Mac; the agent's role is exactly the plain-second-browser role from the Chrome chapter, applied to
the *whole* matrix — the human reports signals A (drawer tab ID) and B (`Value`), the agent owns
signal C (the server log) and renders every verdict. Disable "Preload Top Hit in the background"
first (see the Safari modifier in §3) so the log isn't cluttered with phantom preview scopes.

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

The SideNav also shows two **"(No App Layout)"** variants — `/main-view-no-app-layout` and
`/tab-scoped-route-no-app-layout` — the same views rendered without the `AppLayout` wrapper. They
are **not required** for the matrix (tab scoping is independent of the layout), but are handy to
cross-check a "preserved"/"dropped" verdict on a page that isn't wrapped in `AppLayout`. Signal A's
"Browser tab ID" is drawn in the `AppLayout` drawer, so on these variants read the verdict off
signals B (`Value`) and C (server log) instead.

---

## 3. Scenarios and steps

The scenarios under test, each with its **expected** (correct) behavior and the exact steps to run
it. Outcomes are not recorded here — they go under [Last Testing Outcome](#last-testing-outcome),
one chapter per browser. Read an outcome as **preserved** when the `Value` is unchanged and no new
`Created` appears; **dropped** when the `Value` jumps, a `Created TabScope{...}` fires at once, and
a `Destroying TabScope{...}` follows ~60 s later. "Expected = new scope" entries are not bugs — the
point there is to confirm it's a genuine new tab.

Each entry leads with its expected verdict. The harness must be running and you start on
`http://localhost:8080/`; **before each scenario** note the current `Value` (signal B) and the
`Browser tab ID` (signal A), run the action, then compare and glance at the server console
(signal C).

- **S0 — Control (proves the harness reacts).** *Expected: new scope — distinct tab ID + `Value`.*
  With `/` open, open a second browser tab and go to `http://localhost:8080/`; a fresh
  `Created TabScope{...}` should appear in the log. This confirms distinct tabs really get distinct
  scopes; every "preserved" entry below is meaningful only relative to this.

- **S1 — Plain reload.** *Expected: preserved.* Press **F5** (Windows/Linux) or **Cmd-R** (macOS).
  Same `Value`, same tab ID, **no** new `Created`.

- **S2a — Programmatic same-URL reload (automation only).** *Expected: preserved on every browser.*
  From a driver, run `page.goto(location.href)` (or navigate the tab to the same URL). This is the
  happy path a headless pass can reach; it does *not* reproduce the Safari drop (that's S2b). A
  human running the matrix by hand skips S2a and does S2b instead.

- **S2b — Address-bar reload (human only).** *Expected: preserved — except Safari, which drops it
  (see the S2b/S3 note below).* Click into the address bar (or Cmd/Ctrl-L), leave the URL
  unchanged, press **Enter**. *This is the known Safari failure path* — Safari 18.3.1 with Web
  Inspector **closed** drops `window.name` here (new `Value` + new `Created`); record that as
  `fails`. No automation can perform this — it is the real keyboard action in browser chrome.

- **S3 — Bookmark reload (human only).** *Expected: preserved — except Safari, which drops it (see
  the S2b/S3 note below).* Bookmark `http://localhost:8080/` once. Then, in the same tab, click the
  bookmark. Same failure as S2b.

- **S4 — In-app navigation.** *Expected: preserved.* Click a SideNav entry (e.g. "Tab Scoped
  View"), then click back to "Main View" (or use browser Back). Router navigation stays within one
  document. Cross-check on `/tab-scoped-route` that the `@TabScoped` instance's `Value` is stable
  too.

- **S5 — JS-driven navigation.** *Expected: preserved.* Open DevTools console and run
  `document.location = location.href`. (On Safari, note this is *with* Web Inspector open — see the
  modifier below.)

- **S6 — Back/Forward.** *Expected: preserved.* Navigate `/` → `/tab-scoped-route` via the SideNav,
  then use the browser's **Back** then **Forward** buttons. The `/` `Value` should be unchanged on
  return.

- **S7 — Cross-origin hop.** *Expected: preserved on Back.* In the same tab, type
  `https://example.com` in the address bar and go; then press **Back** to return to the app.
  Browsers clear `window.name` on cross-origin navigation for security and may or may not restore it
  on Back — this row measures that. A new scope here is a real finding.

- **S8 — Duplicate Tab (human only).** *Expected: new scope — the duplicate must get a fresh
  `window.name`.* Right-click the tab → **Duplicate**. Confirm the new tab shows a **different**
  Browser tab ID + `Value` and logs its own `Created TabScope{…}`. **If instead it shows the same ID
  with no new `Created`**, the two tabs have collided on one `TabScope` (shared `Value`, and a
  `@TabScoped` instance would be yanked between the two live UIs — the "Can't move a node from one
  state tree to another" hazard the instantiator exists to prevent) — a real isolation failure;
  record as `fails`. Observed new-scope on Chromium 150 and LibreWolf 149.

- **S9 — `target=_blank`.** *Expected: new scope.* From the console run
  `window.open('http://localhost:8080/tab-scoped-route')` (or any link opening a new tab). The new
  tab should get a **fresh** `window.name` → new scope. Confirms new tabs aren't mis-merged.

- **S10 — Reopen closed tab.** *Expected: new scope.* Close the tab, then **Cmd/Ctrl-Shift-T**. Per
  INTERNALS, reopening does **not** preserve `window.name`; expect a new scope, and (~60 s after the
  original close) a `Destroying` for the old name.

- **S11 — Restore-after-quit.** *Expected: undefined — measure.* Configure the browser to reopen
  windows/tabs on launch (Chrome: "Continue where you left off"; Safari: reopen all windows). Fully
  **quit** and relaunch the browser. Record whether the restored tab keeps its ID/`Value`.

- **S12 — Restore-after-crash.** *Expected: undefined — measure.* Force-kill the browser process (so
  the crash-restore prompt appears), relaunch, choose **Restore**. Record ID/`Value`.

- **S13 — bfcache restore.** *Expected: preserved.* Navigate away via a same-tab link to another
  same-origin page, then press **Back** so the page is restored from the back/forward cache (no full
  reload). A reset here means bfcache restore re-runs bootstrap with a lost name.

- **S14 — `@PreserveOnRefresh` F5.** *Expected: preserved, no reset.* Open `/preserve`, note
  `Value`, press **F5**. The same `Value` should show (the DOM is preserved and the scope survives).
  Isolates the preserve path from the plain-route path in S1.

> **S2b / S3 and Safari.** These two human-only rows are expected to **pass on Chrome, Firefox and
> Edge** (`window.name` preserved) and to **fail on Safari** — Safari 18.3.1 with Web Inspector
> *closed* was observed to drop `window.name` on an address-bar/bookmark reload. That drop is a
> genuine failure of the tab-identity guarantee (a spurious destroy follows ~60 s later), so **on
> Safari record S2b/S3 as `fails`** with the note "expected — this is how Safari works". It is not a
> bug in this library and there is no server-side fix (see §5); recording it as a failure — rather
> than quietly excusing it — is what keeps Safari's column in the outcome tables honest.
>
> **S2a** is the automation-only counterpart: the *programmatic* same-URL reload
> (`page.goto(location.href)`). It preserves `window.name` on every browser including Safari, so it
> is expected to **pass everywhere** and does not surface the Safari drop — that is what S2b is for.

### Safari-specific: Web Inspector modifier

For **every** Safari row (B4), run it **twice**: once with **Web Inspector closed**, once with it
**open** (Develop → Show Web Inspector). Safari 18.3.1 was observed to preserve `window.name` when
the Inspector is open but drop it (S2b/S3) when closed — so testing only with DevTools open would
*hide* the very bug. Record both in the "closed / open" cell.

### Safari-specific: disable address-bar preview preload first

Safari's **"Preload Top Hit in the background"** (Settings → Search; on by default) pre-renders the
page *before* you press Enter, in a hidden webview with its own blank `window.name`. Against this
harness that spawns a **phantom `TabScope`** on every address-bar navigation — a fresh random name
that immediately orphans and is `Destroying`-reaped ~60 s later. It clutters signal C and is easily
mistaken for a real S2b/S3 drop (the tell: its name matches *neither* the old nor the new tab).
**Uncheck it before running the matrix.** The pre-render never touches the real tab's `window.name`,
so disabling it changes no row's verdict — it only removes noise.

---

## 4. Interpreting a failure

When a "preserved"-expected row shows ⚠️:
- **Signal A** (`Browser tab ID`) changes → the browser handed the server a different `window.name`.
- **Signal B** (`Value`) jumps → tab-scoped state silently reset mid-session.
- **Signal C** logs `Created TabScope{<new>}` at once, then `Destroying TabScope{<old>}` ~60 s later
  → the **spurious destroy**: a `addDestroyListener` consumer (e.g. swing-on-vaadin mapping
  tab-close → app shutdown) would tear down as if the tab had closed. This delayed destroy is the
  concrete false-positive to document per browser/action.

---

## 5. Mitigation notes (record findings)

- **No server-side fix.** The server only sees the `window.name` the browser sends; if the browser
  drops it, the two navigations are indistinguishable from close-then-new-tab. State this explicitly
  in README as an inherent limitation for any confirmed ⚠️ row.
- **Possible client-side probe (to evaluate, not yet built):** mirror `window.name` into
  `sessionStorage` (which *is* tab-scoped and survives reload) and, on bootstrap, if `window.name`
  is empty but `sessionStorage` holds a prior name, restore it before Vaadin reads it. This could
  recover S2b/S3 losses. Note whether `sessionStorage` itself survives each failing scenario (it does
  **not** survive S9/S10/new-tab, which is correct). If evaluated, capture the result here.
- Otherwise record **"no known mitigation — inherent limitation"** for the confirmed rows, as the
  issue permits.

---

# Last Testing Outcome

> Overwrite this whole section on each run — only the latest run is kept. One chapter per browser
> (Safari gets two: Web Inspector closed and open). Record the **exact browser name + version** and
> the **test date**, then a two-column table: **Scenario ID** and **Outcome** — `passes` or `fails`;
> on `fails`, a short free-text description of what went wrong (which signal changed, delayed
> destroy, etc.). Scenario IDs and their expectations are defined in [§3](#3-scenarios-and-steps).

_Chrome (Chromium 150), Firefox/LibreWolf, and Safari 26.5.2 (Web Inspector closed): complete passes
of all 17 scenarios on 2026-07-22, below. The Chrome and Firefox chapters were two-browser-in-one-
agent-session runs; the Safari chapter was fully hand-run on a Mac with the agent owning the server
log (see "Driving Safari (B4)"). The Safari Web-Inspector-**open** chapter, Edge, and iOS Safari are
still templates awaiting a real run._

## Chrome (Chromium 150) — 2026-07-22

> **Complete pass — every one of the 17 rows ran, none failed.** Two browsers were used in a single
> agent session:
> - **Playwright-controlled headed Chromium** — the scriptable rows (S0, S1, S2a, S4, S5, S6, S7,
>   S9, S13, S14, driven automatically) plus the browser-chrome rows **S2b, S3, S8** (hand-performed:
>   address-bar Enter, bookmark, right-click → Duplicate).
> - **A separately-launched plain Chromium** (normal profile, outside Playwright) — **S10, S11, S12**,
>   which the Playwright browser can't do: its automation profile disables tab-restore (S10), and
>   quitting/crashing it would sever the agent's channel (S11/S12).
>
> S11 and S12 are "measure" rows: both showed the restored tab getting a **new** `window.name`
> (scope reset), consistent with the documented `window.name` fragility — recorded as the measured
> outcome, not a bug. Ground truth read off signal C (the server log). See §1 "Running the whole
> matrix with an agent" for the workflow. Scopes advanced a shared counter, so each new scope shows
> the next `Value`.
>
> Note the split that S2 exists for: **S2a** (programmatic `page.goto(location.href)`) and **S2b**
> (a real address-bar Enter) both **preserve** `window.name` on Chrome — but only S2b exercises the
> code path that *drops* it on Safari. Chrome passing S2b/S3 is expected; it is Safari that fails
> them (hand-run there, still a template below). Scopes seen this run: reference tab `v-0.194…`
> (drove S1/S2a/S2b/S3), the S8 duplicate `v-0.828…`, plus earlier automated-run scopes.

| Scenario | Outcome |
|----------|---------|
| S0 | passes — 2nd tab got a distinct ID `v-0.4606…` + `Value: 7` (vs. tab A `v-0.194…`/`Value: 6`); fresh `Created TabScope{v-0.4606…}` logged (signal C) |
| S1 | passes — `location.reload()`: `window.name`, tab ID and `Value: 7` all preserved; the transient `is now orphaned` log appeared but no `Destroying` (reload-race, grace window held) |
| S2a | passes — `page.goto(location.href)`: `window.name` + `Value: 7` preserved, no new `Created` |
| S2b | passes (hand-run) — address-bar Enter on `/`: tab ID `v-0.194…` + `Value: 6` preserved; the reload logged a transient `unload beacon`/`is now orphaned` but **no new `Created`** (reattached to the same scope). This is the row that *fails* on Safari; Chrome holds the name |
| S3 | passes (hand-run) — bookmark click on `/`: tab ID `v-0.194…` + `Value: 6` preserved; transient orphan, no new `Created` |
| S4 | passes — SideNav `/` → `/tab-scoped-route` → `/`: `window.name` + `Value: 7` preserved; the `@TabScoped` route's own `Value: 1` stable across the round-trip; no new `Created` |
| S5 | passes — `document.location = location.href`: `window.name` + `Value: 7` preserved |
| S6 | passes — Back to `/` kept `Value: 7`, Forward kept the `@TabScoped` `Value: 1`; `window.name` preserved throughout, no new `Created` |
| S7 | passes — hop to `https://example.com` then Back: `window.name` + `Value: 7` preserved (bfcache restore), no new `Created` |
| S8 | passes (hand-run) — right-click → Duplicate: the duplicate got a fresh `window.name` `v-0.828…` + `Value: 9` and its own `Created TabScope{…}`; the source scope `v-0.194…` stayed alive → two distinct scopes, no collision |
| S9 | passes — `window.open('/tab-scoped-route')`: new tab got a fresh `window.name` `v-0.5155…` + its own `Created TabScope{…}` → distinct new scope (no mis-merge) |
| S10 | passes (plain Chromium) — close tab + Ctrl-Shift-T: reopened tab got a **fresh** `window.name` `v-0.8716…` + `Value: 11` and its own `Created`; old scope `v-0.4487…` orphaned then reaped → new scope, as expected (reopen does not preserve `window.name`) |
| S11 | measured, expected "undefined" (plain Chromium) — "Continue where you left off" + quit/relaunch: restored tab got a **new** scope both times (`v-0.6887…`/`Value: 12`, reproduced `v-0.8596…`/`Value: 13`), each within the 60 s grace (old scope still alive) → session-restore does **not** preserve `window.name`; old scope reaps ~60 s later (spurious destroy) |
| S12 | measured, expected "undefined" (plain Chromium) — real crash (agent SIGKILLed the main browser process) + Restore: restored tab got a **new** scope `v-0.4551…`/`Value: 15` → crash-restore does **not** preserve `window.name` either (same reset as S11). Note: a first `pkill -9` hit only a child renderer → page auto-reloaded (`v-0.0008…`), not a valid crash; killing the *main* (no `--type=`) process gave the genuine restore path |
| S13 | passes — same-origin full-navigate away (`/tab-scoped-route`) then Back to `/`: `window.name` + `Value: 7` preserved, no new `Created` (bfcache restore) |
| S14 | passes — `/preserve` `location.reload()`: `Value: 7` preserved, no reset, no `Created`/`Destroying` |

> **Reaper cross-check (bonus, not a matrix row).** During this run the always-on scheduled reaper
> fired correctly: the four scopes orphaned by the prior browser-close at 09:46:25 were reaped
> ~60 s later (`Destroying TabScope{…}` on the `tab-scope-reaper` thread at 09:47:25), with no
> further request driving it — confirming the sole-last-tab prompt path.

## Firefox 152.0 (scripted) / LibreWolf 149.0.2-2 (hand-run) — 2026-07-22

> **Complete pass — all 17 rows ran; none surfaced a bug.** This chapter mixes **two Gecko browsers**
> because no single driver covers every row (the same two-browser split as the Chrome chapter, one
> engine family):
> - **Scripted rows** — **S0, S1, S2a, S4, S5, S6, S7, S9, S13, S14** — driven by the Playwright MCP
>   against its own bundled **Firefox 152.0** build (`playwright install firefox`; a stock/distro/snap
>   Firefox is not a valid Playwright channel — see §1 "Driving Firefox"). Signals A/B read via one
>   `page.evaluate`, signal C from the server log (ground truth). Firefox preserved `window.name`
>   across every scriptable navigation and issued a fresh scope for each genuinely new tab (S0, S9).
>   Reference tab: `v-0.9761…` (`Value: 4`).
> - **Hand-run rows** — **S2b, S3, S8, S10, S11, S12** — performed by the human on **LibreWolf
>   149.0.2-2** (a Firefox fork; the Playwright build can't reach the omnibox, do a duplicate-tab, or
>   be quit/crashed without severing control). The agent read every verdict off signal C. Reference
>   tab: `v-0.3074…` (`Value: 7`); scopes advanced a shared counter, so each new scope shows the next
>   `Value`.
>
> The engine-level `window.name` behavior matched between the two builds (preserve on reload/nav,
> fresh scope on new/duplicate/reopen/restore), so the split does not muddy the result — but the row
> tags below record exactly which build produced each cell.
>
> **LibreWolf-specific gotcha (recorded, not a library bug):** LibreWolf ships **"Delete cookies and
> site data when LibreWolf is closed" ON by default**. Left on, it drops the `JSESSIONID` on every
> quit — so S11/S12 would start a brand-new Vaadin session regardless of `window.name`. It was
> disabled for this run so the restore rows measured `window.name` alone. Also: on relaunch after the
> S12 crash LibreWolf showed **no crash-recovery prompt** — with "Open previous windows and tabs"
> enabled it silently restored the session, treating the crash like a normal restart.

| Scenario | Outcome |
|----------|---------|
| S0 | passes (Firefox 152.0) — 2nd tab got a distinct ID `v-0.6400…` + `Value: 5` (vs. reference tab `v-0.9761…`/`Value: 4`); fresh `Created TabScope{v-0.6400…}` logged (signal C) |
| S1 | passes (Firefox 152.0) — `location.reload()`: `window.name`, tab ID and `Value: 4` all preserved; the reload logged a transient `unload beacon`/`is now orphaned` but **no new `Created`** (reattached to the same scope, grace window held) |
| S2a | passes (Firefox 152.0) — `page.goto(location.href)`: `window.name` + `Value: 4` preserved, no new `Created` |
| S2b | passes (LibreWolf 149.0.2-2, hand-run) — address-bar Enter on `/`: tab ID `v-0.3074…` + `Value: 7` preserved; a transient `unload beacon`/`is now orphaned` for the same scope, **no new `Created`**. This is the row that *fails* on Safari; LibreWolf holds the name |
| S3 | passes (LibreWolf 149.0.2-2, hand-run) — bookmark click on `/`: tab ID `v-0.3074…` + `Value: 7` preserved; transient orphan, no new `Created` |
| S4 | passes (Firefox 152.0) — SideNav `/` → `/tab-scoped-route` → `/`: `window.name` + `Value: 4` preserved; the `@TabScoped` route's own `Value: 1` stable across the round-trip; no new `Created` |
| S5 | passes (Firefox 152.0) — `document.location = location.href`: `window.name` + `Value: 4` preserved, no new `Created` |
| S6 | passes (Firefox 152.0) — Back to `/` kept `Value: 4`, Forward kept the `@TabScoped` `Value: 1`; `window.name` preserved throughout, no new `Created` |
| S7 | passes (Firefox 152.0) — hop to `https://example.com` then Back: `window.name` + `Value: 4` preserved, no new `Created` (Firefox restored the name on Back) |
| S8 | passes (LibreWolf 149.0.2-2, hand-run) — right-click → Duplicate Tab: the duplicate got a fresh `window.name` `v-0.7395…` + `Value: 8` and its own `Created TabScope{…}`; source scope `v-0.3074…` stayed alive → two distinct scopes, no collision |
| S9 | passes (Firefox 152.0) — `window.open('/tab-scoped-route')`: new tab got a fresh `window.name` `v-0.10201…` + its own `@TabScoped Value: 2` and its own `Created TabScope{…}` → distinct new scope, no mis-merge |
| S10 | passes (LibreWolf 149.0.2-2, hand-run) — close tab + Ctrl-Shift-T: reopened tab got a **fresh** `window.name` `v-0.168…` + `Value: 9` and its own `Created` → new scope, as expected (reopen does not preserve `window.name`); old scope `v-0.7395…` orphans then reaps ~60 s after close |
| S11 | measured, expected "undefined" (LibreWolf 149.0.2-2, hand-run) — "Open previous windows and tabs" + quit/relaunch: both restored tabs got **new** scopes (`v-0.8033…`/`Value: 11`, `v-0.6179…`/`Value: 10`), each within the 60 s grace (old scopes still alive) → session-restore does **not** preserve `window.name`; old scopes reap ~60 s later (spurious destroy). (`JSESSIONID` retained — the delete-cookies-on-close default was turned off, see header) |
| S12 | measured, expected "undefined" (LibreWolf 149.0.2-2, hand-run) — real crash (agent SIGKILLed the main LibreWolf process, PID with no `--type=`) + relaunch: restored tabs got **new** scopes `v-0.8743…`/`Value: 12` and `v-0.1277…`/`Value: 13` → crash-restore does **not** preserve `window.name` either (same reset as S11). No crash-recovery prompt appeared — silent session restore (see header) |
| S13 | passes (Firefox 152.0) — same-origin full-navigate away (`/tab-scoped-route`) then Back to `/`: `window.name` + `Value: 4` preserved, no new `Created` (bfcache restore) |
| S14 | passes (Firefox 152.0) — `/preserve` `location.reload()`: `Value: 4` preserved, no reset, no `Created`/`Destroying` |

## Edge <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2a | |
| S2b | |
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

## Safari 26.5.2 / macOS Tahoe 26.5.2 (Web Inspector closed) — 2026-07-22

> **Complete pass — all 17 rows hand-run on the Mac; none surfaced a bug.** **Headline: the
> documented Safari 18.3.1 `window.name` drop on S2b/S3 (address-bar and bookmark reload) is NOT
> reproducible on Safari 26.5.2** — the name is preserved, exactly like Chrome and Firefox. The drop
> appears **fixed since 18.3.1**, and the Web-Inspector-*closed* case tested here is precisely the one
> that failed on 18.3.1, so this is the meaningful result.
>
> Safari can't be agent-driven on a Linux harness (no Playwright channel, no remote-drive), so **every
> row was hand-run**: the human reported signals A (drawer tab ID) + B (`Value`), the agent owned
> signal C (the server log) and rendered each verdict. See §1 "Driving Safari (B4)".
>
> **Env + gotcha:** Safari's **"Preload Top Hit in the background" was disabled first** — with it on,
> every address-bar navigation spawns a phantom `TabScope` (fresh name → orphan → reap ~60 s) that
> clutters signal C and is easily mistaken for a real S2b/S3 drop (see §3 Safari modifier). **S5 and
> S9** (JS actions) were run via `javascript:` bookmarklets so Web Inspector stayed genuinely closed.
> Reference tab for the preserve rows: `v-0.9717…` / `Value 7`; the shared counter advances per scope,
> so each new scope shows the next `Value`.
>
> **S11/S12 ("measure" rows):** both restored tabs got a **new** `window.name` (new scope) —
> `window.name` is not preserved across quit- or crash-restore, same as Chrome/FF. On the S12 crash
> relaunch **no crash-recovery prompt** appeared; Safari silently restored the session (like
> Firefox/LibreWolf).

| Scenario | Outcome |
|----------|---------|
| S0 | passes — clean 2nd tab got a distinct `window.name` + its own `Created`, distinct from the reference `v-0.3821…` → distinct tabs get distinct scopes. (Also surfaced the Safari address-bar **preview-preload** phantom-scope behavior — disabled for the rest of the run; see header) |
| S1 | passes — Cmd-R: `window.name`, tab ID and `Value` preserved; transient `unload beacon`/`is now orphaned` but no new `Created` (reattached to the same scope, grace window held) |
| S2a | n/a — programmatic `page.goto` is automation-only; a hand run does S2b instead |
| S2b | **passes (preserved) — the row that fails on Safari 18.3.1.** Address-bar Enter on `v-0.9717…`: stayed `0.9717 / Value 7`, transient beacon+orphan then reattach, **no new `Created`**. Safari 26.5.2 does **not** drop `window.name` here |
| S3 | **passes (preserved)** — bookmark click on `v-0.9717…`: stayed `0.9717 / 7`, transient orphan, **no new `Created`**. The 18.3.1 bookmark drop is also gone on 26.5.2 |
| S4 | passes — SideNav `/` → `/tab-scoped-route` → `/`: `0.9717 / 7` preserved with **zero** log activity (single-document router nav — no beacon, no `Created`); the `@TabScoped` route's own `Value` stable across the round-trip |
| S5 | passes — JS nav via a `javascript:document.location=location.href` bookmarklet with Inspector **closed**: page reloaded, `0.9717 / 7` preserved, transient orphan, no new `Created` |
| S6 | passes — SideNav to `/tab-scoped-route`, browser Back then Forward: `/` stayed `0.9717 / 7`, zero log activity (in-document history nav) |
| S7 | passes — hop to `https://vaadin.com` then Back: `0.9717 / 7` preserved (bfcache restore), one beacon/orphan on leaving, no new `Created` — Safari restores `window.name` on Back |
| S8 | passes — right-click tab → **Duplicate Tab**: duplicate got a fresh `window.name` `v-0.6774…` / `Value 9` + its own `Created`; source stayed `0.9717 / 7` → two distinct scopes, no collision |
| S9 | passes — `window.open(location.origin+'/tab-scoped-route')` bookmarklet: new tab got a fresh `window.name` `v-0.2710…` + its own `Created` (its `Value 2` is the `@TabScoped` route's own counter); source untouched → distinct new scope |
| S10 | passes (new scope, expected) — close `/tab-scoped-route` tab (`v-0.2710…`) + Cmd-Shift-T: reopened tab got a **fresh** `window.name` `v-0.9804…` + its own `Created`; old scope orphaned then reaped → reopen does not preserve `window.name` |
| S11 | measured, expected "undefined" — "Safari opens with: All windows from last session" + Cmd-Q/relaunch: restored tab got a **different** `window.name` `v-0.9282…` / `Value 14` (baseline `v-0.2529…`/12) → new scope; old scopes reaped ~60 s later. `window.name` not preserved across quit-restore (same as Chrome/FF) |
| S12 | measured, expected "undefined" — force-quit (Cmd-Opt-Esc → Force Quit) + relaunch: restored tab got a **different** `window.name` `v-0.3265…` / `Value 15` (pre-crash `v-0.9282…`/14) → new scope. **No crash-recovery prompt** — Safari silently restored the session (like Firefox/LibreWolf). Crash-restore does not preserve `window.name` |
| S13 | passes — full-navigate away to `/tab-scoped-route` then browser Back to `/`: `0.9717 / 7` preserved (bfcache), transient beacon/orphan pairs on leave and return, no new `Created` |
| S14 | passes — `/preserve` Cmd-R: `Value 9` preserved; the beacon hook started the grace clock **without closing** the `@PreserveOnRefresh` UI, which reattached → no `Created`/`Destroying` |

## Safari (Web Inspector open) <!-- version --> — <!-- YYYY-MM-DD -->

| Scenario | Outcome |
|----------|---------|
| S0 | |
| S1 | |
| S2a | |
| S2b | |
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
| S2a | |
| S2b | |
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
