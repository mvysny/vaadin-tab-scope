package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.KaribuConfig;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.UnloadBeaconTiming;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.concurrent.atomic.AtomicInteger;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises tab-scope survival across an F5 page reload under every browser unload-beacon ordering
 * that Karibu-Testing can reproduce ({@link UnloadBeaconTiming}, set via
 * {@link KaribuConfig#setUnloadBeaconTiming}). Each ordering models a distinct real reload path
 * documented in INTERNALS.md ("Cleanup" &rarr; "Why the timer is necessary"); the old
 * single-{@code reload()} tests only ever covered the default {@link UnloadBeaconTiming#EAGER}
 * path:
 * <ul>
 *   <li>{@link UnloadBeaconTiming#EAGER} — non-{@code @PreserveOnRefresh} reload where the unload
 *       beacon beats the bootstrap: the old UI detaches <em>before</em> the new one attaches,
 *       leaving a transient zero-UI gap that only the 60&nbsp;s grace period keeps from reaping
 *       the still-live scope.</li>
 *   <li>{@link UnloadBeaconTiming#LATE} — the beacon lands after the new UI is created: the UI set
 *       never empties, so the scope is never even marked orphaned.</li>
 *   <li>{@link UnloadBeaconTiming#NEVER} — the beacon is lost and the old UI lingers: the scope
 *       transiently has two UIs (the "0 or 2 UIs" case noted on {@code TabScope.Lifecycle.uis}).</li>
 * </ul>
 * Plus the {@code @PreserveOnRefresh} path, where Flow ignores the beacon entirely so the timing
 * has no effect.
 */
public class TabScopeReloadTimingTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
    }

    @BeforeEach
    public void setupVaadin() {
        TestInitListener.COUNTER.set(0);
        TabScopedTestView.INSTANCES.set(0);
        MockVaadin.setup(routes);
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
        // KaribuConfig is a global singleton: restore the default so we don't leak into other tests.
        KaribuConfig.setUnloadBeaconTiming(UnloadBeaconTiming.EAGER);
    }

    /**
     * Whatever the beacon ordering, the same {@link TabScope} instance must survive the reload and
     * the tab-init listener must not run a second time (its seeded {@code counter} stays 1).
     */
    @ParameterizedTest
    @EnumSource(UnloadBeaconTiming.class)
    public void scopeAndValuesSurviveReload(UnloadBeaconTiming timing) {
        KaribuConfig.setUnloadBeaconTiming(timing);

        final TabScope before = TabScope.getCurrent();
        assertEquals(1, before.getValues().getAttribute("counter"));

        UI.getCurrent().getPage().reload();

        assertSame(before, TabScope.getCurrent(), "scope must survive a " + timing + " reload");
        assertEquals(1, TabScope.getCurrent().getValues().getAttribute("counter"));
        assertEquals(1, TestInitListener.COUNTER.get(), "tab-init listener must run exactly once per tab");
    }

    /**
     * A {@link TabScoped} route is cached by {@link TabScopedRouteInstantiator}; the very same
     * instance (constructed exactly once) must be reused after the reload, regardless of ordering.
     */
    @ParameterizedTest
    @EnumSource(UnloadBeaconTiming.class)
    public void tabScopedRouteReusedAcrossReload(UnloadBeaconTiming timing) {
        KaribuConfig.setUnloadBeaconTiming(timing);

        UI.getCurrent().navigate(TabScopedTestView.class);
        final TabScopedTestView view = _get(TabScopedTestView.class);

        UI.getCurrent().getPage().reload();

        assertSame(view, _get(TabScopedTestView.class), "@TabScoped route must be reused across a " + timing + " reload");
        assertEquals(1, TabScopedTestView.INSTANCES.get());
    }

    /**
     * The critical race: under EAGER the old UI is closed and detached <em>before</em> the new UI
     * is born, so the scope is momentarily orphaned (no UI points to it). The 60&nbsp;s grace
     * period is the only thing keeping it alive — assert it is not destroyed and that exactly one
     * UI remains afterwards (old discarded, new created).
     */
    @Test
    public void eagerReloadDoesNotReapScopeDespiteZeroUiGap() {
        KaribuConfig.setUnloadBeaconTiming(UnloadBeaconTiming.EAGER);

        final AtomicInteger destroyed = new AtomicInteger();
        TabScope.getCurrent().addDestroyListener(ts -> destroyed.incrementAndGet());

        UI.getCurrent().getPage().reload();

        assertEquals(0, destroyed.get(), "the grace period must keep the scope alive through the zero-UI gap");
        assertEquals(1, VaadinSession.getCurrent().getUIs().size(), "EAGER discards the old UI before creating the new one");
    }

    /**
     * Under NEVER the beacon is lost, so the old UI lingers alongside the new one: the session
     * briefly holds two UIs — the "0 or 2 UIs" situation the scope lifecycle is built to tolerate.
     * The scope survives regardless.
     */
    @Test
    public void neverReloadLeavesOldUiLingeringButScopeSurvives() {
        KaribuConfig.setUnloadBeaconTiming(UnloadBeaconTiming.NEVER);

        final TabScope before = TabScope.getCurrent();
        UI.getCurrent().getPage().reload();

        assertSame(before, TabScope.getCurrent());
        assertEquals(2, VaadinSession.getCurrent().getUIs().size(), "beacon lost: the old UI lingers next to the new one");
    }

    /**
     * On a {@code @PreserveOnRefresh} route Flow ignores the unload beacon (it closes the old UI
     * from the new UI's navigation instead), so the beacon timing has no effect: the scope survives
     * and exactly one UI remains, whatever the flag is set to. Also confirms tab-scoped values are
     * readable on this beacon-ignored path — the tab scope does not require the annotation.
     */
    @ParameterizedTest
    @EnumSource(UnloadBeaconTiming.class)
    public void preserveOnRefreshIgnoresBeaconTimingAndKeepsScope(UnloadBeaconTiming timing) {
        KaribuConfig.setUnloadBeaconTiming(timing);

        UI.getCurrent().navigate(PreserveOnRefreshTestView.class);
        final PreserveOnRefreshTestView view = _get(PreserveOnRefreshTestView.class);
        assertEquals(1, view.value);
        final TabScope before = TabScope.getCurrent();

        UI.getCurrent().getPage().reload();

        assertSame(before, TabScope.getCurrent(), "scope must survive the @PreserveOnRefresh reload under " + timing);
        // @PreserveOnRefresh reuses the very same component instance across the reload...
        assertSame(view, _get(PreserveOnRefreshTestView.class));
        // ...and the beacon is ignored, so the old UI is always discarded: one UI remains.
        assertEquals(1, VaadinSession.getCurrent().getUIs().size(), "@PreserveOnRefresh ignores " + timing + " beacon timing");
    }
}
