package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.MockBrowser;
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
 * Covers the always-on scheduled reap (feature B of issue #3): an orphaned scope is destroyed after
 * the grace period by the timer alone, with no further request in the session — the case that a sole
 * last browser tab produces. Uses {@link ManualReapScheduler} so the timer fires deterministically
 * without real sleeps; see INTERNALS.md, "Cleanup".
 */
public class TabScopePromptReapTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
    }

    private ManualReapScheduler scheduler;

    @BeforeEach
    public void setupVaadin() {
        TestInitListener.COUNTER.set(0);
        scheduler = new ManualReapScheduler();
        TabScope.reapScheduler = scheduler;
        MockVaadin.setup(routes);
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
        TabScope.reapScheduler = null;
        TabScope.CLEANUP_DURATION_MS = 60 * 1000L; // restore the production grace period
    }

    /**
     * The core of feature B: after a tab closes and its scope orphans, nothing else happens in the
     * session — no reload, no other tab, no {@code reapInactiveUIs}. The armed one-shot reap alone
     * must destroy the scope once the grace period elapses. We keep a second (focused) tab alive only
     * so the browserless harness has a current UI to drain the {@code session.access} queue that the
     * reaper thread would self-purge in production.
     */
    @Test
    public void orphanedScopeIsReapedByTheTimerWithoutAnyFurtherRequest() {
        final String focusedTab = MockBrowser.getCurrentWindowName();
        MockBrowser.newTab(); // background tab, now focused
        final String backgroundTab = MockBrowser.getCurrentWindowName();
        final AtomicInteger backgroundDestroyed = new AtomicInteger();
        TabScope.getCurrent().addDestroyListener(ts -> backgroundDestroyed.incrementAndGet());
        MockBrowser.switchTo(focusedTab);

        MockBrowser.closeTab(backgroundTab); // beacon delivered: UI detaches, scope orphans, reap armed
        assertEquals(1, scheduler.pendingCount(), "closing the tab armed exactly one reap");
        assertEquals(0, backgroundDestroyed.get(), "still within the grace period: not reaped yet");

        TabScope.CLEANUP_DURATION_MS = -1L; // the grace period has now 'elapsed'
        scheduler.fireAll();                 // the timer fires off-request: session.access(closeIfOrphaned)
        MockVaadin.clientRoundtrip();         // drain the access queue (self-purged by the reaper thread in production)

        assertEquals(1, backgroundDestroyed.get(), "the timer alone destroyed the orphaned scope");
        assertEquals(0, scheduler.pendingCount(), "the reap fired");
        assertEquals(focusedTab, MockBrowser.getCurrentWindowName(), "the focused tab is untouched");
    }

    /**
     * A real F5 must not be mistaken for a close: the old UI detaches (arming the reap), but the new
     * UI re-attaches within the grace period and cancels it, so the same scope survives.
     */
    @Test
    public void reattachWithinGracePeriodCancelsTheArmedReap() {
        final TabScope original = TabScope.getCurrent();

        UI.getCurrent().getPage().reload(); // EAGER: detach (arms reap) → new UI attaches (cancels it)

        assertSame(original, TabScope.getCurrent(), "the scope survives an F5");
        assertEquals(0, scheduler.pendingCount(), "the armed reap was cancelled when the new UI attached");
        assertEquals(1, TestInitListener.COUNTER.get(), "tab-init did not run again");
    }
}
