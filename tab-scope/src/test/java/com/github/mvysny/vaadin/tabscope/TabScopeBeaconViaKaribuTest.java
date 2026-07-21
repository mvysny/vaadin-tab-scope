package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.github.mvysny.kaributesting.v10.mock.MockService;
import com.github.mvysny.kaributesting.v10.mock.MockVaadinServlet;
import com.github.mvysny.kaributesting.v10.mock.MockedUI;
import com.vaadin.flow.component.UI;
import com.vaadin.flow.function.DeploymentConfiguration;
import com.vaadin.flow.server.RequestHandler;
import com.vaadin.flow.server.ServiceException;
import com.vaadin.flow.server.VaadinServletContext;
import com.vaadin.flow.server.VaadinServletService;
import kotlin.jvm.functions.Function0;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Validates feature A's <em>full HTTP path</em> browserlessly against Karibu-Testing 2.7.2+
 * (karibu-testing#210), which routes the simulated unload beacon through the app's real
 * {@link com.vaadin.flow.server.communication.ServerRpcHandler} — obtained from the installed
 * {@link com.vaadin.flow.server.communication.UidlRequestHandler}. With our
 * {@link TabScopeUidlRequestHandler} installed, closing a {@code @PreserveOnRefresh} tab must reach
 * {@link TabScope#onUnloadBeacon(UI)} — the exact end-to-end path that previously needed a real
 * browser (Playwright).
 * <p>
 * The distinguishing assertion vs. Karibu 2.7.1 (which discarded the UI unconditionally and never
 * ran a real handler): the preserve UI <em>lingers un-closed</em> after the tab closes, yet its
 * scope becomes orphaned via the beacon hook and is reaped by the timer.
 */
public class TabScopeBeaconViaKaribuTest {
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
        // Boot with a servlet whose service installs our tab-close beacon handler into the
        // request-handler chain — exactly what a real app's VaadinServlet does.
        MockVaadin.setup(MockedUI::new, new BeaconServlet(routes));
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
        TabScope.reapScheduler = null;
        TabScope.CLEANUP_DURATION_MS = 60 * 1000L;
    }

    @Test
    public void closingAPreserveTabReachesOnUnloadBeaconViaTheRealHandler() {
        final String focusedTab = MockBrowser.getCurrentWindowName();

        MockBrowser.newTab(); // background tab, now focused
        UI.getCurrent().navigate(PreserveOnRefreshTestView.class);
        final String preserveTab = MockBrowser.getCurrentWindowName();
        final UI preserveUI = UI.getCurrent();
        final AtomicInteger destroyed = new AtomicInteger();
        TabScope.getCurrent().addDestroyListener(ts -> destroyed.incrementAndGet());
        MockBrowser.switchTo(focusedTab);

        // Close the preserve tab: Karibu delivers the beacon through the real ServerRpcHandler
        // (our TabScopeServerRpcHandler), which calls onUnloadBeacon(ui) and then super — super
        // IGNORES the beacon for @PreserveOnRefresh, so the UI is not closed.
        MockBrowser.closeTab(preserveTab);

        // Proof the beacon went through the real handler on the preserve path:
        assertFalse(preserveUI.isClosing(), "@PreserveOnRefresh UI must NOT be closed by the beacon");
        assertTrue(MockBrowser.getTabs().contains(preserveTab), "its UI lingers (beacon ignored by Flow)");
        // ...yet onUnloadBeacon started the grace clock, so the scope is now reap-armed:
        assertEquals(1, scheduler.pendingCount(), "onUnloadBeacon armed the reap despite the UI lingering");
        assertEquals(0, destroyed.get(), "not reaped yet (within grace)");

        // The timer then reaps it (feature B), with no reattach.
        TabScope.CLEANUP_DURATION_MS = -1L;
        scheduler.fireAll();
        MockVaadin.clientRoundtrip();

        assertEquals(1, destroyed.get(), "the beacon-hook + timer destroyed the preserve tab's scope");
        assertEquals(focusedTab, MockBrowser.getCurrentWindowName(), "the focused tab is untouched");
    }

    /** A {@link MockVaadinServlet} whose service installs tab-scope's beacon handler. */
    private static final class BeaconServlet extends MockVaadinServlet {
        BeaconServlet(Routes routes) {
            super(routes);
        }

        @Override
        protected VaadinServletService createServletService(DeploymentConfiguration configuration)
                throws ServiceException {
            final Function0<UI> uiFactory = getUiFactory();
            final VaadinServletService service = new MockService(this, configuration, uiFactory) {
                @Override
                protected List<RequestHandler> createRequestHandlers() throws ServiceException {
                    final List<RequestHandler> handlers = new ArrayList<>(super.createRequestHandlers());
                    TabScope.installTabCloseBeacon(handlers);
                    return handlers;
                }
            };
            service.init();
            getRoutes().register((VaadinServletContext) service.getContext());
            return service;
        }
    }
}
