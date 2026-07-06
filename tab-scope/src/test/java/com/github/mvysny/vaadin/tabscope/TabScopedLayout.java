package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.RouterLayout;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link TabScoped} <em>layout</em> (not a route): {@link TabScopedRouteInstantiator} caches and
 * reuses {@code @TabScoped} types whether they are routes or layouts, so exactly one instance must
 * exist per browser tab. Used to prove the instantiator's layout path, which the route-only tests
 * never touch.
 */
@TabScoped
public class TabScopedLayout extends Div implements RouterLayout {
    /** Counts how many instances were constructed; should be exactly one per tab. */
    static final AtomicInteger INSTANCES = new AtomicInteger();

    public TabScopedLayout() {
        INSTANCES.incrementAndGet();
    }
}
