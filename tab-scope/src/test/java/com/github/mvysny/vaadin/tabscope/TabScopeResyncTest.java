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
 * Covers the client-requested <b>resynchronization</b>
 * (<a href="https://github.com/mvysny/vaadin-tab-scope/issues/6">issue #6</a>): a lost UIDL message or
 * a proxy killing a push connection makes the client ask for a full re-send, and Flow serves it by
 * re-firing detach <em>and</em> attach across the whole state tree of a UI that never leaves the
 * session. The scope must ride that out — the tab is alive.
 * <br/>
 * {@code StateTree#prepareForResync()} is the production call site, invoked by
 * {@code ServerRpcHandler#handleRpc} on a resync request; Karibu does not simulate resync itself, so
 * the tests call it directly. See INTERNALS.md, "A detach event is not always a detach".
 */
public class TabScopeResyncTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
    }

    /** Collects what the session's error handler sees; a detach listener rejecting a UI lands here. */
    private final List<Throwable> errors = new ArrayList<>();

    @BeforeEach
    public void setupVaadin() {
        errors.clear();
        TestInitListener.COUNTER.set(0);
        TabScope.reapScheduler = new ManualReapScheduler();
        MockVaadin.setup(routes);
        VaadinSession.getCurrent().setErrorHandler(e -> errors.add(e.getThrowable()));
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
        TabScope.reapScheduler = new ReapScheduler.ExecutorBacked();
        TabScope.CLEANUP_DURATION_MS = 60 * 1000L; // restore the production grace period
    }

    /**
     * The tab is alive throughout — its UI is neither closed nor detached — so the resync must cost it
     * nothing: same scope, same values, and no orphaning for a later sweep to reap.
     */
    @Test
    public void resyncLeavesTheLiveTabsScopeAlone() {
        final String tabA = MockBrowser.getCurrentWindowName();
        final UI ui = UI.getCurrent();
        final TabScope scope = TabScope.getCurrent();
        scope.getValues().setAttribute("precious", "state");
        final AtomicInteger destroyed = new AtomicInteger();
        scope.addDestroyListener(ts -> destroyed.incrementAndGet());

        ui.getInternals().getStateTree().prepareForResync();

        assertTrue(ui.isAttached(), "precondition: the resync detaches nothing");
        assertFalse(ui.isClosing(), "precondition: ...and closes nothing");

        TabScope.CLEANUP_DURATION_MS = -1L; // any orphaning would now be reaped by the next sweep
        MockBrowser.newTab();               // ...which another tab's init runs
        MockBrowser.switchTo(tabA);

        assertEquals(0, destroyed.get(), "a resync must not orphan the scope of a tab that is still open");
        assertSame(scope, TabScope.getCurrent(), "the tab keeps the scope it had");
        assertEquals("state", scope.getValues().getAttribute("precious"));
    }

    /**
     * The other side of the guard: ignoring the resync's detach must not make us deaf to the real one.
     * A tab that resynced and is later genuinely closed still orphans and reaps, with its UI leaving
     * the scope cleanly.
     */
    @Test
    public void resyncedTabStillReapsWhenItReallyCloses() {
        final String tabA = MockBrowser.getCurrentWindowName();
        final AtomicInteger destroyed = new AtomicInteger();
        TabScope.getCurrent().addDestroyListener(ts -> destroyed.incrementAndGet());

        UI.getCurrent().getInternals().getStateTree().prepareForResync();

        MockBrowser.newTab();               // tab B, so tab A can be closed in the background
        TabScope.CLEANUP_DURATION_MS = -1L; // let the freshly-orphaned scope be reaped in the same sweep
        MockBrowser.closeTab(tabA);         // beacon delivered: tab A's UI closes and detaches for real

        assertEquals(1, destroyed.get(), "a resynced tab still reaps once it really closes");
        assertEquals(List.of(), errors, "...and its detach is handled, not rejected");
    }
}
