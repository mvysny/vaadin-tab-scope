# Idea: Karibu-Testing capabilities we need (and how we use them)

**Status:** all three capabilities have **landed** in Karibu-Testing `master`
(`2.7.1-SNAPSHOT`) and are wired into our tests via the composite build in `settings.gradle.kts`.
What remains is purely the release migration: pin Karibu 2.7.1 and remove the composite-build /
snapshot scaffolding once it is published to Maven Central. Delete this file when that's done.
**Relates to:** [INTERNALS.md](../INTERNALS.md) → "Multi-tab isolation", "Tab identity fragility",
"Cleanup"; [CLAUDE.md](../CLAUDE.md) → "Testing".

## What landed, and where we use it

| Need | Karibu API (`2.7.1-SNAPSHOT`) | Our test |
|---|---|---|
| Multiple UIs (tabs) per session | `MockBrowser.newTab(windowName?, path?)`, `switchTo(name)`, `closeTab(name, beaconLost?)`, `tabs`, `currentWindowName`; `KaribuConfig.windowName` | `MultiTabTest` |
| `window.name` change on reload | `MockBrowser.reload(newWindowName?)` (default preserves) | `TabIdentityTest` |
| Idle-UI reaping (lost beacon) | `MockVaadin.reapInactiveUIs()`; `MockBrowser.closeTab(name, beaconLost = true)`; flagged by `UnloadBeaconTiming.NEVER` reloads | `TabScopeReloadTimingTest.neverReloadLingeringUiIsCleanedUpByReap`, `TabScopeLifecycleTest.lostBeaconBackgroundTabIsReapedAndItsScopeDestroyed` |

`MockBrowser` is the client-side test double (open/switch/close/reload tabs) driving the
server-side `MockVaadin` — a cleaner split than we originally proposed. All methods are `@JvmStatic`,
so from Java: `MockBrowser.newTab()`, `MockBrowser.switchTo(name)`, `MockBrowser.getCurrentWindowName()`,
`MockBrowser.getTabs()`, `MockVaadin.reapInactiveUIs()`.

## Migration checklist (when Karibu 2.7.1 is released)

1. Bump `kaributesting` in `gradle/libs.versions.toml` from `2.7.1-SNAPSHOT` to the `2.7.1` release.
2. Remove the composite build in `settings.gradle.kts` (the `includeBuild(karibuTestingDir)` block).
3. Remove the snapshot repo in `build.gradle.kts`.
4. Run `./gradlew build` to confirm everything resolves from Maven Central.
5. Delete this file (the two upstream idea files were already deleted in the Karibu repo on
   implementation; `reapInactiveUIs` deliberately models the reap *outcome*, not heartbeat timing).

## Note: one project-side seam remains, unrelated to Karibu

The 60 s grace-period reap in `TabScope` is time-gated on our own `System.currentTimeMillis()`, which
Karibu cannot drive. `TabScope.CLEANUP_DURATION_MS` stays package-private + non-final so tests can
shrink it; that is a permanent test seam, not something a Karibu release removes. See INTERNALS.md,
"Routing, layout caching, and lifecycle coverage".
