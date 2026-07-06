package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Exercises the orphan-cleanup and session-destroy branches of {@link TabScope.Lifecycle} that the
 * survival-oriented tests never reach: the scope actually being <em>reaped</em>. See INTERNALS.md,
 * "Cleanup".
 */
public class TabScopeLifecycleTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
    }

    /** Set true by a test that tears Vaadin down itself, so {@link #teardownVaadin} doesn't double-tear-down. */
    private boolean tornDown;

    @BeforeEach
    public void setupVaadin() {
        tornDown = false;
        TestInitListener.COUNTER.set(0);
        MockVaadin.setup(routes);
    }

    @AfterEach
    public void teardownVaadin() {
        if (!tornDown) {
            MockVaadin.tearDown();
        }
        TabScope.CLEANUP_DURATION_MS = 60 * 1000L; // restore the production grace period
    }

    /**
     * The critical reaping branch: once a scope has been orphaned longer than the grace period, the
     * next cleanup sweep must destroy it and drop it from the scope map. We shrink the grace period
     * to a negative value so the zero-UI gap of an EAGER reload (old UI detaches, then the sweep
     * runs before the new UI is born) reaps the scope in-flight. The reloaded tab then gets a fresh
     * scope, so the tab-init listener runs a second time.
     */
    @Test
    public void orphanedScopeIsReapedAfterGracePeriod() {
        TabScope.CLEANUP_DURATION_MS = -1L; // any elapsed time counts as "past the grace period"

        final TabScope original = TabScope.getCurrent();
        final AtomicInteger destroyed = new AtomicInteger();
        original.addDestroyListener(ts -> destroyed.incrementAndGet());
        assertEquals(1, TestInitListener.COUNTER.get());

        UI.getCurrent().getPage().reload(); // EAGER (default): detach → orphan → reap → new UI

        assertEquals(1, destroyed.get(), "the orphaned scope must be reaped once past the grace period");
        assertNotSame(original, TabScope.getCurrent(), "the reloaded tab gets a brand-new scope");
        assertEquals(2, TestInitListener.COUNTER.get(), "tab-init ran again for the fresh scope");
    }

    /**
     * When the session is destroyed, every tab scope is closed (destroy listeners fire) and its
     * values become unavailable. {@code destroyAllTabScopes} runs on session destroy.
     */
    @Test
    public void sessionDestroyClosesScopeAndValuesBecomeUnavailable() {
        final TabScope scope = TabScope.getCurrent();
        final AtomicInteger destroyed = new AtomicInteger();
        scope.addDestroyListener(ts -> destroyed.incrementAndGet());

        MockVaadin.tearDown(); // fires the session destroy listener
        tornDown = true;

        assertEquals(1, destroyed.get(), "session destroy must close the tab scope");
        // getValues() guards with Objects.requireNonNull, so a destroyed scope throws NPE.
        final NullPointerException ex = assertThrows(NullPointerException.class, scope::getValues,
                "values must be unavailable after the scope is destroyed");
        assertEquals("this scope has been destroyed", ex.getMessage());
    }

    /** {@link TabScope#getCurrent()} requires the Vaadin UI thread; without a current UI it fails fast. */
    @Test
    public void getCurrentFailsWithoutUiThread() {
        UI.setCurrent(null);
        // getCurrent() guards the current UI with Objects.requireNonNull, hence NPE.
        final NullPointerException ex = assertThrows(NullPointerException.class, TabScope::getCurrent);
        assertEquals("Must be called from Vaadin UI thread", ex.getMessage());
    }
}
