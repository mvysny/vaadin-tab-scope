package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.MockBrowser;
import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tab identity is keyed on {@code window.name}. Most browsers preserve it across an F5 (so the scope
 * survives), but some do not — Safari 18.3.1 with dev tools closed, or navigation via typed URL /
 * bookmark ([vaadin/flow#21141]) — and then the reloaded page arrives as a brand-new tab. This
 * exercises both outcomes via {@link MockBrowser#reload}. See INTERNALS.md, "Tab identity fragility".
 */
public class TabIdentityTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
    }

    @BeforeEach
    public void setupVaadin() {
        TestInitListener.COUNTER.set(0);
        MockVaadin.setup(routes);
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
    }

    /** The normal case: the browser preserves {@code window.name}, so the same scope survives. */
    @Test
    public void reloadPreservingWindowNameKeepsScope() {
        final TabScope before = TabScope.getCurrent();
        final String name = MockBrowser.getCurrentWindowName();

        MockBrowser.reload(); // window.name preserved (the default)

        assertSame(before, TabScope.getCurrent());
        assertEquals(name, MockBrowser.getCurrentWindowName());
        assertEquals(1, TestInitListener.COUNTER.get(), "tab-init must not run again");
    }

    /**
     * The fragile case: the browser did <em>not</em> preserve {@code window.name}, so the reloaded
     * page is a new tab — a fresh scope, and the tab-init listener runs again.
     */
    @Test
    public void reloadWithNewWindowNameArrivesAsFreshScope() {
        final TabScope before = TabScope.getCurrent();
        final String oldName = MockBrowser.getCurrentWindowName();
        assertEquals(1, before.getValues().getAttribute("counter"));

        MockBrowser.reload("a-different-window-name"); // browser lost the tab id

        assertNotSame(before, TabScope.getCurrent(), "a changed window.name must yield a new scope");
        assertNotEquals(oldName, MockBrowser.getCurrentWindowName());
        assertEquals(2, TestInitListener.COUNTER.get(), "tab-init ran again for the new tab");
        assertEquals(2, TabScope.getCurrent().getValues().getAttribute("counter"));
    }
}
