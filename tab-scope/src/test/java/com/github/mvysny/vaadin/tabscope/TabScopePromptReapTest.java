package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.VaadinSession;
import com.vaadin.flow.server.communication.UidlRequestHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the always-on scheduled reap (issue #3): an orphaned scope is destroyed after
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
        TabScope.reapScheduler = new ReapScheduler.ExecutorBacked();
        TabScope.CLEANUP_DURATION_MS = 60 * 1000L; // restore the production grace period
        TabScope.scheduledReapEnabled = true;      // restore the default (global)
    }

    /**
     * The core of the scheduled reap: after a tab closes and its scope orphans, nothing else happens in the
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

    /**
     * The tab-close beacon hook: {@link TabScope#onUnloadBeacon(UI)} must start the grace clock while the
     * {@code @PreserveOnRefresh} UI stays attached (real Flow ignores the beacon for such a route, so
     * the UI is never closed). The timer then reaps it after the grace, closing the gap where such a
     * scope would otherwise linger until session-destroy.
     */
    @Test
    public void onUnloadBeaconStartsGraceClockWithoutClosingThePreserveUi() {
        UI.getCurrent().navigate(PreserveOnRefreshTestView.class);
        final UI ui = UI.getCurrent();
        final TabScope scope = TabScope.getCurrent();
        final AtomicInteger destroyed = new AtomicInteger();
        scope.addDestroyListener(ts -> destroyed.incrementAndGet());

        TabScope.onUnloadBeacon(ui); // beacon: start the clock, but do NOT close the UI

        assertEquals(1, scheduler.pendingCount(), "the beacon armed the reap");
        assertEquals(0, destroyed.get(), "not destroyed yet: still within the grace period");
        assertFalse(ui.isClosing(), "the @PreserveOnRefresh UI stays open");
        assertTrue(VaadinSession.getCurrent().getUIs().contains(ui), "the UI stays attached");

        TabScope.CLEANUP_DURATION_MS = -1L;
        scheduler.fireAll();
        MockVaadin.clientRoundtrip();

        assertEquals(1, destroyed.get(), "the timer reaped the beacon-closed scope after the grace period");
    }

    /**
     * The beacon fires on a real F5 too, so a beacon followed by a same-{@code window.name} reattach
     * must NOT destroy the scope: the new UI cancels the armed reap.
     */
    @Test
    public void beaconFollowedByReloadKeepsTheScope() {
        UI.getCurrent().navigate(PreserveOnRefreshTestView.class);
        final TabScope scope = TabScope.getCurrent();

        TabScope.onUnloadBeacon(UI.getCurrent()); // beacon arms the reap
        assertEquals(1, scheduler.pendingCount());

        UI.getCurrent().getPage().reload(); // real F5: new UI (same window.name) attaches within grace

        assertSame(scope, TabScope.getCurrent(), "the reattach kept the same scope");
        assertEquals(0, scheduler.pendingCount(), "the beacon-armed reap was cancelled by the reattach");
    }

    /**
     * With {@link TabScope#scheduledReapEnabled} = {@code false}, orphaning arms no background reap;
     * the app rides Vaadin's default closing + request-driven/session-destroy cleanup instead.
     */
    @Test
    public void disablingScheduledReapArmsNoTimer() {
        TabScope.scheduledReapEnabled = false;

        final String focusedTab = MockBrowser.getCurrentWindowName();
        MockBrowser.newTab(); // background tab, now focused
        final String backgroundTab = MockBrowser.getCurrentWindowName();
        final AtomicInteger destroyed = new AtomicInteger();
        TabScope.getCurrent().addDestroyListener(ts -> destroyed.incrementAndGet());
        MockBrowser.switchTo(focusedTab);

        MockBrowser.closeTab(backgroundTab); // orphans the background scope

        assertEquals(0, scheduler.pendingCount(), "no reap is armed when scheduledReapEnabled=false");

        // Even past the grace, no background timer reaps it (there is none to fire).
        TabScope.CLEANUP_DURATION_MS = -1L;
        scheduler.fireAll();
        MockVaadin.clientRoundtrip();
        assertEquals(0, destroyed.get(), "the orphaned scope is not reaped by any background timer");
    }

    /** {@link TabScope#installTabCloseBeacon} swaps the stock UidlRequestHandler in place for ours. */
    @Test
    public void installTabCloseBeaconSwapsTheStockHandler() {
        final RequestHandler other = (session, request, response) -> false;
        final List<RequestHandler> handlers = new ArrayList<>(List.of(other, new UidlRequestHandler(), other));

        TabScope.installTabCloseBeacon(handlers);

        assertEquals(3, handlers.size(), "swapped in place, not added");
        assertTrue(handlers.stream().anyMatch(h -> h instanceof TabScopeUidlRequestHandler), "our handler is installed");
        assertTrue(handlers.stream().noneMatch(h -> h.getClass() == UidlRequestHandler.class), "the stock handler is gone");
    }
}
