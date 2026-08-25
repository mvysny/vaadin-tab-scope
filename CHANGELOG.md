# Changelog

Notable changes per release. Older releases (0.1, 0.2) predate this file; see the
[commit history](https://github.com/mvysny/vaadin-tab-scope/commits/master) for those.

## 0.3

### Fixed

- Don't evict a closing-but-attached UI from `Lifecycle.uis` ([#5](https://github.com/mvysny/vaadin-tab-scope/issues/5)).
  A closing UI now stops counting as live but stays until its own detach arrives, instead of making
  that detach throw *"Invalid state: uis doesn't contain given ui"*.
- Act on a UI detach only when `ui.isClosing()` ([#6](https://github.com/mvysny/vaadin-tab-scope/issues/6)).
  A client-requested resync re-fires detach listeners on a live UI; taking that at face value orphaned
  the tab's scope and reaped it ~60 s later, losing the values.

### Changed

- `TabScope.CLEANUP_DURATION_MS` is now public and configurable (was package-private, test-only),
  documented in the README's new Configuration table next to `TabScope.scheduledReapEnabled`.
- `ReapScheduler` extracted to its own file as an `AutoCloseable`, with reap scheduling consolidated into it.
- Thread-safety contracts documented on the public API.
- Javadoc jar shrunk from 4.2 MB to ~150 KB by stripping the fonts the JDK 22+ doclet bundles.
- Vaadin bumped to 25.2.6.

### Docs

- Cross-browser `window.name` sweep completed ([#2](https://github.com/mvysny/vaadin-tab-scope/issues/2)),
  results in [WINDOW-NAME-BROWSER-TESTS.md](WINDOW-NAME-BROWSER-TESTS.md). The address-bar/bookmark
  `window.name` drop was a Safari 18.3.1 bug, fixed as of Safari 26.5.2; current Chrome, Firefox and
  Safari all preserve it.
