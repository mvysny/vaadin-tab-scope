package com.github.mvysny.vaadin.tabscope;

import com.vaadin.flow.component.html.Div;
import com.vaadin.flow.router.Route;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * A {@link TabScoped} route: exactly one instance per browser tab, cached by
 * {@link TabScopedRouteInstantiator} and reused across page reloads and navigation.
 */
@Route("scoped")
@TabScoped
public class TabScopedTestView extends Div {
    /** Counts how many instances were constructed; should be exactly one per tab. */
    static final AtomicInteger INSTANCES = new AtomicInteger();

    public TabScopedTestView() {
        INSTANCES.incrementAndGet();
    }
}
