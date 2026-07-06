package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.KaribuConfig;
import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.junit.jupiter.api.Assertions.*;

/**
 * The capability tab-scope exists for and previously could not test: two browser tabs in one session
 * (distinct {@code window.name}s, via {@link MockBrowser#newTab}) get two independent
 * {@link TabScope}s. Validates the Karibu-Testing {@code MockBrowser} multi-tab API against our real
 * use case.
 */
public class MultiTabTest {
    private static Routes routes;
    private static String defaultWindowName;

    /** The window.name we seed the initial tab with, so it's known and distinct from newTab's names. */
    private static final String TAB_A_NAME = "tab-A";

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
        defaultWindowName = KaribuConfig.getWindowName(); // capture to restore later (global singleton)
    }

    @BeforeEach
    public void setupVaadin() {
        TestInitListener.COUNTER.set(0);
        TabScopedTestView.INSTANCES.set(0);
        KaribuConfig.setWindowName(TAB_A_NAME); // must be set before setup(); seeds tab #1's window.name
        MockVaadin.setup(routes);
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
        KaribuConfig.setWindowName(defaultWindowName); // don't leak the override into other tests
    }

    /** {@link KaribuConfig#setWindowName} seeds the {@code window.name} of the tab {@code setup()} opens. */
    @Test
    public void initialTabUsesConfiguredWindowName() {
        assertEquals(TAB_A_NAME, MockBrowser.getCurrentWindowName());
    }

    @Test
    public void twoTabsGetTwoIndependentScopes() {
        // tab A: the one MockVaadin.setup() already opened, named via KaribuConfig.windowName
        final String tabAName = MockBrowser.getCurrentWindowName();
        assertEquals(TAB_A_NAME, tabAName);
        final TabScope scopeA = TabScope.getCurrent();
        assertEquals(1, scopeA.getValues().getAttribute("counter")); // seeded once for tab A
        scopeA.getValues().setAttribute("secret", "only-in-A");

        // tab B: a second tab with a distinct window.name, now focused
        MockBrowser.newTab();
        final TabScope scopeB = TabScope.getCurrent();

        // distinct scopes, each seeded by its own tab-init run (counter 1 vs 2), no value leakage
        assertNotSame(scopeA, scopeB);
        assertEquals(2, scopeB.getValues().getAttribute("counter"));
        assertNull(scopeB.getValues().getAttribute("secret"), "tab A's value must not leak into tab B");

        // a @TabScoped route resolves to a different instance per tab
        UI.getCurrent().navigate(TabScopedTestView.class); // in tab B
        final TabScopedTestView viewB = _get(TabScopedTestView.class);
        MockBrowser.switchTo(tabAName);
        UI.getCurrent().navigate(TabScopedTestView.class); // in tab A
        final TabScopedTestView viewA = _get(TabScopedTestView.class);
        assertNotSame(viewA, viewB);
        assertEquals(2, TabScopedTestView.INSTANCES.get(), "one @TabScoped instance per tab");

        // independent lifecycle: closing background tab B must not disturb tab A's scope
        MockBrowser.closeTab(MockBrowser.getTabs().stream()
                .filter(n -> !n.equals(tabAName)).findFirst().orElseThrow());
        assertSame(scopeA, TabScope.getCurrent());
        assertEquals("only-in-A", scopeA.getValues().getAttribute("secret"));
    }
}
