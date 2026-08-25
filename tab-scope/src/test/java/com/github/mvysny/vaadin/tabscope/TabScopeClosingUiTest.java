package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.VaadinSession;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the closing-but-not-yet-detached UI — the state Flow produces routinely, since
 * {@code UI.close()} only sets a flag and the detach follows at {@code requestEnd} or session
 * destroy. Such a UI must stop keeping its scope alive without leaving {@code Lifecycle.uis}, so its
 * own pending detach listener still finds it there
 * (<a href="https://github.com/mvysny/vaadin-tab-scope/issues/5">issue #5</a>). See INTERNALS.md,
 * "Cleanup".
 */
public class TabScopeClosingUiTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
    }

    /** Collects what the session's error handler sees; a failing detach listener lands here. */
    private final List<Throwable> errors = new ArrayList<>();

    /** Set true by a test that tears Vaadin down itself, so {@link #teardownVaadin} doesn't double-tear-down. */
    private boolean tornDown;

    @BeforeEach
    public void setupVaadin() {
        tornDown = false;
        errors.clear();
        TestInitListener.COUNTER.set(0);
        // A manual scheduler keeps the always-on timer from firing a real off-thread reap while a
        // test shrinks the grace period.
        TabScope.reapScheduler = new ManualReapScheduler();
        MockVaadin.setup(routes);
        VaadinSession.getCurrent().setErrorHandler(e -> errors.add(e.getThrowable()));
    }

    @AfterEach
    public void teardownVaadin() {
        if (!tornDown) {
            MockVaadin.tearDown();
        }
        TabScope.reapScheduler = new ReapScheduler.ExecutorBacked();
        TabScope.CLEANUP_DURATION_MS = 60 * 1000L; // restore the production grace period
    }

    /**
     * {@code cleanupOrphans()} sweeps every scope in the session, so an <em>unrelated</em> tab's init
     * reaches a closing-but-attached UI in another tab's scope. It must leave that UI hooked, because
     * the detach listener registered for it is still pending — evicting it made that listener throw
     * {@code "Invalid state: uis doesn't contain given ui"}.
     */
    @Test
    public void unrelatedTabsInitLeavesAClosingUiHooked() {
        final UI uiA = UI.getCurrent();
        assertNotNull(TabScope.getCurrent());
        uiA.close();
        assertTrue(uiA.isAttached(), "precondition: Flow closes the UI without detaching it yet");

        MockBrowser.newTab(); // an unrelated tab's init sweeps tab A's scope too

        assertTrue(uiA.isAttached(), "precondition: still awaiting its detach");
        MockVaadin.tearDown(); // the detach finally happens, for both tabs
        tornDown = true;

        assertEquals(List.of(), errors, "the pending detach must be handled, not rejected");
    }

    /**
     * The same eviction with no app code and no second tab's init involved: Flow's
     * {@code closeInactiveUIs} closes <em>every</em> inactive UI before {@code removeClosedUIs}
     * detaches them, so a heartbeat-timeout batch (and session destroy) holds two closing-but-attached
     * UIs at once, and the first detach sweeps the second one's scope via {@code cleanupOrphans()}.
     */
    @Test
    public void batchOfClosingUisSurvivesUntilEachIsDetached() {
        final UI uiA = UI.getCurrent();
        assertNotNull(TabScope.getCurrent());
        final UI uiB = MockBrowser.newTab();
        assertNotNull(TabScope.getCurrent());

        uiA.close();
        uiB.close();
        assertTrue(uiA.isAttached() && uiB.isAttached(), "precondition: both closing, neither detached");

        MockVaadin.tearDown();
        tornDown = true;

        assertEquals(List.of(), errors, "each detach must be handled, in either order");
    }

    /**
     * The other half of the contract: staying in {@code uis} must not keep the scope alive. A closed
     * UI is not a live one, so the scope orphans at once and the next sweep past the grace period
     * reaps it — even though the UI is still attached, awaiting its detach.
     */
    @Test
    public void closingUiDoesNotKeepTheScopeAlive() {
        final UI uiA = UI.getCurrent();
        final AtomicInteger destroyed = new AtomicInteger();
        TabScope.getCurrent().addDestroyListener(ts -> destroyed.incrementAndGet());

        uiA.close();
        assertEquals(0, destroyed.get(), "not swept yet");

        TabScope.CLEANUP_DURATION_MS = -1L; // any elapsed time counts as "past the grace period"
        MockBrowser.newTab();               // another tab's init runs the sweep

        assertEquals(1, destroyed.get(), "a closed UI keeps nothing alive: the scope was reaped");
        assertTrue(uiA.isAttached(), "...while its UI is still attached, awaiting its detach");
    }
}
