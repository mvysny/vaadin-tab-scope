package com.github.mvysny.vaadin.tabscope;

import com.github.mvysny.kaributesting.v10.MockVaadin;
import com.github.mvysny.kaributesting.v10.Routes;
import com.vaadin.flow.component.UI;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.github.mvysny.kaributesting.v10.LocatorJ._get;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Covers {@link TabScopedRouteInstantiator}'s caching across <em>navigation</em> (not just reload),
 * for both {@code @TabScoped} routes and {@code @TabScoped} layouts. This is the behavior that makes
 * tab scope a strict superset of {@code @PreserveOnRefresh} (which discards its instance on
 * navigation) — see INTERNALS.md, "Relationship to {@code @PreserveOnRefresh}".
 */
public class TabScopedRoutingTest {
    private static Routes routes;

    @BeforeAll
    public static void createRoutes() {
        routes = new Routes().autoDiscoverViews("com.github.mvysny.vaadin.tabscope");
    }

    @BeforeEach
    public void setupVaadin() {
        TestInitListener.COUNTER.set(0);
        TabScopedTestView.INSTANCES.set(0);
        TabScopedLayout.INSTANCES.set(0);
        MockVaadin.setup(routes);
    }

    @AfterEach
    public void teardownVaadin() {
        MockVaadin.tearDown();
    }

    /**
     * A {@code @TabScoped} route navigated away from and back to must be the very same instance,
     * constructed exactly once — unlike {@code @PreserveOnRefresh}, which discards on navigation.
     */
    @Test
    public void navigateAwayAndBackReusesTabScopedRoute() {
        UI.getCurrent().navigate(TabScopedTestView.class);
        final TabScopedTestView view = _get(TabScopedTestView.class);

        UI.getCurrent().navigate(PlainTestView.class); // away to a different route
        _get(PlainTestView.class);
        UI.getCurrent().navigate(TabScopedTestView.class); // back

        assertSame(view, _get(TabScopedTestView.class));
        assertEquals(1, TabScopedTestView.INSTANCES.get());
    }

    /**
     * The {@code @TabScoped} annotation applies to layouts too: the parent {@link TabScopedLayout}
     * must be reused across navigation and reload, constructed exactly once per tab.
     */
    @Test
    public void tabScopedLayoutReusedAcrossNavigationAndReload() {
        UI.getCurrent().navigate(LayoutChildView.class);
        final TabScopedLayout layout = _get(TabScopedLayout.class);
        assertEquals(1, TabScopedLayout.INSTANCES.get());

        UI.getCurrent().navigate(PlainTestView.class); // away
        UI.getCurrent().navigate(LayoutChildView.class); // back
        assertSame(layout, _get(TabScopedLayout.class), "layout must be reused across navigation");

        UI.getCurrent().getPage().reload();
        assertSame(layout, _get(TabScopedLayout.class), "layout must be reused across reload");
        assertEquals(1, TabScopedLayout.INSTANCES.get());
    }

    /**
     * A {@code @TabScoped} route and a {@code @TabScoped} layout live in the same {@link TabScope}
     * simultaneously, keyed independently by their class: interleaved navigation between them keeps
     * both cached instances alive (each constructed exactly once).
     */
    @Test
    public void twoTabScopedTypesCoexistInTheSameScope() {
        UI.getCurrent().navigate(TabScopedTestView.class);
        final TabScopedTestView view = _get(TabScopedTestView.class);
        UI.getCurrent().navigate(LayoutChildView.class);
        final TabScopedLayout layout = _get(TabScopedLayout.class);

        // go back to each: both must still be the original instances
        UI.getCurrent().navigate(TabScopedTestView.class);
        assertSame(view, _get(TabScopedTestView.class));
        UI.getCurrent().navigate(LayoutChildView.class);
        assertSame(layout, _get(TabScopedLayout.class));

        assertEquals(1, TabScopedTestView.INSTANCES.get());
        assertEquals(1, TabScopedLayout.INSTANCES.get());
    }
}
