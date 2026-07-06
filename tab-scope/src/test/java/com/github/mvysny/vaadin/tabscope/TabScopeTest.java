package com.github.mvysny.vaadin.tabscope;

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
 * Exercises the library in isolation: the test classpath ships the two {@code META-INF/services}
 * files (see {@code src/test/resources}) that a real app would register — the
 * {@link TabScopedRouteInstantiator.Factory} and {@link TestInitListener} — plus two demo routes.
 */
public class TabScopeTest {
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
    }

    @Test
    public void getCurrentIsAvailableAfterInit() {
        assertNotNull(TabScope.getCurrent());
    }

    @Test
    public void tabInitListenerSeedsValueExactlyOnce() {
        assertEquals(1, TabScope.getCurrent().getValues().getAttribute("counter"));
    }

    @Test
    public void sameScopeInstanceSurvivesPageReload() {
        final TabScope current = TabScope.getCurrent();
        UI.getCurrent().getPage().reload();
        assertSame(current, TabScope.getCurrent());
    }

    @Test
    public void tabScopedValueIsPreservedAcrossReload() {
        // the tab-init listener must NOT run again on reload, so the counter stays at 1
        assertEquals(1, TabScope.getCurrent().getValues().getAttribute("counter"));
        UI.getCurrent().getPage().reload();
        assertEquals(1, TabScope.getCurrent().getValues().getAttribute("counter"));
    }

    @Test
    public void tabScopedRouteIsCachedAndReusedAcrossReload() {
        UI.getCurrent().navigate(TabScopedTestView.class);
        final TabScopedTestView view = _get(TabScopedTestView.class);
        UI.getCurrent().getPage().reload();

        // the @TabScoped route is the very same instance, constructed exactly once
        assertSame(view, _get(TabScopedTestView.class));
        assertEquals(1, TabScopedTestView.INSTANCES.get());
    }

    @Test
    public void plainRouteIsRecreatedButKeepsTabScopedValue() {
        UI.getCurrent().navigate(PlainTestView.class);
        final PlainTestView view = _get(PlainTestView.class);
        assertEquals(1, view.value);
        UI.getCurrent().getPage().reload();

        // a plain route gets a fresh instance on reload, but the tab-scoped value it reads is stable
        final PlainTestView reloaded = _get(PlainTestView.class);
        assertNotSame(view, reloaded);
        assertEquals(1, reloaded.value);
    }
}
