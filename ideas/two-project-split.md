# Idea: split into a published library + a demo app (two-project Gradle build)

**Status:** implemented — the two-module split, package rename, SPI relocation, slf4j logging, and
library tests are in place and `./gradlew build` is green. What remains is Maven Central release
plumbing (signing key + Central credentials); the build config is ready but untested against a real
publish. Modeled on the proven
[`jdbi-orm-vaadin`](https://gitlab.com/mvysny/jdbi-orm-vaadin) two-project layout.
**Relates to:** [CLAUDE.md](../CLAUDE.md) → "Project purpose"; [INTERNALS.md](../INTERNALS.md) →
"The three pieces".

## Why

Today this is a single-module *example* app. But Vaadin upstream has no plans to support tab scope
([vaadin/flow#13468](https://github.com/vaadin/flow/issues/13468)), so the `TabScope` /
`TabScopedRouteInstantiator` / `TabScoped` trio should graduate into a reusable library published
to Maven Central. You can't publish a demo app as a library, so the project splits in two:

- a small **library** artifact (the product), and
- a **demo app** that depends on it and shows the wiring.

## Proposed layout

Mirrors `jdbi-orm-vaadin` (library module named after the artifact + a `testapp` demo):

```
vaadin-tab-scope/                (repo renamed from -example; URL github.com/mvysny/vaadin-tab-scope)
├── settings.gradle.kts          include("tab-scope", "testapp")
├── build.gradle.kts             root: allprojects{group,version,repos} + subprojects{java21,
│                                 reusable ext["publishing"], test config} + nexusPublishing{sonatype}
├── gradle/libs.versions.toml    shared catalog
├── tab-scope/                   ← the library → Maven Central
│   └── build.gradle.kts         compileOnly(vaadin-core) + testImplementation(vaadin-core,
│                                 kaributesting, junit); ends with publishing("tab-scope")
└── testapp/                     ← the demo (mirrors jdbi-orm-vaadin's testapp)
    └── build.gradle.kts         plugins{vaadin, application}; implementation(project(":tab-scope"));
                                  vaadin-boot, slf4j-simple; mainClass = "testapp.Main"
```

### Divergence from `jdbi-orm-vaadin`: pure Java, no Kotlin

`jdbi-orm-vaadin` pulls in the Kotlin plugin only because its *tests* are Kotlin. Our tests already
use `karibu-testing-v24`'s Java `LocatorJ` API, so we stay pure Java and the root build is *simpler*
than theirs: just `java`, `maven-publish`, `signing`, and the nexus publish plugin — no
`kotlin("jvm")`, no `KotlinCompile` block, no `kotlin.stdlib.default.dependency=false`.

## Coordinates & naming

Following the `jdbi-orm-vaadin` convention that the Maven **group id squashes** while the Java
**package stays dotted/readable**:

| | `jdbi-orm-vaadin` (precedent) | this project |
|---|---|---|
| group id (squashed) | `com.gitlab.mvysny.jdbiormvaadin` | `com.github.mvysny.vaadintabscope` |
| artifact id | `jdbi-orm-vaadin` | `tab-scope` |
| full coordinates | `…jdbiormvaadin:jdbi-orm-vaadin` | `com.github.mvysny.vaadintabscope:tab-scope` |
| Java package | `com.gitlab.mvysny.jdbiorm.vaadin` | `com.github.mvysny.vaadin.tabscope` |
| demo package | `testapp` | `testapp` |

The library classes move `com.vaadin.starter.skeleton` → **`com.github.mvysny.vaadin.tabscope`**
(the starter-skeleton package can't ship to Central, and it isn't even under our namespace). The
demo moves to `testapp`.

## What goes where

**Library (`tab-scope/`) — pure Java, no frontend, does NOT apply the `com.vaadin` Gradle plugin:**

- `TabScope` — the per-tab value store + lifecycle/orphan-cleanup engine
- `TabScoped` — the marker annotation
- `TabScopedRouteInstantiator` (+ `$Factory`) — `@TabScoped` route/layout caching
- **no `META-INF/services` files** (see below)

**Demo (`testapp/`) — applies the Vaadin plugin, owns the frontend build:**

- `Main`, `AppShell`, `MainLayout`, the views, `ApplicationServiceInitListener`
- **both** `META-INF/services` files (the `InstantiatorFactory` and `VaadinServiceInitListener`
  registrations)
- all current tests + `AbstractAppTest`

The cut is clean: the library is pure Java plus a couple of classes; only the demo runs the Vaadin
frontend build. The library JAR is small and fast to publish.

## The key decision: the library ships zero `META-INF/services` files

Spring support is **deferred** — but we deliberately **do not prevent** Spring usage. The lever
that keeps that door open is shipping no SPI service files from the library. Verified Flow/Spring
mechanics:

- **`com.vaadin.flow.di.InstantiatorFactory`** — Vaadin resolves exactly **one** instantiator.
  Spring ships its own `SpringInstantiatorFactory`; a Spring app registers instantiator factories by
  `@Component`, not SPI ([custom instantiators](https://vaadin.com/docs/latest/flow/advanced/custom-instantiators)).
  If *our* library shipped this SPI file it would collide with Spring's and **break Spring apps by
  construction** — this is a hard blocker, not a style preference.
- **`com.vaadin.flow.server.VaadinServiceInitListener`** — Spring auto-registers any `@Component`
  bean implementing it (or an `@EventListener(ServiceInitEvent)` method); non-Spring uses SPI
  ([service init listener](https://vaadin.com/docs/latest/flow/advanced/service-init-listener)).
  Even though this file is *additive* (unlike the instantiator), shipping it invites a
  double-registration trap under Spring, where a listener is discovered both via ServiceLoader and
  as a bean ([vaadin/spring#531](https://github.com/vaadin/spring/issues/531)).

**Therefore the library ships no service files.** Both registrations live in `testapp`, and the
README documents them as the wiring a plain Servlet/Vaadin-Boot app must add. This is also
consistent with house style: `jdbi-orm-vaadin`'s library ships no SPI either.

## Consequence for the `setup()` contract

Because the library can't ship a `VaadinServiceInitListener`, there is no drop-in auto-wiring path
for the core lib — the app **must** wire the plumbing. So `TabScope.setup(consumer)` stays as the
app-facing contract, called from the app's own `VaadinServiceInitListener`. The footgun of "jar on
the classpath but `setup()` forgotten" largely evaporates: the whole wiring — both SPI files plus
the `setup()` call — now lives together and visibly in the app, documented as one unit. Splitting
mandatory plumbing from optional per-tab seeding becomes a nice-to-have, **not** forced by the
module boundary. Keep `setup()` as-is for the first pass.

## Future Spring module (not built now)

"Support Spring later" splits cleanly along the two product pieces:

- **`TabScope` (the value store)** is framework-agnostic — reusable under Spring as-is.
- **`TabScopedRouteInstantiator` (the `@TabScoped` caching)** is *inherently* non-Spring — Spring
  needs its own `SpringInstantiator`, so ours cannot be stacked. A future Spring integration would
  be a **separate `tab-scope-spring` module** implementing a custom bean scope (the way
  `vaadin-spring` does `@RouteScope` / `VaadinRouteScope`), not a reuse of this class.

So: don't bake SPI assumptions into the core, and leave room beside `tab-scope/` for a future
`tab-scope-spring/`.

## Publishing bits (steal from `jdbi-orm-vaadin` root build)

The root `build.gradle.kts` defines a reusable `ext["publishing"] = { artifactId -> … }` function
that configures `withJavadocJar()` / `withSourcesJar()`, the POM (name, description, url, MIT
license, developer, scm), and signing. The library module invokes `publishing("tab-scope")`;
`testapp` simply never invokes it, so nothing tries to publish the demo. `nexusPublishing { sonatype
{ … } }` targets the Central portal OSSRH staging API. Update url/description/scm to
`github.com/mvysny/vaadin-tab-scope`.

## Decisions

- **Starting version:** `0.1-SNAPSHOT` (in `allprojects { version = … }`).
- **Logging:** `TabScope` logs via `slf4j-api` as appropriate (lifecycle/orphan-cleanup events),
  replacing the demo's ad-hoc `System.out.println`. The library declares
  `implementation(libs.slf4j.api)`; both the library tests and `testapp` pull in
  `slf4j-simple` to see the output.

## Done

- ~~Rename repo `vaadin-tab-scope-example` → `vaadin-tab-scope` (and the GitHub URL).~~ Homepage and
  git remote are now `github.com/mvysny/vaadin-tab-scope`. (Local working-dir name is cosmetic.)
- ~~Move + rename packages; split the two SPI files into `testapp`.~~ Library is
  `com.github.mvysny.vaadin.tabscope`, demo is `testapp`, both SPI files live in `testapp`.
- ~~Add library-level tests for `TabScope` lifecycle/orphan-cleanup.~~ `TabScopeTest` in `tab-scope`
  with test-scope SPI wiring exercises lifecycle + `@TabScoped` caching in isolation.
- ~~Logging via slf4j-api.~~ `TabScope` logs created/orphaned/destroyed at debug.

## Remaining (real follow-ups, not part of the split)

- Wire up Maven Central publishing credentials/signing and do a first `0.1` release. The root build
  has the `ext["publishing"]` + `nexusPublishing` config ready but it has not been run against a
  real publish.
- `jakarta.servlet-api` had to be added as `compileOnly` to the library (Vaadin declares the servlet
  API as `provided`, but `VaadinSession` implements `HttpSessionBindingListener`, needed at compile).
- Per this repo's convention (implemented ideas get absorbed into INTERNALS.md and the idea file
  deleted), consider folding this file into INTERNALS.md once the release lands.
